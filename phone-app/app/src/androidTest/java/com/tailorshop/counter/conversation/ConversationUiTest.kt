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
