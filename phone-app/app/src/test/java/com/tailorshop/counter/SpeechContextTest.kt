package com.tailorshop.counter
import org.junit.Assert.*
import org.junit.Test
class SpeechContextTest {
    private val workers=listOf("Artour","Emma")
    private fun resolve(c:SpeechContext,s:String,t:Long,w:Lookup.Waiting=Lookup.Waiting.NONE)=c.resolve(listOf(s),workers,w,t).single()
    @Test fun separatedWakeIntroAndFinishRetainWorker() {
        val c=SpeechContext();resolve(c,"София",100);resolve(c,"это Артур",5_000)
        val f=Lookup.parseFinish(resolve(c,"запиши номер 12345 и отправь сообщение",15_000),workers)!!
        assertEquals("Artour",f.worker);assertEquals(listOf("12345"),f.receipts);assertTrue(f.sms)
    }
    @Test fun englishHintWorksInOneUtteranceAndAcrossPause() {
        val f=Lookup.parseFinish("Sofia, this is Emma. Save 12345 and send SMS.",workers)!!
        assertEquals("Emma",f.worker);assertTrue(f.sms)
        val c=SpeechContext();resolve(c,"Sofia this is Arthur",1_000)
        assertEquals("Artour",Lookup.parseFinish(resolve(c,"save receipt 12345",20_000),workers)!!.worker)
    }
    @Test fun roomTalkAndExpiredContextAreNotAddressed() {
        val c=SpeechContext();assertFalse(Lookup.addressed(resolve(c,"save 12345",100)))
        resolve(c,"Sofia",1_000);assertFalse(Lookup.addressed(resolve(c,"nice weather",5_000)))
        assertFalse(Lookup.addressed(resolve(c,"save 12345",32_000)))
    }
    @Test fun replyWindowsAndNewConversationDoNotReuseWorker() {
        val c=SpeechContext();resolve(c,"Sofia this is Emma",1_000)
        assertEquals("no",resolve(c,"no",2_000,Lookup.Waiting.YES_NO))
        assertFalse(Lookup.addressed(resolve(c,"save 12345",3_000)))
        resolve(c,"Sofia this is Arthur",4_000);c.clear()
        assertFalse(Lookup.addressed(resolve(c,"save 12345",5_000)))
    }
    @Test fun newAddressedIntroStartsContextDuringPriorFinishWindow() {
        val c=SpeechContext();resolve(c,"Sofia this is Emma",1_000,Lookup.Waiting.FINISH_WINDOW)
        val f=Lookup.parseFinish(resolve(c,"save receipt 12345",10_000),workers)!!
        assertEquals("Emma",f.worker)
    }
    @Test fun questionsNeverBecomeFinishWrites() {
        for(s in listOf("София кто сделал ресит 12345", "Sofia who finished receipt 12345", "София проверь готово 12345", "Sofia is receipt 12345 saved in Daily Report")) {
            assertFalse(s,Lookup.isFinishPhrase(s));assertNull(s,Lookup.parseFinish(s,workers))
        }
        assertEquals(Topic.TAILOR,Lookup.followUp("София кто сделал ресит 12345"))
    }
}
