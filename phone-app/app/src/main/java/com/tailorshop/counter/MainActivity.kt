package com.tailorshop.counter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

// Counter screen: listen -> look up in "All Receipts" -> big cards + spoken answer.
// Read-only: the only network calls are the Google token and one sheet GET.
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var sheets: Sheets
    private lateinit var pickups: Pickups

    @Volatile private var orders: List<Order> = emptyList()
    @Volatile private var loadedAt = 0L

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var handsFree = true
    private var russianFirst = false
    private var resumed = false
    private var tapped = false // this listen was started by the button, so always answer

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var topBar: View
    private lateinit var stage: LinearLayout
    private lateinit var rootView: LinearLayout
    private lateinit var micButton: Button
    private lateinit var pickedButton: Button
    private lateinit var checkButton: Button
    private lateinit var langButton: Button
    private lateinit var handsButton: Button

    private val refreshEvery = 60_000L
    private val refresher = object : Runnable {
        override fun run() {
            refresh(null)
            syncPickups()
            main.postDelayed(this, refreshEvery)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sheets = Sheets(File(filesDir, "sheet-key.json"))
        pickups = Pickups(filesDir)
        Updater.scheduleNight(this)
        russianFirst = getPreferences(MODE_PRIVATE).getBoolean("russianFirst", true)
        setContentView(buildUi())
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { speakNext() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { main.post { speakNext() } }
            })
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Wake.clear(this)
        hideSystemBars()
        main.post(refresher)
        main.post(bouncer)
        main.removeCallbacks(ticker); main.post(ticker)
        screenOff = !inShopHours()
        if (screenOff) scheduleMorningWake()
        render()
        if (handsFree) listenSoon(800)
        setupLanguages()
    }

    // Automatic EN/RU: Google's on-phone recognizer can switch language mid-session, but
    // only when both language packs are on the phone. If Russian is missing, download it
    // once; until then the online recognizer and the manual switch are used.
    private var useOnDevice = false
    private var languagesChecked = false

    private fun langIntent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)

    private fun setupLanguages() {
        if (languagesChecked || Build.VERSION.SDK_INT < 34 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return
        languagesChecked = true
        val od = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        od.checkRecognitionSupport(langIntent("ru-RU"), mainExecutor, object : android.speech.RecognitionSupportCallback {
            override fun onSupportResult(s: android.speech.RecognitionSupport) {
                val have = s.installedOnDeviceLanguages
                Log.d("TailorCounter", "on-device installed=$have pending=${s.pendingOnDeviceLanguages}")
                when {
                    "en-US" in have && "ru-RU" in have -> enableAuto()
                    "ru-RU" in s.supportedOnDeviceLanguages && "ru-RU" !in s.pendingOnDeviceLanguages -> downloadRussian(od)
                    else -> od.destroy()
                }
                if ("en-US" in have && "ru-RU" in have) od.destroy()
            }
            override fun onError(error: Int) { Log.d("TailorCounter", "support check error $error"); od.destroy() }
        })
    }

    private fun downloadRussian(od: SpeechRecognizer) {
        setStatus("Downloading Russian speech (one time)…")
        od.triggerModelDownload(langIntent("ru-RU"), mainExecutor, object : android.speech.ModelDownloadListener {
            override fun onProgress(completedPercent: Int) { setStatus("Downloading Russian speech… $completedPercent%") }
            override fun onSuccess() { Log.d("TailorCounter", "ru-RU downloaded"); od.destroy(); languagesChecked = false; setupLanguages() }
            override fun onScheduled() { Log.d("TailorCounter", "ru-RU download scheduled"); od.destroy(); setStatus(idleText()) }
            override fun onError(error: Int) { Log.d("TailorCounter", "ru-RU download error $error"); od.destroy(); setStatus(idleText()) }
        })
    }

    private fun enableAuto() {
        Log.d("TailorCounter", "auto EN/RU on")
        useOnDevice = true
        langButton.visibility = View.GONE // manual switch only as a fallback
        stopListening(); recognizer?.destroy(); recognizer = null
        if (handsFree) listenSoon(300)
    }

    private fun disableAuto() {
        Log.d("TailorCounter", "auto EN/RU off (on-device failed)")
        useOnDevice = false
        langButton.visibility = View.VISIBLE
        recognizer?.destroy(); recognizer = null
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(refresher)
        main.removeCallbacks(bouncer)
        main.removeCallbacks(ticker)
        stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) listenSoon(300)
        else setStatus("Microphone is off — allow it in Settings › Apps › Tailor Counter")
    }

    // ---- sheet ----

    /** Fresh read in the background; keeps the last good copy if it fails. */
    private fun refresh(then: (() -> Unit)?) {
        io.execute {
            try {
                val rows = sheets.readRows()
                orders = Lookup.parseOrders(rows)
                loadedAt = System.currentTimeMillis()
                main.post { if (!listening) setStatus(idleText()); then?.invoke() }
            } catch (e: Sheets.KeyMissing) {
                main.post { setStatus("Setup needed: the sheet key is not installed"); then?.invoke() }
            } catch (e: Exception) {
                main.post {
                    if (loadedAt == 0L) setStatus("Can't reach the sheet — check Wi-Fi")
                    then?.invoke()
                }
            }
        }
    }

    // ---- listening ----

    private fun listenSoon(delayMs: Long) {
        main.removeCallbacks(startRunnable)
        main.postDelayed(startRunnable, delayMs)
    }

    private val startRunnable = Runnable { startListening() }

    private fun startListening() {
        if (!resumed || listening || tts?.isSpeaking == true || screenOff) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition is not available on this phone"); return
        }
        val r = recognizer ?: (if (useOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)).also {
            it.setRecognitionListener(listener); recognizer = it
        }
        val primary = if (russianFirst && !useOnDevice) "ru-RU" else "en-US"
        val other = if (russianFirst) "en-US" else "ru-RU"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf(primary, other))
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf(primary, other))
            }
        }
        listening = true
        updateMic()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            listening = false; updateMic(); listenSoon(1500)
        }
    }

    private fun stopListening() {
        main.removeCallbacks(startRunnable)
        if (listening) recognizer?.cancel()
        listening = false
        updateMic()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            busyStreak = 0
            // Hands-free: no listening indicator anywhere on screen (Art).
            if (!handsFree) setStatus((if (russianFirst) "Слушаю…" else "Listening…") + " · ${orders.size} orders")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (!handsFree) setStatus(if (russianFirst) "Ищу…" else "Looking…") }
        override fun onPartialResults(partial: Bundle?) {
            // Art: once "Sofia" is heard (or while she waits for a number), her words show live; room talk never does.
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            val now = System.currentTimeMillis()
            // Art: the moment her name is heard, the screen switches sharply to the bright listening screen.
            if (Lookup.addressed(t) && now >= wakeUntil) { wakeUntil = now + 8_000; Chime.play(chime()) } // + Art's chosen chime
            else if (now < wakeUntil) wakeUntil = now + 8_000 // still talking to her: the 8 s count from the last word
            if (Lookup.addressed(t) || now < armedUntil || now < followUntil) { live = t; render() }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (live != null) { live = null; render() }
            listening = false
            tapped = false
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                Log.d("TailorCounter", "recognizer error $error")
            updateMic()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    setStatus("Microphone is off — allow it in Settings › Apps › Tailor Counter")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (useOnDevice) disableAuto()
                    else { setStatus("Speech language not available — switching"); russianFirst = !russianFirst; updateLang() }
                    if (handsFree) listenSoon(1000)
                }
                // Busy: keep the SAME recognizer and wait longer each time (2, 4, 8 … 30 s). Making a new one on
                // every busy answer leaked sessions until the phone's speech service was full (10-28 night).
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    busyStreak++
                    recognizer?.cancel()
                    if (handsFree) listenSoon(minOf(30_000L, 1_000L shl minOf(busyStreak, 5)))
                }
                else -> { setStatus(idleText()); if (handsFree) listenSoon(400) }
            }
        }

        override fun onResults(results: Bundle?) {
            busyStreak = 0
            live = null
            listening = false
            updateMic()
            val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val detected = if (Build.VERSION.SDK_INT >= 34) results?.getString(SpeechRecognizer.DETECTED_LANGUAGE) else null
            if (alts.isEmpty()) { if (handsFree) listenSoon(300); return }
            answer(alts, detected)
        }
    }

    // ---- answering ----

    private fun answer(alts: List<String>, detectedLang: String?) {
        val tappedNow = tapped
        tapped = false
        // Right after "find receipt…" with no number, the next phrase is taken as the number
        // (people pause, and the recognizer splits the command from the digits).
        val armed = System.currentTimeMillis() < armedUntil
        // For 15 s after an answer a follow-up number needs no trigger word (Art).
        val followUp = System.currentTimeMillis() < followUntil
        armedUntil = 0L
        main.removeCallbacks(armedTimeout)
        live = null
        val ru = alts.any(Lookup::isRussian) || (detectedLang?.startsWith("ru") == true)
        // Keep the listening screen while she waits for the rest: after a bare "Sofia", or "Sofia, this is
        // Arthur" + a pause (Art, 10-28: the screen gave up mid-sentence). Reset below once she acts.
        val stillTalking = System.currentTimeMillis() < wakeUntil
        wakeUntil = if (alts.any(Lookup::onlyHerName) || alts.any(Lookup::addressed) || stillTalking) System.currentTimeMillis() + 8_000 else 0L
        // Voice Finish isn't built yet: say so instead of dropping the phrase.
        if (alts.any { Lookup.addressed(it) || stillTalking } && alts.any(Lookup::isFinishPhrase)) {
            Log.d("TailorCounter", "heard=$alts finishSoon"); wakeUntil = 0L
            speak((if (ru) "Запись голосом скоро будет." else "Voice finish is coming soon.") to ru); render(); return
        }
        if (alts.any(Lookup::asksWhoSheIs)) { Log.d("TailorCounter", "heard=$alts intro"); speak((if (ru) Lookup.INTRO_RU else Lookup.INTRO) to ru); answered(); render(); return }
        if (alts.any(Lookup::asksIfSheHears)) { Log.d("TailorCounter", "heard=$alts hears"); speak(Lookup.hearsReply(ru) to ru); answered(); render(); return }
        alts.firstNotNullOfOrNull(Lookup::thanksReply)?.let { Log.d("TailorCounter", "heard=$alts thanks"); speak(it); answered(); render(); return }
        // A follow-up about the receipt on screen: in the 15 s after an answer, or any time with "Sofia" in front.
        val onScreen = shown.getOrNull(index)?.card
        if (onScreen != null && message == null) {
            val ask = alts.firstNotNullOfOrNull { a ->
                Lookup.followUp(a)?.takeIf { System.currentTimeMillis() < followUntil || Lookup.commandTail(a) != null }
                    ?.takeIf { Lookup.parseQueries(Lookup.commandTail(a) ?: a).none { q -> q !is Query.Name } } // a number = a new lookup
            }
            if (ask != null) {
                Log.d("TailorCounter", "heard=$alts followUp=$ask")
                speak(Lookup.followUpAnswer(ask, onScreen, ru) to ru); answered(); return
            }
        }
        val go = {
            // Only a command ("find receipt…", "check…", "найди заказ…") is acted on; a tap
            // counts as a command for whatever was said. Among the recognizer's guesses, a
            // command whose number finds an order wins, then any command with a number,
            // then the first command.
            val tails = alts.mapNotNull { a ->
                (Lookup.commandTail(a) ?: a.takeIf { tappedNow || ((armed || followUp || stillTalking) && Lookup.parseQueries(it).any { q -> q !is Query.Name }) })
                    ?.let { a to it }
            }
            fun hasNumber(t: String) = Lookup.parseQueries(t).any { it !is Query.Name }
            val chosen = tails.firstOrNull { (_, t) -> hasNumber(t) && Lookup.findAll(t, orders).isNotEmpty() }
                ?: tails.firstOrNull { (_, t) -> hasNumber(t) }
                ?: tails.firstOrNull()
            Log.d("TailorCounter", "heard=$alts command=${chosen?.second}")
            if (chosen == null) {
                if (armed && !armedQuiet) sayDidntCatch(armedRussian) // asked, but no number followed
                else if (armed) { message = null; render(); if (handsFree) listenSoon(300) } // just her name: drop it quietly
                else if (handsFree) listenSoon(300) // room talk: ignored silently
            } else {
                val (said, tail) = chosen
                val russian = said.any { it in 'а'..'я' || it in 'А'..'Я' } ||
                    detectedLang?.startsWith("ru") == true || (detectedLang == null && russianFirst)
                val queries = Lookup.parseQueries(tail)
                val cards = Lookup.findAll(tail, orders)
                when {
                    queries.isEmpty() && !armed -> {
                        // Wait a few seconds for the number before giving up.
                        armedUntil = System.currentTimeMillis() + 8_000
                        armedRussian = russian
                        // Only her name (e.g. someone mentions Sofia while talking): wait quietly, never "didn't catch".
                        armedQuiet = Lookup.onlyHerName(said)
                        main.postDelayed(armedTimeout, 8_000)
                        showMessage(if (russian) "Скажите номер…" else "Say the number…", 8_000)
                        listenSoon(100)
                    }
                    queries.isEmpty() -> if (armedQuiet) { message = null; render(); if (handsFree) listenSoon(300) } else sayDidntCatch(russian)
                    cards.isEmpty() -> { showMessage(Lookup.notFound(russian)); speak(Lookup.notFound(russian) to russian); answered() }
                    else -> {
                        addResults(cards)
                        speak(Lookup.reply(cards, russian) to russian) // Art: answer in the language asked
                        answered()
                    }
                }
            }
            setStatus(idleText())
        }
        // Use a copy no older than 20 s so a just-dropped-off order is found.
        if (System.currentTimeMillis() - loadedAt > 20_000) refresh(go) else go()
    }

    private var armedUntil = 0L
    private var followUntil = 0L
    private fun answered() { followUntil = System.currentTimeMillis() + 15_000; wakeUntil = 0L } // she acted: listening screen off
    private var armedRussian = false
    private var armedQuiet = false
    private val armedTimeout = Runnable {
        if (armedUntil != 0L) {
            armedUntil = 0L
            if (armedQuiet) { message = null; render() } else { stopListening(); sayDidntCatch(armedRussian) }
        }
    }

    private fun sayDidntCatch(russian: Boolean) {
        showMessage(Lookup.didntCatch(russian))
        speak(Lookup.didntCatch(russian) to russian)
    }

    // Utterances are spoken one after another, each in its own language; listening
    // resumes only after the last one, so the app never hears itself.
    private val speechQueue = ArrayDeque<Pair<String, Boolean>>()

    private fun speak(vararg parts: Pair<String, Boolean>) {
        tts?.stop()
        speechQueue.clear()
        speechQueue.addAll(parts)
        speakNext()
    }

    private fun speakNext() {
        val t = tts
        val next = speechQueue.removeFirstOrNull()
        if (t == null || next == null) {
            if (followUntil > System.currentTimeMillis()) answered() // the 15 s run from the end of the answer
            if (handsFree) listenSoon(300); return
        }
        val (text, russian) = next
        t.language = if (russian) Locale("ru", "RU") else Locale.US
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer-${speechQueue.size}")
    }

    // ---- UI ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).also { rootView = it }.apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(10))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar = top
        status = TextView(this).apply {
            setTextColor(Color.rgb(170, 170, 170)); textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        langButton = smallButton {
            russianFirst = !russianFirst
            getPreferences(MODE_PRIVATE).edit().putBoolean("russianFirst", russianFirst).apply()
            updateLang(); restartIfListening()
        }
        handsButton = smallButton {
            handsFree = !handsFree; updateHands(); setStatus(idleText()); render()
            if (handsFree) listenSoon(200) else stopListening()
        }
        holdForSettings(handsButton)
        top.addView(status); top.addView(langButton); top.addView(handsButton)

        heard = TextView(this).apply {
            setTextColor(Color.rgb(120, 200, 255)); textSize = 18f; maxLines = 1
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { bottomMargin = dp(30) } // ~5 mm gap so nothing sits under the button
        }
        micButton = Button(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
            setOnClickListener { if (listening) stopListening() else { tts?.stop(); tapped = true; startListening() } }
        }
        pickedButton = Button(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
            setOnClickListener { onPickedTap() }
        }
        // A Picked up tap the tablet couldn't apply stays here until it's checked (or leaves the pickup list).
        checkButton = Button(this).apply {
            textSize = 22f; setTextColor(Color.BLACK); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply { setColor(Color.rgb(255, 190, 60)); cornerRadius = dp(16).toFloat() }
            setOnClickListener {
                if (pickups.warning() != null) pickups.acknowledgeRecovery() // a save failure can't be tapped away
                else pickups.toCheck().firstOrNull()?.let { pickups.dismiss(it.id) }
                render()
            }
        }
        root.addView(top); root.addView(heard); root.addView(stage); root.addView(checkButton); root.addView(pickedButton); root.addView(micButton)
        updateLang(); updateHands(); updateMic()
        setStatus(if (sheets.hasKey()) "Loading orders…" else "Setup needed: the sheet key is not installed")
        stage.post { render() }
        return root
    }

    private fun smallButton(onClick: () -> Unit) = Button(this).apply {
        textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(50, 50, 50)); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)).apply { leftMargin = dp(8) }
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    // ---- what the screen shows ----
    //
    // Every found order is added to a list (a repeat lookup moves it to the end). The list
    // shows one order at a time, full screen, rotating every few seconds; swipe left/right
    // to flip by hand. Each order drops off 2 minutes after it was looked up; with none left
    // the screen is a big clock. Short messages ("I can't find this order") show for a few
    // seconds on top, then the list or clock comes back.

    private class Shown(val card: Card, val addedAt: Long)
    private val shown = mutableListOf<Shown>()
    private var index = 0
    private var message: String? = null
    private var pausedUntil = 0L
    private val keepMs = 120_000L
    private val rotateMs = 10_000L

    private val endMessage = Runnable { message = null; render() }
    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val before = shown.size
            prune()
            if (message == null && shown.size > 1 && now >= pausedUntil && now - lastRotate >= rotateMs) {
                index = (index + 1) % shown.size; lastRotate = now; render()
            } else if (shown.size != before || (message == null && shown.isEmpty())) render()
            val off = !inShopHours()
            if (off != screenOff) {
                screenOff = off
                if (off) { stopListening(); tts?.stop(); scheduleMorningWake() } else if (handsFree) listenSoon(500)
                render()
            }
            if (now - lastShift >= 60_000) { lastShift = now; shiftContent() }
            updatePicked() // the button goes away once its minute is up
            if (wasListening && !isListeningScreen()) { live = null; render() } // quietly back to the clock / result
            Updater.confirmIntent?.let { if (resumed) { Updater.confirmIntent = null; startActivity(it) } }
            main.postDelayed(this, 1000)
        }
    }
    private var lastRotate = 0L

    // ---- protecting the Fold's OLED screen ----
    //
    // Shop hours 10:45–19:30 (Los Angeles): outside them the screen is black, the app stops
    // listening and lets the phone sleep, and an alarm wakes it at 10:45. A tap after hours
    // brings it back for 5 minutes. The idle clock is dim and alone on screen (controls
    // show for 10 s after a tap), and whatever is shown drifts slightly every minute.
    private var screenOff = false
    private var awakeUntil = 0L   // after-hours tap
    private var chromeUntil = 0L  // controls shown over the clock after a tap
    private var lastShift = 0L
    private val shopZone = java.time.ZoneId.of("America/Los_Angeles")
    private val CLOCK_DRIFT_DP = 30
    private val NUMBER_DRIFT_DP = 6

    /**
     * Test-only (Art): while the Fold is plugged into the Mac with USB debugging active, the closed-hours
     * limit doesn't apply, so Sofia can be tried at any hour. It ends by itself when unplugged; a wall
     * charger at the shop is not a debug connection, so shop nights stay mic-off.
     */
    private fun usbTestMode(): Boolean {
        val st = try { registerReceiver(null, android.content.IntentFilter("android.hardware.usb.action.USB_STATE")) } catch (_: Exception) { null }
        return st?.getBooleanExtra("connected", false) == true && st.getBooleanExtra("adb", false)
    }

    private fun inShopHours(): Boolean {
        if (System.currentTimeMillis() < awakeUntil) return true
        if (usbTestMode()) return true
        val t = java.time.ZonedDateTime.now(shopZone)
        val m = t.hour * 60 + t.minute
        return m >= 10 * 60 + 45 && m < 19 * 60 + 30
    }

    private fun scheduleMorningWake() {
        val now = java.time.ZonedDateTime.now(shopZone)
        var wake = now.withHour(10).withMinute(45).withSecond(0).withNano(0)
        if (!wake.isAfter(now)) wake = wake.plusDays(1)
        Wake.schedule(this, wake.toInstant().toEpochMilli(), 1)
        Log.d("TailorCounter", "wake scheduled for $wake")
    }

    private fun shiftContent() {
        val r = java.util.Random()
        // The receipt number is edge to edge, so it mostly moves up/down; the clock moves both ways.
        val clock = shown.isEmpty() && message == null
        if (clock) { stage.translationX = 0f; stage.translationY = 0f; return } // the idle block bounces instead
        val dx = dp(if (clock) CLOCK_DRIFT_DP else NUMBER_DRIFT_DP)
        stage.translationX = (r.nextInt(2 * dx + 1) - dx).toFloat()
        stage.translationY = (r.nextInt(2 * dp(24) + 1) - dp(24)).toFloat()
    }

    private fun setBrightness(value: Float) {
        val lp = window.attributes
        if (lp.screenBrightness != value) { lp.screenBrightness = value; window.attributes = lp }
    }

    /** Controls, brightness and keep-awake for the current mode. */
    private fun applyMode(clockOnly: Boolean) {
        val chrome = !screenOff && (!clockOnly || System.currentTimeMillis() < chromeUntil)
        val vis = if (chrome) View.VISIBLE else View.GONE
        topBar.visibility = vis; heard.visibility = vis
        updatePicked()
        // The big button only in tap-to-talk mode; hands-free gives its space to the receipt number.
        val showMic = chrome && !handsFree
        micButton.visibility = if (showMic) View.VISIBLE else View.GONE
        (stage.layoutParams as LinearLayout.LayoutParams).let { lp ->
            val m = if (showMic) dp(30) else 0
            if (lp.bottomMargin != m) { lp.bottomMargin = m; stage.layoutParams = lp }
        }
        if (screenOff) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBrightness(0f)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) // Art: no dimming; the idle block bounces instead
        }
    }

    // ---- Picked up (Art: each receipt its own button, shown as long as its number is; tap -> 5 s Undo -> number and button go, one request) ----

    private var undoReceipts: List<String>? = null
    private var keyDeniedShown = false
    private val commitPickup = Runnable {
        val receipts = undoReceipts ?: return@Runnable
        undoReceipts = null
        val saved = pickups.add(receipts)
        shown.removeAll { it.card.receipt in receipts }; index = 0
        Log.d("TailorCounter", "picked up saved=$saved: ${receipts.size}")
        // Truthful: saved on the phone only; "sent" and "confirmed" come from the tablet's answers.
        showMessage(if (saved) "Saved on the phone — sending to the tablet…" else pickups.warning() ?: "Not saved — mark it on the tablet")
        syncPickups()
    }

    /** The receipt on screen. Its Picked up button lives exactly as long as the number is shown (Art). */
    private fun currentGroup(): List<String> {
        val cur = shown.getOrNull(index) ?: return emptyList()
        return listOf(cur.card.receipt)
    }

    private fun onPickedTap() {
        if (undoReceipts != null) { // Undo
            undoReceipts = null; main.removeCallbacks(commitPickup); render(); return
        }
        val group = currentGroup().filter { Lookup.digits(it) !in pickups.pending() }
        if (group.isEmpty()) return
        undoReceipts = group
        main.postDelayed(commitPickup, 5_000)
        render()
    }

    private fun updatePicked() {
        val warn = pickups.warning()
        val open = pickups.toCheck()
        val showCheck = !screenOff && (warn != null || open.isNotEmpty()) && undoReceipts == null
        checkButton.visibility = if (showCheck) View.VISIBLE else View.GONE
        if (showCheck) checkButton.text = warn ?: (Pickups.problemText(open.first().receipt, open.first().status) +
            (if (open.size > 1) "  (+${open.size - 1} more)" else "") + "\nTap here when checked")
        val undo = undoReceipts
        val group = currentGroup().filter { Lookup.digits(it) !in pickups.pending() }
        val show = pickups.configured() && !screenOff && (undo != null || (message == null && group.isNotEmpty()))
        pickedButton.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        pickedButton.text = if (undo != null) "↩  Undo — picked up " + undo.joinToString(", ") { "#$it" }
            else "✓  Picked up #${group.first()}"
        pickedButton.background = GradientDrawable().apply {
            setColor(if (undo != null) Color.rgb(200, 120, 20) else Color.rgb(30, 140, 70)); cornerRadius = dp(20).toFloat()
        }
    }

    private fun syncPickups() {
        if (!pickups.configured()) return
        io.execute {
            try {
                val out = pickups.sync()
                main.post {
                    for (r in out.sent) showMessage("#${r.receipt} sent — waiting for the tablet to confirm")
                    for (r in out.confirmed) showMessage("#${r.receipt} — tablet confirmed picked up")
                    for (r in out.problems) showMessage(Pickups.problemText(r.receipt, r.status), 10_000)
                    // Key missing or wrong: say it once, not every minute.
                    if (out.denied && !keyDeniedShown) showMessage("Phone key not set up — Picked up isn't reaching the tablet", 10_000)
                    keyDeniedShown = out.denied
                    render()
                }
            } catch (e: Exception) {
                Log.d("TailorCounter", "pickup sync failed: ${e.message}") // kept; retried next minute
            }
        }
    }

    /**
     * Art: a normal tap on the automatic / tap-to-talk button switches as before; holding it 3 s opens
     * Settings (version + Update) instead. The Update button and version are no longer on the main screen.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun holdForSettings(b: Button) {
        var opened = false
        val open = Runnable { opened = true; b.isPressed = false; showSettings() }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { opened = false; v.isPressed = true; main.postDelayed(open, 3_000) }
                android.view.MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(open); v.isPressed = false
                    if (!opened) v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(open); v.isPressed = false }
            }
            true
        }
    }

    /** The wake chime Art picked (index into Chime.OPTIONS, -1 = off); "Ding-dong" until he picks. */
    private fun chime() = getPreferences(MODE_PRIVATE).getInt("chime", 0)

    private fun showSettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Settings — Tailor Counter v${Updater.versionName(this)}")
            .setSingleChoiceItems((Chime.OPTIONS.map { "Chime: ${it.name}" } + "Chime: off").toTypedArray(),
                chime().let { if (it < 0) Chime.OPTIONS.size else it }) { _, i ->
                // Tapping a chime plays it and picks it (Art hears them on the Fold and chooses).
                val pick = if (i >= Chime.OPTIONS.size) -1 else i
                getPreferences(MODE_PRIVATE).edit().putInt("chime", pick).apply()
                Chime.play(pick)
            }
            .setPositiveButton("Update now") { _, _ -> checkUpdate() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkUpdate() {
        showMessage("Checking for update…", 20_000)
        io.execute {
            val r = try { Updater.checkAndInstall(applicationContext) } catch (e: Exception) { "Update check failed: ${e.message}" }
            main.post { showMessage(r) }
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val cur = shown.getOrNull(index)
        shown.removeAll { now - it.addedAt > keepMs }
        index = cur?.let { shown.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    }

    private fun addResults(cards: List<Card>) {
        val now = System.currentTimeMillis()
        shown.removeAll { s -> cards.any { it.receipt == s.card.receipt } }
        val first = shown.size
        cards.forEach { shown += Shown(it, now) }
        index = first; lastRotate = now
        message = null; main.removeCallbacks(endMessage)
        render()
    }

    private fun showMessage(text: String, ms: Long = 6_000) {
        message = text
        main.removeCallbacks(endMessage)
        main.postDelayed(endMessage, ms)
        render()
    }

    private fun flip(step: Int) {
        if (shown.size < 2 || message != null) return
        index = (index + step + shown.size) % shown.size
        pausedUntil = System.currentTimeMillis() + 15_000 // let Art read what he flipped to
        lastRotate = System.currentTimeMillis()
        render()
    }

    // A swipe in any direction over the result area flips between orders. Caught here,
    // before any view, so nothing on screen can swallow it.
    private var downX = 0f
    private var downY = 0f
    private var downInStage = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                val loc = IntArray(2).also { stage.getLocationOnScreen(it) }
                downInStage = ev.rawY >= loc[1] && ev.rawY <= loc[1] + stage.height
            }
            android.view.MotionEvent.ACTION_UP -> if (Math.abs(ev.rawX - downX) < dp(20) && Math.abs(ev.rawY - downY) < dp(20)) {
                val now = System.currentTimeMillis()
                if (screenOff) { awakeUntil = now + 5 * 60_000; screenOff = false; if (handsFree) listenSoon(300) }
                chromeUntil = now + 10_000
                render(); main.postDelayed({ render() }, 10_200)
            } else if (downInStage) {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                // Any direction: left/up = next, right/down = previous.
                when {
                    Math.abs(dx) > dp(80) && Math.abs(dx) >= Math.abs(dy) -> flip(if (dx < 0) 1 else -1)
                    Math.abs(dy) > dp(80) -> flip(if (dy < 0) 1 else -1)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding/unfolding changes the size: redraw so the number fills it again.
        stage.post { render() }
    }

    /** Text size (px) that makes [text] fill [width] px in bold, capped at [maxPx]. */
    private fun fillWidth(text: String, width: Int, maxPx: Float): Float {
        val p = android.graphics.Paint().apply { typeface = Typeface.DEFAULT_BOLD; textSize = 100f }
        val w = p.measureText(text).coerceAtLeast(1f)
        return minOf(100f * width * 0.98f / w, maxPx)
    }

    /** [drift] = room left on each side for the anti-burn-in shift, so the text is never cut off. */
    private fun pulse(v: View) = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.25f).apply {
        duration = 500; repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE; start()
    }

    private fun bigLine(text: String, color: Int, maxHeightShare: Float, drift: Int) = TextView(this).apply {
        setTextColor(color); typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; includeFontPadding = false; gravity = Gravity.CENTER_HORIZONTAL
        val w = (if (stage.width > 0) stage.width else resources.displayMetrics.widthPixels - dp(16)) - 2 * drift
        val h = if (stage.height > 0) stage.height else resources.displayMetrics.heightPixels / 2
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fillWidth(text, w, h * maxHeightShare))
        this.text = text
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private val HINT = "Say: Sofia, find + last 4 phone digits or receipt #"
    private var busyStreak = 0      // recognizer "busy" answers in a row (back-off)
    private var wakeUntil = 0L      // her name was just heard: bright listening screen until then
    private var wasListening = false

    private fun isListeningScreen(): Boolean {
        val now = System.currentTimeMillis()
        return !screenOff && (now < wakeUntil || (now < armedUntil && wakeUntil != 0L))
    }
    private var live: String? = null           // her words while she's being spoken to
    private var block: View? = null            // the idle Sofia + clock + date block
    private var bounceX = 0f; private var bounceY = 0f
    private var vx = 1f; private var vy = 0.7f // px per frame (~25 frames a second): slow, steady

    private fun stageW() = if (stage.width > 0) stage.width else resources.displayMetrics.widthPixels

    /** Small "Sofia" + the hint, at the top of every non-idle screen. */
    private fun banner() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(sofiaLine(dp(90).toFloat()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(16) }
        })
        addView(smallLine(HINT, Color.rgb(140, 140, 140), 18f))
    }

    private fun liveLine(text: String, size: Float) = TextView(this).apply {
        setTextColor(Color.rgb(120, 200, 255)); textSize = size; gravity = Gravity.CENTER; maxLines = 3
        this.text = text
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(24) }
    }

    /** Moves the idle block a little each frame, bouncing off the stage edges like a ping-pong screensaver. */
    private val bouncer = object : Runnable {
        override fun run() {
            val b = block
            if (b != null && b.parent === stage && b.width > 0) {
                val lx = ((stage.width - b.width) / 2f).coerceAtLeast(0f)
                val ly = ((stage.height - b.height) / 2f).coerceAtLeast(0f)
                bounceX += vx; bounceY += vy
                if (bounceX > lx) { bounceX = lx; vx = -kotlin.math.abs(vx) } else if (bounceX < -lx) { bounceX = -lx; vx = kotlin.math.abs(vx) }
                if (bounceY > ly) { bounceY = ly; vy = -kotlin.math.abs(vy) } else if (bounceY < -ly) { bounceY = -ly; vy = kotlin.math.abs(vy) }
                b.translationX = bounceX; b.translationY = bounceY
            }
            main.postDelayed(this, 40)
        }
    }

    private fun sofiaLine(width: Float) = TextView(this).apply {
        val face = try { resources.getFont(R.font.great_vibes) } catch (e: Exception) { Typeface.SERIF }
        typeface = face; setTextColor(Color.rgb(200, 200, 200)); gravity = Gravity.CENTER; includeFontPadding = false
        val probe = android.graphics.Paint().apply { typeface = face; textSize = 100f }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 100f * width / probe.measureText("Sofia"))
        text = "Sofia"
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun smallLine(text: String, color: Int, size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color); textSize = size; gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1
        if (bold) typeface = Typeface.DEFAULT_BOLD
        this.text = text
    }

    private fun render() {
        if (!::stage.isInitialized) return
        prune()
        block = null
        stage.removeAllViews()
        val msg = message
        applyMode(clockOnly = msg == null && shown.isEmpty())
        val listeningNow = isListeningScreen()
        wasListening = listeningNow
        rootView.setBackgroundColor(if (listeningNow) Color.rgb(10, 60, 150) else Color.BLACK)
        when {
            screenOff -> {} // black
            listeningNow -> {
                // Bright, unmistakable: she heard her name and is listening (until the number comes or 8 s pass).
                stage.addView(sofiaLine(stageW() * 0.35f).apply { setTextColor(Color.WHITE) })
                stage.addView(smallLine("Listening…  ·  Слушаю…", Color.WHITE, 44f, bold = true))
                live?.let { stage.addView(liveLine(it, 40f).apply { setTextColor(Color.rgb(200, 230, 255)) }) }
                stage.addView(smallLine(HINT, Color.rgb(170, 200, 240), 18f).apply { setPadding(0, dp(24), 0, 0) })
            }
            msg != null -> stage.addView(banner()).let { stage.addView(TextView(this).apply {
                setTextColor(Color.rgb(255, 120, 120)); textSize = 44f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER; text = msg
            }) }
            live != null && shown.isEmpty() -> { stage.addView(banner()); stage.addView(liveLine(live!!, 48f)) }
            shown.isEmpty() -> {
                heard.text = ""
                val now = java.text.SimpleDateFormat("h:mm", Locale.US).format(java.util.Date())
                val date = java.text.SimpleDateFormat("MM/dd/yyyy", Locale.US).format(java.util.Date())
                val dateLine = smallLine(date, Color.rgb(170, 170, 170), 44f)
                // Art: her name above the clock in pen-style calligraphy (Great Vibes, OFL, bundled), about as wide
                // as the date line; then the clock, the date (US month/day/year) and the hint. The block is smaller
                // than the screen and bounces around it slowly (no dimming) to protect the screen.
                val b = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                b.addView(sofiaLine(dateLine.paint.measureText(date)).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                b.addView(bigLine(now, Color.rgb(200, 200, 200), 0.42f, drift = stageW() / 5).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                b.addView(dateLine) // Art: the idle block is only Sofia + clock + date (no hint)
                stage.addView(b)
                block = b
                b.translationX = bounceX; b.translationY = bounceY
            }
            else -> {
                val c = shown[index].card
                stage.addView(banner()) // Art: Sofia + what to say on every screen
                live?.let { stage.addView(liveLine(it, 28f)) }
                // The receipt number edge to edge; everything else small underneath.
                // Any balance left: the number in red, pulsing once per second. Paid / account / unknown: as before.
                stage.addView(bigLine(c.receipt, if (c.pay == Pay.DUE) Color.rgb(255, 50, 50) else Color.WHITE, 0.5f,
                    drift = dp(NUMBER_DRIFT_DP)).apply { if (c.pay == Pay.DUE) pulse(this) })
                stage.addView(smallLine(listOf(c.customer, c.phone).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    Color.rgb(190, 190, 190), 22f))
                stage.addView(smallLine(if (c.ready) "Ready for pickup · Готово к выдаче" else "Not finished yet · Ещё не готово",
                    if (c.ready) Color.rgb(90, 210, 120) else Color.rgb(255, 170, 60), 22f, bold = true))
                pickups.latest(c.receipt)?.let { stage.addView(smallLine(Pickups.stateText(it), Color.rgb(120, 170, 255), 20f)) }
                if (shown.size > 1) stage.addView(smallLine("${index + 1} / ${shown.size}   · swipe to flip", Color.rgb(120, 120, 120), 16f))
            }
        }
    }

    private fun idleText(): String {
        if (!sheets.hasKey()) return "Setup needed: the sheet key is not installed"
        val n = orders.size
        val test = if (usbTestMode()) " · TEST: always on (USB)" else ""
        return if (handsFree) "$n orders$test" else "$n orders · tap the button to talk$test"
    }

    private fun setStatus(s: String) { status.text = s }

    private fun updateMic() {
        micButton.text = if (listening) "● Listening — tap to stop" else "🎤  Tap to talk / Нажмите и говорите"
        micButton.background = GradientDrawable().apply {
            setColor(if (listening) Color.rgb(200, 40, 40) else Color.rgb(30, 100, 220)); cornerRadius = dp(20).toFloat()
        }
    }

    private fun updateLang() { langButton.text = if (russianFirst) "RU → EN" else "EN → RU" }
    private fun updateHands() { handsButton.text = if (handsFree) "Hands-free ON" else "Hands-free OFF" }

    private fun restartIfListening() { if (listening) { stopListening(); listenSoon(200) } }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
