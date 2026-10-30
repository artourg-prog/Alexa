package com.tailorshop.counter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.FrameLayout
import com.tailorshop.counter.conversation.DayClock
import com.tailorshop.counter.conversation.DayClockView
import com.tailorshop.counter.conversation.Conversation
import com.tailorshop.counter.conversation.ConversationIndicator
import com.tailorshop.counter.conversation.HoldSettingsControl
import com.tailorshop.counter.conversation.CardDeck
import com.tailorshop.counter.conversation.Intent as VoiceIntent
import com.tailorshop.counter.conversation.OfflineSpeech
import com.tailorshop.counter.conversation.OrderCache
import com.tailorshop.counter.conversation.SafeIntent
import com.tailorshop.counter.conversation.SemanticClient
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Voice, conversation and delivery are separate: a visible card never confirms a business action. */
class MainActivity:Activity() {
    private val main=Handler(Looper.getMainLooper())
    private val storage=Executors.newSingleThreadExecutor()
    private val delivery=Executors.newSingleThreadExecutor()
    private val reads=Executors.newSingleThreadExecutor()
    private val updates=Executors.newSingleThreadExecutor()
    private var updateDialog:AlertDialog?=null
    private var updateChecking=false
    private var updateCheck:(android.content.Context,(String)->Unit)->String = { context,progress -> Updater.checkAndInstall(context,progress) }
    private val semantics=Executors.newSingleThreadExecutor()
    private val weatherReads=Executors.newSingleThreadExecutor()
    private lateinit var clockFace:DayClockView
    private lateinit var boardScroll:ScrollView
    private var clockShowing=false
    private var clockPreviewUntil=0L
    private var weatherAt=0L
    private var weatherObservation:DayClock.Weather?=null
    private var powered=false
    private var batteryPercent=-1
    private val batteryReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context,intent:android.content.Intent) {
            powered=intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0)!=0
            val level=intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1);val scale=intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100)
            batteryPercent=if(level>=0 && scale>0)level*100/scale else -1
            updateStatus()
        }
    }
    private fun clockEnabled()=getSharedPreferences("clock",MODE_PRIVATE).getBoolean("enabled",true)
    private lateinit var speech:OfflineSpeech
    private lateinit var finishes:Finishes
    private lateinit var pickups:Pickups
    private lateinit var sheets:Sheets
    private lateinit var cache:OrderCache
    private lateinit var intentService:SemanticClient
    private lateinit var dialog:Conversation
    private lateinit var root:LinearLayout
    private lateinit var controls:LinearLayout
    private var settingsDialog:AlertDialog?=null
    private lateinit var settingsGear:HoldSettingsControl
    private lateinit var logo:ConversationIndicator
    private lateinit var clock:TextView
    private lateinit var date:TextView
    private lateinit var heard:TextView
    private lateinit var mic:TextView
    private lateinit var dataState:TextView
    private lateinit var answer:TextView
    private lateinit var cards:LinearLayout
    private lateinit var queue:TextView
    private lateinit var smsYes:Button
    private lateinit var smsNo:Button
    private lateinit var hints:TextView
    private lateinit var picked:Button
    private lateinit var problem:Button
    private var orders:List<Order> = emptyList()
    private var loadedAt=0L
    private var resumed=false
    private var running=false
    private var lastAudio=0L
    private var retryAt=0L
    private var errorCount=0
    private var manualUntil=0L
    private var refreshAt=0L
    private var syncAt=0L
    private var refreshing=false
    private var lastRefreshFailed=false
    private var waitingLookup:Pair<Conversation.Effect.Find,Long>?=null
    private var syncing=false
    private var russian=true
    private var micState="Preparing local speech…"
    private var lastRaw=""
    private var lastProgress=""
    private var lastSegment=""
    private var lastSegmentAt=0L
    private var segmentRevision=0L
    private data class Segment(val text:String,val ru:Boolean,val confidence:Double)
    private val segments=mutableListOf<Segment>()
    private val flushSegments=Runnable { flushSpeech() }
    private var pendingPrompt:Pair<String,Long>?=null
    private val speakPrompt=Runnable {
        val prompt=pendingPrompt;pendingPrompt=null
        if(prompt!=null && resumed && dialog.engaged && dialog.revision==prompt.second && !speaking) say(prompt.first)
    }
    private fun cancelPrompt() { pendingPrompt=null;main.removeCallbacks(speakPrompt) }
    private fun clarify(text:String) {
        cancelPrompt();answer.text=text;pendingPrompt=text to dialog.revision
        main.postDelayed(speakPrompt,2000)
    }
    private val deck=CardDeck()
    private var lookupNumbers:List<String> = emptyList()
    private var lookupMessage=""
    private var lookupUntil=0L
    private var pickupCommitting=false
    private var undo:String?=null
    private var undoDeadline=0L
    private var finishId:String?=null
    private val zone=ZoneId.of("America/Los_Angeles")
    private var wallClock=java.time.Clock.system(zone)
    private var tts:TextToSpeech?=null
    private var ttsReady=false
    private data class Spoken(val text:String,val male:Boolean=false)
    private val spoken=ArrayDeque<Spoken>()
    private var speaking=false
    private var speechId=0L
    private var speechDeadline=0L
    private var voiceWarning=false
    private var night:Boolean?=null
    private var activeTheme:Boolean?=null
    private var boardStyle=-1
    private var screenHeld=false
    private var readyCue=false
    private fun tapToTalk()=getSharedPreferences("power",MODE_PRIVATE).getBoolean("tapToTalk",true)
    private fun beginListening() {
        clockPreviewUntil=0
        manualUntil=SystemClock.elapsedRealtime()+300_000;refreshAt=0
        dialog.wake(SystemClock.elapsedRealtime());retryAt=0
        readyCue=true
        if(running && micState.startsWith("Microphone ready") && !speaking) { readyCue=false;Sounds.play(this,Sounds.Event.WAKE) }
        updateStatus()
    }

    private var boardTouchX=0f
    private var boardTouchY=0f
    private var boardTouchAt=0L
    private var boardTouchMoved=false
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{boardTouchX=event.rawX;boardTouchY=event.rawY;boardTouchAt=event.eventTime;boardTouchMoved=false}
            MotionEvent.ACTION_MOVE->if(kotlin.math.abs(event.rawX-boardTouchX)>android.view.ViewConfiguration.get(this).scaledTouchSlop || kotlin.math.abs(event.rawY-boardTouchY)>android.view.ViewConfiguration.get(this).scaledTouchSlop)boardTouchMoved=true
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->boardTouchMoved=true
            MotionEvent.ACTION_UP->{
                if(::dialog.isInitialized && ::controls.isInitialized && resumed && !clockShowing && !speaking && !dialog.awake && !boardTouchMoved && event.eventTime-boardTouchAt<500 && settingsDialog?.isShowing!=true) {
                    val bounds=android.graphics.Rect()
                    val onAction=listOf<View>(controls,picked,smsYes,smsNo,problem).any { it.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(),event.rawY.toInt()) }
                    if(!onAction)beginListening()
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val power=getSharedPreferences("power",MODE_PRIVATE)
        if(!power.getBoolean("bounded-dialogue-v43",false))power.edit().putBoolean("tapToTalk",true).putBoolean("bounded-dialogue-v43",true).apply()
        finishes=Finishes(filesDir);pickups=Pickups(filesDir);sheets=Sheets(File(filesDir,"sheet-key.json"));cache=OrderCache(filesDir)
        intentService=SemanticClient(applicationContext)
        dialog=Conversation { finishes.workers() }
        weatherObservation=DayClock.cached(getSharedPreferences("clock",MODE_PRIVATE).getString("weather","").orEmpty())
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(batteryReceiver,android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),RECEIVER_NOT_EXPORTED)
        else registerReceiver(batteryReceiver,android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        semantics.execute { intentService.refresh() }
        cache.load()?.let { try { orders=it.orders();loadedAt=it.at } catch(_:Exception) {} }
        seedAnnouncements();finishId=finishes.all().lastOrNull()?.scanId
        setContentView(buildUi());renderQueue()
        speech=OfflineSpeech(applicationContext,object:OfflineSpeech.Listener {
            override fun onState(state:String) { main.post {
                micState=state;lastAudio=SystemClock.elapsedRealtime()
                if(readyCue && resumed && running && dialog.awake && state.startsWith("Microphone ready")) { readyCue=false;Sounds.play(this@MainActivity,Sounds.Event.WAKE) }
                updateStatus()
            } }
            override fun onPartial(ru:String,en:String,level:Float) { main.post {
                if(!running || speaking || tapToTalk() && !dialog.awake)return@post
                lastAudio=SystemClock.elapsedRealtime();errorCount=0;logo.energy(level)
                val text=if(russian) ru.ifBlank { en } else en.ifBlank { ru }
                if(text.isNotBlank()) {
                    if(text!=lastProgress) {
                        dialog.progress(lastAudio);lastProgress=text;segmentRevision++
                        if(pendingPrompt!=null) { main.removeCallbacks(speakPrompt);main.postDelayed(speakPrompt,2000) }
                    }
                    lastRaw=text;heard.text="${SpokenNumbers.display(text)}"
                }
                updateStatus()
            } }
            override fun onWake(russian:Boolean) { main.post {
                if(!resumed || speaking || tapToTalk()) return@post
                this@MainActivity.russian=russian
                val wasAwake=dialog.engaged;dialog.wake(SystemClock.elapsedRealtime())
                if(!wasAwake) Sounds.play(this@MainActivity,Sounds.Event.WAKE)
                updateStatus()
            } }
            override fun onSegment(text:String,russian:Boolean,confidence:Double) { main.post {
                if(!resumed || speaking || !running || !dialog.awake) return@post
                lastRaw=text;heard.text="${SpokenNumbers.display(text)}"
                if(!dialog.awake) return@post
                dialog.progress(SystemClock.elapsedRealtime());segmentRevision++
                segments+=Segment(text,russian,confidence)
                main.removeCallbacks(flushSegments);main.postDelayed(flushSegments,450)
            } }
            override fun onFailure(message:String) { main.post {
                running=false;errorCount++;retryAt=SystemClock.elapsedRealtime()+minOf(30_000,errorCount*2000).toLong()
                micState=message;updateStatus()
            } }
        })
        tts=TextToSpeech(this) { code -> main.post {
            ttsReady=code==TextToSpeech.SUCCESS
            if(ttsReady) tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                override fun onStart(id:String?) {}
                override fun onDone(id:String?) { main.post { if(id=="s$speechId") speakNext() } }
                @Deprecated("Android callback") override fun onError(id:String?) { main.post { if(id=="s$speechId") speakNext() } }
            })
        } }
        Updater.scheduleNight(this);scheduleWake()
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1)
    }
    override fun onResume() {
        super.onResume();resumed=true;Wake.clear(this)
        window.insetsController?.apply { hide(android.view.WindowInsets.Type.systemBars());systemBarsBehavior=android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        if(tapToTalk() && shopHours() && !clockEnabled()) { dialog.wake(SystemClock.elapsedRealtime());readyCue=true }
        refreshAt=0 // A newly awakened counter must not wait out an old polling interval.
        main.post(tick)
    }
    override fun onPause() {
        resumed=false;readyCue=false;waitingLookup=null;settingsGear.cancelHold();logo.cancelSettingsHold();logo.show(ConversationIndicator.Mode.REST);cancelPrompt();main.removeCallbacks(tick);main.removeCallbacks(flushSegments);segments.clear();segmentRevision++
        dialog.cancel();undo=null;speech.stop();running=false;stopVoice()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);screenHeld=false
        super.onPause()
    }
    override fun onDestroy() { try { unregisterReceiver(batteryReceiver) } catch(_:Exception) {};weatherReads.shutdownNow();updateDialog?.dismiss();updates.shutdown();speech.close();tts?.shutdown();delivery.shutdownNow();storage.shutdownNow();reads.shutdownNow();semantics.shutdownNow();super.onDestroy() }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(results.firstOrNull()!=PackageManager.PERMISSION_GRANTED) { micState="Microphone permission denied · allow it in Android settings";updateStatus() }
    }
    private fun shopHours():Boolean {
        if(SystemClock.elapsedRealtime()<manualUntil) return true
        val usb=try { registerReceiver(null,android.content.IntentFilter("android.hardware.usb.action.USB_STATE")) } catch(_:Exception) { null }
        if(usb?.getBooleanExtra("connected",false)==true && usb.getBooleanExtra("adb",false)) return true
        val t=ZonedDateTime.now(wallClock);val minutes=t.hour*60+t.minute
        return minutes>=645 && minutes<1170
    }
    private fun scheduleWake() {
        if(tapToTalk()) { Wake.cancel(this,1);Wake.cancel(this,3);return }
        val now=ZonedDateTime.now(wallClock);var at=now.withHour(10).withMinute(45).withSecond(0).withNano(0)
        if(!at.isAfter(now)) at=at.plusDays(1)
        Wake.schedule(this,at.toInstant().toEpochMilli(),1)
    }
    private val tick=object:Runnable {
        override fun run() {
            if(!resumed)return
            val now=SystemClock.elapsedRealtime()
            val shop=shopHours()
            dialog.tick(now)
            val capture=shop && !clockShowing && !speaking && (if(tapToTalk()) dialog.awake && dialog.phase !in setOf(Conversation.Phase.PROCESSING,Conversation.Phase.SAVING) else true)
            if(capture && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
                if(running && !speaking && now-lastAudio>20_000) { speech.stop();running=false;retryAt=now+1000;micState="Recovering microphone…" }
                if(!running && now>=retryAt) { running=true;lastAudio=now;speech.start() }
            } else if(running) { speech.stop();running=false;if(!shop)dialog.cancel();micState=if(shop)"Tap Listen · microphone off" else "Microphone off · outside shop hours" }
            if(speaking && now>speechDeadline) { stopVoice();answer.text="Voice playback interrupted · listening resumed" }
            Updater.confirmIntent?.let { Updater.confirmIntent=null;startActivity(it) }
            if(lookupNumbers.isNotEmpty() && now>=lookupUntil) { lookupNumbers=emptyList();renderCards() }
            if(deck.expire(now,undo))renderCards()
            if(undo!=null && now>=undoDeadline) commitPickup()
            if(now>=refreshAt) { refreshAt=now+60_000;refresh() }
            if(now>=syncAt) { syncAt=now+5000;sync() }
            val t=ZonedDateTime.now(wallClock)
            val drift=((t.minute%7)-3)*resources.displayMetrics.density*5
            if(clock.translationX!=drift) { clock.translationX=drift;date.translationX=-drift }
            clock.showText(t.format(DateTimeFormatter.ofPattern("h:mm a")));date.showText(t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
            dataState.showText(when {
                refreshing->"Refreshing orders…"
                loadedAt==0L->if(lastRefreshFailed)"Offline · orders unavailable" else "Orders not loaded"
                else->(if(lastRefreshFailed)"Offline · cached orders" else "Orders")+" · ${orders.size} · ${((System.currentTimeMillis()-loadedAt)/60_000).coerceAtLeast(0)} min old"
            })
            updateStatus();main.postDelayed(this,if(clockShowing)1000 else if(shop)250 else 1000)
        }
    }
    private fun flushSpeech() {
        val batch=segments.toList();segments.clear()
        if(batch.isEmpty() || !dialog.awake) return
        val chosen=batch.maxBy { s ->
            val parsed=SafeIntent.local(s.text,dialog.context())
            (if(parsed!=VoiceIntent.Ignore) 2.0 else 0.0)+(if(s.ru==russian) 1.0 else 0.0)+s.confidence
        }
        val now=SystemClock.elapsedRealtime()
        if(chosen.text==lastSegment && now-lastSegmentAt<2000)return
        lastSegment=chosen.text;lastSegmentAt=now;russian=chosen.ru
        heard.text="${SpokenNumbers.display(chosen.text)}"
        val words=Lookup.normalize(chosen.text).split(' ')
        if(words.all { it in setOf("sofia","sophia","sofiya","sofya","sophie","sofi","this","is","i","am","eto","ya","pozhaluysta","please") })return
        if(SafeIntent.endsConversation(chosen.text) || SafeIntent.local(chosen.text,dialog.context())==VoiceIntent.Cancel) {
            dialog.cancel();cancelPrompt();stopVoice();pauseCapture();updateStatus();return
        }
        if(Lookup.asksIfSheHears(chosen.text)) { cancelPrompt();say(Lookup.hearsReply(russian));return }
        if(Lookup.asksWhoSheIs(chosen.text)) { cancelPrompt();say(if(russian)Lookup.INTRO_RU else Lookup.INTRO);return }
        val context=dialog.context()
        val local=SafeIntent.local(chosen.text,context)
        if(dialog.phase in setOf(Conversation.Phase.FOLLOWUP,Conversation.Phase.PROCESSING) && local !is VoiceIntent.Find && local !is VoiceIntent.Report)return
        if(local is VoiceIntent.Finish && chosen.confidence<0.72) {
            clarify(if(russian)"Не уверена в номере. Повтори команду с номером квитанции." else "I'm not sure of the number. Please repeat the command with the receipt number.")
            return
        }
        if(local is VoiceIntent.Answer && chosen.confidence<0.65) {
            say(if(russian)"SMS отправить? Да или нет?" else "Send SMS? Yes or no?")
            return
        }
        if(local==VoiceIntent.Ignore || local==VoiceIntent.Need(VoiceIntent.Field.ACTION)) {
            val rev=dialog.revision;val audioRev=segmentRevision
            answer.text=if(russian)"Уточняю команду…" else "Understanding your request…"
            semantics.execute {
                val suggested=intentService.classify(chosen.text,context)
                main.post {
                    if(resumed && rev==dialog.revision && audioRev==segmentRevision) {
                        dispatch(dialog.accept(chosen.text,suggested?:VoiceIntent.Need(VoiceIntent.Field.ACTION),SystemClock.elapsedRealtime()));updateStatus()
                    }
                }
            }
        } else { dispatch(dialog.accept(chosen.text,local,now));updateStatus() }
    }
    private fun dispatch(effects:List<Conversation.Effect>) {
        if(effects.isNotEmpty())cancelPrompt()
        effects.forEach { effect -> when(effect) {
            is Conversation.Effect.Say -> {
                val text=if(russian)effect.ru else effect.en
                if(effect.prompt)clarify(text) else say(text)
            }
            is Conversation.Effect.Find -> find(effect)
            is Conversation.Effect.Report -> {
                val rev=dialog.revision
                answer.text=if(russian)"Проверяю сохранённые отчёты…" else "Checking saved reports…"
                reads.execute {
                    val reply=try { ReportStatus.reply(ReportStatus.Request(effect.receipt,effect.worker),finishes.reportStatus(effect.receipt),russian) }
                        catch(_:Exception) { if(russian)"Не удалось проверить Daily Report. Попробуй ещё раз." else "Daily Report is unavailable. Please try again." }
                    main.post { if(resumed && rev==dialog.revision) { dialog.reportFinished(SystemClock.elapsedRealtime());pauseCapture();say(reply) } }
                }
            }
            is Conversation.Effect.Save -> {
                deck.clear();lookupNumbers=emptyList();renderCards()
                val rev=dialog.revision
                if(!finishes.configured()) { dispatch(dialog.saved(null,SystemClock.elapsedRealtime()));return@forEach }
                storage.execute {
                    val saved=finishes.addOrReuse(effect.worker,effect.receipt)
                    main.post {
                        // Storage results still exist after a cancelled UI; never attach them to a newer conversation.
                        if(resumed && rev==dialog.revision) {
                            finishId=saved?.scanId;renderQueue();dispatch(dialog.saved(saved?.scanId,SystemClock.elapsedRealtime()));updateStatus()
                        }
                    }
                }
            }
            is Conversation.Effect.Sms -> storage.execute {
                val ok=finishes.requestSms(effect.scanId)
                main.post { if(resumed) { say(if(ok) if(russian)"SMS в очереди." else "SMS queued." else if(russian)"Не удалось сохранить запрос SMS. Повтори." else "Couldn't save the SMS request. Please retry.");renderQueue() } }
            }
        } }
    }
    private fun find(request:Conversation.Effect.Find) { findInOrders(request,false) }
    private fun findInOrders(request:Conversation.Effect.Find,rechecked:Boolean,refreshFailed:Boolean=false) {
        if(!rechecked && refreshing) {
            waitingLookup=request to dialog.revision
            deck.clear();lookupNumbers=request.numbers;lookupUntil=SystemClock.elapsedRealtime()+30_000
            lookupMessage=if(russian)"Обновляю список перед поиском…" else "Updating the order list before searching…"
            answer.text=lookupMessage;renderCards();return
        }
        val pending=pickups.pending()
        val results=request.numbers.flatMap { n -> Lookup.find(when(n.length) { 4->Query.Last4(n);5,6->Query.Receipt(n);else->Query.FullPhone(n) },orders) }
            .filter { Lookup.digits(it.receipt) !in pending }.distinctBy { it.receipt }
        if(results.isEmpty()) {
            deck.clear();lookupNumbers=request.numbers;lookupUntil=SystemClock.elapsedRealtime()+10_000
            if(!rechecked && sheets.hasKey()) {
                val rev=dialog.revision
                lookupMessage=if(russian)"Проверяю обновлённый список…" else "Checking the updated order list…"
                answer.text=lookupMessage;renderCards()
                reads.execute {
                    val fresh=try {
                        val rows=sheets.readRows();val parsed=Lookup.parseOrders(rows);val at=System.currentTimeMillis()
                        cache.save(rows,at);parsed to at
                    } catch(_:Exception) { null }
                    main.post {
                        lastRefreshFailed=fresh==null
                        if(fresh!=null) { orders=fresh.first;loadedAt=fresh.second }
                        if(resumed && rev==dialog.revision)findInOrders(request,true,fresh==null)
                    }
                }
                return
            }
            lookupMessage=if(loadedAt==0L) if(russian)"Список пока недоступен." else "The order list isn't available yet."
                else if(refreshFailed) if(russian)"Обновить список не удалось. В сохранённой копии не найдено." else "Couldn't refresh the list. Not found in the cached copy."
                else if(russian)"В последней копии списка не найдено." else "Not found in the latest available list."
            renderCards();dialog.queryFinished();pauseCapture()
            say(request.numbers.joinToString(". ") { it.toCharArray().joinToString(" ") }+". "+lookupMessage)
            return
        }
        val now=SystemClock.elapsedRealtime();lookupNumbers=emptyList()
        deck.add(results,now);renderCards()
        val speech=results.take(4).flatMap { c ->
            if(request.topic!=null) listOf(Spoken(Lookup.followUpAnswer(request.topic,c,russian),request.topic==Topic.TAILOR && !c.ready))
            else listOf(Spoken((if(russian)"Квитанция " else "Receipt ")+c.receipt.toCharArray().joinToString(" ")+"."),
                Spoken(if(c.ready) if(russian)"Готово к выдаче." else "Ready for pickup." else if(russian)"Ещё не готово." else "Not ready yet.",!c.ready),
                Spoken(when(c.pay) { Pay.PAID->if(russian)"Оплачено." else "Paid.";Pay.DUE->if(russian)"Не оплачено." else "Not paid.";Pay.ACCOUNT->if(russian)"На счёте магазина." else "On the store account.";else->"" }))
        }
        val prefix=if(System.currentTimeMillis()-loadedAt>120_000)listOf(Spoken(if(russian)"По сохранённому списку." else "From the cached list.")) else emptyList()
        dialog.queryFinished();pauseCapture()
        sayParts(prefix+speech.filter { it.text.isNotBlank() })
    }
    private fun refresh() {
        if(refreshing || !sheets.hasKey())return
        refreshing=true
        reads.execute {
            try {
                val rows=sheets.readRows();val parsed=Lookup.parseOrders(rows);val at=System.currentTimeMillis();cache.save(rows,at)
                main.post { orders=parsed;loadedAt=at;lastRefreshFailed=false;dataState.text="Orders up to date" }
            } catch(_:Exception) { main.post { lastRefreshFailed=true;dataState.text="Offline · keeping last available orders" } }
            finally { main.post {
                refreshing=false
                val pending=waitingLookup;waitingLookup=null
                if(resumed && pending!=null && pending.second==dialog.revision)findInOrders(pending.first,true,lastRefreshFailed)
            } }
        }
    }
    private fun sync() {
        if(syncing)return
        syncing=true
        delivery.execute {
            try {
                val updates=finishes.sync()
                val out=pickups.sync()
                main.post {
                    if(!resumed)return@post
                    updates.forEach { if(it.scan=="ADDED") announceOnce("saved:${it.scanId}",Sounds.Event.SAVED);if(it.sms=="SENT") announceOnce("sms:${it.smsId}",Sounds.Event.SMS) }
                    if(out.confirmed.isNotEmpty()) Sounds.play(this@MainActivity,Sounds.Event.PICKED)
                    if(out.denied) queue.text="Pickup authorization failed · queued actions retained"
                    renderQueue();renderCards()
                }
            } catch(_:Exception) { main.post { if(resumed) renderQueue() } }
            finally { main.post { syncing=false } }
        }
    }
    private fun renderQueue() {
        val f=finishId?.let { finishes.get(it) }
        val waiting=finishes.all().count { it.scan !in Finishes.SCAN_FINAL || it.sendSms && it.sms !in Finishes.SMS_FINAL }
        queue.text=(if(waiting>0)"Work delivery pending: $waiting\n" else "")+(f?.let { "#${it.receipt} · ${Finishes.scanText(it,russian)}\n${Finishes.smsText(it,russian)}" }.orEmpty())
        val warning=finishes.warning()?:pickups.warning();val check=pickups.toCheck()
        problem.visibility=if(shopHours() && (warning!=null || check.isNotEmpty()))View.VISIBLE else View.GONE
        problem.text=warning?:check.firstOrNull()?.let { Pickups.problemText(it.receipt,it.status)+"\nTap to review" }.orEmpty()
    }
    private fun commitPickup() {
        val number=undo?:return;undo=null;pickupCommitting=true;renderCards()
        storage.execute { val ok=pickups.addOrReuse(number);main.post {
            pickupCommitting=false
            if(ok) { deck.remove(number);queue.text="#$number · queued, waiting for tablet confirmation" }
            else queue.text="#$number · could not save pickup confirmation; please retry"
            renderCards()
        } }
    }
    private fun onPicked() {
        if(pickupCommitting)return
        if(undo!=null) { undo=null;renderCards();return }
        val c=deck.current?:return
        if(!c.ready || !pickups.configured() || Lookup.digits(c.receipt) in pickups.pending())return
        undo=c.receipt;undoDeadline=SystemClock.elapsedRealtime()+5000;renderCards()
    }
    private fun reviewProblems() {
        val workWarning=finishes.warning()
        if(workWarning!=null) {
            AlertDialog.Builder(this).setTitle("Work queue needs checking").setMessage(workWarning)
                .setPositiveButton("I checked the tablet") { _,_->storage.execute { finishes.acknowledgeRecovery();main.post { renderQueue() } } }
                .setNegativeButton("Keep warning",null).show()
            return
        }
        val requests=pickups.toCheck()
        val r=requests.firstOrNull()
        AlertDialog.Builder(this).setTitle("Pickup needs checking")
            .setMessage(r?.let { Pickups.problemText(it.receipt,it.status)+"\nOnly dismiss after checking the receipt on the tablet." }?:pickups.warning())
            .setPositiveButton("I checked on the tablet") { _,_-> storage.execute {
                if(r!=null) pickups.dismiss(r.id) else pickups.acknowledgeRecovery()
                main.post { renderQueue() }
            } }.setNegativeButton("Keep warning",null).show()
    }
    private fun pauseCapture() {
        if(tapToTalk()) { speech.stop();running=false;readyCue=false;main.removeCallbacks(flushSegments);segments.clear();segmentRevision++ }
    }
    private fun say(text:String) { answer.text=text;sayParts(listOf(Spoken(text))) }
    private fun sayParts(parts:List<Spoken>) {
        answer.text=parts.joinToString(" ") { it.text }
        stopVoice();if(!ttsReady)return
        spoken.addAll(parts);speech.mute(true);speaking=true;speakNext()
    }
    private fun stopVoice() { speechId++;tts?.stop();spoken.clear();speaking=false;speech.mute(false) }
    private fun speakNext() {
        val next=spoken.removeFirstOrNull()
        if(next==null) {
            speaking=false
            main.postDelayed({ if(!speaking) speech.mute(false) },300)
            return
        }
        val t=tts?:return;val lang=if(russian)Locale("ru","RU") else Locale.US
        t.language=lang
        val key=if(next.male)"maleVoice-${lang.language}" else "femaleVoice-${lang.language}"
        val wanted=getSharedPreferences("voice",MODE_PRIVATE).getString(key,null)
        val voice=t.voices?.firstOrNull { it.name==wanted && it.locale.language==lang.language }
            ?:t.voices?.firstOrNull { it.locale.language==lang.language && (if(next.male) it.name.contains("male",true) && !it.name.contains("female",true) else it.name.contains("female",true)) }
        if(voice!=null)t.voice=voice
        else if(next.male)voiceWarning=true
        t.setPitch(if(next.male && voice==null)0.75f else 1f)
        speechId++;speechDeadline=SystemClock.elapsedRealtime()+30_000
        if(t.speak(next.text,TextToSpeech.QUEUE_FLUSH,null,"s$speechId")==TextToSpeech.ERROR) speakNext()
    }
    private fun updateStatus() {
        if(!::mic.isInitialized)return
        val shop=shopHours()
        if(night!=!shop) {
            night=!shop
            for(i in 0 until root.childCount) {
                val child=root.getChildAt(i)
                child.visibility=if(shop || child===controls)View.VISIBLE else View.GONE
            }
            if(shop) { renderCards();renderQueue() }
            clock.setTextColor(if(shop)Color.WHITE else Color.DKGRAY);date.setTextColor(if(shop)Color.WHITE else Color.DKGRAY)
            window.attributes=window.attributes.apply { screenBrightness=if(shop)-1f else 0.02f }
        }
        clock.visibility=View.GONE;date.visibility=View.GONE;mic.visibility=View.GONE
        dataState.visibility=if(shop && lastRefreshFailed)View.VISIBLE else View.GONE
        val active=dialog.engaged && shop
        logo.show(when {
            !resumed || !shop->ConversationIndicator.Mode.REST
            speaking->ConversationIndicator.Mode.SPEAKING
            dialog.phase in setOf(Conversation.Phase.SAVING,Conversation.Phase.PROCESSING) || waitingLookup!=null || lookupNumbers.isNotEmpty() && lookupMessage.contains("…")->ConversationIndicator.Mode.THINKING
            dialog.awake && running && micState.startsWith("Microphone ready")->ConversationIndicator.Mode.LISTENING
            dialog.awake->if(errorCount>0)ConversationIndicator.Mode.PROBLEM else ConversationIndicator.Mode.THINKING
            else->ConversationIndicator.Mode.REST
        })
        val at=ZonedDateTime.now(wallClock)
        val preview=SystemClock.elapsedRealtime()<clockPreviewUntil
        clockShowing=DayClock.show(clockEnabled(),DayClock.daytime(at),!dialog.awake && !speaking && !updateChecking && settingsDialog?.isShowing!=true && (preview || deck.size==0 && lookupNumbers.isEmpty()) && undo==null && !pickupCommitting,preview)
        clockFace.visibility=if(clockShowing)View.VISIBLE else View.GONE
        boardScroll.visibility=if(clockShowing)View.GONE else View.VISIBLE
        if(clockEnabled() && requestedOrientation!=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if(!clockEnabled() && requestedOrientation!=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if(clockShowing) {
            if(running && ::speech.isInitialized) { speech.stop();running=false;readyCue=false;main.removeCallbacks(flushSegments);segments.clear();segmentRevision++ }
            clockFace.show(at,weatherObservation?.label(System.currentTimeMillis())?:"Las Vegas · weather unavailable",batteryPercent)
            if(resumed)refreshWeather()
        }
        window.attributes=window.attributes.apply { screenBrightness=if(clockShowing)0.08f else if(shop)-1f else 0.02f }
        val hold=resumed && (if(clockShowing) DayClock.keepAwake(true,powered) else updateChecking || if(tapToTalk()) shop && (dialog.awake || speaking || undo!=null || pickupCommitting) else true)
        if(screenHeld!=hold) {
            screenHeld=hold
            if(hold)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if(::speech.isInitialized)speech.attending(active)
        activeTheme=active;applyBoardAppearance()
        mic.showText((dialog.worker?.let { "$it · " }?:"")+when {
            !shopHours()->"Microphone off · outside shop hours · tap Listen to test"
            speaking->"Sofia is speaking · microphone paused"
            tapToTalk() && !dialog.awake->"Tap Listen · microphone off · screen may sleep"
            !running->micState
            dialog.phase==Conversation.Phase.SMS->"Listening · send SMS?"
            active->"Listening · take your time"
            else->micState
        })
        smsYes.visibility=if(shop && dialog.phase==Conversation.Phase.SMS)View.VISIBLE else View.GONE
        smsNo.visibility=smsYes.visibility
    }
    private fun applyBoardAppearance() {
        if(!::logo.isInitialized)return
        val presenting=deck.current!=null || lookupNumbers.isNotEmpty()
        val communicating=dialog.awake || speaking || presenting
        val style=(if(presenting)1 else 0)+(if(communicating)2 else 0)+(if(dialog.engaged)4 else 0)+(if(shopHours())8 else 0)
        if(boardStyle==style)return
        boardStyle=style
        root.setBackgroundColor(if(presenting || !dialog.engaged)Color.BLACK else Color.rgb(12,40,62))
        clock.textSize=if(communicating)16f else 34f;date.textSize=if(communicating)12f else 16f
        logo.textSize=if(presenting)38f else 52f
        heard.textSize=if(presenting)14f else 20f;heard.minLines=if(presenting)1 else 2;heard.maxLines=2
        answer.textSize=if(presenting)14f else 18f;answer.maxLines=if(presenting)2 else 4
        if(::hints.isInitialized)hints.visibility=if(communicating || !shopHours())View.GONE else View.VISIBLE
        if(::queue.isInitialized)queue.visibility=if(presenting || !shopHours())View.GONE else View.VISIBLE
    }
    private fun receiptNumber(value:String,color:Int)=label(value,56f,color).apply {
        typeface=android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD)
        maxLines=1;setAutoSizeTextTypeUniformWithConfiguration(32,260,2,android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private fun renderCards() {
        cards.removeAllViews();applyBoardAppearance()
        val c=deck.current
        if(c==null) {
            picked.visibility=View.GONE
            if(lookupNumbers.isNotEmpty()) {
                cards.addView(receiptNumber(lookupNumbers.joinToString(" · "),Color.WHITE),LinearLayout.LayoutParams(-1,0,1f))
                cards.addView(label(lookupMessage,19f,Color.LTGRAY))
            }
            return
        }
        val color=when(c.pay) { Pay.PAID->Color.rgb(80,240,135);Pay.DUE->Color.rgb(255,95,95);else->Color.WHITE }
        cards.addView(receiptNumber(c.receipt,color),LinearLayout.LayoutParams(-1,0,1f))
        cards.addView(label(c.customer,18f).apply { maxLines=1 });cards.addView(label(c.total.ifBlank { "Amount unavailable" },22f))
        cards.addView(label((if(c.ready)"READY" else "NOT READY")+" · "+when(c.pay) { Pay.PAID->"PAID";Pay.DUE->"NOT PAID";Pay.ACCOUNT->"STORE ACCOUNT";else->"PAYMENT UNKNOWN" },14f,color))
        if(deck.size>1) cards.addView(label("‹   ${deck.index+1} / ${deck.size}   ›   Swipe to browse",16f))
        picked.visibility=if(shopHours() && pickups.configured() && (undo!=null || c.ready && Lookup.digits(c.receipt) !in pickups.pending()))View.VISIBLE else View.GONE
        picked.isEnabled=!pickupCommitting
        picked.text=if(undo!=null)"Undo · picked up #$undo" else "Confirm Pickup · #${c.receipt}"
    }
    private fun flip(step:Int) { deck.move(step);renderCards() }
    private fun TextView.showText(value:String) { if(text.toString()!=value)text=value }
    private fun label(value:String,size:Float,color:Int=Color.WHITE)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER;setPadding(8,4,8,4)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value;setOnClickListener { action() } }
    private fun buildUi():View {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(16,12,16,12);setBackgroundColor(Color.BLACK) }
        controls=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        controls.addView(button("Listen") { beginListening() },LinearLayout.LayoutParams(-2,-2))
        controls.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        settingsGear=HoldSettingsControl(this) { settings() };controls.addView(settingsGear)
        root.addView(controls,LinearLayout.LayoutParams(-1,-2))
        logo=ConversationIndicator(this).apply { settingsEntry { settings() } };root.addView(logo)
        clock=label("",34f).apply { setOnClickListener { beginListening() } };root.addView(clock);date=label("",16f);root.addView(date)
        mic=label("Preparing local speech…",16f,Color.CYAN);root.addView(mic)
        heard=label(SpokenNumbers.display(lastRaw),20f,Color.rgb(64,224,208));heard.minLines=2;root.addView(heard)
        dataState=label("Orders not loaded",12f,Color.LTGRAY);root.addView(dataState)
        cards=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=(120*resources.displayMetrics.density).toInt() }
        root.addView(cards,LinearLayout.LayoutParams(-1,0,1f))
        var downX=0f
        cards.setOnTouchListener { _,event -> when(event.action) {
            MotionEvent.ACTION_DOWN->{downX=event.x;true}
            MotionEvent.ACTION_UP->{if(kotlin.math.abs(event.x-downX)>70)flip(if(event.x<downX)1 else -1);true}
            else->true
        } }
        answer=label("",18f);answer.maxLines=4;root.addView(answer)
        queue=label("",14f,Color.LTGRAY);root.addView(queue)
        val sms=LinearLayout(this).apply { gravity=Gravity.CENTER }
        smsYes=button("Send SMS") { dispatch(dialog.accept("yes",now=SystemClock.elapsedRealtime()));updateStatus() }
        smsNo=button("No SMS") { dispatch(dialog.accept("no",now=SystemClock.elapsedRealtime()));updateStatus() }
        sms.addView(smsYes);sms.addView(smsNo);root.addView(sms)
        picked=button("Confirm Pickup") { onPicked() }.apply {
            textSize=24f;isAllCaps=false
            minimumHeight=(104*resources.displayMetrics.density).toInt()
            setTextColor(Color.BLACK);backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(80,240,135))
        };picked.visibility=View.GONE
        problem=button("") { reviewProblems() };problem.visibility=View.GONE;root.addView(problem)
        hints=label("Pickup: “Sofia, find last four 1234” / “check receipt 12345”\nWork: “Sofia, this is [name], finished receipt 12345”\nOr: “Sofia, this is [name], just save receipt 12345”",13f,Color.LTGRAY);root.addView(hints)
        root.addView(picked,LinearLayout.LayoutParams(-1,-2))
        clock.visibility=View.GONE;date.visibility=View.GONE;mic.visibility=View.GONE;dataState.visibility=View.GONE
        boardScroll=ScrollView(this).apply { isFillViewport=true;addView(root) }
        clockFace=DayClockView(this) { beginListening() }.apply { visibility=View.GONE }
        return FrameLayout(this).apply { setBackgroundColor(Color.BLACK);addView(boardScroll,FrameLayout.LayoutParams(-1,-1));addView(clockFace,FrameLayout.LayoutParams(-1,-1)) }
    }
    override fun onConfigurationChanged(config:android.content.res.Configuration) {
        super.onConfigurationChanged(config);night=null;activeTheme=null;boardStyle=-1;setContentView(buildUi());heard.text="${SpokenNumbers.display(lastRaw)}";renderCards();renderQueue();updateStatus()
    }
    private fun seedAnnouncements() {
        val prefs=getSharedPreferences("announcements",MODE_PRIVATE)
        if(prefs.getBoolean("initialized",false))return
        val seen=finishes.all().flatMap { i->listOfNotNull(if(i.scan=="ADDED")"saved:${i.scanId}" else null,if(i.sms=="SENT")"sms:${i.smsId}" else null) }.toSet()
        prefs.edit().putStringSet("seen",seen).putBoolean("initialized",true).commit()
    }
    private fun announceOnce(id:String,event:Sounds.Event) {
        val prefs=getSharedPreferences("announcements",MODE_PRIVATE);val seen=prefs.getStringSet("seen",emptySet()).orEmpty().toMutableSet()
        if(!seen.add(id))return
        if(prefs.edit().putStringSet("seen",seen.toList().takeLast(512).toSet()).commit()) Sounds.play(this,event)
    }
    private fun settings() {
        if(settingsDialog?.isShowing==true)return
        val items=Sounds.Event.entries.map { "${it.label}: ${Sounds.choice(this,it)}" }+listOf("Restart listening","Choose voices"+(if(voiceWarning)" · male voice needs selection" else ""),"Refresh orders",if(tapToTalk())"Listening mode · tap to talk (battery saver)" else "Listening mode · hands-free","Daytime clock · "+if(clockEnabled())"on" else "off","Preview large clock · five minutes")
        settingsDialog=AlertDialog.Builder(this).setTitle("Sofia ${Updater.versionName(this)}")
            .setItems(items.toTypedArray()) { _,i -> when {
                i<4->soundSettings(Sounds.Event.entries[i])
                i==4->{speech.stop();running=false;beginListening()}
                i==5->voiceSettings()
                i==6->refresh()
                i==8->{getSharedPreferences("clock",MODE_PRIVATE).edit().putBoolean("enabled",!clockEnabled()).apply();updateStatus()}
                i==9->{getSharedPreferences("clock",MODE_PRIVATE).edit().putBoolean("enabled",true).apply();dialog.cancel();stopVoice();pauseCapture();clockPreviewUntil=SystemClock.elapsedRealtime()+300_000;updateStatus()}
                else->AlertDialog.Builder(this).setTitle("Listening mode")
                    .setSingleChoiceItems(arrayOf("Tap to talk · microphone off between commands","Hands-free · continuously listens during shop hours"),if(tapToTalk())0 else 1) { d,choice->
                        getSharedPreferences("power",MODE_PRIVATE).edit().putBoolean("tapToTalk",choice==0).apply()
                        scheduleWake();dialog.cancel();speech.stop();running=false;retryAt=0;d.dismiss();updateStatus()
                    }.setNegativeButton("Close",null).show()
            } }.setPositiveButton("Check for update") { _,_->checkUpdate() }.setNegativeButton("Close",null).show()
    }
    private fun refreshWeather() {
        val now=SystemClock.elapsedRealtime()
        if(now<weatherAt)return
        weatherAt=now+900_000
        weatherReads.execute {
            val value=try {
                val url=java.net.URL("https://api.weather.gov/stations/KLAS/observations/latest")
                val connection=url.openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout=15_000;connection.readTimeout=15_000
                    connection.setRequestProperty("User-Agent","SofiaCounterClock/0.45 (https://github.com/artourg-prog/Alexa)")
                    connection.setRequestProperty("Accept","application/geo+json")
                    check(connection.responseCode==200)
                    val body=connection.inputStream.bufferedReader().use { it.readText() };check(body.length<131072)
                    DayClock.observation(body)
                } finally { connection.disconnect() }
            } catch(_:Exception) { null }
            if(value!=null) {
                getSharedPreferences("clock",MODE_PRIVATE).edit().putString("weather",value.json()).apply()
                main.post { if(!isDestroyed) { weatherObservation=value;updateStatus() } }
            }
        }
    }
    private fun checkUpdate() {
        if(updateChecking) { updateDialog?.show();return }
        updateChecking=true
        dialog.cancel();cancelPrompt();stopVoice();speech.stop();running=false
        val status=AlertDialog.Builder(this).setTitle("Sofia update · installed ${Updater.versionName(this)}")
            .setMessage("Checking for update…").setCancelable(false).setPositiveButton("Close",null).create()
        updateDialog=status;status.show();status.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
        updateStatus()
        updates.execute {
            val result=try { updateCheck(applicationContext) { message -> main.post { if(!isDestroyed) { status.setMessage(message);answer.text=message } } } }
                catch(_:Exception) { "Update unavailable. Check Wi-Fi and retry." }
            main.post {
                updateChecking=false
                if(!isDestroyed) {
                    status.setMessage(result);status.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                    answer.text=result;updateStatus()
                }
            }
        }
    }
    private fun soundSettings(event:Sounds.Event) {
        val names=Sounds.available(this).keys.toList()+Sounds.OFF
        AlertDialog.Builder(this).setTitle(event.label).setSingleChoiceItems(names.toTypedArray(),names.indexOf(Sounds.choice(this,event))) { _,i->
            Sounds.choose(this,event,names[i]);speech.mute(true);Sounds.playTitle(this,names[i]);main.postDelayed({if(!speaking)speech.mute(false)},2500)
        }.setNegativeButton("Back") { _,_->settings() }.show()
    }
    private fun voiceSettings() {
        val choices=listOf("Female · English","Male · English · not ready only","Female · Russian","Male · Russian · not ready only")
        AlertDialog.Builder(this).setTitle("Choose voices · tap to preview").setItems(choices.toTypedArray()) { _,which ->
            val lang=if(which<2)"en" else "ru";val male=which%2==1
            val voices=tts?.voices.orEmpty().filter { it.locale.language==lang }.sortedBy { it.name }
            AlertDialog.Builder(this).setTitle(choices[which]).setItems(voices.map { it.name }.toTypedArray()) { _,i ->
                getSharedPreferences("voice",MODE_PRIVATE).edit().putString("${if(male)"male" else "female"}Voice-$lang",voices[i].name).apply()
                russian=lang=="ru";voiceWarning=false;sayParts(listOf(Spoken(if(russian)if(male)"Ещё не готово." else "Готово к выдаче." else if(male)"Not ready yet." else "Ready for pickup.",male)))
            }.setNegativeButton("Back") { _,_->voiceSettings() }.show()
        }.setNegativeButton("Close",null).show()
    }
}
