package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTest {
    // Shape of the real "All Receipts" tab; names and phones made up.
    private val rows = listOf(
        listOf("Date", "Receipt #", "Total", "Payment Type", "Account", "Description", "Customer", "D", "Phone", "P", "M.T."),
        listOf("10/10/26", "26240", "$40.00", "Card", "Walking In Customers", "Pants hem", "Andrea Lopez", "", "702 555 1034", "", ""),
        listOf("10/12/26", "26250", "$20.00", "Cash", "Walking In Customers", "Shirt", "Sam Roe", "", "+1 702 555 1034", "", ""),
        listOf("10/11/26", "26251", "$20.00", "Cash", "Walking In Customers", "Shirt", "Наталья", "", "(725) 555-1203", "", ""),
        listOf("10/11/26", "", "$20.00", "Cash", "Walking In Customers", "Blank receipt", "Ghost", "", "702 555 1034", "", ""),
        listOf("10/09/26", "26100", "$20.00", "Account", "7 For All Mankind @ Palazzo", "Jeans", "Palazzo Store", "", "", "", ""),
    )
    private val orders = Lookup.parseOrders(rows)

    private fun receipts(said: String) = Lookup.findAll(said, orders).map { it.receipt }

    @Test fun headerByName_skipsBlankReceipt() {
        assertEquals(4, orders.size)
        assertEquals("Andrea Lopez", orders.first { it.receipt == "26240" }.customer)
    }

    @Test fun last4_newestFirst() = assertEquals(listOf("26250", "26240"), receipts("1034"))

    @Test fun receiptNumber() = assertEquals(listOf("26251"), receipts("26251"))

    @Test fun twoNumbers() = assertEquals(listOf("26250", "26240", "26251"), receipts("1034 and 1203"))

    @Test fun fullPhoneSpokenInGroups() = assertEquals(listOf("26251"), receipts("725 555 1203"))

    @Test fun nameEnglish() = assertEquals(listOf("26240"), receipts("find Andrea"))

    @Test fun nameRussianSpeechMatchesLatinSheet() = assertEquals(listOf("26240"), receipts("Андреа"))

    @Test fun nameLatinSpeechMatchesCyrillicSheet() = assertEquals(listOf("26251"), receipts("Natalya"))

    @Test fun nameOneTypo() = assertEquals(listOf("26240"), receipts("Andria"))

    @Test fun shortScrapsDontMatchNames() = assertTrue(receipts("sa").isEmpty())

    @Test fun fillerOnlyIsNoQuery() = assertTrue(Lookup.parseQueries("find the order please").isEmpty())

    @Test fun shopChatterIsIgnored() {
        assertTrue(!Lookup.isLikelyQuestion("towards that you should treat the weather"))
        assertTrue(Lookup.isLikelyQuestion("speak 1203"))
        assertTrue(Lookup.isLikelyQuestion("find Andrea Lopez"))
        assertTrue(Lookup.isLikelyQuestion("найди заказ Андреа"))
    }

    @Test fun notFoundSaysOnlyThat() {
        assertEquals("I can't find this order.", Lookup.reply(emptyList(), russian = false))
        assertEquals("Не могу найти этот заказ.", Lookup.reply(emptyList(), russian = true))
        assertTrue(receipts("9999").isEmpty())
    }

    @Test fun replyNeverMentionsPickedUp() {
        val r = Lookup.reply(Lookup.findAll("1034", orders), russian = false)
        assertTrue(r.startsWith("2 orders. Receipt 26250, Sam Roe, phone ending 1 0 3 4"))
        assertTrue(!r.contains("picked", ignoreCase = true))
    }

    @Test fun prettyPhone() {
        assertEquals("(702) 555-1034", Lookup.prettyPhone("+1 702 555 1034"))
        assertEquals("(725) 555-1203", Lookup.prettyPhone("(725) 555-1203"))
    }

    @Test(expected = IllegalStateException::class)
    fun badHeaderFails() { Lookup.parseOrders(listOf(listOf("Foo", "Bar"), listOf("1", "2"))) }
}
