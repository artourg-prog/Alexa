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
        var workday: String = "",
        var reportState: String = "", // WAITING / UNCONFIRMED / RECORDED in the saved payroll report
        var reportReason: String = "", var confirmedMs: Long = 0,
        // Reusable receipt numbers (Alexa #4 6571368558, DR PR #38): the Receipts txId this event is about, resolved by a
        // read-only findReceipt BEFORE the first addScan of a new online submission (bound) and sent unchanged on every
        // retry, report check and SMS request. An event saved offline or by an older version stays unbound ("") and is
        // never given a txId later: the Reader's reuse fence decides it. replyTx = the txId the Reader answered.
        val expectedTx: String = "", val bound: Boolean = false, var replyTx: String = "",
        // Phase times (owner 11-02, 26448: measure before optimizing): Reader first accepted the scan / tablet applied it.
        // savedAt = the Reader's own "Saved at" of the confirming record, shop time; "" when not reported (never our clock).
        var sentMs: Long = 0, var appliedMs: Long = 0, var savedAt: String = "",
        // The recorded spelling when it isn't the plain number (26-490). For the screen only: [receipt] is what is compared and sent.
        var shown: String = "",
    ) {
        fun number() = shown.ifEmpty { receipt }
    }

    /** findReceipt's answer: ok = the Reader was reached; txId = the one live receipt's txId ("" when none, several or none carried). */
    data class Resolution(val ok: Boolean, val txId: String = "")

    companion object {
        val SCAN_FINAL = setOf("ADDED", "ALREADY", "REJECTED", "HELD")
        val SCAN_APPLIED = setOf("ADDED", "ALREADY")
        val SMS_FINAL = setOf("SENT", "FAILED", "NO_PHONE", "BAD_PHONE", "AMBIGUOUS", "NOT_FOUND", "UNSURE", "REJECTED")
        private const val TIMEOUT_MS = 25_000 // Apps Script can take over 10 s on a cold start; every call is safe to repeat

        fun recorded(i: Item) = i.scan in SCAN_APPLIED && i.reportState == "RECORDED" && ReportConfirmation.validDay(i.workday)

        /** An explicit Reader miss in the master list, not a network error or a missing saved report. */
        fun receiptNotFound(i: Item) = i.scan == "UNVERIFIED" &&
            i.reason.startsWith("UNVERIFIED: receipt ${i.receipt} is not in 2026 RECEIPTS")

        /** The Reader's receipt-identity contract (DR 6904a4a): a fresh event is bound only by a Reader that enforces it. */
        const val IDENTITY = "tx-v1"

        /** findReceipt reply → the txId to bind. Only exactly one live receipt binds; 0 or 2+ leave the decision to the Reader.
         *  A Reader without identity "tx-v1" (older deployment) → unresolved: the event stays unbound, as in 0.56. */
        fun resolution(r: JSONObject): Resolution {
            if (!r.optBoolean("ok", false) || r.optString("identity") != IDENTITY) return Resolution(false)
            val a = r.optJSONArray("receipts") ?: return Resolution(false)
            return Resolution(true, if (a.length() == 1) a.optJSONObject(0)?.optString("txId").orEmpty().trim() else "")
        }
        fun reportPending(i: Item) = i.scan in SCAN_APPLIED && !recorded(i)

        /** ADDED proves tablet application only, not a saved payroll report. */
        fun scanText(i: Item, russian: Boolean): String {
            if(recorded(i))return if(russian)"Сохранено в отчёте для ${i.worker}" else "Saved in Daily Report for ${i.worker}"
            if(i.scan in SCAN_APPLIED)return if(russian) {
                if(i.reportState=="UNCONFIRMED")"Запись на планшете; сохранение отчёта не подтверждено" else "На планшете у ${i.worker}; сохраняется в отчёт…"
            } else {
                if(i.reportState=="UNCONFIRMED")"On the tablet; Daily Report save not confirmed" else "On ${i.worker}'s tablet report; saving…"
            }
            return if (russian) when (i.scan) {
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

        /** One line for the home Daily Report list, by what really happened (owner 11-05): being sent, or even applied on
         *  the tablet, is not success; only the Reader's confirmation says "In Daily Report", with its time, never our clock. */
        fun overviewLine(i: Item): String = when {
            recorded(i) -> "In Daily Report" + if (i.savedAt.isNotBlank()) " · ${i.savedAt}" else " · save time not reported"
            i.scan == "HELD" -> "Not in Daily Report · held for owner review"
            i.scan == "REJECTED" -> "Failed · ${scanText(i, false)}"
            receiptNotFound(i) -> "Failed · receipt not in the master list"
            i.scan in SCAN_APPLIED -> if (i.reportState == "UNCONFIRMED") "Applied on tablet · Daily Report not confirmed" else "Applied on tablet · waiting for Daily Report"
            i.scan.isEmpty() -> "Scanned · not sent yet"
            else -> "Pending · waiting for the tablet"
        } + if (i.sendSms) " · ${smsText(i, false)}" else ""

        /** Needs a person: refused, held, not in the master list, or an SMS that ended without being sent. */
        fun overviewProblem(i: Item): Boolean = i.scan == "REJECTED" || i.scan == "HELD" || receiptNotFound(i) ||
            i.sendSms && i.sms in SMS_FINAL && i.sms != "SENT"

        fun dollars(cents: Long) = "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
    }

    private val configFile = File(dir, "finish-config.json")
    private val queueFile = File(dir, "finish-queue.json")
    private val tmpFile = File(dir, "finish-queue.tmp")
    private val items = mutableListOf<Item>()
    private val reviewedFile = File(dir, "finish-reviewed.json")
    private val reviewedTmp = File(dir, "finish-reviewed.tmp")
    private val warningFile=File(dir,"finish-queue.warning")
    private val recoveryAck=File(dir,"finish-queue.recovery-ack")
    private fun latestBackup():Long=dir.listFiles().orEmpty().mapNotNull {
        it.name.removePrefix("finish-queue.unreadable-").removeSuffix(".json").toLongOrNull().takeIf { _->it.name.startsWith("finish-queue.unreadable-") }
    }.maxOrNull()?:0L
    private fun unreviewedBackup():Boolean=latestBackup()>(try { recoveryAck.readText().toLong() } catch(_:Exception) { 0L })
    private var recoveryBlocked=false
    @Volatile var saveFailed = false; private set

    init { load() }

    fun configured() = configFile.isFile
    fun warning():String?=when {
        recoveryBlocked->"Work queue unreadable and could not be backed up. Recording is blocked; original file retained."
        (warningFile.isFile || unreviewedBackup())->"Work queue recovery needs checking. The unreadable file was retained; check the tablet before dismissing."
        saveFailed->"Work queue could not be saved. Unconfirmed actions are retained for retry."
        else->null
    }
    fun acknowledgeRecovery() {
        if(!recoveryBlocked) try { recoveryAck.writeText(latestBackup().toString());warningFile.delete() } catch(_:Exception) { }
    }

    /** The Approved Workers names from the config (exact spelling the Reader expects). */
    fun workers(): List<String> = try {
        JSONObject(configFile.readText()).optJSONArray("workers")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    } catch (_: Exception) { emptyList() }

    @Synchronized fun all():List<Item> = items.map { it.copy() }

    @Synchronized fun get(scanId: String): Item? = items.firstOrNull { it.scanId == scanId }?.copy()

    /** Repeated speech reuses today's durable event, including its SMS request id. */
    /** A new online submission reuses today's event only when it is about the same receipt (same resolved txId). */
    @Synchronized fun addOrReuse(worker:String,receipt:String,nowMs:Long=System.currentTimeMillis(),resolved:Resolution?=null):Item? {
        val zone=java.time.ZoneId.of("America/Los_Angeles")
        val day=java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val existing=items.lastOrNull { it.worker==worker && it.receipt==Lookup.digits(receipt) &&
            java.time.Instant.ofEpochMilli(it.createdMs).atZone(zone).toLocalDate()==day && it.scan!="REJECTED" &&
            (resolved==null || !resolved.ok || it.expectedTx==resolved.txId) }
        if(existing!=null) {
            Lookup.recorded(receipt).takeIf { it.isNotEmpty() && it!=existing.shown }?.let { existing.shown=it;existing.durable=false }
            if(recorded(existing)) { existing.reportState="WAITING";existing.reportReason="";existing.durable=false }
            return if(existing.durable || save()) existing.copy() else null
        }
        return add(worker,receipt,false,nowMs,resolved?.takeIf { it.ok })
    }

    /** Creates the event and saves it before anything is sent. Null = couldn't save (nothing will be sent). */
    @Synchronized fun add(worker: String, receipt: String, sendSms: Boolean, nowMs: Long = System.currentTimeMillis(), resolved: Resolution? = null): Item? {
        val i = Item(UUID.randomUUID().toString(), worker, Lookup.digits(receipt), java.time.Instant.ofEpochMilli(nowMs).toString(), nowMs,
            sendSms = sendSms, smsId = if (sendSms) UUID.randomUUID().toString() else "",
            expectedTx = resolved?.txId.orEmpty(), bound = resolved != null, shown = Lookup.recorded(receipt))
        items += i
        return if (save()) i.copy() else null
    }

    /** "Send the message" confirmed after the job was already saved: same event, the SMS is requested now. */
    @Synchronized fun requestSms(scanId: String): Boolean {
        val i = items.firstOrNull { it.scanId == scanId } ?: return false
        if (!i.sendSms) { i.sendSms = true; i.smsId = UUID.randomUUID().toString(); i.durable=false }
        return save()
    }

    /** A receipt the Reader keeps answering UNVERIFIED for (not in 2026 RECEIPTS, e.g. a deleted test receipt) never
     *  becomes final, so its notice would stay forever. After a day, with no SMS ever requested, a person may mark it
     *  reviewed: it moves to finish-reviewed.json (kept, never deleted) and stops being re-checked. Nothing is sent to
     *  the tablet, the Reader or the customer, and no credit changes. */
    @Synchronized fun dismissable(nowMs: Long = System.currentTimeMillis()): List<Item> =
        items.filter { it.durable && it.scan == "UNVERIFIED" && it.createdMs < nowMs - 24 * 3600_000L && !it.sendSms && it.smsId.isEmpty() && it.sms.isEmpty() }
            .map { it.copy() }

    @Synchronized fun dismiss(scanId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val i = dismissable(nowMs).firstOrNull { it.scanId == scanId } ?: return false
        val history = try { JSONArray(reviewedFile.readText()) } catch (_: Exception) { if (reviewedFile.exists()) return false else JSONArray() }
        if ((0 until history.length()).none { history.getJSONObject(it).optString("scanId") == scanId }) {
            history.put(json(i).put("reviewedAt", java.time.Instant.ofEpochMilli(nowMs).toString()))
            val ok = try {
                FileOutputStream(reviewedTmp).use { it.write(history.toString().toByteArray()); it.fd.sync() }
                replace(reviewedTmp, reviewedFile)
            } catch (_: Exception) { false }
            if (!ok) return false
        }
        val at = items.indexOfFirst { it.scanId == scanId }; val original = items.removeAt(at)
        if (save()) return true
        items.add(at, original); return false
    }

    @Synchronized fun reviewed(): JSONArray = try { JSONArray(reviewedFile.readText()) } catch (_: Exception) { JSONArray() }

    /** Sends / asks about everything not final (or only [onlyId]: the entry a screen is waiting on, checked back-to-back
     *  without waiting behind older entries). Network: off the main thread. Returns the items that changed. */
    fun sync(onlyId: String? = null): List<Item> {
        if (!configured()) return emptyList()
        val cfg = JSONObject(configFile.readText())
        val url = cfg.getString("url"); val secret = cfg.getString("secret")
        val changed = mutableListOf<Item>()
        val todo = synchronized(this) {
            if (items.any { !it.durable }) save()
            items.filter { it.durable && (onlyId == null || it.scanId == onlyId) && (it.scan !in SCAN_FINAL || reportPending(it) || (it.sendSms && it.sms !in SMS_FINAL)) }
                .sortedByDescending { it.createdMs }
        }
        for (i in todo) {
            val before = i.copy()
            try {
            if (i.scan == "" || i.scan == "UNKNOWN") {
                val scan = JSONObject().put("scanId", i.scanId).put("workerId", i.worker).put("receipt", i.receipt).put("scannedAt", i.scannedAt)
                    .also { if (i.expectedTx.isNotEmpty()) it.put("txId", i.expectedTx) }
                val r = post(url, JSONObject().put("secret", secret).put("mode", "addScan").put("scan", scan))
                if (!r.optBoolean("ok", false)) { if (!r.optBoolean("retry", false)) synchronized(this) { i.reason = r.optString("error") }; continue }
                synchronized(this) { if (i.sentMs == 0L) i.sentMs = System.currentTimeMillis(); applyScan(i, r) }
            } else if (i.scan !in SCAN_FINAL || reportPending(i) && !ReportConfirmation.validDay(i.workday)) {
                val r = post(url, JSONObject().put("secret", secret).put("mode", "scanStatus").put("scanId", i.scanId))
                if (r.optBoolean("ok", true)) synchronized(this) { applyScan(i, r) }
            }
            if(reportPending(i)) {
                if(!ReportConfirmation.validDay(i.workday)) {
                    synchronized(this) { i.reportState="UNCONFIRMED";i.reportReason="Waiting for the Reader's work date" }
                } else {
                    val r=post(url,JSONObject().put("secret",secret).put("mode","receiptReportStatus").put("number",i.receipt)
                        .also { if(i.expectedTx.isNotEmpty())it.put("receiptId",i.expectedTx) })
                    synchronized(this) {
                        val record=ReportConfirmation.match(r,i.workday,i.worker,i.expectedTx)
                        if(record!=null) {
                            i.reportState="RECORDED";i.reportReason="";i.confirmedMs=System.currentTimeMillis()
                            i.savedAt=ReportConfirmation.savedAt(record)
                        } else {
                            i.reportState=if(r.optBoolean("ok",false))"WAITING" else "UNCONFIRMED"
                            i.reportReason=if(r.optBoolean("ok",false))"Not in the saved worker/day report yet" else "Could not verify Daily Report save"
                        }
                    }
                }
            }
            // The SMS is requested once the scan row exists and isn't refused or held (coordinator 6489109587).
            val smsRequest=synchronized(this) {
                if (i.durable && i.sendSms && i.sms !in SMS_FINAL && i.scan in setOf("ACCEPTED", "UNVERIFIED", "ADDED", "ALREADY")) {
                    JSONObject().put("requestId",i.smsId).put("receipt",i.receipt).put("choice",if(i.sms=="")"now" else "status")
                        .put("by",i.worker).put("source","sofia").also { if(i.receiptId.isNotEmpty())it.put("receiptId",i.receiptId)
                            if(i.expectedTx.isNotEmpty())it.put("txId",i.expectedTx) }
                } else null
            }
            if(smsRequest!=null) {
                val r = post(url, JSONObject().put("secret", secret).put("mode", "pickup").put("request", smsRequest))
                val st = r.optString("state", r.optString("status", ""))
                if (st.isNotEmpty()) synchronized(this) { i.sms = st }
            }
            } catch (_: Exception) {
                synchronized(this) {
                    if(reportPending(i)) { i.reportState="UNCONFIRMED";i.reportReason="Connection unavailable; Daily Report save not confirmed" }
                }
            }
            if (i != before) changed += i.copy()
        }
        synchronized(this) { save() }
        return changed
    }

    private fun applyScan(i: Item, r: JSONObject) {
        val tx = r.optString("txId").trim()
        if (tx.isNotEmpty()) i.replyTx = tx
        if (i.expectedTx.isNotEmpty() && tx.isNotEmpty() && tx != i.expectedTx) {
            // The Reader answered about another receipt than the one this event names: never take its state as ours.
            i.scan = "REJECTED"; i.reason = "IDENTITY_MISMATCH: the Reader answered for a different receipt — check the tablet"
            return
        }
        r.optString("status").takeIf { it.isNotEmpty() }?.let { i.scan = it }
        if (i.scan in SCAN_APPLIED && i.appliedMs == 0L) i.appliedMs = System.currentTimeMillis()
        i.reason = r.optString("reason", i.reason)
        if (r.has("appliedCents")) i.appliedCents = r.optLong("appliedCents", -1)
        r.optString("receiptId").takeIf { it.isNotEmpty() }?.let { i.receiptId = it }
        r.optString("workday").takeIf(ReportConfirmation::validDay)?.let { i.workday = it }
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

    /** Read-only: the live receipts behind a number (findReceipt), for binding a NEW online submission before its
     *  first addScan. Any failure → Resolution(false): the event is saved unbound, exactly as before. Network: off main. */
    fun resolve(number: String): Resolution = try {
        if (!configured()) Resolution(false) else {
            val cfg = JSONObject(configFile.readText())
            resolution(post(cfg.getString("url"), JSONObject().put("secret", cfg.getString("secret"))
                .put("mode", "findReceipt").put("number", Lookup.digits(number))))
        }
    } catch (_: Exception) { Resolution(false) }

    /** Separate read-only endpoint; does not add/change a scan or request SMS. */
    fun reportStatus(number: String): JSONObject {
        val cfg = JSONObject(configFile.readText())
        return post(cfg.getString("url"), JSONObject().put("secret", cfg.getString("secret"))
            .put("mode", "receiptReportStatus").put("number", number))
    }

    private fun load() {
        if (!queueFile.isFile) return
        try {
            val a = JSONArray(queueFile.readText())
            for (k in 0 until a.length()) a.getJSONObject(k).run {
                items += Item(getString("scanId"), getString("worker"), getString("receipt"), getString("scannedAt"), getLong("createdMs"),
                    optBoolean("sendSms"), optString("scan"), optString("reason"), optLong("appliedCents", -1), optString("receiptId"),
                    optString("smsId"), optString("sms"), durable = true,
                    workday=optString("workday"),reportState=optString("reportState"),reportReason=optString("reportReason"),confirmedMs=optLong("confirmedMs",0),
                    expectedTx=optString("expectedTx"),bound=optBoolean("bound"),replyTx=optString("replyTx"),
                    sentMs=optLong("sentMs"),appliedMs=optLong("appliedMs"),savedAt=optString("savedAt"),shown=optString("shown"))
            }
        } catch (e: Exception) {
            items.clear() // a partially parsed file must not masquerade as a complete recovered queue
            recoveryBlocked=!queueFile.renameTo(File(dir, "finish-queue.unreadable-${System.currentTimeMillis()}.json"))
            try { warningFile.writeText("Unverified queue recovery; backup retained.") } catch(_:Exception) {}
        }
    }

    private fun json(it: Item) = JSONObject().put("scanId", it.scanId).put("worker", it.worker).put("receipt", it.receipt)
        .put("scannedAt", it.scannedAt).put("createdMs", it.createdMs).put("sendSms", it.sendSms).put("scan", it.scan)
        .put("reason", it.reason).put("appliedCents", it.appliedCents).put("receiptId", it.receiptId)
        .put("smsId", it.smsId).put("sms", it.sms).put("workday",it.workday).put("reportState",it.reportState)
        .put("reportReason",it.reportReason).put("confirmedMs",it.confirmedMs)
        .put("expectedTx",it.expectedTx).put("bound",it.bound).put("replyTx",it.replyTx)
        .put("sentMs",it.sentMs).put("appliedMs",it.appliedMs).put("savedAt",it.savedAt).put("shown",it.shown)

    private fun save(): Boolean {
        if(recoveryBlocked) { saveFailed=true;return false }
        val a = JSONArray()
        val dayAgo = System.currentTimeMillis() - 24 * 3600_000L
        items.removeAll { (if(recorded(it))it.confirmedMs else it.createdMs) < dayAgo && it.scan in SCAN_FINAL &&
            !reportPending(it) && (!it.sendSms || it.sms in SMS_FINAL) }
        items.forEach { a.put(json(it)) }
        val ok = try {
            FileOutputStream(tmpFile).use { it.write(a.toString().toByteArray()); it.fd.sync() }
            replace(tmpFile, queueFile)
        } catch (e: Exception) { false }
        saveFailed = !ok
        if (ok) items.forEach { it.durable = true }
        return ok
    }
}
