package com.tailorshop.counter

import com.tailorshop.counter.conversation.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ConversationTest {
    private val workers=listOf("WorkerAlpha","WorkerBeta")
    private fun engine()=Conversation { workers }.also { it.wake(0) }
    @Test fun introSurvivesSeparateSegmentsAndLongActiveSpeech() {
        val e=engine();e.accept("Sofia this is WorkerAlpha",now=100)
        for(t in 1..100) { e.progress(t*10_000L);e.tick(t*10_000L);assertTrue(e.awake) }
        val a=e.accept("I finished receipt 12345",now=1_000_100).single() as Conversation.Effect.Save
        assertEquals("WorkerAlpha",a.worker);assertEquals("12345",a.receipt)
    }
    @Test fun saveBeforeSmsAndNoLeavesSavedWork() {
        val e=engine();assertTrue(e.accept("WorkerAlpha finished 12345",now=1).single() is Conversation.Effect.Save)
        assertEquals(Conversation.Phase.SAVING,e.phase)
        assertTrue(e.accept("yes",now=2).isEmpty())
        assertTrue(e.saved("fake-event",3).single() is Conversation.Effect.Say)
        assertEquals(Conversation.Phase.SMS,e.phase)
        assertFalse(e.accept("no",now=4).any { it is Conversation.Effect.Sms || it is Conversation.Effect.Save })
    }
    @Test fun smsUsesSameDurableEventOnce() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("same",2)
        assertEquals(Conversation.Effect.Sms("same"),e.accept("yes",now=3).single())
        assertTrue(e.accept("yes",now=4).isEmpty())
    }
    @Test fun failedStorageCannotSendSms() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved(null,2)
        assertFalse(e.accept("yes",now=3).any { it is Conversation.Effect.Sms })
    }
    @Test fun silenceDoesNotUndoSavedRecordOrSendSms() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("event",2);e.tick(30_003)
        assertFalse(e.awake);assertTrue(e.accept("yes",now=30_004).isEmpty())
    }
    @Test fun missingWorkerPromptsAndResumesOriginalReceipt() {
        val e=engine();e.accept("finished receipt 12345",now=1)
        assertEquals(Conversation.Phase.WORKER,e.phase)
        assertEquals(Conversation.Effect.Save("WorkerBeta","12345",true),e.accept("WorkerBeta",now=2).single())
    }
    @Test fun questionsNeverCreateRecordsEvenWithBadModelSuggestion() {
        val phrases=listOf("who did receipt 12345", "who finished 12345", "is receipt 12345 done", "check 12345", "София кто сделал ресит 12345", "под кем записан 12345", "was receipt 12345 finished", "12345 not ready", "не закончил 12345")
        for(p in phrases) {
            val e=engine()
            assertFalse(p,e.accept(p,Intent.Finish("WorkerAlpha",listOf("12345")),1).any { it is Conversation.Effect.Save })
        }
    }
    @Test fun workerQuestionRoutesReportWithoutSms() {
        val a=engine().accept("who did receipt 12345",now=1).single()
        assertEquals(Conversation.Effect.Report("12345",null),a)
    }
    @Test fun unknownNumbersAndWorkersCannotBeInventedByModel() {
        val c=IntentContext(workers)
        assertFalse(SafeIntent.validate(Intent.Finish("WorkerAlpha",listOf("99999")),"save 12345",c) is Intent.Finish)
        assertFalse(SafeIntent.validate(Intent.Finish("Unknown",listOf("12345")),"save 12345",c) is Intent.Finish)
        assertFalse(engine().accept("save",Intent.Finish("WorkerAlpha",emptyList()),1).any { it is Conversation.Effect.Save })
    }
    @Test fun newCommandsSupersedeSlowReportResult() {
        val e=engine();e.accept("who did 12345",now=1);val old=e.revision
        assertTrue(e.accept("check 54321",now=2).single() is Conversation.Effect.Find)
        assertTrue(e.revision>old)
    }
    @Test fun noSmsChoiceSurvivesMissingNumberPrompt() {
        val e=engine();e.accept("WorkerAlpha just save",now=1)
        val effect=e.accept("12345",now=2).single() as Conversation.Effect.Save
        assertFalse(effect.askSms)
    }
    @Test fun repeatedWorkerSaveReusesDurableIdAfterRestart() {
        val dir=java.nio.file.Files.createTempDirectory("sofia-dedup").toFile()
        try {
            val f=Finishes(dir);val now=System.currentTimeMillis()
            val first=f.addOrReuse("WorkerAlpha","12345",now)!!
            val second=Finishes(dir).addOrReuse("WorkerAlpha","12345",now+10)!!
            assertEquals(first.scanId,second.scanId)
            assertNotEquals(first.scanId,f.addOrReuse("WorkerBeta","12345",now)!!.scanId)
        } finally { dir.deleteRecursively() }
    }
    @Test fun sixDigitsNeverBecomeLastFour() { assertEquals(listOf("123456"),SafeIntent.numbers("receipt 123456")) }
    @Test fun privatePayloadContainsNoNamesNumbersOrUnrecognizedWords() {
        val c=IntentContext(workers)
        val p=PrivateTokens.encode("Sofia WorkerAlpha finished receipt 12345 for secretCustomer",c)
        val out=p.publicJson(c).toString()
        assertFalse(out.contains("12345"));assertFalse(out.contains("WorkerAlpha"));assertFalse(out.contains("secretcustomer"))
        assertTrue(out.contains("N1"));assertTrue(out.contains("W1"));assertTrue(out.contains("unknown"))
        val i=PrivateTokens.decode(JSONObject("{action:finish,numbers:[N1],worker:W1}"),p,c) as Intent.Finish
        assertEquals("12345",i.receipts.single());assertEquals("WorkerAlpha",i.worker)
    }
}
