package com.tailorshop.counter.conversation

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.tailorshop.counter.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File

/** The target is an isolated emulator with fake transports, no production credentials or real SMS. */
class ConversationUiTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private fun field(a:MainActivity,name:String):Any?=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
    private fun set(a:MainActivity,name:String,value:Any?)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value)
    private fun call(a:MainActivity,name:String,vararg args:Any?) {
        val m=MainActivity::class.java.declaredMethods.single { it.name==name && it.parameterCount==args.size };m.isAccessible=true;m.invoke(a,*args)
    }
    private fun launch():MainActivity {
        val activity=ins.startActivitySync(Intent(ins.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.runOnMainSync {
            set(activity,"wallClock",java.time.Clock.fixed(java.time.Instant.parse("2026-10-29T19:00:00Z"),java.time.ZoneId.of("America/Los_Angeles")))
            call(activity,"updateStatus")
        }
        return activity
    }
    private fun waitFor(test:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+4000
        while(SystemClock.elapsedRealtime()<until) { ins.waitForIdleSync();if(test())return;Thread.sleep(40) }
        assertTrue("Asynchronous adapter did not finish",test())
    }
    @Test fun settingsRequiresThreeSecondsAndCancelledHoldDoesNotOpen() {
        val activity=launch()
        try {
            lateinit var gear:HoldSettingsControl
            fun touch(action:Int) { val t=SystemClock.uptimeMillis();val e=android.view.MotionEvent.obtain(t,t,action,10f,10f,0);gear.onTouchEvent(e);e.recycle() }
            ins.runOnMainSync {
                gear=field(activity,"settingsGear") as HoldSettingsControl
                assertEquals("⚙",gear.text.toString());gear.performClick();touch(android.view.MotionEvent.ACTION_DOWN)
            }
            Thread.sleep(350)
            ins.runOnMainSync { assertTrue(gear.isPressed);touch(android.view.MotionEvent.ACTION_UP) }
            Thread.sleep(350)
            ins.runOnMainSync { assertFalse((field(activity,"settingsDialog") as? android.app.AlertDialog)?.isShowing==true) }
            ins.runOnMainSync { assertFalse(gear.isPressed);touch(android.view.MotionEvent.ACTION_DOWN) }
            Thread.sleep(3100)
            ins.runOnMainSync { assertFalse(gear.isPressed) }
            ins.runOnMainSync {
                val dialog=field(activity,"settingsDialog") as? android.app.AlertDialog
                assertTrue("Three-second hold did not open Settings",dialog?.isShowing==true)
                dialog?.dismiss()
            }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
    @Test fun paymentNumbersDominateBoardAndUseOwnerColorsOnBlack() {
        val activity=launch()
        try {
            for(pay in listOf(Pay.PAID,Pay.DUE)) {
                ins.runOnMainSync {
                    set(activity,"refreshAt",Long.MAX_VALUE)
                    val deck=field(activity,"deck") as CardDeck
                    deck.clear();deck.add(listOf(Card("12345","Synthetic customer","0000",true,pay,total="$65.00")),SystemClock.elapsedRealtime())
                    call(activity,"renderCards");call(activity,"updateStatus")
                    val number=(field(activity,"cards") as android.widget.LinearLayout).getChildAt(0) as TextView
                    assertEquals("12345",number.text.toString())
                    assertEquals(if(pay==Pay.PAID)android.graphics.Color.rgb(80,240,135) else android.graphics.Color.rgb(255,95,95),number.currentTextColor)
                    assertEquals(android.graphics.Color.BLACK,((field(activity,"root") as View).background as android.graphics.drawable.ColorDrawable).color)
                    assertEquals(260,(number.autoSizeMaxTextSize/activity.resources.displayMetrics.scaledDensity).toInt())
                }
                ins.waitForIdleSync();Thread.sleep(180)
                val bitmap=ins.uiAutomation.takeScreenshot()
                File(ins.targetContext.cacheDir,"board-${pay.name}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
    @Test fun indicatorReflectsListeningProcessingAndStopsWhenIdle() {
        val activity=launch()
        try {
            ins.runOnMainSync {
                val logo=field(activity,"logo") as ConversationIndicator
                val dialog=field(activity,"dialog") as Conversation
                dialog.wake(SystemClock.elapsedRealtime());set(activity,"running",true);set(activity,"micState","Microphone ready · waiting for Sofia")
                call(activity,"updateStatus");assertEquals(ConversationIndicator.Mode.LISTENING,logo.mode)
                dialog.accept("who did receipt 12345",now=SystemClock.elapsedRealtime())
                call(activity,"updateStatus");assertEquals(ConversationIndicator.Mode.THINKING,logo.mode)
                dialog.cancel();call(activity,"stopVoice");call(activity,"updateStatus")
                assertEquals(ConversationIndicator.Mode.REST,logo.mode);assertFalse(logo.animating)
                set(activity,"wallClock",java.time.Clock.fixed(java.time.Instant.parse("2026-10-30T03:00:00Z"),java.time.ZoneId.of("America/Los_Angeles")))
                set(activity,"manualUntil",0L);call(activity,"updateStatus")
                assertEquals(ConversationIndicator.Mode.REST,logo.mode);assertFalse(logo.animating)
            }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
    @Test fun missingLookupRefreshesOnceAndShowsFoundReceipt() {
        val activity=launch();val dir=File(ins.targetContext.cacheDir,"lookup-fake");dir.mkdirs()
        val calls=java.util.concurrent.atomic.AtomicInteger(0)
        val rows=listOf(listOf("Receipt #","Phone","M.T.","Customer","Total"),listOf("12345","00001234","Tester","Synthetic","65.00"))
        try {
            ins.runOnMainSync {
                set(activity,"refreshAt",Long.MAX_VALUE);set(activity,"loadedAt",System.currentTimeMillis());set(activity,"orders",emptyList<Order>())
                set(activity,"sheets",Sheets(File(dir,"none"),reader={calls.incrementAndGet();rows}));set(activity,"cache",OrderCache(dir))
                val dialog=field(activity,"dialog") as Conversation;dialog.wake(SystemClock.elapsedRealtime())
                call(activity,"dispatch",dialog.accept("check receipt 12345",now=SystemClock.elapsedRealtime()))
            }
            waitFor { (field(activity,"deck") as CardDeck).current?.receipt=="12345" }
            assertEquals(1,calls.get())
        } finally { ins.runOnMainSync { activity.finish() };dir.deleteRecursively() }
    }
    @Test fun failedLookupStillShowsDigitsAndRemovesPreviousCustomerCard() {
        val activity=launch();val dir=File(ins.targetContext.cacheDir,"lookup-missing");dir.mkdirs()
        try {
            ins.runOnMainSync {
                set(activity,"refreshAt",Long.MAX_VALUE);set(activity,"loadedAt",System.currentTimeMillis());set(activity,"orders",emptyList<Order>())
                set(activity,"sheets",Sheets(File(dir,"none"),reader={listOf(listOf("Receipt #","Phone"))}));set(activity,"cache",OrderCache(dir))
                (field(activity,"deck") as CardDeck).add(listOf(Card("54321","Previous synthetic customer","0000",true)),SystemClock.elapsedRealtime())
                val dialog=field(activity,"dialog") as Conversation;dialog.wake(SystemClock.elapsedRealtime())
                call(activity,"dispatch",dialog.accept("check receipt 12345",now=SystemClock.elapsedRealtime()))
            }
            waitFor { (field(activity,"lookupMessage") as String).let { "не найдено" in it || "Not found" in it } }
            ins.runOnMainSync {
                val cards=field(activity,"cards") as android.widget.LinearLayout
                assertEquals("12345",(cards.getChildAt(0) as TextView).text.toString())
                assertNull((field(activity,"deck") as CardDeck).current)
                assertEquals(View.GONE,(field(activity,"picked") as View).visibility)
            }
        } finally { ins.runOnMainSync { activity.finish() };dir.deleteRecursively() }
    }
    @Test fun lookupWaitsForWakeRefreshInsteadOfAnsweringOldReadiness() {
        val activity=launch();val dir=File(ins.targetContext.cacheDir,"lookup-wake");dir.mkdirs()
        val start=java.util.concurrent.CountDownLatch(1);val finish=java.util.concurrent.CountDownLatch(1)
        val calls=java.util.concurrent.atomic.AtomicInteger(0)
        try {
            ins.runOnMainSync {
                set(activity,"refreshAt",Long.MAX_VALUE);set(activity,"loadedAt",System.currentTimeMillis())
                set(activity,"orders",listOf(Order("12345","Synthetic","00001234","",false)))
                set(activity,"sheets",Sheets(File(dir,"none"),reader={
                    calls.incrementAndGet();start.countDown();check(finish.await(4,java.util.concurrent.TimeUnit.SECONDS))
                    listOf(listOf("Receipt #","Phone","M.T."),listOf("12345","00001234","Tester"))
                }));set(activity,"cache",OrderCache(dir));call(activity,"refresh")
                val dialog=field(activity,"dialog") as Conversation;dialog.wake(SystemClock.elapsedRealtime())
                call(activity,"dispatch",dialog.accept("check receipt 12345",now=SystemClock.elapsedRealtime()))
                assertNotNull(field(activity,"waitingLookup"));assertNull((field(activity,"deck") as CardDeck).current)
            }
            assertTrue(start.await(2,java.util.concurrent.TimeUnit.SECONDS));finish.countDown()
            waitFor { (field(activity,"deck") as CardDeck).current?.ready==true }
            assertEquals(1,calls.get())
        } finally { finish.countDown();ins.runOnMainSync { activity.finish() };dir.deleteRecursively() }
    }
    @Test fun tapModeStopsCaptureAndReleasesScreenWithoutLosingQueuedWork() {
        val ctx=ins.targetContext
        ctx.getSharedPreferences("power",0).edit().putBoolean("tapToTalk",true).commit()
        val activity=launch()
        try {
            lateinit var dialog:Conversation
            ins.runOnMainSync {
                set(activity,"wallClock",java.time.Clock.fixed(java.time.Instant.parse("2026-10-29T19:00:00Z"),java.time.ZoneId.of("America/Los_Angeles")))
                call(activity,"beginListening")
                dialog=field(activity,"dialog") as Conversation
            }
            waitFor { field(activity,"running")==true }
            ins.runOnMainSync {
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0)
                dialog.cancel()
            }
            waitFor { field(activity,"running")==false }
            ins.runOnMainSync {
                assertEquals(0,activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                assertTrue((field(activity,"mic") as TextView).text.contains("microphone off"))
                call(activity,"beginListening")
            }
            waitFor { field(activity,"running")==true }
            ins.runOnMainSync { assertTrue(dialog.awake) }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
    @Test fun clarificationDoesNotSpeakOverContinuedDigitEntry() {
        val ctx=ins.targetContext
        val activity=launch()
        try {
            ins.runOnMainSync {
                val dialog=field(activity,"dialog") as Conversation
                dialog.wake(SystemClock.elapsedRealtime())
                call(activity,"dispatch",dialog.accept("finished receipt two six",now=SystemClock.elapsedRealtime()))
                assertNotNull(field(activity,"pendingPrompt"))
                call(activity,"dispatch",dialog.accept("three seven two",now=SystemClock.elapsedRealtime()))
                // A new clarification replaces the stale request for a receipt; it asks only for worker.
                assertEquals(Conversation.Phase.WORKER,dialog.phase)
                assertTrue((field(activity,"pendingPrompt") as Pair<*,*>).first.toString().let { "name" in it || "именем" in it })
                call(activity,"cancelPrompt");assertNull(field(activity,"pendingPrompt"))
            }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
    @Test fun nightTurnsMicrophoneOffAndManualWakeIsTemporary() {
        val ctx=ins.targetContext
        val activity=launch()
        try {
            ins.runOnMainSync {
                set(activity,"wallClock",java.time.Clock.fixed(java.time.Instant.parse("2026-10-30T03:00:00Z"),java.time.ZoneId.of("America/Los_Angeles")))
                set(activity,"manualUntil",0L)
            }
            waitFor { (field(activity,"mic") as TextView).text.contains("outside shop hours") }
            ins.runOnMainSync {
                assertFalse(field(activity,"running") as Boolean)
                assertEquals(View.GONE,(field(activity,"heard") as View).visibility)
                (field(activity,"clock") as View).performClick()
            }
            waitFor { field(activity,"running")==true }
            ins.runOnMainSync {
                assertEquals(View.VISIBLE,(field(activity,"heard") as View).visibility)
                set(activity,"manualUntil",0L)
            }
            waitFor { field(activity,"running")==false }
        } finally { ins.runOnMainSync { activity.finish() } }
    }
    @Test fun finishNoSmsAndReadOnlyWorkerQuestionUseExistingContracts() {
        val ctx=ins.targetContext
        val dir=File(ctx.cacheDir,"rewrite-ui-fakes");dir.deleteRecursively();dir.mkdirs()
        File(dir,"finish-config.json").writeText("{url:'https://example.invalid',secret:'test-only',workers:[Tester]}")
        var scanCalls=0;var smsCalls=0
        val f=Finishes(dir,poster={_,body->when(body.optString("mode")) {
            "addScan"->{scanCalls++;JSONObject("{ok:true,status:ADDED}")}
            "pickup"->{smsCalls++;JSONObject("{ok:true,state:SENT}")}
            "receiptReportStatus"->JSONObject("{ok:true,source:savedReports,records:[{worker:Tester,date:'2026-01-01'}]}")
            else->JSONObject("{ok:true,status:ADDED}")
        }})
        val activity=launch()
        try {
            lateinit var dialog:Conversation
            ins.runOnMainSync {
                set(activity,"finishes",f);set(activity,"syncAt",Long.MAX_VALUE);set(activity,"refreshAt",Long.MAX_VALUE)
                dialog=field(activity,"dialog") as Conversation
                dialog.wake(SystemClock.elapsedRealtime())
                call(activity,"dispatch",dialog.accept("Sofia this is Tester finished receipt 12345",now=SystemClock.elapsedRealtime()))
            }
            waitFor { dialog.phase==Conversation.Phase.SMS }
            assertEquals(1,f.all().size);assertFalse(f.all().single().sendSms)
            ins.runOnMainSync { (field(activity,"smsNo") as View).performClick() }
            assertFalse(f.all().single().sendSms);assertEquals(0,smsCalls)
            f.sync();assertEquals(1,scanCalls)
            ins.runOnMainSync {
                dialog.wake(SystemClock.elapsedRealtime())
                call(activity,"dispatch",dialog.accept("who did receipt 12345",now=SystemClock.elapsedRealtime()))
            }
            waitFor { (field(activity,"answer") as TextView).text.contains("Tester") }
            assertEquals(1,f.all().size);assertEquals(1,scanCalls);assertEquals(0,smsCalls)
            ins.runOnMainSync {
                val heard=field(activity,"heard") as TextView;heard.text="Heard · synthetic transcript"
                dialog.cancel();call(activity,"updateStatus")
                assertEquals(View.VISIBLE,heard.visibility);assertTrue(heard.text.contains("synthetic"))
                call(activity,"stopVoice")
            }
        } finally { ins.runOnMainSync { activity.finish() };dir.deleteRecursively() }
    }
}
