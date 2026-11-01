package com.tailorshop.counter

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
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
    private val writes = Executors.newSingleThreadExecutor()
    private val updating = Executors.newSingleThreadExecutor()
    private val syncing = AtomicBoolean(false)
    private lateinit var web: WebView
    private lateinit var finishes: Finishes
    private lateinit var pickups: Pickups
    private lateinit var sheets: Sheets
    private lateinit var cache: OrderCache
    @Volatile private var orders: List<Order> = emptyList()
    @Volatile private var loadedAt = 0L
    @Volatile private var offline = false
    private var resumed = false
    private var destroyed = false
    private var tts: TextToSpeech? = null
    private var voiceReady = false
    private var lastSpoken = ""
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
        finishes = Finishes(filesDir); pickups = Pickups(filesDir)
        sheets = Sheets(File(filesDir, "sheet-key.json")); cache = OrderCache(filesDir)
        cache.load()?.let { snapshot -> runCatching { orders = snapshot.orders(); loadedAt = snapshot.at } }
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
        tts = TextToSpeech(this) { code -> main.post { voiceReady = code == TextToSpeech.SUCCESS } }
        Updater.scheduleNight(this)
        // Touch is now the primary input. Do not wake a microphone conversation in the background.
        Wake.cancel(this, 1); Wake.cancel(this, 3)
    }

    override fun onResume() {
        super.onResume(); resumed = true; Wake.clear(this)
        main.removeCallbacks(ticker); main.post(ticker)
    }

    override fun onPause() {
        resumed = false; main.removeCallbacks(ticker); tts?.stop(); super.onPause()
    }

    override fun onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null)
        web.removeJavascriptInterface("Sofia"); web.destroy(); tts?.shutdown()
        reads.shutdown(); writes.shutdown(); updating.shutdown(); super.onDestroy()
    }

    private fun reply(id: String, result: JSONObject) {
        main.post {
            if (!destroyed) web.evaluateJavascript("window.counterReply(${JSONObject.quote(id)},${JSONObject.quote(result.toString())})", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun request(id: String, action: String, json: String) {
            if (id.length > 80 || json.length > 4096) return
            val body = runCatching { JSONObject(json) }.getOrElse { reply(id, error("Invalid request")); return }
            when (action) {
                "speak" -> main.post { speak(body.optString("text").take(700)); reply(id, JSONObject().put("ok", true)) }
                "settings" -> main.post { settings(); reply(id, JSONObject().put("ok", true)) }
                "load" -> reply(id, JSONObject().put("ok", true).put("workers", JSONArray(finishes.workers()))
                    .put("finishConfigured", finishes.configured()).put("pickupConfigured", pickups.configured()))
                "find", "report" -> reads.execute {
                    try { lookup(id, action, body.optString("number")) }
                    catch (_: Exception) { reply(id, error("Orders unavailable. Check the connection and try again.")) }
                }
                "save", "pickup" -> writes.execute {
                    try {
                        if (action == "save") saveWork(id, body) else savePickup(id, body.optString("number"))
                    } catch (_: Exception) { reply(id, error("Could not save. Please try again.")) }
                }
                "status" -> {
                    val event = finishes.get(body.optString("id"))
                    reply(id, event?.let(::finishJson) ?: error("Saved request unavailable. Check pending work."))
                }
                "queue" -> reply(id, queueJson())
                "dismiss" -> writes.execute {
                    val ok = finishes.dismiss(body.optString("id"))
                    reply(id, if (ok) queueJson() else error("Couldn't mark it reviewed. Nothing was changed."))
                }
                else -> reply(id, error("Unsupported action"))
            }
        }
    }

    @Volatile private var index: List<OrderIndex.Entry> = emptyList()

    private fun refreshOrders() {
        try {
            val rows = sheets.readRows(); val parsed = Lookup.parseOrders(rows); val at = System.currentTimeMillis()
            cache.save(rows, at); orders = parsed; loadedAt = at; offline = false
        } catch (e: Exception) { offline = true; if (loadedAt == 0L) throw e }
        // The compact Order Index (not-ready / collected states), when the Pickups projection publishes it.
        // A failed read keeps the last copy; no tab yet = empty, and Find works from the ready list alone.
        runCatching { sheets.readIndex() }.onSuccess { rows -> index = rows?.let(OrderIndex::parse) ?: emptyList() }
    }

    private fun lookup(id: String, action: String, value: String) {
        val number = value.trim()
        if (action == "find" && !number.matches(Regex("[0-9]{4}"))) { reply(id, error("Enter the last four phone digits.")); return }
        if (action == "report" && !number.matches(Regex("[0-9]{3,9}"))) { reply(id, error("Enter a receipt number.")); return }
        if (loadedAt == 0L || System.currentTimeMillis() - loadedAt > 60_000L) runCatching { refreshOrders() }
        if (action == "find" && loadedAt == 0L) { reply(id, error("Orders not loaded. Connect to the internet and try again.")); return }
        fun search() = if (action == "find") Lookup.find(Query.Last4(number), orders)
            .filter { Lookup.digits(it.receipt) !in pickups.pending() }
            else Lookup.find(Query.Receipt(number), orders)
        var results = search()
        // A miss is only an answer when the list is fresh: re-read once on a miss, and if the read fails say so
        // instead of a false "no matching receipt" from an old copy (the 0.41 reread-on-miss rule).
        if (results.isEmpty()) { runCatching { refreshOrders() }; results = search() }
        if (action == "find" && results.isEmpty() && offline) {
            reply(id, error("Couldn't refresh the orders list, so no answer yet. Check Wi-Fi and try again.")); return
        }
        // A work entry may not yet be in the ready-orders mirror. It is validated by the existing Reader when saved.
        val cards = if (action == "report" && results.isEmpty()) listOf(Card(number, "", "")) else results
        // Find also lists the same digits from the Order Index when they aren't in the ready list, each with its own
        // state (not ready + promised time / already picked up / unknown). Picked up stays ready-list only.
        val extra = if (action == "find") OrderIndex.find(number, index).filter { e -> results.none { Lookup.digits(it.receipt) == Lookup.digits(e.receipt) } } else emptyList()
        val nowShop = java.time.LocalDateTime.now(java.time.ZoneId.of("America/Los_Angeles"))
        reply(id, JSONObject().put("ok", true).put("receipts", JSONArray().apply {
            cards.forEach { card -> put(cardJson(card, action == "report" && results.isEmpty())) }
            extra.forEach { e -> put(cardJson(Card(e.receipt, e.customer, ""), false).put("ready", false)
                .put("state", e.state.name).put("stateText", OrderIndex.stateText(e)).put("stale", OrderIndex.stale(e, nowShop))) }
        }).put("offline", offline).put("loadedAt", loadedAt))
    }

    private fun cardJson(c: Card, unverified: Boolean = false) = JSONObject()
        .put("number", c.receipt).put("name", c.customer).put("total", c.total).put("work", c.work)
        .put("ready", c.ready).put("pay", c.pay.name).put("unverified", unverified)

    private fun saveWork(id: String, body: JSONObject) {
        val worker = body.optString("worker"); val number = body.optString("number")
        if (!finishes.configured()) { reply(id, error("Daily Report connection is not configured.")); return }
        if (worker !in finishes.workers() || !number.matches(Regex("[0-9]{3,9}"))) { reply(id, error("Choose an approved worker and receipt number.")); return }
        val item = finishes.addOrReuse(worker, number)
        if (item == null) { reply(id, error("Not saved on this phone. Please retry.")); return }
        if (body.optBoolean("sms") && !finishes.requestSms(item.scanId)) {
            reply(id, error("Work saved locally, but the SMS choice could not be saved. Check pending work.")); return
        }
        reply(id, finishJson(finishes.get(item.scanId)!!))
        synchronizeQueues()
    }

    private fun finishJson(i: Finishes.Item) = JSONObject().put("ok", true).put("id", i.scanId)
        .put("number", i.receipt).put("worker", i.worker).put("at", i.scannedAt).put("scan", i.scan)
        .put("smsRequested", i.sendSms).put("sms", i.sms).put("scanText", Finishes.scanText(i, false))
        .put("smsText", Finishes.smsText(i, false)).put("reason", i.reason)
        .put("recorded",Finishes.recorded(i)).put("workday",i.workday)
        .put("reportState",i.reportState).put("reportReason",i.reportReason)

    private fun savePickup(id: String, number: String) {
        if (!pickups.configured()) { reply(id, error("Pickup connection is not configured.")); return }
        val matches = orders.filter { Lookup.digits(it.receipt) == number }
            .distinctBy { listOf(Lookup.digits(it.receipt), Lookup.digits(it.phone), it.customer) }
        if (matches.size != 1 || !matches.single().ready) { reply(id, error("Pickup is unavailable or ambiguous. Check the order.")); return }
        if (!pickups.addOrReuse(number)) { reply(id, error("Pickup not saved on this phone. Please retry.")); return }
        val request = pickups.latest(number)!!
        reply(id, JSONObject().put("ok", true).put("number", number).put("status", request.status)
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
        }.map { "#${it.receipt}: ${Finishes.scanText(it, false)} · ${Finishes.smsText(it, false)}" }
        val problems = listOfNotNull(finishes.warning(), pickups.warning()) + workProblems + pickups.toCheck().map { Pickups.problemText(it.receipt, it.status) }
        // Not-in-the-sheet entries over a day old with no SMS can be marked reviewed by a person (Finishes.dismiss).
        val dismiss = JSONArray(finishes.dismissable().map { JSONObject().put("id", it.scanId).put("receipt", it.receipt) })
        return JSONObject().put("ok", true).put("work", pendingWork).put("pickups", pendingPickups)
            .put("warning", problems.joinToString("\n")).put("dismiss", dismiss)
    }

    private fun synchronizeQueues() {
        if (!syncing.compareAndSet(false, true)) return
        writes.execute {
            try { runCatching { finishes.sync() }; runCatching { pickups.sync() } }
            finally { syncing.set(false) }
        }
    }

    private fun speak(text: String) {
        lastSpoken = text
        if (!voiceReady || !resumed) return
        val lang = Locale("ru", "RU")
        tts?.language = lang
        val wanted = getSharedPreferences("voice", MODE_PRIVATE).getString("femaleVoice-ru", null)
        tts?.voices?.firstOrNull { it.name == wanted && it.locale.language == "ru" }?.let { tts?.voice = it }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "touch-result")
    }

    private fun settings() {
        AlertDialog.Builder(this).setTitle("Sofia ${Updater.versionName(this)}")
            .setItems(arrayOf("Check for update", "Refresh orders", "Pending work", "Repeat announcement")) { _, which ->
                when (which) {
                    0 -> {
                        val box = AlertDialog.Builder(this).setTitle("Sofia update").setMessage("Checking…").setPositiveButton("Close", null).show()
                        updating.execute {
                            val result = runCatching { Updater.checkAndInstall(this) { message -> main.post { if (!destroyed) box.setMessage(message) } } }
                                .getOrElse { "Update check failed. Please try again." }
                            main.post { if (!destroyed) box.setMessage(result) }
                        }
                    }
                    1 -> reads.execute { runCatching { refreshOrders() } }
                    2 -> {
                        val q = queueJson()
                        AlertDialog.Builder(this).setTitle("Pending work")
                            .setMessage("Work processing: ${q.optInt("work")}\nPickup confirmations: ${q.optInt("pickups")}\n\n${q.optString("warning")}")
                            .setPositiveButton("Close", null).show()
                    }
                    3 -> speak(lastSpoken)
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)
}
