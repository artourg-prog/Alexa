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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

    @Volatile private var orders: List<Order> = emptyList()
    @Volatile private var loadedAt = 0L

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var handsFree = true
    private var russianFirst = false
    private var resumed = false

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var cardsBox: LinearLayout
    private lateinit var micButton: Button
    private lateinit var langButton: Button
    private lateinit var handsButton: Button

    private val refreshEvery = 60_000L
    private val refresher = object : Runnable {
        override fun run() {
            refresh(null)
            main.postDelayed(this, refreshEvery)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sheets = Sheets(File(filesDir, "sheet-key.json"))
        setContentView(buildUi())
        tts = TextToSpeech(this) { ok ->
            if (ok == TextToSpeech.SUCCESS) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { if (handsFree) listenSoon(300) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { main.post { if (handsFree) listenSoon(300) } }
            })
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        main.post(refresher)
        if (handsFree) listenSoon(800)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(refresher)
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
        if (!resumed || listening || tts?.isSpeaking == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition is not available on this phone"); return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener); recognizer = it
        }
        val primary = if (russianFirst) "ru-RU" else "en-US"
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
        override fun onReadyForSpeech(params: Bundle?) { setStatus(if (russianFirst) "Слушаю…" else "Listening…") }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { setStatus(if (russianFirst) "Ищу…" else "Looking…") }
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            updateMic()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    setStatus("Microphone is off — allow it in Settings › Apps › Tailor Counter")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    setStatus("Speech language not available — switching"); russianFirst = !russianFirst; updateLang()
                    if (handsFree) listenSoon(1000)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recognizer?.destroy(); recognizer = null; if (handsFree) listenSoon(1500) }
                else -> { setStatus(idleText()); if (handsFree) listenSoon(400) }
            }
        }

        override fun onResults(results: Bundle?) {
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
        heard.text = alts.first()
        val go = {
            // First alternative that finds something wins; otherwise the top one.
            val pick = alts.firstOrNull { Lookup.findAll(it, orders).isNotEmpty() } ?: alts.first()
            val cards = Lookup.findAll(pick, orders)
            val russian = pick.any { it in 'а'..'я' || it in 'А'..'Я' } ||
                detectedLang?.startsWith("ru") == true || (detectedLang == null && russianFirst)
            if (Lookup.parseQueries(pick).isEmpty()) {
                setStatus(idleText()); if (handsFree) listenSoon(300)
            } else {
                heard.text = pick
                showCards(cards)
                speak(Lookup.reply(cards, russian), russian)
                setStatus(idleText())
            }
        }
        // Use a copy no older than 20 s so a just-dropped-off order is found.
        if (System.currentTimeMillis() - loadedAt > 20_000) refresh(go) else go()
    }

    private fun speak(text: String, russian: Boolean) {
        val t = tts ?: return
        t.language = if (russian) Locale("ru", "RU") else Locale.US
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    // ---- UI ----

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply {
            setTextColor(Color.rgb(170, 170, 170)); textSize = 20f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        langButton = smallButton { russianFirst = !russianFirst; updateLang(); restartIfListening() }
        handsButton = smallButton {
            handsFree = !handsFree; updateHands()
            if (handsFree) listenSoon(200) else stopListening()
        }
        top.addView(status); top.addView(langButton); top.addView(handsButton)

        heard = TextView(this).apply {
            setTextColor(Color.rgb(120, 200, 255)); textSize = 26f
            setPadding(0, dp(8), 0, dp(8))
        }
        cardsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(cardsBox)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        micButton = Button(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
            setOnClickListener { if (listening) stopListening() else { tts?.stop(); startListening() } }
        }
        root.addView(top); root.addView(heard); root.addView(scroll); root.addView(micButton)
        updateLang(); updateHands(); updateMic()
        setStatus(if (sheets.hasKey()) "Loading orders…" else "Setup needed: the sheet key is not installed")
        hint()
        return root
    }

    private fun smallButton(onClick: () -> Unit) = Button(this).apply {
        textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(50, 50, 50)); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)).apply { leftMargin = dp(8) }
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun hint() {
        cardsBox.removeAllViews()
        cardsBox.addView(TextView(this).apply {
            setTextColor(Color.rgb(140, 140, 140)); textSize = 24f
            text = "Say a receipt number, the last 4 digits of the phone, or the customer's name.\n\n" +
                "Скажите номер квитанции, последние 4 цифры телефона или имя клиента."
        })
    }

    private fun showCards(cards: List<Card>) {
        cardsBox.removeAllViews()
        if (cards.isEmpty()) {
            cardsBox.addView(TextView(this).apply {
                setTextColor(Color.rgb(255, 120, 120)); textSize = 40f; typeface = Typeface.DEFAULT_BOLD
                text = "I can't find this order"
            })
            return
        }
        if (cards.size > 1) cardsBox.addView(TextView(this).apply {
            setTextColor(Color.rgb(170, 170, 170)); textSize = 22f; text = "${cards.size} orders"
        })
        val big = if (cards.size == 1) 110f else 72f
        for (c in cards) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Color.rgb(28, 28, 30)); cornerRadius = dp(18).toFloat() }
                setPadding(dp(20), dp(12), dp(20), dp(16))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(big)); text = c.receipt
            })
            card.addView(TextView(this).apply { setTextColor(Color.WHITE); textSize = 40f; text = c.customer.ifEmpty { "—" } })
            card.addView(TextView(this).apply { setTextColor(Color.rgb(190, 190, 190)); textSize = 32f; text = c.phone })
            cardsBox.addView(card)
        }
    }

    private fun idleText(): String {
        if (!sheets.hasKey()) return "Setup needed: the sheet key is not installed"
        val n = orders.size
        return if (handsFree) "$n orders · always listening" else "$n orders · tap the button to talk"
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
