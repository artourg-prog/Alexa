package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

// Voice Finish (Art): "Sofia, this is Andrei, done 26316 [, send the message]" is recorded EXACTLY like a QR
// scan: one Daily Report Reader `addScan` (no amount; the tablet allocates as for a scan), then the final
// answer from `scanStatus`. The client SMS is a separate, existing `pickup {choice:"now"}` request, only after
// the tailor confirmed the read-back. Contract: Alexa PR #2 6489088068 / 6489092254 / 6489109587 / 6489136930.
// - One finish = one durable event: scanId, scannedAt, worker and receipt are made once, saved before anything
//   is sent, and reused unchanged on every retry (the Reader answers the same id with the same row).
// - What the screen says is only what the Reader answered: queued is not saved; only SMS state SENT is "sent".
// finish-config.json ({"url": Reader /exec, "secret": Sofia's own key, "workers": [Approved Workers names]})
// is put in app storage over USB, never in git; until it exists, finish phrases get "coming soon".
class Finishes(
    private val dir: File,
    private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    private val poster: ((String, JSONObject) -> JSONObject)? = null, // tests: a fake Reader
) {
    data class Item(
        val scanId: String, val worker: String, val receipt: String, val scannedAt: String, val createdMs: Long,
        var sendSms: Boolean = false,          // the tailor confirmed "send the message"
        var scan: String = "",                 // "", ACCEPTED, UNVERIFIED, HELD, ADDED, ALREADY, REJECTED, UNKNOWN
        var reason: String = "", var appliedCents: Long = -1, var receiptId: String = "",
        var smsId: String = "", var sms: String = "", // "", REQUESTED, QUEUED, SENDING, HELD, SENT, UNSURE, FAILED, …
        var durable: Boolean = false,
    )

    companion object {
        val SCAN_FINAL = setOf("ADDED", "ALREADY", "REJECTED", "HELD")
        val SMS_FINAL = setOf("SENT", "FAILED", "NO_PHONE", "BAD_PHONE", "AMBIGUOUS", "NOT_FOUND", "UNSURE", "REJECTED")
        private const val TIMEOUT_MS = 10_000

        /** One plain line for the screen, from the Reader's answers only. */
        fun scanText(i: Item, russian: Boolean): String = if (russian) when (i.scan) {
            "ADDED" -> "Сохранено для ${i.worker}" + (if (i.appliedCents >= 0) ", ${dollars(i.appliedCents)}" else "")
            "ALREADY" -> "Уже сохранено"
            "REJECTED" -> "Не сохранено: ${i.reason}"
            "HELD" -> "Отложено для проверки владельцем"
            "UNVERIFIED" -> "Ждёт: квитанции ещё нет в таблице"
            "ACCEPTED" -> "В очереди — ждёт планшет"
            else -> "Отправляется…"
        } else when (i.scan) {
            "ADDED" -> "Saved for ${i.worker}" + (if (i.appliedCents >= 0) ", ${dollars(i.appliedCents)}" else "")
            "ALREADY" -> "Already saved"
            "REJECTED" -> "Not saved: ${i.reason}"
            "HELD" -> "Held for owner review"
            "UNVERIFIED" -> "Waiting: receipt not in the sheet yet"
            "ACCEPTED" -> "Queued — waiting for the tablet"
            else -> "Sending…"
        }

        fun smsText(i: Item, russian: Boolean): String = if (!i.sendSms) (if (russian) "Без SMS" else "No SMS") else if (russian) when (i.sms) {
            "SENT" -> "SMS отправлено"; "" , "REQUESTED", "QUEUED", "SENDING" -> "SMS: ждёт отправки"
            "HELD" -> "SMS: отложено (тихие часы)"; "UNSURE" -> "SMS: не подтверждено"
            else -> "SMS не отправлено: ${i.sms}"
        } else when (i.sms) {
            "SENT" -> "SMS sent"; "", "REQUESTED", "QUEUED", "SENDING" -> "SMS: waiting to send"
            "HELD" -> "SMS: held (quiet hours)"; "UNSURE" -> "SMS: not confirmed"
            else -> "SMS not sent: ${i.sms}"
        }

        fun dollars(cents: Long) = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
    }

    private val configFile = File(dir, "finish-config.json")
    private val queueFile = File(dir, "finish-queue.json")
    private val tmpFile = File(dir, "finish-queue.tmp")
    private val items = mutableListOf<Item>()
    @Volatile var saveFailed = false; private set

    init { load() }

    fun configured() = configFile.isFile

    /** The Approved Workers names from the config (exact spelling the Reader expects). */
    fun workers(): List<String> = try {
        JSONObject(configFile.readText()).optJSONArray("workers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    } catch (_: Exception) { emptyList() }

    @Synchronized fun get(scanId: String): Item? = items.firstOrNull { it.scanId == scanId }?.copy()

    /** Creates the event and saves it before anything is sent. Null = couldn't save (nothing will be sent). */
    @Synchronized fun add(worker: String, receipt: String, sendSms: Boolean, nowMs: Long = System.currentTimeMillis()): Item? {
        val i = Item(UUID.randomUUID().toString(), worker, Lookup.digits(receipt), java.time.Instant.ofEpochMilli(nowMs).toString(), nowMs,
            sendSms = sendSms, smsId = if (sendSms) UUID.randomUUID().toString() else "")
        items += i
        return if (save()) i.copy() else null
    }

    /** "Send the message" confirmed after the job was already saved: same event, the SMS is requested now. */
    @Synchronized fun requestSms(scanId: String): Boolean {
        val i = items.firstOrNull { it.scanId == scanId } ?: return false
        if (!i.sendSms) { i.sendSms = true; i.smsId = UUID.randomUUID().toString() }
        return save()
    }

    /** Sends / asks about everything not final. Network: off the main thread. Returns the items that changed. */
    fun sync(): List<Item> {
        if (!configured()) return emptyList()
        val cfg = JSONObject(configFile.readText())
        val url = cfg.getString("url"); val secret = cfg.getString("secret")
        val changed = mutableListOf<Item>()
        val todo = synchronized(this) {
            if (items.any { !it.durable }) save()
            items.filter { it.durable && (it.scan !in SCAN_FINAL || (it.sendSms && it.sms !in SMS_FINAL)) }.toList()
        }
        for (i in todo) {
            val before = i.copy()
            if (i.scan == "" || i.scan == "UNKNOWN") {
                val scan = JSONObject().put("scanId", i.scanId).put("workerId", i.worker).put("receipt", i.receipt).put("scannedAt", i.scannedAt)
                val r = post(url, JSONObject().put("secret", secret).put("mode", "addScan").put("scan", scan))
                if (!r.optBoolean("ok", false)) { if (!r.optBoolean("retry", false)) synchronized(this) { i.reason = r.optString("error") }; continue }
                synchronized(this) { applyScan(i, r) }
            } else if (i.scan !in SCAN_FINAL) {
                val r = post(url, JSONObject().put("secret", secret).put("mode", "scanStatus").put("scanId", i.scanId))
                if (r.optBoolean("ok", true)) synchronized(this) { applyScan(i, r) }
            }
            // The SMS is requested once the scan row exists and isn't refused or held (coordinator 6489109587).
            if (i.sendSms && i.sms !in SMS_FINAL && i.scan in setOf("ACCEPTED", "UNVERIFIED", "ADDED", "ALREADY")) {
                val req = JSONObject().put("requestId", i.smsId).put("receipt", i.receipt).put("choice", if (i.sms == "") "now" else "status")
                    .put("by", i.worker).put("source", "sofia")
                if (i.receiptId.isNotEmpty()) req.put("receiptId", i.receiptId)
                val r = post(url, JSONObject().put("secret", secret).put("mode", "pickup").put("request", req))
                val st = r.optString("state", r.optString("status", ""))
                if (st.isNotEmpty()) synchronized(this) { i.sms = st }
            }
            if (i != before) changed += i.copy()
        }
        synchronized(this) { save() }
        return changed
    }

    private fun applyScan(i: Item, r: JSONObject) {
        r.optString("status").takeIf { it.isNotEmpty() }?.let { i.scan = it }
        i.reason = r.optString("reason", i.reason)
        if (r.has("appliedCents")) i.appliedCents = r.optLong("appliedCents", -1)
        r.optString("receiptId").takeIf { it.isNotEmpty() }?.let { i.receiptId = it }
    }

    // Apps Script answers a POST with a redirect to the result, which must be fetched with GET.
    private fun post(url: String, body: JSONObject): JSONObject {
        poster?.let { return it(url, body) }
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
            for (k in 0 until a.length()) a.getJSONObject(k).run {
                items += Item(getString("scanId"), getString("worker"), getString("receipt"), getString("scannedAt"), getLong("createdMs"),
                    optBoolean("sendSms"), optString("scan"), optString("reason"), optLong("appliedCents", -1), optString("receiptId"),
                    optString("smsId"), optString("sms"), durable = true)
            }
        } catch (e: Exception) {
            queueFile.renameTo(File(dir, "finish-queue.unreadable-${System.currentTimeMillis()}.json"))
        }
    }

    private fun save(): Boolean {
        val a = JSONArray()
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        items.removeAll { it.createdMs < dayAgo && it.scan in SCAN_FINAL && (!it.sendSms || it.sms in SMS_FINAL) }
        items.forEach { a.put(JSONObject().put("scanId", it.scanId).put("worker", it.worker).put("receipt", it.receipt)
            .put("scannedAt", it.scannedAt).put("createdMs", it.createdMs).put("sendSms", it.sendSms).put("scan", it.scan)
            .put("reason", it.reason).put("appliedCents", it.appliedCents).put("receiptId", it.receiptId)
            .put("smsId", it.smsId).put("sms", it.sms)) }
        val ok = try {
            FileOutputStream(tmpFile).use { it.write(a.toString().toByteArray()); it.fd.sync() }
            replace(tmpFile, queueFile)
        } catch (e: Exception) { false }
        saveFailed = !ok
        if (ok) items.forEach { it.durable = true }
        return ok
    }
}
