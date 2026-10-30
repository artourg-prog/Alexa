package com.tailorshop.counter

import com.tailorshop.counter.conversation.*
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class ConversationTest {
    private val workers=listOf("WorkerAlpha","WorkerBeta")
    private fun engine()=Conversation { workers }.also { it.wake(0) }
    @Test fun completedLookupRequiresNewTouchBeforeAnyFurtherAction() {
        val e=engine();e.accept("find 12345",now=1);e.queryFinished()
        assertFalse(e.awake)
        assertTrue(e.accept("check 54321",now=2).isEmpty())
        e.wake(3);assertTrue(e.accept("check 54321",now=4).single() is Conversation.Effect.Find)
    }
    @Test fun thanksEndsIncompleteCommandAndSmsWithoutSending() {
        for(phrase in listOf("спасибо","София спасибо","спасибо всё","thank you","thanks Sofia","thank you that's all")) {
            val e=engine();e.accept("find",now=1);assertTrue(e.accept(phrase,now=2).isEmpty());assertFalse(phrase,e.awake)
            e.wake(3);e.accept("WorkerAlpha finished 12345",now=4);e.saved("saved-event",5)
            assertTrue(e.accept(phrase,now=6).isEmpty());assertFalse(phrase,e.awake)
        }
        assertFalse(SafeIntent.endsConversation("thanks please save receipt 12345"))
    }
    @Test fun smsQuestionRejectsUnrelatedLookupAndWorkCommands() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("saved-event",2)
        for(phrase in listOf("find 54321","who did 12345","WorkerBeta finished 54321","some television conversation")) {
            assertTrue(e.accept(phrase,now=3).isEmpty());assertEquals(Conversation.Phase.SMS,e.phase)
        }
        assertEquals(Conversation.Effect.Sms("saved-event"),e.accept("yes",now=4).single())
        assertFalse(e.awake)
    }
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
    @Test fun lastFourLabelNeverBecomesAnExtraNumberDigit() {
        for(s in listOf("Sofia find last four 1234","last four digits one two three four","последние четыре цифры один два три четыре","check this one 1234")) {
            assertEquals(s,listOf("1234"),SafeIntent.numbers(s))
        }
        val p=PrivateTokens.encode("last four digits one two three four",IntentContext(workers))
        assertEquals(listOf("1234"),p.numbers.values.toList())
    }
    @Test fun recordUnderWorkerIsNotMistakenForReadOnlyReportQuestion() {
        assertEquals(Conversation.Effect.Save("WorkerAlpha","12345",true),engine().accept("save receipt 12345 in Daily Report under WorkerAlpha",now=1).single())
        assertEquals(Conversation.Effect.Save("WorkerAlpha","12345",true),engine().accept("WorkerAlpha I did receipt 12345",now=1).single())
        assertTrue(engine().accept("who did receipt 12345",now=1).single() is Conversation.Effect.Report)
    }
    @Test fun wakeNameAllowsPunctuationButNotLongerWords() {
        for(text in listOf("Sofia, check", "Софья, проверь", "София!", "Sophia."))assertTrue(text,OfflineSpeech.wakeWord(text))
        for(text in listOf("sofiastic", "несофия", "статья"))assertFalse(text,OfflineSpeech.wakeWord(text))
    }
    @Test fun ambientCompletionAfterLookupCannotWriteWithoutNewWake() {
        val e=engine();e.accept("check 12345",now=1)
        assertEquals(Conversation.Phase.PROCESSING,e.phase)
        e.queryFinished()
        assertFalse(e.awake)
        assertTrue(e.accept("WorkerAlpha finished 54321",now=2).isEmpty())
        e.wake(3)
        assertTrue(e.accept("WorkerAlpha finished 54321",now=4).single() is Conversation.Effect.Save)
    }
    @Test fun ambientCompletionDuringReadOnlyNetworkQueryCannotWrite() {
        val e=engine();e.accept("who did 12345",now=1)
        assertTrue(e.accept("WorkerAlpha finished 54321",now=2).isEmpty())
        e.wake(3)
        assertTrue(e.accept("WorkerAlpha finished 54321",now=4).single() is Conversation.Effect.Save)
    }
    @Test fun aQuestionContainingRightCannotAuthorizeSms() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("event",2)
        assertFalse(e.accept("is this the right receipt 12345",now=3).any { it is Conversation.Effect.Sms })
    }
    @Test fun duplicateFinalDoesNotAskToSaveOrSendAgain() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("event",2)
        assertTrue(e.accept("WorkerAlpha finished 12345",now=3).isEmpty())
        assertEquals(Conversation.Phase.SMS,e.phase)
        assertEquals(Conversation.Effect.Sms("event"),e.accept("send it please",now=4).single())
    }
    @Test fun smsForAnotherReceiptNeedsANewCommand() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("event",2)
        assertFalse(e.accept("yes for receipt 54321",now=3).any { it is Conversation.Effect.Sms })
    }
    @Test fun modelCannotTurnUnknownSpeechIntoSmsApprovalOrInventIdentity() {
        val e=engine();e.accept("WorkerAlpha finished 12345",now=1);e.saved("event",2)
        assertTrue(e.accept("unrelated conversation",Intent.Answer(true),3).isEmpty())
        assertFalse(SafeIntent.validate(Intent.Finish("WorkerAlpha",listOf("12345")),"save 12345",IntentContext(workers)) is Intent.Finish)
    }
    @Test fun uncertainNameIsOnlyACandidateNotAnAlias() {
        assertTrue(WakeCandidate.needsVerification("статья проверь квитанцию"))
        assertFalse(OfflineSpeech.wakeWord("статья проверь квитанцию"))
        assertFalse(WakeCandidate.needsVerification("мы работаем в магазине"))
        assertFalse(WakeCandidate.needsVerification("please check receipt one two three"))
    }
    @Test fun visibleTranscriptUsesDigitsWhileKeepingOriginalLanguage() {
        assertEquals("Sofia, check receipt 26383",SpokenNumbers.display("Sofia, check receipt two six three eight three"))
        assertEquals("София, проверь ресит 26383",SpokenNumbers.display("София, проверь ресит два шесть три восемь три"))
        assertEquals("last 4 digits 0034",SpokenNumbers.display("last four digits zero zero three four"))
        assertEquals("Please check for Tester",SpokenNumbers.display("Please check for Tester"))
    }
    @Test fun reportQuestionRetainsReadOnlyIntentWhenReceiptComesLaterInFragments() {
        val e=engine();e.accept("Sofia who did the work",now=1)
        assertEquals(Conversation.Phase.REPORT_RECEIPT,e.phase)
        e.accept("two six",now=2)
        assertEquals(Conversation.Effect.Report("26372",null),e.accept("three seven two",now=3).single())
    }
    @Test fun naturalLastFourAndReceiptQueriesAreReadOnlyWithoutCloudService() {
        for(s in listOf("Sofia last four digits two eight zero seven", "София последние четыре цифры два восемь ноль семь", "Sofia 2807")) {
            assertEquals(s,Conversation.Effect.Find(listOf("2807"),null),engine().accept(s,now=1).single())
        }
        for(s in listOf("has receipt 26372 been recorded", "is 26372 already saved", "ресит 26372 записан", "София квитанция 26372 сохранена")) {
            assertEquals(s,Conversation.Effect.Report("26372",null),engine().accept(s,now=1).single())
        }
    }
    @Test fun receiptDigitsContinueAcrossSpeechSegmentsWithoutLosingWorkerOrNoSms() {
        val e=engine()
        val prompt=e.accept("WorkerAlpha just save receipt two six",now=1).single() as Conversation.Effect.Say
        assertTrue(prompt.prompt)
        assertEquals(Conversation.Phase.RECEIPT,e.phase)
        assertEquals(Conversation.Effect.Save("WorkerAlpha","26372",false),e.accept("three seven two",now=2).single())
    }
    @Test fun phoneDigitsContinueAcrossSpeechSegments() {
        val e=engine();e.accept("find last four two eight",now=1)
        assertEquals(Conversation.Phase.NUMBER,e.phase)
        assertEquals(Conversation.Effect.Find(listOf("2807"),null),e.accept("zero seven",now=2).single())
    }
    @Test fun fullReceiptCorrectionReplacesIncompleteDigits() {
        val e=engine();e.accept("WorkerAlpha finished receipt two six",now=1)
        assertEquals(Conversation.Effect.Save("WorkerAlpha","12345",true),e.accept("no receipt one two three four five",now=2).single())
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
