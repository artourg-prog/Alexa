package com.tailorshop.counter

import com.tailorshop.counter.conversation.SafeIntent
import com.tailorshop.counter.conversation.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

class ForDigitTest {
    @Test fun trailingForIsTheLastReceiptDigit() {
        assertEquals(listOf("26384"),SafeIntent.numbers("check receipt two six three eight for"))
        assertEquals("26384",SpokenNumbers.toDigits("two six three eight for"))
    }
    @Test fun leadingForIsTheFirstDigitWhenDictating() {
        assertEquals("48776",SpokenNumbers.toDigits("for eight seven seven six"))
        assertEquals(listOf("48776"),SafeIntent.numbers("find for eight seven seven six"))
    }
    @Test fun isolatedForIsFourForSegmentedDictation() {
        assertEquals("4",SpokenNumbers.toDigits("for"))
    }
    @Test fun lookupJoinsAnIsolatedForWithFollowingDigits() {
        val conversation = Conversation { listOf("WorkerAlpha") }.also { it.wake(0) }
        conversation.accept("find", now=1)
        conversation.accept("for", now=2)
        assertEquals(Conversation.Effect.Find(listOf("4877"), null),
            conversation.accept("eight seven seven", now=3).single())
    }
    @Test fun grammaticalForIsNotAnExtraNumber() {
        assertEquals(listOf("6824"),SafeIntent.numbers("find order for six eight two four"))
        assertEquals("ready for pickup",SpokenNumbers.toDigits("ready for pickup"))
        assertEquals("receipt 12345 for alex",SpokenNumbers.toDigits("receipt 12345 for alex"))
    }
    @Test fun transcriptAndNumberParserAgree() {
        assertEquals("Check receipt 26384",SpokenNumbers.display("Check receipt two six three eight for"))
        assertEquals("Find order for 6824",SpokenNumbers.display("Find order for six eight two four"))
    }
    @Test fun forInLastFourLabelDoesNotBecomePartOfNumber() {
        assertEquals(listOf("4877"),SafeIntent.numbers("find last for digits for eight seven seven"))
    }
}
