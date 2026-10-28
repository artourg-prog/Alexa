package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// "Picked up" requests to the Pickups web app (format agreed with Pickups — Mac, Pickups-Builder PR #2).
// The phone never writes to the sheet: it posts one receipt # per request with its own phone-only key,
// and the tablet applies it only when exactly one active order has that number.
//
// Safety rules (owner-approved pickup-safety task, reviewed):
// - Nothing unconfirmed is ever dropped: offline, restarts and updates keep the queue. Unsent taps are
//   retried with the SAME request id. A tap not sent within 24 h is never sent late (the tablet would
//   refuse it as too old): it becomes NEEDS_CHECK.
// - Only an answer from the tablet (APPLIED / ALREADY_PICKED_UP) or a person tapping "Checked" settles a
//   tap. A receipt leaving the pickup list is NOT taken as proof.
// - Sent but no answer for 30 min: shown for checking, still asked about under the same id.
// - The screen says what is true: saved on the phone / sent, waiting / confirmed by the tablet.
// - The queue file is written then swapped in; a failed write keeps the last good file and shows a
//   warning. A file that can't be read is set aside (never overwritten) and a warning stays on screen
//   until someone confirms they checked the tablet.
// pickup-config.json ({"url": …, "secret": …}) is put in app storage over USB by set-pickup-key-on-fold.sh
// (called from Pickups' phone-key.sh), never in git or the APK.
class Pickups(private val dir: File, private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    data class Req(val id: String, val receipt: String, val at: Long, var sent: Boolean = false, var status: String = "",
                   var resolved: Boolean = false, var sentAt: Long = 0L)

    /** What one sync found, for short messages: newly sent, confirmed by the tablet, needing a person, key refused. */
    data class Outcome(val sent: List<Req>, val confirmed: List<Req>, val problems: List<Req>, val denied: Boolean)

    companion object {
        // Final answers (Pickups API 5c50055) plus the phone's own NEEDS_CHECK.
        val DONE = setOf("APPLIED", "ALREADY_PICKED_UP")                                          // settled by the tablet
        val UNRESOLVED = setOf("NOT_FOUND", "AMBIGUOUS", "EXPIRED", "BAD_REQUEST", "NEEDS_CHECK") // a person must check
        val FINAL = DONE + UNRESOLVED
        const val DAY_MS = 24 * 3600_000L
        const val NO_ANSWER_MS = 30 * 60_000L // the tablet applies within ~15 min
        const val STATUS_BATCH = 20           // ids per pickupStatus call (the server caps open requests at 20)
        private const val TIMEOUT_MS = 10_000

        /** pickupRequest reply -> new status for the request, or null to keep it unsent and retry (FULL, network). */
        fun afterRequest(result: String): String? = when (result) {
            "QUEUED", "ALREADY" -> "QUEUED"
            "BAD_REQUEST" -> "BAD_REQUEST"
            else -> null // FULL / DENIED / anything new: keep, try again later
        }

        /**
         * Once a minute: a tap that never reached the server within 24 h becomes NEEDS_CHECK (never sent late,
         * never dropped); only settled or person-checked records older than 24 h are removed. Returns the new NEEDS_CHECKs.
         */
        fun housekeep(reqs: MutableList<Req>, now: Long): List<Req> {
            val flagged = reqs.filter { !it.sent && it.status !in FINAL && now - it.at > DAY_MS }
            flagged.forEach { it.status = "NEEDS_CHECK" }
            reqs.removeAll { now - it.at > DAY_MS && (it.status in DONE || (it.resolved && it.status in FINAL)) }
            return flagged
        }

        /** Shown for a person to check: an unresolved answer, or sent with no answer for 30 min. */
        fun needsCheck(r: Req, now: Long) = !r.resolved &&
            (r.status in UNRESOLVED || (r.sent && r.status !in FINAL && now - r.sentAt > NO_ANSWER_MS))

        fun batches(ids: List<String>): List<List<String>> = ids.chunked(STATUS_BATCH)

        /** The truthful line under a receipt the phone has a tap for. */
        fun stateText(r: Req): String = when {
            r.status == "APPLIED" -> "Picked up — confirmed by the tablet"
            r.status == "ALREADY_PICKED_UP" -> "Already picked up on the tablet"
            r.resolved -> "Picked up — checked on the tablet by staff"
            r.status in UNRESOLVED -> "Picked up NOT confirmed — check on the tablet"
            r.sent -> "Picked up — sent, waiting for the tablet"
            else -> "Picked up — saved on the phone, not sent yet"
        }

        /** Text for something a person must check. */
        fun problemText(receipt: String, status: String): String = when (status) {
            "ALREADY_PICKED_UP" -> "#$receipt: already picked up"
            "NOT_FOUND" -> "#$receipt: not found on the tablet — mark it on the tablet"
            "AMBIGUOUS" -> "#$receipt: more than one order with that number — mark it on the tablet"
            "BAD_REQUEST" -> "#$receipt: not marked — check on the tablet"
            "NEEDS_CHECK" -> "#$receipt: not sent in time — check on the tablet"
            "EXPIRED" -> "#$receipt: too old — mark it on the tablet"
            else -> "#$receipt: sent, the tablet hasn't answered — check on the tablet"
        }
    }

    private val configFile = File(dir, "pickup-config.json")
    private val queueFile = File(dir, "pickup-queue.json")
    private val tmpFile = File(dir, "pickup-queue.tmp")
    private val recoveryFlag = File(dir, "pickup-recovery-needed")
    private val reqs = mutableListOf<Req>()
    @Volatile private var saveFailed = false
    private var queueBlocked = false // unreadable file couldn't be set aside: never write over it

    init { load() }

    fun configured() = configFile.isFile

    /** A warning that must stay on screen, or null. */
    fun warning(): String? = when {
        recoveryFlag.isFile -> "The phone's pickup list couldn't be read — check today's pickups on the tablet. Tap when checked."
        saveFailed -> "The phone can't save Picked up taps — mark pickups on the tablet."
        else -> null
    }

    fun acknowledgeRecovery() { recoveryFlag.delete() }

    /** Receipts with a tap still in progress or settled (no new button for them). */
    @Synchronized fun pending(): Set<String> =
        reqs.filter { !it.resolved && it.status !in UNRESOLVED }.map { it.receipt }.toSet()

    /** The newest tap for a receipt, for the line under it. */
    @Synchronized fun latest(receipt: String): Req? =
        reqs.filter { it.receipt == Lookup.digits(receipt) }.maxByOrNull { it.at }?.copy()

    /** Taps a person must check, oldest first. */
    @Synchronized fun toCheck(now: Long = System.currentTimeMillis()): List<Req> =
        reqs.filter { needsCheck(it, now) }.map { it.copy() }

    /** A person checked it on the tablet. A sent tap keeps its id and is still asked about. */
    @Synchronized fun dismiss(id: String) { reqs.firstOrNull { it.id == id }?.resolved = true; save() }

    /** Saves the taps; false = couldn't save (the warning is shown). */
    @Synchronized fun add(receipts: List<String>): Boolean {
        val now = System.currentTimeMillis()
        receipts.forEach { reqs += Req(UUID.randomUUID().toString(), Lookup.digits(it), now) }
        return save()
    }

    /** Sends what isn't sent yet and asks for the outcome of the rest. Network; call off the main thread. */
    fun sync(): Outcome {
        if (!configured()) return Outcome(emptyList(), emptyList(), emptyList(), false)
        val cfg = JSONObject(configFile.readText().trim())
        val url = cfg.getString("url"); val secret = cfg.getString("secret")
        val sent = mutableListOf<Req>(); val confirmed = mutableListOf<Req>(); val problems = mutableListOf<Req>()
        fun out(denied: Boolean): Outcome { synchronized(this) { save() }; return Outcome(sent, confirmed, problems, denied) }
        val todo = synchronized(this) {
            problems += housekeep(reqs, System.currentTimeMillis())
            reqs.filter { it.status !in FINAL }.toList()
        }
        for (r in todo.filter { !it.sent }) {
            val res = post(url, JSONObject().put("secret", secret).put("mode", "pickupRequest").put("requestId", r.id)
                .put("receipt", r.receipt).put("revision", "").put("at", java.time.Instant.ofEpochMilli(r.at).toString()))
            val result = res.optString("result")
            if (result == "DENIED") return out(true)
            val next = afterRequest(result) ?: continue
            synchronized(this) {
                r.status = next
                if (next == "QUEUED") { r.sent = true; r.sentAt = System.currentTimeMillis(); sent += r } else problems += r
            }
        }
        for (batch in batches(todo.filter { it.sent }.map { it.id })) {
            val res = post(url, JSONObject().put("secret", secret).put("mode", "pickupStatus").put("requestIds", JSONArray(batch)))
            if (res.optString("result") == "DENIED") return out(true)
            val states = res.optJSONObject("states") ?: res.optJSONObject("statuses") ?: res
            synchronized(this) {
                for (r in todo.filter { it.id in batch }) {
                    val s = states.optString(r.id, "")
                    if (s.isEmpty() || s == r.status || s == "UNKNOWN") continue // keep asking, same id
                    r.status = s
                    when (s) { "APPLIED" -> confirmed += r; in UNRESOLVED, "ALREADY_PICKED_UP" -> problems += r }
                }
            }
        }
        return out(false)
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
        // A leftover .tmp is an interrupted write: the last good file is the real one.
        if (!queueFile.isFile) return
        try {
            val a = JSONArray(queueFile.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                // Older files lack "resolved"/"sentAt": read as not checked / unknown, nothing pending is lost.
                reqs += Req(getString("id"), getString("receipt"), getLong("at"), optBoolean("sent"), optString("status"),
                    optBoolean("resolved"), optLong("sentAt", if (optBoolean("sent")) getLong("at") else 0L))
            }
        } catch (e: Exception) {
            reqs.clear()
            recoveryFlag.writeText("unreadable queue at ${System.currentTimeMillis()}")
            val aside = File(dir, "pickup-queue.unreadable-${System.currentTimeMillis()}.json")
            if (!replace(queueFile, aside)) queueBlocked = true
        }
    }

    /** Write then swap in; on any failure the last good file stays and the warning shows. */
    private fun save(): Boolean {
        if (queueBlocked) { saveFailed = true; return false }
        val a = JSONArray()
        reqs.forEach { a.put(JSONObject().put("id", it.id).put("receipt", it.receipt).put("at", it.at).put("sent", it.sent)
            .put("status", it.status).put("resolved", it.resolved).put("sentAt", it.sentAt)) }
        val ok = try {
            FileOutputStream(tmpFile).use { it.write(a.toString().toByteArray()); it.fd.sync() }
            replace(tmpFile, queueFile)
        } catch (e: Exception) { false }
        saveFailed = !ok
        return ok
    }
}
