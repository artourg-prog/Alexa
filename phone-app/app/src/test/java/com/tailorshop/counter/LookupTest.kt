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

    @Test fun tailorNameMeansReady() {
        val rows2 = listOf(
            listOf("Receipt #", "Customer", "Phone", "M.T."),
            listOf("30001", "A", "702 555 1111", "Gor"),
            listOf("30002", "B", "702 555 1111", ""),
        )
        val cards = Lookup.findAll("1111", Lookup.parseOrders(rows2)).associate { it.receipt to it.ready }
        assertEquals(mapOf("30002" to false, "30001" to true), cards)
    }

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

    private fun tail(said: String) = Lookup.commandTail(said)

    @Test fun onlyCommandsAreActedOn() {
        assertEquals(listOf(Query.Last4("2151")), q(tail("Find receipt for the phone number 2151")!!))
        assertEquals(listOf(Query.Last4("6824")), q(tail("Find a receipt number for 6824")!!))
        assertEquals(listOf(Query.Last4("6824")), q(tail("find the receipt number for six eight two four")!!))
        assertEquals(listOf(Query.Last4("6824")), q(tail("Fine Order for Six eight to for")!!))
        assertEquals(listOf(Query.Last4("7970")), q(tail("check for the phone number 7970")!!))
        assertEquals(listOf(Query.Last4("2151")), q(tail("check receipt number 21 51")!!))
        assertEquals(listOf(Query.Last4("2151")), q(tail("найди ресит 2151")!!))
        assertEquals(listOf(Query.Last4("2151")), q(tail("найди номер ресита двадцать один пятьдесят один")!!))
        assertEquals(listOf(Query.Last4("2151")), q(tail("найди квитанцию 2151")!!))
        assertEquals(listOf(Query.Last4("4847")), q(tail("Найди заказ на 48 47")!!))
        assertEquals(listOf(Query.FullPhone("7255551203")), q(tail("find order 725 555 1203")!!))
    }

    @Test fun ninetyNineThirtyThreeEveryWay() {
        for (said in listOf(
            "Find order for 9933", "Check number 9933", "find order for ninety nine thirty three",
            "find order for ninety-nine thirty-three", "Find order for 99 33", "find order for 9,933",
            "find order for $99.33", "check number nine nine three three", "Find order for найн найн сёрти фри",
            "найди заказ девяносто девять тридцать три", "найди заказ 99-33",
            "check number nine thousand nine hundred thirty three",
        )) assertEquals(said, listOf(Query.Last4("9933")), q(tail(said)!!))
    }

    @Test fun misheardDigitsInsideCommand() {
        assertEquals(listOf(Query.Last4("6824")), q(tail("Fine Order for Sex Side to for")!!))
        assertTrue(q(tail("find order for")!!).isEmpty())
    }

    @Test fun findNumberAndPhoneTriggers() {
        for (said in listOf("find phone number 4742", "find number 4742", "найди номер 4742", "Define phone number 4742",
                "найди телефон 4742", "find the phone 47 42"))
            assertEquals(said, listOf(Query.Last4("4742")), q(tail(said)!!))
    }

    @Test fun splitCommand() {
        assertEquals("", tail("Найди"))
        assertEquals("", tail("find"))
        assertEquals("", tail("Check receipt number"))
        assertEquals("", tail("найди заказ"))
        // Without a trigger a number is taken only right after a command or an answer (MainActivity).
        assertEquals(null, tail("заказ 4742"))
        assertEquals(null, tail("Number 8438"))
        assertEquals(null, tail("у меня был заказ вчера"))
    }

    @Test fun triggerWords() {
        for (said in listOf("find 4742", "look up 4742", "Lookup 47 42", "найди 4742", "посмотри 4742",
                "Okay check number 4742", "Так найди сорок семь сорок два", "проверь 4742"))
            assertEquals(said, listOf(Query.Last4("4742")), q(tail(said)!!))
    }

    @Test fun artsTriggerPhrases() {
        for (said in listOf("find 4417", "find it 4417", "look for 4417", "look up 4417", "check 4417", "search 4417",
                "search for 4417", "pull up 4417", "show me 4417", "where is 4417", "do you have 4417", "get me 4417",
                "bring up 4417", "can you find 4417", "look for receipt 4417", "find it order number 4417",
                "найди 4417", "поищи 4417", "посмотри 4417", "проверь 4417", "покажи 4417", "глянь 4417", "где 4417",
                "есть ли 4417", "подними 4417", "найди-ка 4417", "поищи ресит 4417", "посмотри номер 4417",
                "покажи телефон клиента 4417", "where is ticket 4417"))
            assertEquals(said, listOf(Query.Last4("4417")), q(tail(said)!!))
        assertEquals(listOf(Query.Receipt("26314")), q(tail("find it 26314")!!))
        assertEquals(listOf(Query.Receipt("26314")), q(tail("поищи ресит 26314")!!))
    }

    @Test fun everydayWordsWithoutANumberDoNothing() {
        for (said in listOf("где", "покажи мне", "where is it", "show me that", "do you have time", "можешь глянуть",
                "есть ли у нас нитки", "search", "bring up the box"))
            assertEquals(said, null, tail(said))
    }

    @Test fun triggerOnlyAtTheStart() {
        assertEquals(null, tail("я сказал ему что надо посмотреть 4742")) // trigger late, no "receipt"
        assertEquals(null, tail("we should look up the weather"))
        assertEquals(null, tail("посмотри какая погода"))
    }

    @Test fun roomTalkIsIgnored() {
        assertEquals(null, tail("towards that you should treat the weather"))
        assertEquals(null, tail("да последние номера телефонов или же весь номер телефона"))
        assertEquals(null, tail("я найду время 1203")) // number without a command
        assertEquals(null, tail("I'll find it later"))
    }

    @Test fun commandWithoutNumberIsDidntCatch() {
        assertTrue(q(tail("Find receipt number for")!!).isEmpty())
        assertEquals("I didn't catch the number.", Lookup.didntCatch(false))
    }

    @Test fun fillerOnlyIsNoQuery() = assertTrue(Lookup.parseQueries("find the order please").isEmpty())

    @Test fun answerIsReceiptDigitsEnglishOnly() {
        val en = Lookup.replyEn(listOf(Card("26253", "Pat", "(702) 555-1034", ready = true)))
        assertEquals("Receipt two, six, two, five, three — ready for pickup.", en)
        assertTrue(!en.contains("1034") && !en.contains("one, zero, three, four"))
        assertEquals("Receipt two, six, two, five, four — work is not finished yet.",
            Lookup.replyEn(listOf(Card("26254", "Sam", "", ready = false))))
    }

    @Test fun paidDueAccountFromPaymentType() {
        val rows2 = listOf(
            listOf("Receipt #", "Payment Type", "Customer", "Phone", "M.T."),
            listOf("30001", "Card", "A", "702 555 2222", "Gor"),
            listOf("30002", "Zelle + Due", "B", "702 555 2222", ""),
            listOf("30003", "Account", "C", "702 555 2222", ""),
            listOf("30004", "", "D", "702 555 2222", ""),
            listOf("30005", "Due", "E", "702 555 2222", ""),
            listOf("30006", "Cash + Card", "F", "702 555 2222", ""),
        )
        val pay = Lookup.findAll("2222", Lookup.parseOrders(rows2)).associate { it.receipt to it.pay }
        assertEquals(mapOf("30001" to Pay.PAID, "30002" to Pay.DUE, "30003" to Pay.ACCOUNT, "30004" to Pay.UNKNOWN,
            "30005" to Pay.DUE, "30006" to Pay.PAID), pay)
        assertEquals("Receipt two, six, two, five, three — due — ready for pickup.",
            Lookup.replyEn(listOf(Card("26253", "Pat", "", ready = true, pay = Pay.DUE))))
        assertEquals("Receipt two, six, two, five, three — paid — work is not finished yet.",
            Lookup.replyEn(listOf(Card("26253", "Pat", "", pay = Pay.PAID))))
        assertEquals("Receipt two, six, two, five, three — account — ready for pickup.",
            Lookup.replyEn(listOf(Card("26253", "Pat", "", ready = true, pay = Pay.ACCOUNT))))
    }

    @Test fun notFoundSaysOnlyThat() {
        assertEquals("I can't find this order.", Lookup.notFound(false))
        assertEquals("Не могу найти этот заказ.", Lookup.notFound(true))
        assertTrue(receipts("9999").isEmpty())
    }

    @Test fun prettyPhone() {
        assertEquals("(702) 555-1034", Lookup.prettyPhone("+1 702 555 1034"))
        assertEquals("(725) 555-1203", Lookup.prettyPhone("(725) 555-1203"))
    }

    @Test(expected = IllegalStateException::class)
    fun badHeaderFails() { Lookup.parseOrders(listOf(listOf("Foo", "Bar"), listOf("1", "2"))) }
}
