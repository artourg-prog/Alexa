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

    private fun q(said: String) = Lookup.parseQueries(said)

    @Test fun fourDigitsInsideAnyPhrase() {
        assertEquals(listOf(Query.Last4("6824")), q("Find receipt number for 6824"))
        assertEquals(listOf(Query.Last4("6824")), q("find order for 6824"))
        assertEquals(listOf(Query.Last4("6824")), q("квитанция на 6824"))
        assertEquals(listOf(Query.Last4("4847")), q("Найди заказ на 48 47"))
        assertEquals(listOf(Query.Last4("4847")), q("Найди заказ на 48-47"))
        assertEquals(listOf(Query.Last4("3267")), q("Find order number for 3267"))
    }

    @Test fun numberWordsEnglish() {
        assertEquals(listOf(Query.Last4("8438")), q("eighty-four thirty-eight"))
        assertEquals(listOf(Query.Last4("6824")), q("Fine Order for Six eight to for"))
        assertEquals(listOf(Query.Last4("6824")), q("find order for six eight two four"))
        assertEquals(listOf(Query.Last4("1203")), q("twelve oh three"))
        assertEquals(listOf(Query.Last4("4838")), q("for eight three eight"))
        assertEquals(listOf(Query.Last4("1208")), q("twelve hundred eight"))
    }

    @Test fun numberWordsRussian() {
        assertEquals(listOf(Query.Last4("8438")), q("восемь четыре три восемь"))
        assertEquals(listOf(Query.Last4("6824")), q("шестьдесят восемь двадцать четыре"))
        assertEquals(listOf(Query.Last4("1208")), q("найди заказ одна тысяча двести восемь"))
        assertEquals(listOf(Query.Last4("6824")), q("сикс эйт ту фор"))
    }

    @Test fun fullPhoneAnyForm() {
        assertEquals(listOf(Query.FullPhone("7255551203")), q("725 555 1203"))
        assertEquals(listOf(Query.FullPhone("7255551203")), q("phone 7255551203"))
        assertEquals(listOf(Query.FullPhone("7255551203")), q("1 725 555 1203"))
        assertEquals(listOf(Query.FullPhone("5551203")), q("555 1203"))
        assertEquals(listOf("26251"), receipts("my number is 725 555 1203"))
        assertEquals(listOf("26251"), receipts("555 1203"))
    }

    @Test fun twoNumbersStaySeparate() =
        assertEquals(listOf(Query.Last4("1034"), Query.Last4("1203")), q("1034 1203"))

    @Test fun shortNumbersInChatAreNotQuestions() {
        assertTrue(q("четыре будет легче").isEmpty())
        assertTrue(q("I'll be there in 5").isEmpty())
    }

    @Test fun lookupPhrasesAreAlwaysAnswered() {
        assertTrue(Lookup.soundsLikeLookup("Find receipt number for"))
        assertTrue(Lookup.soundsLikeLookup("Найди заказ на"))
        assertTrue(!Lookup.soundsLikeLookup("да последние номера телефонов или же весь номер телефона"))
        assertEquals("I didn't catch the number.", Lookup.didntCatch(false))
    }

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
