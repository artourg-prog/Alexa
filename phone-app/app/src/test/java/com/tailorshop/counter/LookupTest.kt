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

    private fun tail(said: String) = Lookup.commandTail("Sofia, $said") // every command starts with her name
    private fun raw(said: String) = Lookup.commandTail(said)

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
        assertEquals(null, raw("заказ 4742"))
        assertEquals(null, raw("Number 8438"))
        assertEquals(null, raw("у меня был заказ вчера"))
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
            assertEquals(said, null, raw(said))
    }

    @Test fun sofiaMustBeAddressed() {
        for (said in listOf("find 8438", "найди 8438", "look for receipt 26316", "8438", "check number 9933", "где 4417"))
            assertEquals(said, null, raw(said))                                 // no name: ignored
        for (said in listOf("Sofia, find receipt 26316", "Sophia find 26316", "София, найди 26316", "Софья найди ресит 26316",
                "Hey Sofia, look for 26316", "Sofia 26316", "София 26316"))
            assertEquals(said, listOf(Query.Receipt("26316")), q(raw(said)!!))
        assertEquals(listOf(Query.Last4("8438")), q(raw("Sofia, find 8438")!!))
        assertEquals("", raw("Sofia"))                                          // waits for the number
        assertEquals("", raw("София, найди"))
        assertEquals(null, raw("Sofia, how is the weather"))
        assertEquals(null, raw("yesterday I told Sofia to find 8438"))         // her name too late
    }

    @Test fun followUpQuestions() {
        assertEquals(Topic.TAILOR, Lookup.followUp("who was the tailor?"))
        assertEquals(Topic.TAILOR, Lookup.followUp("Sofia, кто мастер?"))
        assertEquals(Topic.AMOUNT, Lookup.followUp("how much is it"))
        assertEquals(Topic.AMOUNT, Lookup.followUp("сколько?"))
        assertEquals(Topic.PAY, Lookup.followUp("is it paid?"))
        assertEquals(Topic.PAY, Lookup.followUp("оплачено?"))
        assertEquals(Topic.DATE, Lookup.followUp("when was it dropped off"))
        assertEquals(Topic.DATE, Lookup.followUp("когда сдали"))
        assertEquals(Topic.WORK, Lookup.followUp("what was the work"))
        assertEquals(Topic.WORK, Lookup.followUp("что делали"))
        assertEquals(null, Lookup.followUp("thank you"))
    }

    @Test fun followUpAnswersFromTheSheetOnly() {
        val c = Lookup.findAll("26240", orders).first() // from the made-up rows above
        assertEquals("It was dropped off on October 10.", Lookup.followUpAnswer(Topic.DATE, c))
        assertEquals("The total is $40.00.", Lookup.followUpAnswer(Topic.AMOUNT, c))
        assertEquals("The work is: Pants hem.", Lookup.followUpAnswer(Topic.WORK, c))
        assertEquals("It's not finished yet, there's no tailor on it.", Lookup.followUpAnswer(Topic.TAILOR, c))
        assertEquals("The tailor was Gor.", Lookup.followUpAnswer(Topic.TAILOR, c.copy(tailor = "Gor")))
        assertEquals("There is a balance due.", Lookup.followUpAnswer(Topic.PAY, c.copy(pay = Pay.DUE)))
        for (t in Topic.values()) Lookup.followUpAnswer(t, c).let { assertTrue(!it.contains("Andrea") && !it.contains("555")) }
    }

    @Test fun artsLiveTranscripts() {
        // Exactly what the Fold heard on 10-28 21:18 ("Sofia, find number 26279", said as "26-279").
        for (said in listOf("София Найди номер 26 279", "София Найди номер 262-79", "София Найди номер 26279"))
            assertEquals(said, listOf(Query.Receipt("26279")), q(raw(said)!!))
        assertEquals(listOf(Query.Receipt("26314"), Query.Receipt("26315"), Query.Receipt("26316")),
            q("готово 26.314 26.315 и 26.316"))
        assertEquals(listOf(Query.Receipt("26279")), q("Sofia twenty six two seventy nine"))
        assertEquals(listOf(Query.FullPhone("7025551034")), q("702 555 1034")) // a phone still reads as a phone
        assertEquals(listOf(Query.Last4("4847")), q("48 47"))
    }

    @Test fun herNameAloneIsQuiet() {
        assertTrue(Lookup.onlyHerName("вот София"))
        assertTrue(!Lookup.onlyHerName("София, найди"))
        assertTrue(!Lookup.onlyHerName("we talked about it"))
    }

    @Test fun politeWordsDontBreakCommands() {
        for (said in listOf("Sofia, can you please find me 26316", "Sofia, please find 26316", "София, найди, пожалуйста, номер 26316",
                "Sofia, could you find 26316", "Sofia, would you please look up 26316", "София, будь добра, найди 26316"))
            assertEquals(said, listOf(Query.Receipt("26316")), q(raw(said)!!))
        assertEquals("You're welcome!" to false, Lookup.thanksReply("Sofia, thank you"))
        assertEquals("Пожалуйста!" to true, Lookup.thanksReply("София, спасибо"))
        assertEquals(null, Lookup.thanksReply("Sofia, find 26316 thank you"))
        assertEquals(null, Lookup.thanksReply("thank you"))
    }

    @Test fun sheAnswersInTheLanguageAsked() {
        assertTrue(Lookup.isRussian("София, проверь номер 26315"))
        assertTrue(!Lookup.isRussian("Sofia, check 26315"))
        val c = Card("26315", "x", "", ready = true, pay = Pay.DUE)
        assertEquals("Квитанция 2 6 3 1 5 готова к выдаче, есть долг.", Lookup.reply(listOf(c), true))
        assertEquals("Receipt 2 6 3 1 5 is ready for pickup, there is a balance due.", Lookup.reply(listOf(c), false))
        assertEquals("Квитанция 2 6 3 1 5 ещё не готова, оплачено.", Lookup.replyRu(listOf(c.copy(ready = false, pay = Pay.PAID))))
        val d = Lookup.findAll("26240", orders).first()
        assertEquals("Сдали 10 октября.", Lookup.followUpAnswer(Topic.DATE, d, true))
        assertEquals("Ещё не готово, мастера пока нет.", Lookup.followUpAnswer(Topic.TAILOR, d, true))
        assertTrue(Lookup.asksIfSheHears("София, ты меня слышишь?"))
        assertTrue(Lookup.asksIfSheHears("Sofia, can you hear me"))
        assertEquals("Да, слушаю.", Lookup.hearsReply(true))
        assertEquals(listOf(Query.Receipt("26315")), q(raw("София, проверь номер 26315")!!))
    }

    @Test fun finishPhrasesAreRecognized() {
        assertTrue(Lookup.isFinishPhrase("София, это Артур, запиши, пожалуйста, номер 26315 и отправь сообщение"))
        assertTrue(Lookup.isFinishPhrase("Sofia, this is Arthur, done 26314, send the message and save"))
        assertTrue(!Lookup.isFinishPhrase("София, найди 26315"))
    }

    @Test fun sheIntroducesHerself() {
        assertTrue(Lookup.asksWhoSheIs("Sofia, who are you?"))
        assertTrue(Lookup.asksWhoSheIs("София, кто ты?"))
        assertTrue(!Lookup.asksWhoSheIs("who are you"))
        assertEquals("I'm Sofia, the counter assistant.", Lookup.INTRO)
    }

    @Test fun triggerOnlyAtTheStart() {
        assertEquals(null, raw("я сказал ему что надо посмотреть 4742")) // trigger late, no "receipt"
        assertEquals(null, raw("we should look up the weather"))
        assertEquals(null, raw("посмотри какая погода"))
    }

    @Test fun roomTalkIsIgnored() {
        assertEquals(null, raw("towards that you should treat the weather"))
        assertEquals(null, raw("да последние номера телефонов или же весь номер телефона"))
        assertEquals(null, raw("я найду время 1203")) // number without a command
        assertEquals(null, raw("I'll find it later"))
    }

    @Test fun commandWithoutNumberIsDidntCatch() {
        assertTrue(q(tail("Find receipt number for")!!).isEmpty())
        assertEquals("I didn't catch the number.", Lookup.didntCatch(false))
    }

    @Test fun fillerOnlyIsNoQuery() = assertTrue(Lookup.parseQueries("find the order please").isEmpty())

    @Test fun answerIsReceiptDigitsEnglishOnly() {
        val en = Lookup.replyEn(listOf(Card("26253", "Pat", "(702) 555-1034", ready = true)))
        assertEquals("Receipt 2 6 2 5 3 is ready for pickup.", en)
        assertTrue(!en.contains("1034") && !en.contains("1 0 3 4"))
        assertEquals("Receipt 2 6 2 5 4 is not finished yet.",
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
        assertEquals("Receipt 2 6 2 5 3 is ready for pickup, there is a balance due.",
            Lookup.replyEn(listOf(Card("26253", "Pat", "", ready = true, pay = Pay.DUE))))
        assertEquals("Receipt 2 6 2 5 3 is not finished yet, it's paid.",
            Lookup.replyEn(listOf(Card("26253", "Pat", "", pay = Pay.PAID))))
        assertEquals("Receipt 2 6 2 5 3 is ready for pickup, it's on the store account.",
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
