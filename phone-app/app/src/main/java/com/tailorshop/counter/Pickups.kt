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
// pickup-config.json ({"url": …, "secret": …}) is put in app storage by Art's install script, never git.
class Pickups(dir: File) {
    data class Req(val id: String, val receipt: String, val at: Long, var sent: Boolean = false, var status: String = "")

    companion object {
        val FINAL = setOf("APPLIED", "NOT_FOUND", "AMBIGUOUS", "EXPIRED")
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

    @Synchronized fun add(receipts: List<String>) {
        val now = System.currentTimeMillis()
        receipts.forEach { reqs += Req(UUID.randomUUID().toString(), Lookup.digits(it), now) }
        save()
    }

    /**
     * Sends what isn't sent yet and asks for the outcome of the rest. Network; call off the main thread.
     * Returns the requests that just reached a final answer other than APPLIED (to show on screen).
     */
    fun sync(): List<Req> {
        if (!configured()) return emptyList()
        val cfg = JSONObject(configFile.readText().trim())
        val url = cfg.getString("url"); val secret = cfg.getString("secret")
        val problems = mutableListOf<Req>()
        val todo = synchronized(this) {
            reqs.removeAll { System.currentTimeMillis() - it.at > DAY_MS && (it.status in FINAL || !it.sent) }
            reqs.filter { it.status !in FINAL }.toList()
        }
        for (r in todo.filter { !it.sent }) {
            val res = post(url, JSONObject().put("secret", secret).put("mode", "pickupRequest").put("requestId", r.id)
                .put("receipt", r.receipt).put("revision", "").put("at", java.time.Instant.ofEpochMilli(r.at).toString()))
            val result = res.optString("result")
            if (result == "QUEUED" || result == "ALREADY") synchronized(this) { r.sent = true; r.status = "QUEUED" }
        }
        val sent = todo.filter { it.sent }
        if (sent.isNotEmpty()) {
            val res = post(url, JSONObject().put("secret", secret).put("mode", "pickupStatus")
                .put("requestIds", JSONArray(sent.map { it.id })))
            val statuses = res.optJSONObject("statuses") ?: res
            synchronized(this) {
                for (r in sent) {
                    val s = statuses.optString(r.id, "")
                    if (s.isEmpty() || s == r.status) continue
                    r.status = s
                    if (s in FINAL && s != "APPLIED") problems += r
                }
            }
        }
        synchronized(this) { save() }
        return problems
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
                reqs += Req(getString("id"), getString("receipt"), getLong("at"), optBoolean("sent"), optString("status"))
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        val a = JSONArray()
        reqs.forEach { a.put(JSONObject().put("id", it.id).put("receipt", it.receipt).put("at", it.at).put("sent", it.sent).put("status", it.status)) }
        queueFile.writeText(a.toString())
    }
}
