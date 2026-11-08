package com.tailorshop.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tailorshop.counter.conversation.OrderCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted, bundled touch UI. Existing durable queues and API credentials stay in private app storage. */
class TouchActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val reads = Executors.newSingleThreadExecutor()
    private val writes = Executors.newSingleThreadExecutor()   // queue sync (network)
    private val saves = Executors.newSingleThreadExecutor()    // a person's Save / Picked up: never waits behind a sync pass
    private val updating = Executors.newSingleThreadExecutor()
    private val syncing = Queues.syncing
    private lateinit var web: WebView
    private lateinit var finishes: Finishes
    private lateinit var pickups: Pickups
    private lateinit var findLog: FindLog
    private lateinit var sheets: Sheets
    private lateinit var cache: OrderCache
    private lateinit var lists: ListReads
    private val orders get() = lists.orders
    private val loadedAt get() = lists.loadedAt
    private val offline get() = lists.offline
    private var resumed = false
    private var destroyed = false
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var voiceFailed = false
    private var lastSpoken = ""
    private var lastMale = false
    private var waitingUtterance: Pair<String, Boolean>? = null // asked for before the engine was ready: spoken on init
    private var utterance = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            Updater.confirmIntent?.let { Updater.confirmIntent = null; startActivity(it) }
            synchronizeQueues()
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        finishes = Queues.finishes(this); pickups = Queues.pickups(this); findLog = Queues.findLog(this)
        sheets = Sheets(File(filesDir, "sheet-key.json")); cache = OrderCache(filesDir)
        lists = ListReads(sheets::readAll, cache::save)
        cache.load()?.let { snapshot -> runCatching { lists.restore(snapshot.rows, snapshot.at) } }
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
        web.setBackgroundColor(android.graphics.Color.rgb(238, 245, 238))
        // No text-selection mode on a long press: the page uses the hold on "Sofia" to open Settings.
        web.setOnLongClickListener { true }; web.isLongClickable = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        web.addJavascriptInterface(Bridge(), "Sofia")
        setContentView(web)
        web.loadUrl("file:///android_asset/counter.html")
        // 0.56 dropped every announcement asked for before the engine finished starting, and never noticed engine or
        // voice errors (silent on the owner's phone, 11-02). Now: spoken on init, played as assistant speech (the same
        // audio usage as Sofia's chimes), and an engine/voice error or muted media is shown under the spoken text.
        tts = TextToSpeech(this) { code -> main.post {
            voiceReady = code == TextToSpeech.SUCCESS; voiceFailed = !voiceReady
            if (!voiceReady) { voiceProblem("Voice engine unavailable on this phone — text only."); return@post }
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                @Deprecated("Android callback") override fun onError(id: String?) { onError(id, -1) }
                override fun onError(id: String?, code: Int) { main.post { if (id == "touch-$utterance") voiceProblem("Voice playback failed (engine error $code) — text only.") } }
            })
            waitingUtterance?.let { (text, male) -> waitingUtterance = null; speak(text, male) }
        } }
        Updater.scheduleNight(this)
        // Touch is now the primary input. Do not wake a microphone conversation in the background.
        Wake.cancel(this, 1); Wake.cancel(this, 3)
    }

    override fun onResume() {
        super.onResume(); resumed = true; Wake.clear(this)
        main.removeCallbacks(ticker); main.post(ticker)
    }

    override fun onPause() {
        resumed = false; main.removeCallbacks(ticker); tts?.stop()
        // Unfinished saved work / pickup taps keep going in the background (screen off, app closed, restart).
        if (QueueJob.unfinished(finishes, pickups)) QueueJob.schedule(this)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("Sofia"); web.destroy(); tts?.shutdown()
        reads.shutdown(); writes.shutdown(); saves.shutdown(); updating.shutdown(); super.onDestroy()
    }

    /** System Back steps back inside the page (as the on-screen Back does); on the home screen it leaves as before. */
    @Deprecated("Activity back callback") override fun onBackPressed() {
        web.evaluateJavascript("window.counterBack ? window.counterBack() : false") { result ->
            @Suppress("DEPRECATION") if (result != "true" && !destroyed) super.onBackPressed()
        }
    }

    private fun reply(id: String, result: JSONObject) {
        main.post {
            if (!destroyed) web.evaluateJavascript("window.counterReply(${JSONObject.quote(id)},${JSONObject.quote(result.toString())})", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun request(id: String, action: String, json: String) {
            // Every request is answered (0.56 dropped an oversized one silently, leaving the page waiting forever).
            if (id.length > 80) return
            if (json.length > 4096) { reply(id, error("Request too large.")); return }
            val body = runCatching { JSONObject(json) }.getOrElse { reply(id, error("Invalid request")); return }
            when (action) {
                "speak" -> main.post {
                    val problem = speak(body.optString("text").take(700), body.optString("voice") == "male-en")
                    reply(id, JSONObject().put("ok", true).put("problem", problem ?: ""))
                }
                "settings" -> main.post { settings(); reply(id, JSONObject().put("ok", true)) }
                "load" -> reply(id, JSONObject().put("ok", true).put("workers", JSONArray(finishes.workers()))
                    .put("finishConfigured", finishes.configured()).put("pickupConfigured", pickups.configured()))
                "find", "report" -> { val asked = System.currentTimeMillis(); reads.execute {
                    try { lookup(id, action, body.optString("number"), asked) }
                    catch (_: Exception) {
                        if (action == "find") logMiss(body.optString("number").trim(), FindLog.NO_ANSWER)
                        reply(id, error("Orders unavailable. Check the connection and try again."))
                    }
                } }
                "reopen" -> { val asked = System.currentTimeMillis(); reads.execute {
                    try { reopen(id, body.optString("last4"), body.optString("receipt"), body.optString("txn"), body.optString("total"), body.optString("shown"), asked) }
                    catch (_: Exception) { reply(id, error("Orders unavailable. Check the connection and try again.")) }
                } }
                "save", "pickup" -> saves.execute {
                    try {
                        if (action == "save") saveWork(id, body) else savePickup(id, body.optString("number"), body.optString("txn"))
                    } catch (_: Exception) { reply(id, error("Could not save. Please try again.")) }
                }
                "status" -> {
                    val event = finishes.get(body.optString("id"))
                    // The screen is waiting on this entry (asked every 2 s): check it again right away, not on the 5 s tick.
                    if (event != null && (event.scan !in Finishes.SCAN_FINAL || Finishes.reportPending(event))) synchronizeQueues(event.scanId)
                    reply(id, event?.let(::finishJson) ?: error("Saved request unavailable. Check pending work."))
                }
                "queue" -> reply(id, queueJson())
                "overview" -> reply(id, overviewJson())
                "dismiss" -> saves.execute {
                    val ok = finishes.dismiss(body.optString("id"))
                    reply(id, if (ok) queueJson() else error("Couldn't mark it reviewed. Nothing was changed."))
                }
                else -> reply(id, error("Unsupported action"))
            }
        }
    }

    private val index get() = lists.index

    private val indexFresh get() = lists.indexFresh
    private val listAge get() = lists.listAge

    // Every tab (ready list, Order Index, Picked Up Orders, Sync Status) in one read; see ListReads. A failed read keeps the
    // last good copy and marks everything offline with that copy's age, so the screen never presents it as current.
    private fun refreshLists(asked: Long = System.currentTimeMillis()) = lists.refresh(asked)

    private fun lookup(id: String, action: String, value: String, asked: Long) {
        val number = Lookup.plainReceipt(value)
        if (action == "find" && !number.matches(Regex("[0-9]{4}"))) { reply(id, error("Enter the last four phone digits.")); return }
        if (action == "report" && !number.matches(Regex("[0-9]{3,9}"))) { reply(id, error("Enter a receipt number.")); return }
        // Always read now (owner 11-08): a number already in the copy used to be answered from a copy up to a minute old.
        runCatching { refreshLists(asked) }
        if (action == "find" && loadedAt == 0L) { logMiss(number, FindLog.NO_ANSWER); reply(id, error("Orders not loaded. Connect to the internet and try again.")); return }
        // An order already tapped Picked up stays visible with its pickup state (0.53 hid it, so a listed order could
        // answer "No matching receipt" while its tap was waiting or settled; owner report 11-01, last four 5502).
        fun search() = Lookup.find(if (action == "find") Query.Last4(number) else Query.Receipt(number), orders)
        val results = search()
        // A miss is only an answer when the list is fresh: if the read failed, say so instead of a false
        // "no matching receipt" from an old copy (the 0.41 rule).
        if (action == "find" && results.isEmpty() && offline) {
            logMiss(number, FindLog.NO_ANSWER)
            reply(id, error("Couldn't refresh the orders list, so no answer yet. Check Wi-Fi and try again.")); return
        }
        // A work entry may not yet be in the ready-orders mirror. It is validated by the existing Reader when saved.
        val cards = if (action == "report" && results.isEmpty()) listOf(Card(number, "", "")) else results
        // Find also lists the same digits from the Order Index when they aren't in the ready list, each with its own
        // state (not ready + promised time / already picked up / unknown). Picked up stays ready-list only.
        val extra = if (action == "find") OrderIndex.find(number, index).filter { e -> results.none { Lookup.digits(it.receipt) == Lookup.digits(e.receipt) } } else emptyList()
        if (action == "find") logFind(number, results, extra)
        reply(id, answer(action, number, cards, results.isEmpty(), extra))
    }

    /** A tap on a Find row. It only reads, and it reads everything again first, so the row is never the authority: nothing
     *  is logged, marked or sent here. Picked up stays the card's own button, with its own re-read before anything is kept. */
    private fun reopen(id: String, last4: String, receipt: String, txn: String, total: String, shown: String, asked: Long) {
        if (!last4.matches(Regex("[0-9]{4}")) || !receipt.matches(Regex("[0-9]+"))) { reply(id, error("This row can't be opened. Search again.")); return }
        runCatching { refreshLists(asked) }; runCatching { settleFinds() }
        val same = Lookup.find(Query.Last4(last4), orders).filter { Lookup.digits(it.receipt) == receipt }
        val cards = same.filter { txn.isEmpty() || it.txn == txn }
        val changed = cards.isEmpty() && same.isNotEmpty() // the number now belongs to another order: its index row says nothing about this one
        val extra = if (cards.isEmpty() && !changed) OrderIndex.find(last4, index).filter { Lookup.digits(it.receipt) == receipt } else emptyList()
        val json = answer("find", last4, cards, false, extra, checked = true)
        if (cards.isEmpty() && extra.isEmpty()) json.put("receipts", JSONArray().put(cardJson(Card(shown.takeIf { Lookup.digits(it) == receipt } ?: receipt, "", "", total = total)).put("ready", false).put("state", "UNKNOWN")
            .put("stateText", if (offline || !indexFresh) "Couldn't refresh, so the current status is unknown" else if (changed) "This order changed — search again" else "Not on the lists any more — check the tablet")))
        reply(id, json)
    }

    /** The cards for the screen. [checked] = a reopened row: a card that may not be picked up says why and offers no button. */
    private fun answer(action: String, number: String, cards: List<Card>, unverified: Boolean, extra: List<OrderIndex.Entry>, checked: Boolean = false): JSONObject {
        val readyFresh = !offline
        fun sameNumber(r: String) = index.filter { Lookup.digits(it.receipt) == Lookup.digits(r) && Lookup.digits(it.phone).takeLast(4) == number }
        return JSONObject().put("ok", true).put("receipts", JSONArray().apply {
            cards.forEach { card -> put(cardJson(card, action == "report" && unverified).also { json ->
                val tap = if (action == "find" && pickups.pendingFor(card.receipt, card.txn)) pickups.latest(card.receipt, card.txn) else null
                if (tap != null) json.put("pickupNote", Pickups.stateText(tap))
                if (action == "find") json.put("pickupLine", tap?.let(Pickups::stateText) ?: OrderIndex.pickupLine(true, readyFresh, indexFresh, sameNumber(card.receipt), listAge))
                if (checked && tap == null && !OrderIndex.pickupAllowed(json.getString("pickupLine"))) json.put("pickupNote", json.getString("pickupLine"))
                if (checked && json.has("pickupNote")) json.put("blocked", true) }) }
            extra.forEach { e -> put(cardJson(Card(e.receipt, e.customer, ""), false).put("ready", false)
                .put("state", e.state.name).put("stateText", OrderIndex.stateText(e) + if (indexFresh) "" else " · couldn't refresh, may be out of date")
                .put("stale", listAge.level != ListAge.Level.CURRENT && e.state != OrderIndex.State.COLLECTED)
                .put("pickupLine", OrderIndex.pickupLine(false, readyFresh, indexFresh, listOf(e), listAge))) }
        }).put("offline", offline).put("loadedAt", loadedAt).also { json -> if (action == "find") json.put("listState", listAge.level.name)
            .put("asOf", listAge.asOf).put("missText", ListAge.missText(listAge)) }
    }

    private fun picked(receipt: String, last4: String) = index.filter { it.state == OrderIndex.State.COLLECTED &&
        Lookup.digits(it.receipt) == Lookup.digits(receipt) && Lookup.digits(it.phone).takeLast(4) == last4 }

    /** Keeps the search for the right column on home. The history must never get in the way of the answer itself. */
    private fun logFind(last4: String, results: List<Card>, extra: List<OrderIndex.Entry>) = runCatching {
        settleFinds()
        val hits = results.map { c -> val prior = picked(c.receipt, last4).size
            FindLog.Hit(Lookup.digits(c.receipt), c.txn, if (prior > 0) FindLog.CONFLICT else if (c.ready) FindLog.READY else FindLog.NOT_READY, prior, Lookup.recorded(c.receipt), c.total) } +
            extra.map { e -> FindLog.Hit(Lookup.digits(e.receipt), "", e.state.name, picked(e.receipt, last4).size, Lookup.recorded(e.receipt)) }
        findLog.add(last4, hits.ifEmpty { listOf(FindLog.Hit("", "", FindLog.NOT_FOUND)) }, listAge.level.name, listAge.asOf, offline || !indexFresh)
    }

    private fun logMiss(last4: String, state: String) {
        if (last4.matches(Regex("[0-9]{4}"))) runCatching { findLog.add(last4, listOf(FindLog.Hit("", "", state)), "", "", true) }
    }

    /** After a good read: what the shared list now says about each open request. A failed read changes nothing. */
    private fun settleFinds() {
        if (offline || !indexFresh) return
        findLog.settleList({ e ->
            val same = index.filter { Lookup.digits(it.receipt) == e.receipt && Lookup.digits(it.phone).takeLast(4) == e.last4 }
            val done = same.filter { it.state == OrderIndex.State.COLLECTED }
            val ready = orders.firstOrNull { Lookup.digits(it.receipt) == e.receipt && Lookup.digits(it.phone).takeLast(4) == e.last4 }
            val state = when {
                ready != null && done.isNotEmpty() -> FindLog.CONFLICT
                ready != null -> if (ready.ready) FindLog.READY else FindLog.NOT_READY
                else -> same.firstOrNull { it.state != OrderIndex.State.COLLECTED }?.state?.name
            }
            FindLog.Seen(state, done.size, done.firstOrNull { it.collectedAt.isNotBlank() }?.collectedAt.orEmpty())
        }, listAge.level.name, listAge.asOf)
    }

    private fun cardJson(c: Card, unverified: Boolean = false) = JSONObject()
        .put("number", c.receipt).put("name", c.customer).put("total", c.total).put("work", c.work)
        .put("ready", c.ready).put("pay", c.pay.name).put("unverified", unverified).put("txn", c.txn).put("due", c.due)

    private fun saveWork(id: String, body: JSONObject) {
        val worker = body.optString("worker"); val recorded = body.optString("number"); val number = Lookup.plainReceipt(recorded)
        if (!finishes.configured()) { reply(id, error("Daily Report connection is not configured.")); return }
        if (worker !in finishes.workers() || !number.matches(Regex("[0-9]{3,9}"))) { reply(id, error("Choose an approved worker and receipt number.")); return }
        // Reusable numbers: a new online submission names its exact receipt (read-only findReceipt) before the first send.
        val item = finishes.addOrReuse(worker, recorded, resolved = finishes.resolve(number))
        if (item == null) { reply(id, error("Not saved on this phone. Please retry.")); return }
        if (body.optBoolean("sms") && !finishes.requestSms(item.scanId)) {
            reply(id, error("Work saved locally, but the SMS choice could not be saved. Check pending work.")); return
        }
        reply(id, finishJson(finishes.get(item.scanId)!!))
        synchronizeQueues()
    }

    private fun finishJson(i: Finishes.Item) = JSONObject().put("ok", true).put("id", i.scanId)
        .put("number", i.number()).put("worker", i.worker).put("at", i.scannedAt).put("scan", i.scan)
        .put("smsRequested", i.sendSms).put("sms", i.sms).put("scanText", Finishes.scanText(i, false))
        .put("smsText", Finishes.smsText(i, false)).put("reason", i.reason)
        .put("recorded",Finishes.recorded(i)).put("workday",i.workday)
        .put("receiptNotFound", Finishes.receiptNotFound(i))
        .put("reportState",i.reportState).put("reportReason",i.reportReason)

    private fun savePickup(id: String, recorded: String, txn: String) {
        val number = Lookup.plainReceipt(recorded) // the card hands back the recorded spelling (26-490); only that exact form is turned plain
        if (!pickups.configured()) { reply(id, error("Pickup connection is not configured.")); return }
        // The tapped card's Txn must still be the row in the list: a reused number's new receipt is never marked for the old one.
        val matches = orders.filter { Lookup.digits(it.receipt) == number }
            .distinctBy { listOf(Lookup.digits(it.receipt), Lookup.digits(it.phone), it.customer, it.txn) }
        if (matches.size != 1 || !matches.single().ready) { reply(id, error("Pickup is unavailable or ambiguous. Check the order.")); return }
        if (matches.single().txn != txn) { reply(id, error("This order changed. Look it up again.")); return }
        // Re-read right before the tap is kept (owner 11-02): a pickup already recorded for this number and phone, or a
        // conflicting record, is never offered again; a failed re-read is said, not treated as "not picked up".
        runCatching { refreshLists() }
        val phone4 = Lookup.digits(matches.single().phone).takeLast(4)
        val line = OrderIndex.pickupLine(true, !offline, indexFresh, index.filter { Lookup.digits(it.receipt) == number && Lookup.digits(it.phone).takeLast(4) == phone4 }, listAge)
        if (!OrderIndex.pickupAllowed(line)) { reply(id, error("Pickup not recorded: $line.")); return }
        if (orders.none { Lookup.digits(it.receipt) == number && it.txn == txn && it.ready }) { reply(id, error("This order changed. Look it up again.")); return }
        if (!pickups.addOrReuse(recorded, txn)) { reply(id, error("Pickup not saved on this phone. Please retry.")); return }
        val request = pickups.latest(number, txn)!!
        reply(id, JSONObject().put("ok", true).put("number", request.number()).put("status", request.status)
            .put("message", Pickups.stateText(request)))
        synchronizeQueues()
    }

    private fun queueJson(): JSONObject {
        // Waiting on the tablet / Reader = processing. UNVERIFIED for over an hour (the receipt isn't in the
        // master) is a problem to check, not "processing" forever (0.48 showed old test receipts as processing).
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        fun stuck(i: Finishes.Item) = i.scan == "UNVERIFIED" && i.createdMs < hourAgo
        val pendingWork = finishes.all().count { !stuck(it) && (it.scan !in Finishes.SCAN_FINAL || Finishes.reportPending(it) || it.sendSms && it.sms !in Finishes.SMS_FINAL) }
        val pendingPickups = pickups.pending().count { pickups.latest(it)?.status !in Pickups.DONE }
        val workProblems = finishes.all().filter {
            it.scan in setOf("HELD", "REJECTED") || stuck(it) || it.sendSms && it.sms in Finishes.SMS_FINAL && it.sms != "SENT"
        }.map { "#${it.number()}: ${Finishes.scanText(it, false)} · ${Finishes.smsText(it, false)}" }
        val problems = listOfNotNull(finishes.warning(), pickups.warning()) + workProblems + pickups.toCheck().map { Pickups.problemText(it.number(), it.status) }
        // Not-in-the-sheet entries over a day old with no SMS can be marked reviewed by a person (Finishes.dismiss).
        val dismiss = JSONArray(finishes.dismissable().map { JSONObject().put("id", it.scanId).put("receipt", it.number()) })
        return JSONObject().put("ok", true).put("work", pendingWork).put("pickups", pendingPickups)
            .put("warning", problems.joinToString("\n")).put("dismiss", dismiss)
    }

    /** Home overview: today's Daily Report entries made on this phone, newest first. Saved time only from the Reader. */
    private fun overviewJson(): JSONObject {
        val zone = java.time.ZoneId.of("America/Los_Angeles"); val today = java.time.LocalDate.now(zone)
        val list = finishes.all().filter { java.time.Instant.ofEpochMilli(it.createdMs).atZone(zone).toLocalDate() == today }
            .sortedByDescending { it.createdMs }.take(200)
        return JSONObject().put("ok", true).put("report", JSONArray(list.map { i ->
            JSONObject().put("number", i.number()).put("worker", i.worker).put("recorded", Finishes.recorded(i)).put("time", FindLog.time(i.createdMs))
                .put("id", i.scanId).put("line", Finishes.overviewLine(i)).put("problem", Finishes.overviewProblem(i)) }))
            .also { json -> findsInto(json); if (QueueJob.unfinished(finishes, pickups)) synchronizeQueues() }
    }

    @Volatile private var findsReadAt = 0L
    @Volatile private var findsRead = false

    /** Right column: today's searches with their answers; open ones re-read from the shared list every two minutes while any is waiting. */
    private fun findsInto(json: JSONObject) {
        val tap = { e: FindLog.Entry -> pickups.latest(e.receipt, e.txn) }
        findLog.settleTaps(tap)
        if (findLog.anyOpen() && System.currentTimeMillis() - findsReadAt > 120_000L) {
            findsReadAt = System.currentTimeMillis()
            reads.execute { runCatching { refreshLists(); settleFinds() }; findsRead = true }
        }
        json.put("finds", JSONArray(findLog.rows(tap).map { r -> JSONObject().put("id", r.entry.id).put("time", r.time).put("number", r.entry.number())
            .put("last4", r.entry.last4).put("line", r.line).put("unsure", r.unsure).put("state", r.entry.state)
            .put("receipt", r.entry.receipt).put("txn", r.entry.txn).put("amount", r.entry.amount).put("picked", r.entry.pickedMs != 0L) }))
        json.put("findNote", listOfNotNull(findLog.warning(), findSource().takeIf { it.isNotEmpty() }).joinToString(" · "))
    }

    /** How current the right column is as a whole: a row keeps the state of its last good read. */
    private fun findSource() = when {
        !findLog.anyOpen() -> ""
        !findsRead -> "Checking the list…"
        offline || !indexFresh -> "Offline · statuses are from the last good check"
        else -> ""
    }

    private fun synchronizeQueues(onlyId: String? = null) {
        if (!syncing.compareAndSet(false, true)) return
        writes.execute {
            try { runCatching { finishes.sync(onlyId).forEach(::logPhases) }; if (onlyId == null) runCatching { pickups.sync() } }
            finally { syncing.set(false) }
        }
    }

    // Phase timings for a slow confirmation (owner 11-02, 26448), seconds after the entry: accepted / applied / confirmed.
    private fun logPhases(i: Finishes.Item) {
        fun s(ms: Long) = if (ms == 0L) "-" else ((ms - i.createdMs) / 1000).toString()
        android.util.Log.d("TailorCounter", "report phases ${i.scanId.take(8)}: accepted=${s(i.sentMs)}s applied=${s(i.appliedMs)}s " +
            "confirmed=${s(if (Finishes.recorded(i)) i.confirmedMs else 0)}s scan=${i.scan} report=${i.reportState}")
    }

    private fun voiceProblem(message: String) {
        if (!destroyed) web.evaluateJavascript("window.counterVoice&&window.counterVoice(${JSONObject.quote(message)})", null)
    }

    private fun options(t: TextToSpeech): List<Pair<Voices.Option, android.speech.tts.Voice>> = runCatching {
        t.voices.orEmpty().map { v -> Voices.Option(v.name, v.locale.language,
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(), v.isNetworkConnectionRequired) to v }
    }.getOrDefault(emptyList())

    private fun online() = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** One whole announcement in one voice: male English for a DUE order, the usual Sofia voice otherwise.
     *  Returns what kept it from being spoken as asked (shown on screen), or null. */
    private fun speak(text: String, male: Boolean = false): String? {
        lastSpoken = text; lastMale = male
        if (!resumed) return null
        if (voiceFailed) return "Voice engine unavailable on this phone — text only."
        if (!voiceReady) { waitingUtterance = text to male; return null }
        val t = tts ?: return "Voice engine unavailable — text only."
        val prefs = getSharedPreferences("voice", MODE_PRIVATE); val all = options(t); val net = online()
        val pick = if (male) Voices.maleEnglish(all.map { it.first }, prefs.getString("maleVoice-en", null), net)
                   else Voices.sofia(all.map { it.first }, prefs.getString("femaleVoice-ru", null), net)
        t.setPitch(1f)
        t.language = if (male) Locale.US else Locale("ru", "RU")
        all.firstOrNull { it.first == pick }?.second?.let { t.voice = it }
        val id = "touch-${++utterance}"
        var problem: String? = null
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            // the chosen voice can't play (e.g. needs data): the engine default for the language, and say so
            t.language = if (male) Locale.US else Locale("ru", "RU")
            problem = if (t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "touch-${++utterance}") == TextToSpeech.ERROR)
                "Voice playback failed — text only." else "Chosen voice unavailable — played with the phone's default voice."
        }
        if (male && pick == null) problem = "No male English voice installed or chosen — played with the phone's default English voice. Choose one in Settings → Due voice."
        val audio = getSystemService(AudioManager::class.java)
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) problem = (problem?.let { "$it " } ?: "") + "Media volume is off."
        return problem
    }

    /** Owner picks the male English voice for DUE announcements after hearing it (no gender metadata exists). */
    private fun chooseDueVoice() {
        val t = tts?.takeIf { voiceReady } ?: run { AlertDialog.Builder(this).setMessage("Voice engine unavailable.").setPositiveButton("Close", null).show(); return }
        val english = options(t).filter { it.first.language == "en" && it.first.installed }.sortedBy { it.first.name }
        if (english.isEmpty()) { AlertDialog.Builder(this).setMessage("No English voices are installed on this phone.").setPositiveButton("Close", null).show(); return }
        val names = english.map { it.first.name + if (it.first.network) " (online)" else "" }.toTypedArray()
        var picked = -1
        AlertDialog.Builder(this).setTitle("Due voice · tap to hear, then Save").setSingleChoiceItems(names, english.indexOfFirst {
            it.first.name == getSharedPreferences("voice", MODE_PRIVATE).getString("maleVoice-en", null) }) { _, which ->
            picked = which; t.setPitch(1f); t.language = Locale.US; t.voice = english[which].second
            t.speak("Receipt 1 2 3 4 5. Outstanding balance due: 40 dollars.", TextToSpeech.QUEUE_FLUSH, null, "touch-preview")
        }.setPositiveButton("Save as male voice") { _, _ ->
            if (picked >= 0) getSharedPreferences("voice", MODE_PRIVATE).edit().putString("maleVoice-en", english[picked].first.name).apply()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun settings() {
        AlertDialog.Builder(this).setTitle("Sofia ${Updater.versionName(this)}")
            .setItems(arrayOf("Check for update", "Refresh orders", "Pending work", "Repeat announcement", "Due voice (male English)", "Find history")) { _, which ->
                when (which) {
                    0 -> {
                        val box = AlertDialog.Builder(this).setTitle("Sofia update").setMessage("Checking…").setPositiveButton("Close", null).show()
                        updating.execute {
                            val result = runCatching { Updater.checkAndInstall(this) { message -> main.post { if (!destroyed) box.setMessage(message) } } }
                                .getOrElse { Updater.failureText(it) }
                            main.post { if (!destroyed) box.setMessage(result) }
                        }
                    }
                    1 -> reads.execute { runCatching { refreshLists() } }
                    2 -> {
                        val q = queueJson()
                        AlertDialog.Builder(this).setTitle("Pending work")
                            .setMessage("Work processing: ${q.optInt("work")}\nPickup confirmations: ${q.optInt("pickups")}\n\n${q.optString("warning")}")
                            .setPositiveButton("Close", null).show()
                    }
                    3 -> speak(lastSpoken, lastMale)?.let(::voiceProblem)
                    4 -> chooseDueVoice()
                    5 -> AlertDialog.Builder(this).setTitle("Find history")
                        .setMessage(findLog.history().joinToString("\n\n", transform = FindLog::historyLine).ifEmpty { "No searches kept yet." })
                        .setPositiveButton("Close", null).show()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)
}
