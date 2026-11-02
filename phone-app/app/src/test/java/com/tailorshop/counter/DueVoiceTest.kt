package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Owner 11-02 (Alexa #4 6574558463): the amount due is said only when certain; a male voice is only an owner-chosen or
// engine-named-male voice (no gender metadata exists, no pitch faking).
class DueVoiceTest {
    @Test fun amountDueOnlyWhenTheWholeTotalIsOwed() {
        assertEquals("$650.00", Lookup.dueOf("Due", "$650.00"))
        assertEquals("$1,250.00", Lookup.dueOf(" due ", "$1,250.00"))
        assertEquals("", Lookup.dueOf("Zelle + Due", "$90.00"))          // part paid: balance amount unknown
        assertEquals("", Lookup.dueOf("Due", ""))                        // no Total
        assertEquals("", Lookup.dueOf("Due", "see note"))
        assertEquals("", Lookup.dueOf("Card", "$40.00"))
        assertEquals("", Lookup.dueOf("", "$40.00"))                     // unknown is never paid or due
    }

    @Test fun parsedOrdersCarryDueAndPayState() {
        val rows = listOf(listOf("Date", "Receipt #", "Total", "Payment Type", "Customer", "Phone", "M.T."),
            listOf("2026-11-01", "26601", "$650.00", "Due", "", "7025550101", "Emma"),
            listOf("2026-11-01", "26602", "$90.00", "Zelle + Due", "C", "7025550101", ""),
            listOf("2026-11-01", "26603", "$40.00", "Card", "C", "7025550101", "Emma"),
            listOf("2026-11-01", "26604", "$40.00", "", "C", "7025550101", "Emma"))
        val cards = Lookup.find(Query.Last4("0101"), Lookup.parseOrders(rows)).associateBy { it.receipt }
        assertEquals(Pay.DUE, cards.getValue("26601").pay); assertEquals("$650.00", cards.getValue("26601").due)
        assertEquals("", cards.getValue("26601").customer)                                   // missing customer kept empty
        assertEquals(Pay.DUE, cards.getValue("26602").pay); assertEquals("", cards.getValue("26602").due)
        assertEquals(Pay.PAID, cards.getValue("26603").pay); assertEquals("", cards.getValue("26603").due)
        assertEquals(Pay.UNKNOWN, cards.getValue("26604").pay)
        assertEquals(4, cards.size)                                                          // multi-match by last four
    }

    private val v = listOf(
        Voices.Option("en-us-x-sfg-local", "en"), Voices.Option("en-us-x-iom-network", "en", network = true),
        Voices.Option("en-US-female-1", "en"), Voices.Option("en-US-male-2", "en"), Voices.Option("ru-ru-x-dfc-local", "ru"),
        Voices.Option("en-us-x-tpd-local", "en", installed = false))

    @Test fun maleVoiceIsOwnerChosenOrNamedMaleNeverGuessed() {
        assertEquals("en-us-x-sfg-local", Voices.maleEnglish(v, "en-us-x-sfg-local", false)!!.name)   // the owner's verified pick
        assertEquals("en-US-male-2", Voices.maleEnglish(v, null, false)!!.name)                       // engine name says male
        assertEquals("en-US-male-2", Voices.maleEnglish(v, "en-us-x-tpd-local", false)!!.name)         // chosen but not installed
        assertEquals("en-US-male-2", Voices.maleEnglish(v, "en-us-x-iom-network", false)!!.name)       // online voice while offline
        assertEquals("en-us-x-iom-network", Voices.maleEnglish(v, "en-us-x-iom-network", true)!!.name)
        assertNull(Voices.maleEnglish(v.filter { it.name != "en-US-male-2" }, null, true))            // "female" never counts
        assertNull(Voices.maleEnglish(listOf(Voices.Option("ru-ru-male", "ru")), null, true))          // English only
    }

    @Test fun sofiaVoiceIsTheOwnersRussianChoiceOrTheDefault() {
        assertEquals("ru-ru-x-dfc-local", Voices.sofia(v, "ru-ru-x-dfc-local", false)!!.name)
        assertNull(Voices.sofia(v, "en-US-male-2", true))                                             // never an English/male voice
        assertNull(Voices.sofia(v, null, true))
    }
}
