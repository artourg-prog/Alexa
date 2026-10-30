package com.tailorshop.counter
import com.tailorshop.counter.conversation.CardDeck
import org.junit.Assert.*
import org.junit.Test
class CardDeckTest {
    private fun card(n:String,ready:Boolean=true)=Card(n,"Synthetic customer","0000",ready)
    @Test fun multipleResultsKeepSearchOrderAndCanBeBrowsed() {
        val d=CardDeck();d.add(listOf(card("12345"),card("12346"),card("12347")),0)
        assertEquals("12345",d.current!!.receipt);d.move(1);assertEquals("12346",d.current!!.receipt)
        d.move(-1);assertEquals("12345",d.current!!.receipt);d.move(-1);assertEquals("12347",d.current!!.receipt)
    }
    @Test fun notReadyExpiresAfterTenSecondsReadyAfterMinute() {
        val d=CardDeck();d.add(listOf(card("12345",false),card("12346")),0)
        assertFalse(d.expire(9999));assertTrue(d.expire(10_000));assertEquals(1,d.size)
        assertEquals("12346",d.current!!.receipt);assertTrue(d.expire(60_000));assertNull(d.current)
    }
    @Test fun expirationOfAnotherCustomerDoesNotSwitchSelectedReceipt() {
        val d=CardDeck();d.add(listOf(card("12345",false),card("12346"),card("12347")),0)
        d.move(1);d.expire(10_000)
        assertEquals("12346",d.current!!.receipt);assertEquals(0,d.index)
    }
    @Test fun undoKeepsItsExactReceiptWhileItIsPending() {
        val d=CardDeck();d.add(listOf(card("12345"),card("12346")),0);d.move(1)
        d.expire(61_000,"12345");assertEquals("12345",d.current!!.receipt)
        d.expire(61_000);assertNull(d.current)
    }
    @Test fun refreshingCardDoesNotDuplicateItOrSwitchOtherCustomerOnRemoval() {
        val d=CardDeck();d.add(listOf(card("12345"),card("12346")),0);d.add(listOf(card("12345")),20_000)
        assertEquals(2,d.size);d.move(1);d.remove("12345");assertEquals("12346",d.current!!.receipt)
    }
}
