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
import com.tailorshop.counter.conversation.Conversation
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
    private val delivery=Executors.newSingleThreadExecutor()
    private val reads=Executors.newSingleThreadExecutor()
    private val semantics=Executors.newSingleThreadExecutor()
    private lateinit var speech:OfflineSpeech
    private lateinit var finishes:Finishes
    private lateinit var pickups:Pickups
    private lateinit var sheets:Sheets
    private lateinit var cache:OrderCache
    private lateinit var intentService:SemanticClient
    private lateinit var dialog:Conversation
    private lateinit var root:LinearLayout
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
    private data class Shown(val card:Card,val until:Long)
    private val shown=mutableListOf<Shown>()
    private var index=0
    private var pickupCommitting=false
    private var undo:String?=null
    private var undoDeadline=0L
    private var finishId:String?=null
    private val zone=ZoneId.of("America/Los_Angeles")
    private var tts:TextToSpeech?=null
    private var ttsReady=false
    private data class Spoken(val text:String,val male:Boolean=false)
    private val spoken=ArrayDeque<Spoken>()
    private var speaking=false
    private var speechId=0L
    private var speechDeadline=0L
    private var voiceWarning=false

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finishes=Finishes(filesDir);pickups=Pickups(filesDir);sheets=Sheets(File(filesDir,"sheet-key.json"));cache=OrderCache(filesDir)
        intentService=SemanticClient(applicationContext)
        dialog=Conversation { finishes.workers() }
        semantics.execute { intentService.refresh() }
        cache.load()?.let { try { orders=it.orders();loadedAt=it.at } catch(_:Exception) {} }
        seedAnnouncements()
        setContentView(buildUi())
        speech=OfflineSpeech(applicationContext,object:OfflineSpeech.Listener {
            override fun onState(state:String) { main.post { micState=state;lastAudio=SystemClock.elapsedRealtime();updateStatus() } }
            override fun onPartial(ru:String,en:String,level:Float) { main.post {
                lastAudio=SystemClock.elapsedRealtime();errorCount=0
                val text=if(russian) ru.ifBlank { en } else en.ifBlank { ru }
                if(text.isNotBlank()) {
                    if(text!=lastProgress) { dialog.progress(lastAudio);lastProgress=text }
                    lastRaw=text;heard.text="Heard · $text"
                }
                updateStatus()
            } }
            override fun onWake(russian:Boolean) { main.post {
                if(!resumed || speaking) return@post
                this@MainActivity.russian=russian
                val wasAwake=dialog.awake;dialog.wake(SystemClock.elapsedRealtime())
                if(!wasAwake) Sounds.play(this@MainActivity,Sounds.Event.WAKE)
                updateStatus()
            } }
            override fun onSegment(text:String,russian:Boolean,confidence:Double) { main.post {
                if(!resumed || speaking) return@post
                lastRaw=text;heard.text="Heard · $text"
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
    override fun onResume() { super.onResume();resumed=true;Wake.clear(this);main.post(tick) }
    override fun onPause() {
        resumed=false;main.removeCallbacks(tick);main.removeCallbacks(flushSegments);segments.clear();segmentRevision++
        dialog.cancel();undo=null;speech.stop();running=false;stopVoice();super.onPause()
    }
    override fun onDestroy() { speech.close();tts?.shutdown();delivery.shutdownNow();reads.shutdownNow();semantics.shutdownNow();super.onDestroy() }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(results.firstOrNull()!=PackageManager.PERMISSION_GRANTED) { micState="Microphone permission denied · allow it in Android settings";updateStatus() }
    }
    private fun shopHours():Boolean {
        if(SystemClock.elapsedRealtime()<manualUntil) return true
        val usb=try { registerReceiver(null,android.content.IntentFilter("android.hardware.usb.action.USB_STATE")) } catch(_:Exception) { null }
        if(usb?.getBooleanExtra("connected",false)==true && usb.getBooleanExtra("adb",false)) return true
        val t=ZonedDateTime.now(zone);val minutes=t.hour*60+t.minute
        return minutes>=645 && minutes<1170
    }
    private fun scheduleWake() {
        val now=ZonedDateTime.now(zone);var at=now.withHour(10).withMinute(45).withSecond(0).withNano(0)
        if(!at.isAfter(now)) at=at.plusDays(1)
        Wake.schedule(this,at.toInstant().toEpochMilli(),1)
    }
    private val tick=object:Runnable {
        override fun run() {
            if(!resumed)return
            val now=SystemClock.elapsedRealtime()
            val shop=shopHours()
            if(shop && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
                if(running && !speaking && now-lastAudio>20_000) { speech.stop();running=false;retryAt=now+1000;micState="Recovering microphone…" }
                if(!running && now>=retryAt) { running=true;lastAudio=now;speech.start() }
            } else if(running) { speech.stop();running=false;dialog.cancel();micState="Microphone off · outside shop hours" }
            if(speaking && now>speechDeadline) { stopVoice();answer.text="Voice playback interrupted · listening resumed" }
            Updater.confirmIntent?.let { Updater.confirmIntent=null;startActivity(it) }
            dialog.tick(now)
            val removed=shown.removeAll { it.until<=now && it.card.receipt!=undo }
            if(removed) { index=index.coerceAtMost((shown.size-1).coerceAtLeast(0));renderCards() }
            if(undo!=null && now>=undoDeadline) commitPickup()
            if(now>=refreshAt) { refreshAt=now+60_000;refresh() }
            if(now>=syncAt) { syncAt=now+5000;sync() }
            val t=ZonedDateTime.now(zone)
            clock.text=t.format(DateTimeFormatter.ofPattern("h:mm a"));date.text=t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
            dataState.text=if(loadedAt==0L) "Orders not loaded" else "Orders · ${orders.size} · ${((System.currentTimeMillis()-loadedAt)/60_000).coerceAtLeast(0)} min old"
            updateStatus();main.postDelayed(this,250)
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
        heard.text="Heard · ${chosen.text}"
        val words=Lookup.normalize(chosen.text).split(' ')
        if(words.all { it in setOf("sofia","sophia","sofiya","sofya","sophie","sofi","this","is","i","am","eto","ya","pozhaluysta","please") })return
        val context=dialog.context()
        val local=SafeIntent.local(chosen.text,context)
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
        effects.forEach { effect -> when(effect) {
            is Conversation.Effect.Say -> say(if(russian)effect.ru else effect.en)
            is Conversation.Effect.Find -> find(effect)
            is Conversation.Effect.Report -> {
                val rev=dialog.revision
                answer.text=if(russian)"Проверяю сохранённые отчёты…" else "Checking saved reports…"
                reads.execute {
                    val reply=try { ReportStatus.reply(ReportStatus.Request(effect.receipt,effect.worker),finishes.reportStatus(effect.receipt),russian) }
                        catch(_:Exception) { if(russian)"Не удалось проверить Daily Report. Попробуй ещё раз." else "Daily Report is unavailable. Please try again." }
                    main.post { if(resumed && rev==dialog.revision) { dialog.reportFinished(SystemClock.elapsedRealtime());say(reply) } }
                }
            }
            is Conversation.Effect.Save -> {
                val rev=dialog.revision
                if(!finishes.configured()) { dispatch(dialog.saved(null,SystemClock.elapsedRealtime()));return@forEach }
                delivery.execute {
                    val saved=finishes.addOrReuse(effect.worker,effect.receipt)
                    main.post {
                        // Storage results still exist after a cancelled UI; never attach them to a newer conversation.
                        if(resumed && rev==dialog.revision) {
                            finishId=saved?.scanId;renderQueue();dispatch(dialog.saved(saved?.scanId,SystemClock.elapsedRealtime()));updateStatus()
                        }
                    }
                }
            }
            is Conversation.Effect.Sms -> delivery.execute {
                val ok=finishes.requestSms(effect.scanId)
                main.post { if(resumed) { say(if(ok) if(russian)"SMS в очереди." else "SMS queued." else if(russian)"Не удалось сохранить запрос SMS. Повтори." else "Couldn't save the SMS request. Please retry.");renderQueue() } }
            }
        } }
    }
    private fun find(request:Conversation.Effect.Find) {
        val pending=pickups.pending()
        val results=request.numbers.flatMap { n -> Lookup.find(when(n.length) { 4->Query.Last4(n);5,6->Query.Receipt(n);else->Query.FullPhone(n) },orders) }
            .filter { Lookup.digits(it.receipt) !in pending }.distinctBy { it.receipt }
        if(results.isEmpty()) { say(if(loadedAt==0L) if(russian)"Список пока недоступен." else "The order list isn't available yet." else if(russian)"В последней копии списка не найдено." else "Not found in the latest available list.");return }
        val now=SystemClock.elapsedRealtime()
        results.forEach { card -> shown.removeAll { it.card.receipt==card.receipt };shown.add(0,Shown(card,now+if(card.ready)60_000 else 10_000)) }
        index=0;renderCards()
        val speech=results.take(4).flatMap { c ->
            if(request.topic!=null) listOf(Spoken(Lookup.followUpAnswer(request.topic,c,russian),request.topic==Topic.TAILOR && !c.ready))
            else listOf(Spoken((if(russian)"Квитанция " else "Receipt ")+c.receipt.toCharArray().joinToString(" ")+"."),
                Spoken(if(c.ready) if(russian)"Готово к выдаче." else "Ready for pickup." else if(russian)"Ещё не готово." else "Not ready yet.",!c.ready),
                Spoken(when(c.pay) { Pay.PAID->if(russian)"Оплачено." else "Paid.";Pay.DUE->if(russian)"Не оплачено." else "Not paid.";Pay.ACCOUNT->if(russian)"На счёте магазина." else "On the store account.";else->"" }))
        }
        sayParts(speech.filter { it.text.isNotBlank() })
    }
    private fun refresh() {
        if(refreshing || !sheets.hasKey())return
        refreshing=true
        reads.execute {
            try {
                val rows=sheets.readRows();val parsed=Lookup.parseOrders(rows);val at=System.currentTimeMillis();cache.save(rows,at)
                main.post { orders=parsed;loadedAt=at;dataState.text="Orders up to date" }
            } catch(_:Exception) { main.post { dataState.text="Offline · keeping last available orders" } }
            finally { main.post { refreshing=false } }
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
        queue.text=f?.let { "#${it.receipt} · ${Finishes.scanText(it,russian)}\n${Finishes.smsText(it,russian)}" }.orEmpty()
        val warning=pickups.warning();val check=pickups.toCheck()
        problem.visibility=if(warning!=null || check.isNotEmpty())View.VISIBLE else View.GONE
        problem.text=warning?:check.firstOrNull()?.let { Pickups.problemText(it.receipt,it.status)+"\nTap to review" }.orEmpty()
    }
    private fun commitPickup() {
        val number=undo?:return;undo=null;pickupCommitting=true;renderCards()
        delivery.execute { val ok=pickups.addOrReuse(number);main.post {
            pickupCommitting=false
            if(ok) { shown.removeAll { it.card.receipt==number };index=0;queue.text="#$number · queued, waiting for tablet confirmation" }
            else queue.text="#$number · could not save pickup confirmation; please retry"
            renderCards()
        } }
    }
    private fun onPicked() {
        if(pickupCommitting)return
        if(undo!=null) { undo=null;renderCards();return }
        val c=shown.getOrNull(index)?.card?:return
        if(!c.ready || !pickups.configured() || Lookup.digits(c.receipt) in pickups.pending())return
        undo=c.receipt;undoDeadline=SystemClock.elapsedRealtime()+5000;renderCards()
    }
    private fun reviewProblems() {
        val requests=pickups.toCheck()
        val r=requests.firstOrNull()
        AlertDialog.Builder(this).setTitle("Pickup needs checking")
            .setMessage(r?.let { Pickups.problemText(it.receipt,it.status)+"\nOnly dismiss after checking the receipt on the tablet." }?:pickups.warning())
            .setPositiveButton("I checked on the tablet") { _,_-> delivery.execute {
                if(r!=null) pickups.dismiss(r.id) else pickups.acknowledgeRecovery()
                main.post { renderQueue() }
            } }.setNegativeButton("Keep warning",null).show()
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
        val active=dialog.awake
        root.setBackgroundColor(if(active)Color.rgb(12,40,62) else Color.BLACK)
        mic.text=when {
            !shopHours()->"Microphone off · outside shop hours · tap Listen to test"
            speaking->"Sofia is speaking · microphone paused"
            !running->micState
            dialog.phase==Conversation.Phase.SMS->"Listening · send SMS?"
            active->"Listening · take your time"
            else->micState
        }
        smsYes.visibility=if(dialog.phase==Conversation.Phase.SMS)View.VISIBLE else View.GONE
        smsNo.visibility=smsYes.visibility
    }
    private fun renderCards() {
        cards.removeAllViews();index=index.coerceAtMost((shown.size-1).coerceAtLeast(0))
        val c=shown.getOrNull(index)?.card
        if(c==null) { picked.visibility=View.GONE;return }
        val color=if(c.pay==Pay.DUE)Color.rgb(255,95,95) else Color.WHITE
        cards.addView(label("#${c.receipt}",56f,color))
        cards.addView(label(c.customer,25f,color));cards.addView(label(c.total.ifBlank { "Amount unavailable" },30f,color))
        cards.addView(label((if(c.ready)"READY" else "NOT READY")+" · "+when(c.pay) { Pay.PAID->"PAID";Pay.DUE->"NOT PAID";Pay.ACCOUNT->"STORE ACCOUNT";else->"PAYMENT UNKNOWN" },18f,color))
        if(shown.size>1) cards.addView(label("‹   ${index+1} / ${shown.size}   ›   Swipe to browse",16f))
        picked.visibility=if(pickups.configured() && (undo!=null || c.ready && Lookup.digits(c.receipt) !in pickups.pending()))View.VISIBLE else View.GONE
        picked.isEnabled=!pickupCommitting
        picked.text=if(undo!=null)"Undo · picked up #$undo" else "Confirm picked up #${c.receipt}"
    }
    private fun flip(step:Int) { if(shown.isNotEmpty()) { index=(index+step+shown.size)%shown.size;renderCards() } }
    private fun label(value:String,size:Float,color:Int=Color.WHITE)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER;setPadding(8,4,8,4)
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply { text=value;setOnClickListener { action() } }
    private fun buildUi():View {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(16,12,16,12);setBackgroundColor(Color.BLACK) }
        root.addView(label("Sofia",28f).apply { setOnLongClickListener { settings();true } })
        clock=label("",34f);root.addView(clock);date=label("",16f);root.addView(date)
        mic=label("Preparing local speech…",16f,Color.CYAN);root.addView(mic)
        heard=label("Heard · say Sofia to begin",20f);heard.minLines=2;root.addView(heard)
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
        picked=button("Confirm picked up") { onPicked() };picked.visibility=View.GONE;root.addView(picked)
        problem=button("") { reviewProblems() };problem.visibility=View.GONE;root.addView(problem)
        root.addView(label("Pickups: “Sofia, find last four 1234”\nWork: “Sofia, this is [name], finished receipt 12345”",13f,Color.LTGRAY))
        val controls=LinearLayout(this).apply { gravity=Gravity.CENTER }
        controls.addView(button("Listen") { manualUntil=SystemClock.elapsedRealtime()+300_000;dialog.wake(SystemClock.elapsedRealtime());retryAt=0;updateStatus() })
        controls.addView(button("Settings") { settings() });root.addView(controls)
        return ScrollView(this).apply { isFillViewport=true;addView(root) }
    }
    override fun onConfigurationChanged(config:android.content.res.Configuration) {
        super.onConfigurationChanged(config);setContentView(buildUi());heard.text="Heard · $lastRaw";renderCards();renderQueue();updateStatus()
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
        val items=Sounds.Event.entries.map { "${it.label}: ${Sounds.choice(this,it)}" }+listOf("Restart listening","Choose voices"+(if(voiceWarning)" · male voice needs selection" else ""),"Refresh orders")
        AlertDialog.Builder(this).setTitle("Sofia ${Updater.versionName(this)}")
            .setItems(items.toTypedArray()) { _,i -> when {
                i<4->soundSettings(Sounds.Event.entries[i])
                i==4->{speech.stop();running=false;retryAt=0;manualUntil=SystemClock.elapsedRealtime()+300_000}
                i==5->voiceSettings()
                else->refresh()
            } }.setPositiveButton("Check for update") { _,_->reads.execute {
                val result=try { Updater.checkAndInstall(applicationContext) } catch(_:Exception) { "Update unavailable. Please retry." }
                main.post { answer.text=result }
            } }.setNegativeButton("Close",null).show()
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
