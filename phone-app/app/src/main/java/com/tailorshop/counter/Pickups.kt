package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// "Picked up" requests to the Pickups web app (format agreed with Pickups — Mac, Pickups-Builder PR #2).
// The phone never writes to the sheet: it posts one receipt # per request with its own phone-only key,
// and the tablet applies it only when exactly one active order has that number. Requests are kept on
// the phone and resent with the same requestId until the server has them, so nothing is marked twice.
// Nothing unconfirmed is ever dropped: offline, restarts and updates keep the queue (atomic file). A tap
// not sent within 24 h is never sent late (the tablet would refuse it as too old); it becomes NEEDS_CHECK
// and, like NOT_FOUND / AMBIGUOUS / EXPIRED / BAD_REQUEST, stays on screen until it's reconciled: the
// receipt has left the pickup list (the tablet marked it), or someone taps "Checked" on the phone.
// pickup-config.json ({"url": …, "secret": …}) is put in app storage over USB by set-pickup-key-on-fold.sh
// (called from Pickups' phone-key.sh), never in git or the APK.
class Pickups(dir: File) {
    data class Req(val id: String, val receipt: String, val at: Long, var sent: Boolean = false, var status: String = "",
                   var resolved: Boolean = false)

    /** What sync() found: requests that just ended badly (to show), and whether the key was refused. */
    data class Outcome(val problems: List<Req>, val denied: Boolean)

    companion object {
        // Final answers (Pickups API 5c50055) plus the phone's own NEEDS_CHECK.
        val DONE = setOf("APPLIED", "ALREADY_PICKED_UP")                       // settled, nothing to do
        val UNRESOLVED = setOf("NOT_FOUND", "AMBIGUOUS", "EXPIRED", "BAD_REQUEST", "NEEDS_CHECK") // a person must check
        val FINAL = DONE + UNRESOLVED

        /**
         * Once a minute: a tap that never reached the server within 24 h becomes NEEDS_CHECK (never sent late,
         * never dropped); only settled or reconciled records older than 24 h are removed. Returns the new NEEDS_CHECKs.
         */
        fun housekeep(reqs: MutableList<Req>, now: Long): List<Req> {
            val flagged = reqs.filter { !it.sent && it.status !in FINAL && now - it.at > DAY_MS }
            flagged.forEach { it.status = "NEEDS_CHECK" }
            reqs.removeAll { now - it.at > DAY_MS && (it.status in DONE || it.resolved) }
            return flagged
        }

        /** Unresolved taps whose receipt has left the pickup list were handled on the tablet. Empty list = no data, no change. */
        fun reconcile(reqs: List<Req>, onList: Set<String>): Boolean {
            if (onList.isEmpty()) return false
            var changed = false
            for (r in reqs) if (r.status in UNRESOLVED && !r.resolved && r.receipt !in onList) { r.resolved = true; changed = true }
            return changed
        }

        /** pickupRequest reply -> new status for the request, or null to keep it unsent and retry (FULL, network). */
        fun afterRequest(result: String): String? = when (result) {
            "QUEUED", "ALREADY" -> "QUEUED"
            "BAD_REQUEST" -> "BAD_REQUEST"
            else -> null // FULL / DENIED / anything new: keep, try again later
        }

        /** Screen text for a final answer other than APPLIED. */
        fun problemText(receipt: String, status: String): String = when (status) {
            "ALREADY_PICKED_UP" -> "#$receipt: already picked up"
            "NOT_FOUND" -> "#$receipt: not found on the tablet — mark it on the tablet"
            "AMBIGUOUS" -> "#$receipt: more than one order with that number — mark it on the tablet"
            "BAD_REQUEST" -> "#$receipt: not marked — check on the tablet"
            "NEEDS_CHECK" -> "#$receipt: not sent in time — check on the tablet"
            else -> "#$receipt: too old — mark it on the tablet"
        }
        private const val DAY_MS = 24 * 3600_000L
        private const val TIMEOUT_MS = 10_000
    }

    private val configFile = File(dir, "pickup-config.json")
    private val queueFile = File(dir, "pickup-queue.json")
    private val reqs = mutableListOf<Req>()

    init { load() }

    fun configured() = configFile.isFile

    /** Receipts sent (or waiting to be sent) that the tablet hasn't finished with. */
    @Synchronized fun pending(): Set<String> = reqs.filter { it.status !in FINAL }.map { it.receipt }.toSet()

    /** Taps that need a person to check on the tablet, oldest first. */
    @Synchronized fun unresolved(): List<Req> = reqs.filter { it.status in UNRESOLVED && !it.resolved }.map { it.copy() }

    @Synchronized fun dismiss(id: String) { reqs.firstOrNull { it.id == id }?.resolved = true; save() }

    @Synchronized fun reconcileWith(onList: Set<String>) { if (reconcile(reqs, onList.map(Lookup::digits).toSet())) save() }

    @Synchronized fun add(receipts: List<String>) {
        val now = System.currentTimeMillis()
        receipts.forEach { reqs += Req(UUID.randomUUID().toString(), Lookup.digits(it), now) }
        save()
    }

    /**
     * Sends what isn't sent yet and asks for the outcome of the rest. Network; call off the main thread.
     * Returns the requests that just reached a final answer other than APPLIED (to show on screen).
     */
    fun sync(): Outcome {
        if (!configured()) return Outcome(emptyList(), false)
        val cfg = JSONObject(configFile.readText().trim())
        val url = cfg.getString("url"); val secret = cfg.getString("secret")
        val problems = mutableListOf<Req>()
        val todo = synchronized(this) {
            problems += housekeep(reqs, System.currentTimeMillis())
            reqs.filter { it.status !in FINAL }.toList()
        }
        for (r in todo.filter { !it.sent }) {
            val res = post(url, JSONObject().put("secret", secret).put("mode", "pickupRequest").put("requestId", r.id)
                .put("receipt", r.receipt).put("revision", "").put("at", java.time.Instant.ofEpochMilli(r.at).toString()))
            val result = res.optString("result")
            if (result == "DENIED") { synchronized(this) { save() }; return Outcome(problems, true) }
            val next = afterRequest(result) ?: continue
            synchronized(this) {
                r.sent = next == "QUEUED"; r.status = next
                if (next != "QUEUED") problems += r
            }
        }
        val sent = todo.filter { it.sent }
        if (sent.isNotEmpty()) {
            val res = post(url, JSONObject().put("secret", secret).put("mode", "pickupStatus")
                .put("requestIds", JSONArray(sent.map { it.id })))
            if (res.optString("result") == "DENIED") { synchronized(this) { save() }; return Outcome(problems, true) }
            val statuses = res.optJSONObject("states") ?: res.optJSONObject("statuses") ?: res
            synchronized(this) {
                for (r in sent) {
                    val s = statuses.optString(r.id, "")
                    if (s.isEmpty() || s == r.status || s == "UNKNOWN") continue // UNKNOWN: keep asking
                    r.status = s
                    if (s in UNRESOLVED || s == "ALREADY_PICKED_UP") problems += r
                }
            }
        }
        synchronized(this) { save() }
        return Outcome(problems, false)
    }

    // Apps Script answers a POST with a redirect to the result, which must be fetched with GET.
    private fun post(url: String, body: JSONObject): JSONObject {
        var c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        var code = c.responseCode
        var hops = 0
        while (code in 300..399 && hops++ < 5) {
            val next = c.getHeaderField("Location") ?: break
            c = URL(next).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
            code = c.responseCode
        }
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw Exception("HTTP $code")
        return JSONObject(text)
    }

    private fun load() {
        if (!queueFile.isFile) return
        try {
            val a = JSONArray(queueFile.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                // Older files have no "resolved": read as false, so nothing pending is lost on upgrade.
                reqs += Req(getString("id"), getString("receipt"), getLong("at"), optBoolean("sent"), optString("status"),
                    optBoolean("resolved"))
            }
        } catch (e: Exception) {
            // Never overwrite a queue we couldn't read: keep it aside for checking.
            queueFile.renameTo(File(queueFile.parentFile, "pickup-queue.unreadable-${System.currentTimeMillis()}.json"))
        }
    }

    private fun save() {
        val a = JSONArray()
        reqs.forEach { a.put(JSONObject().put("id", it.id).put("receipt", it.receipt).put("at", it.at).put("sent", it.sent)
            .put("status", it.status).put("resolved", it.resolved)) }
        // Write then rename, so a crash or power loss mid-write can't leave a half file.
        val tmp = File(queueFile.parentFile, "pickup-queue.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(queueFile)
    }
}
