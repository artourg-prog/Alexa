package com.tailorshop.counter

import java.text.Normalizer

// Pure logic: sheet rows + what was said -> order cards. No network, no writes.
//
// Source: "Order Ready For Pickup" / tab "All Receipts" (the Pickups mirror: only
// orders not yet picked up). Columns are found by header name, so a moved column
// can't shift data into the wrong field. Only receipt, customer and phone are shown,
// plus ready / not finished: column "M.T." holds the tailor's name once the work is done
// (Art's rule), so a filled M.T. = ready for pickup, blank = not finished yet.

data class Order(val receipt: String, val customer: String, val phone: String, val date: String, val ready: Boolean = false,
                 val pay: Pay = Pay.UNKNOWN, val tailor: String = "", val total: String = "", val work: String = "")

// tailor / total / date / work are only for Sofia's follow-up answers (never the customer or phone).
data class Card(val receipt: String, val customer: String, val phone: String, val ready: Boolean = false,
                val pay: Pay = Pay.UNKNOWN, val tailor: String = "", val total: String = "", val date: String = "",
                val work: String = "")

/** Follow-up questions about the receipt on screen (Art): tailor, amount, paid or due, drop-off date, work. */
enum class Topic { TAILOR, AMOUNT, PAY, DATE, WORK }

/** From column "Payment Type": any "Due" (incl. "Zelle + Due") = due; "Account" = billed to the store. */
enum class Pay { PAID, DUE, ACCOUNT, UNKNOWN }

sealed class Query {
    data class Last4(val digits: String) : Query()
    data class Receipt(val number: String) : Query()
    data class FullPhone(val digits: String) : Query()
    data class Name(val words: List<String>) : Query()
}

object Lookup {
    private val FIELDS = mapOf(
        "receipt" to listOf("receipt #", "receipt_number"),
        "customer" to listOf("customer", "customer_or_shop"),
        "phone" to listOf("phone"),
        "date" to listOf("date", "received_date"),
        "tailor" to listOf("m.t.", "m.t", "tailor"),
        "payment" to listOf("payment type", "payment_type", "payment"),
        "total" to listOf("total"),
        "work" to listOf("description", "work"),
    )

    private val PAID_WORDS = listOf("card", "cash", "zelle", "venmo", "check", "paid")

    /** Never a guess: anything not clearly paid / due / account is UNKNOWN (nothing said). */
    fun payOf(raw: String): Pay {
        val t = raw.trim().lowercase()
        return when {
            "due" in t -> Pay.DUE
            "account" in t -> Pay.ACCOUNT
            PAID_WORDS.any { it in t } -> Pay.PAID
            else -> Pay.UNKNOWN
        }
    }

    // Words people say around the actual name or number.
    private val FILLER = setOf(
        "find", "look", "up", "lookup", "search", "check", "show", "order", "orders", "receipt",
        "receipts", "customer", "client", "phone", "number", "for", "of", "the", "a", "please", "and",
        "last", "four", "digits", "is", "it", "name",
        "найди", "найти", "покажи", "проверь", "поиск", "заказ", "заказы", "квитанция", "квитанцию",
        "клиент", "клиента", "клиентка", "телефон", "номер", "для", "на", "и", "пожалуйста", "имя",
        "последние", "цифры", "это", "ресит", "ресита", "рисит", "квитанции", "with", "by", "on", "my",
        "ticket", "me", "you", "have", "квитанцию", "клиента", "can", "could", "would", "пожалуйста",
    )

    // ---- sheet ----

    /** rows[0] is the header row. Rows without a receipt number are skipped. */
    fun parseOrders(rows: List<List<String>>): List<Order> {
        if (rows.isEmpty()) return emptyList()
        val names = rows[0].map { it.trim().lowercase() }
        val idx = FIELDS.mapValues { (_, aliases) -> aliases.map { names.indexOf(it) }.firstOrNull { it >= 0 } }
        val r = idx["receipt"] ?: throw IllegalStateException("BAD_HEADER")
        val p = idx["phone"] ?: throw IllegalStateException("BAD_HEADER")
        fun cell(row: List<String>, i: Int?) = if (i == null) "" else row.getOrNull(i)?.trim().orEmpty()
        return rows.drop(1).mapNotNull { row ->
            val receipt = cell(row, r)
            if (receipt.isEmpty()) null
            else Order(receipt, cell(row, idx["customer"]), cell(row, p), cell(row, idx["date"]), cell(row, idx["tailor"]).isNotEmpty(),
                payOf(cell(row, idx["payment"])), cell(row, idx["tailor"]), cell(row, idx["total"]), cell(row, idx["work"]))
        }
    }

    // ---- what was said ----

    /**
     * One utterance -> one or more queries.
     * "1034" -> last 4; "26240" -> receipt; "1034 and 1203" -> two; "702 555 1034" -> full phone;
     * no digits -> customer name.
     */
    fun parseQueries(said: String): List<Query> {
        val text = SpokenNumbers.toDigits(normalize(said))
        val groups = Regex("\\d+").findAll(text).map { it.value }.toList()
        if (groups.isEmpty()) {
            val words = text.split(' ').filter { it.isNotEmpty() && it !in FILLER_N }
            return if (words.isEmpty()) emptyList() else listOf(Query.Name(words))
        }
        if (groups.all { it.length == 4 || it.length == 5 }) {
            return groups.distinct().take(3).map { if (it.length == 4) Query.Last4(it) else Query.Receipt(it) }
        }
        // Receipts said in parts ("26-279", "twenty-six two seventy-nine") come back as "26 279", "262-79",
        // "26.314 26.315": put 2+3 / 3+2 / 2+2 groups back together, each into its own number.
        pairUp(groups)?.let { nums -> return nums.distinct().take(3).map { if (it.length == 4) Query.Last4(it) else Query.Receipt(it) } }
        val all = groups.joinToString("")
        return when {
            all.length == 4 -> listOf(Query.Last4(all))
            all.length == 5 -> listOf(Query.Receipt(all))
            all.length == 6 -> listOf(Query.Last4(all.takeLast(4)))
            all.length >= 7 -> listOf(Query.FullPhone(all.takeLast(10)))
            else -> emptyList() // 1-3 digits: not a phone ending or receipt
        }
    }

    /** Groups -> 4/5-digit numbers when every group fits a pair (2+3, 3+2, 2+2) or stands alone; else null. */
    private fun pairUp(groups: List<String>): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        while (i < groups.size) {
            val g = groups[i]; val n = groups.getOrNull(i + 1)
            when {
                g.length == 4 || g.length == 5 -> { out += g; i++ }
                n != null && (g.length + n.length == 5 || (g.length == 2 && n.length == 2)) && g.length in 2..3 -> { out += g + n; i += 2 }
                else -> return null
            }
        }
        return out
    }

    private val FILLER_N by lazy { FILLER.map(::normalize).toSet() }

    // After "Sofia", a find phrase at the start of the rest: "find / look up / check …", "найди /
    // посмотри / проверь …" (or "find receipt …" anywhere). Everything else is ignored and never shown. "fine"/"faynd" and "chek" are how the
    // recognizer often writes "find" and "check".
    private val FIND = setOf("find", "fine", "fined", "define", "faynd", "fayind", "naydi", "nayti", "naydite",
        "lookup", "posmotri", "posmotrite", "check", "chek", "checks", "prover", "proverte")
    private val OBJECT_PREFIXES = listOf("receipt", "order", "resi", "risi", "zakaz", "kvitan", "nomer", "number", "namber", "phone", "telefon")
    private const val TRIGGER_WITHIN = 2 // "Okay, find …", "Так, найди …": at most 2 words before the trigger

    // Art: many ways to say "find" (people mix words). These count only with a number in the same phrase,
    // since words like "where" / "где" / "show me" are common in ordinary talk. Phrases are folded to one word.
    private val WEAK_PHRASES = listOf(
        "can you find", "do you have", "look for", "search for", "pull up", "show me", "where is", "get me", "bring up",
        "find it", "est li", "naydi ka",
    )
    private val WEAK = setOf("search", "where", "poischi", "pokazhi", "glyan", "gde", "podnimi") +
        WEAK_PHRASES.map { it.replace(' ', '_') }

    // Art: the counter assistant is "Sofia" / "София" and every command starts with her name, so normal
    // talk is ignored. These are her name as the recognizer writes it (after Cyrillic -> Latin).
    // Only the forms used to call her: "Софии / Софию / о Софье" (talking ABOUT her) don't count.
    private val SOFIA = setOf("sofia", "sophia", "sofiya", "sofya", "sofie", "sophie", "sofi", "soffia")
    // Her name must open the phrase, or follow one call word ("Hey Sofia", "Так, София"). On 10-28 Art's
    // "на стороне Софии лишний звук…" woke her because 2 words before the name were allowed.
    private val CALL_WORDS = setOf("hey", "hi", "ok", "okay", "so", "and", "tak", "vot", "nu", "slushay", "a", "privet", "ey", "oy")

    private fun words(said: String): List<String> = normalize(said).split(' ').filter { it.isNotEmpty() }

    // Art: courtesy words anywhere in a command don't break it ("Sofia, can you please find me 26316").
    private val POLITE = setOf("please", "pozhaluysta", "pozhalusta", "bud", "dobra", "dobr", "kindly")

    /** What follows "Sofia" when her name opens the phrase (courtesy words dropped), or null when she wasn't addressed. */
    private fun afterName(said: String): List<String>? {
        val w = words(said)
        val i = w.indexOfFirst { it in SOFIA }
        val opens = i == 0 || (i == 1 && w[0] in CALL_WORDS)
        return if (i < 0 || !opens) null else w.drop(i + 1).filter { it !in POLITE }
    }

    /** She was addressed: her name opens the phrase (used to show the words live). */
    fun addressed(said: String): Boolean = afterName(said) != null

    private val THANKS = setOf("thank", "thanks", "spasibo")

    /** "Sofia, thank you" / "София, спасибо": the reply, in the speaker's language; else null. */
    fun thanksReply(said: String): Pair<String, Boolean>? {
        val rest = afterName(said) ?: return null
        if (rest.none { it in THANKS } || rest.any { it !in THANKS + setOf("you", "very", "much", "so", "bolshoe", "ochen") }) return null
        val russian = "spasibo" in rest
        return if (russian) "Пожалуйста!" to true else "You're welcome!" to false
    }

    // "запиши / готово / закончил / сохрани / отправь сообщение", "done / finished / save / send the message"
    private val FINISH = setOf("zapishi", "zapisat", "gotovo", "zakonchil", "zakonchila", "sdelal", "sdelala", "sokhrani",
        "sohrani", "done", "finished", "save", "otprav", "otpravi")

    /** A Voice Finish phrase (the tailor reporting finished work), not a lookup. */
    fun isFinishPhrase(said: String): Boolean = words(said).any { it in FINISH }

    // ---- Voice Finish ----

    /** What a finish command said: the matched worker (null = no / unclear name), the receipts, SMS wanted. */
    data class Finish(val worker: String?, val nameHeard: String?, val receipts: List<String>, val sms: Boolean)

    private val SMS_WORDS = listOf("send the message", "send message", "send a message", "send sms", "send the text", "text the client",
        "otprav soobschenie", "otpravit soobschenie", "otprav sms", "otpravit sms", "otprav smsku", "otpravi soobschenie")
    private val INTRO_WORDS = setOf("this", "is", "it", "s", "its", "here", "eto", "ya", "i", "am", "m")

    /** The Approved Workers name the tailor said (one typo allowed, e.g. "Андрей" -> Andrei); null if none or unclear. */
    fun matchWorker(word: String, workers: List<String>): String? {
        val w = normalize(word)
        if (w.length < 3) return null
        val hits = workers.filter { name ->
            val n = normalize(name).split(' ').first()
            n == w || (w.length >= 4 && editDistance(w, n) <= 1) || (w.length >= 4 && n.startsWith(w))
        }
        return hits.singleOrNull()
    }

    /** A finish command after "Sofia" (or while she is still listening): name, receipts, "send the message". */
    fun parseFinish(said: String, workers: List<String>): Finish? {
        if (!isFinishPhrase(said)) return null
        val all = words(said).filter { it !in SOFIA && it !in POLITE }
        val text = " " + all.joinToString(" ") + " "
        val sms = SMS_WORDS.any { text.contains(" $it ") }
        // The name: the first word that matches a worker, among the words before the first finish word.
        val firstFinish = all.indexOfFirst { it in FINISH }.let { if (it < 0) all.size else it }
        val candidates = all.take(firstFinish).filter { it !in INTRO_WORDS }
        val worker = candidates.firstNotNullOfOrNull { matchWorker(it, workers) }
        val heard = candidates.firstOrNull { it.length >= 3 && it.none(Char::isDigit) }
        val receipts = parseQueries(all.joinToString(" ")).filterIsInstance<Query.Receipt>().map { it.number }.distinct()
        return Finish(worker, heard, receipts, sms)
    }

    private val YES = setOf("yes", "yeah", "yep", "correct", "right", "sure", "da", "verno", "aga", "tochno", "pravilno", "konechno")
    private val NO = setOf("no", "nope", "cancel", "wrong", "net", "otmena", "ne", "neverno", "otmenit", "stop")

    /** The tailor's answer to "correct?": true = yes, false = no, null = neither. */
    fun yesNo(said: String): Boolean? {
        val w = words(said).filter { it !in SOFIA }
        return when { w.any { it in NO } -> false; w.any { it in YES } -> true; else -> null }
    }

    fun readBack(worker: String, receipt: String, russian: Boolean): String {
        val d = digits(receipt).toCharArray().joinToString(" ")
        return if (russian) "$worker, $d, верно?" else "$worker, $d, correct?"
    }

    /** First words to a tailor that day: good morning / hello by name, in the speaker's language. */
    fun greeting(worker: String, hour: Int, russian: Boolean): String = if (russian) {
        if (hour < 12) "Доброе утро, $worker." else "Здравствуй, $worker."
    } else { if (hour < 12) "Good morning, $worker." else "Hello, $worker." }

    /** Just her name, nothing else after it ("вот София", "Sofia?"): not a command yet. */
    fun onlyHerName(said: String): Boolean = afterName(said)?.isEmpty() == true

    /** Art: she answers in the language she was asked in. Cyrillic letters = Russian. */
    fun isRussian(said: String) = said.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }

    /** "Sofia, who are you?" / "София, кто ты?" */
    fun asksWhoSheIs(said: String): Boolean {
        val rest = afterName(said)?.joinToString(" ") ?: return false
        return listOf("who are you", "what is your name", "what s your name", "kto ty", "kto vy", "kak tebya zovut").any { rest.startsWith(it) }
    }

    const val INTRO = "I'm Sofia, the counter assistant."
    const val INTRO_RU = "Я София, помощница на приёмке."

    /** "Sofia, can you hear me?" / "София, ты меня слышишь?" -> a short "yes, listening". */
    fun asksIfSheHears(said: String): Boolean {
        val rest = afterName(said)?.joinToString(" ") ?: return false
        return listOf("can you hear me", "do you hear me", "are you there", "are you listening", "ty menya slyshish",
            "ty slyshish", "vy menya slyshite", "ty tut", "slyshish").any { rest.startsWith(it) }
    }

    fun hearsReply(russian: Boolean) = if (russian) "Да, слушаю." else "Yes, I'm listening."

    /**
     * Text after the command ("" = Sofia was called but the number is still to come), or null when Sofia
     * wasn't addressed. After her name: any find phrase + number, or just the number ("Sofia, 8438").
     */
    fun commandTail(said: String): String? {
        val rest = afterName(said) ?: return null
        if (rest.isEmpty()) return ""
        val text = rest.joinToString(" ")
        triggerTail(text)?.let { return it }
        if (parseQueries(text).any { it !is Query.Name }) return text
        return if (rest.all { OBJECT_PREFIXES.any { p -> it.startsWith(p) } || it in FILLER_N }) "" else null
    }

    /** The find phrase rules, applied to what was said after "Sofia". */
    private fun triggerTail(said: String): String? {
        var text = " " + normalize(said) + " "
        for (ph in WEAK_PHRASES) text = text.replace(" $ph ", " ${ph.replace(' ', '_')} ")
        val words = text.trim().split(' ').filter { it.isNotEmpty() }
            .let { w -> w.indices.mapNotNull { i -> if (w[i] == "look" && w.getOrNull(i + 1) == "up") null
                else if (w[i] == "up" && w.getOrNull(i - 1) == "look") "lookup" else w[i] } }
        fun isObject(n: String) = OBJECT_PREFIXES.any { n.startsWith(it) }
        for (i in words.indices) {
            if (words[i] in WEAK && i <= TRIGGER_WITHIN) {
                val tail = words.drop(i + 1).joinToString(" ")
                if (parseQueries(tail).any { it !is Query.Name }) return tail
                continue
            }
            if (words[i] !in FIND) continue
            val rest = words.drop(i + 1)
            val tail = rest.joinToString(" ")
            val strong = rest.take(3).any(::isObject) // "find receipt …" counts anywhere
            if (i > TRIGGER_WITHIN && !strong) continue
            if (parseQueries(tail).any { it !is Query.Name }) return tail
            // Trigger and maybe "receipt / number", the number still to come ("Найди" … "заказ 4742").
            if (rest.all { isObject(it) || it in FILLER_N }) return ""
            if (strong) return tail
        }
        return null
    }

    // ---- follow-up questions ----

    private val TOPIC_PHRASES = listOf(
        Topic.TAILOR to listOf("who was the tailor", "who is the tailor", "who did it", "who did the work", "which tailor",
            "who worked on it", "kto master", "kto byl master", "kto delal", "kto shil", "kakoy master"),
        Topic.AMOUNT to listOf("how much", "what is the total", "what s the total", "the total", "what is the price", "price",
            "skolko", "kakaya summa", "summa", "tsena"),
        Topic.PAY to listOf("paid or due", "is it paid", "was it paid", "is there a balance", "balance", "do they owe",
            "oplacheno", "oplachen", "est dolg", "dolg"),
        Topic.DATE to listOf("when was it dropped off", "when did they drop", "drop off date", "dropped off", "when was it brought",
            "when did they bring", "kogda sdali", "kogda prinesli", "kogda sdan"),
        Topic.WORK to listOf("what was the work", "what work", "what is the job", "what s the job", "what was done", "what did we do",
            "description", "chto delali", "kakaya rabota", "chto za rabota", "chto delat"),
    )

    /** A follow-up question, or null. Works with or without "Sofia" in front. */
    fun followUp(said: String): Topic? {
        val text = " " + words(said).filter { it !in SOFIA }.joinToString(" ") + " "
        return TOPIC_PHRASES.firstOrNull { (_, ps) -> ps.any { text.contains(" $it ") } }?.first
    }

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September",
        "October", "November", "December")

    private val MONTHS_RU = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
        "октября", "ноября", "декабря")

    fun followUpAnswer(topic: Topic, c: Card, russian: Boolean): String = if (!russian) followUpAnswer(topic, c) else when (topic) {
        Topic.TAILOR -> if (c.tailor.isNotBlank()) "Мастер — ${c.tailor}." else "Ещё не готово, мастера пока нет."
        Topic.AMOUNT -> if (c.total.isNotBlank()) "Сумма ${c.total}." else "Суммы не вижу."
        Topic.PAY -> when (c.pay) {
            Pay.PAID -> "Оплачено."; Pay.DUE -> "Есть долг."; Pay.ACCOUNT -> "На счёте магазина."
            Pay.UNKNOWN -> "Не вижу, как оплачено."
        }
        Topic.DATE -> dateKey(c.date).let { k -> if (k == 0) "Не вижу дату сдачи." else "Сдали ${k % 100} ${MONTHS_RU[k / 100 % 100 - 1]}." }
        Topic.WORK -> if (c.work.isNotBlank()) "Работа: ${c.work}." else "Описания не вижу."
    }

    /** Sofia's answer, one natural English sentence. Never the customer, the phone or the account. */
    fun followUpAnswer(topic: Topic, c: Card): String = when (topic) {
        Topic.TAILOR -> if (c.tailor.isNotBlank()) "The tailor was ${c.tailor}." else "It's not finished yet, there's no tailor on it."
        Topic.AMOUNT -> if (c.total.isNotBlank()) "The total is ${c.total}." else "I don't see a total for it."
        Topic.PAY -> when (c.pay) {
            Pay.PAID -> "It's paid."; Pay.DUE -> "There is a balance due."; Pay.ACCOUNT -> "It's on the store account."
            Pay.UNKNOWN -> "I don't see how it was paid."
        }
        Topic.DATE -> dateKey(c.date).let { k ->
            if (k == 0) "I don't see the drop-off date." else "It was dropped off on ${MONTHS[k / 100 % 100 - 1]} ${k % 100}."
        }
        Topic.WORK -> if (c.work.isNotBlank()) "The work is: ${c.work}." else "I don't see a description."
    }

    fun didntCatch(russian: Boolean) = if (russian) "Не расслышал номер." else "I didn't catch the number."

    // ---- matching ----

    fun find(query: Query, orders: List<Order>): List<Card> {
        val hits = orders.filter { o ->
            val d = digits(o.phone)
            when (query) {
                is Query.Last4 -> d.length >= 4 && d.takeLast(4) == query.digits
                is Query.FullPhone -> d.length >= 7 && d.takeLast(7) == query.digits.takeLast(7)
                is Query.Receipt -> digits(o.receipt) == query.number
                is Query.Name -> nameMatches(query.words, o.customer)
            }
        }
        return hits
            .sortedWith(compareByDescending<Order> { dateKey(it.date) }.thenByDescending { digits(it.receipt).toLongOrNull() ?: 0L })
            .map { Card(it.receipt, it.customer, prettyPhone(it.phone), it.ready, it.pay, it.tailor, it.total, it.date, it.work) }
    }

    fun findAll(said: String, orders: List<Order>): List<Card> =
        parseQueries(said).flatMap { find(it, orders) }.distinctBy { it.receipt }

    /** Every spoken word must match (prefix, or one typo for longer words) some word of the customer name. */
    fun nameMatches(words: List<String>, customer: String): Boolean {
        val target = normalize(customer).split(' ').filter { it.isNotEmpty() }
        if (target.isEmpty()) return false
        // Short scraps ("to", "a") would prefix-match half the list, so a word needs 3+ letters.
        return words.all { w -> w.length >= 3 && target.any { t -> t.startsWith(w) || (w.length >= 5 && editDistance(w, t) <= 1) } }
    }

    // ---- speech out ----

    private val EN_DIGITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val RU_DIGITS = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")

    /** "26253" -> "two, six, two, five, three" (or Russian). */
    fun spellDigits(number: String, russian: Boolean): String =
        digits(number).map { (if (russian) RU_DIGITS else EN_DIGITS)[it - '0'] }.joinToString(", ")

    /**
     * Spoken answer: once, in English only (Art: a longer answer made the app miss the next customer), as ONE
     * natural sentence per receipt (Art: no word-by-word pauses): the receipt number as quick single digits,
     * ready / not finished, then paid / due / account from the records (nothing when unknown). Never the
     * phone, never "picked up". Up to 3 receipts; the rest are on screen. Uses the phone's default voice.
     */
    fun reply(cards: List<Card>, russian: Boolean) = if (russian) replyRu(cards) else replyEn(cards)

    /** The same answer in Russian: "Квитанция 2 6 3 1 5 готова к выдаче, есть долг." */
    fun replyRu(cards: List<Card>): String = cards.take(3).joinToString(" ") {
        val pay = when (it.pay) {
            Pay.PAID -> ", оплачено"; Pay.DUE -> ", есть долг"; Pay.ACCOUNT -> ", на счёте магазина"; Pay.UNKNOWN -> ""
        }
        "Квитанция ${digits(it.receipt).toCharArray().joinToString(" ")} " +
            (if (it.ready) "готова к выдаче" else "ещё не готова") + "$pay."
    }

    fun replyEn(cards: List<Card>): String = cards.take(3).joinToString(" ") {
        val pay = when (it.pay) {
            Pay.PAID -> ", it's paid"; Pay.DUE -> ", there is a balance due"; Pay.ACCOUNT -> ", it's on the store account"
            Pay.UNKNOWN -> ""
        }
        "Receipt ${digits(it.receipt).toCharArray().joinToString(" ")} " +
            (if (it.ready) "is ready for pickup" else "is not finished yet") + "$pay."
    }

    fun notFound(russian: Boolean) = if (russian) "Не могу найти этот заказ." else "I can't find this order."

    // ---- helpers ----

    fun digits(s: String) = s.filter { it.isDigit() }

    fun prettyPhone(raw: String): String {
        val d = digits(raw)
        val ten = if (d.length == 11 && d.startsWith("1")) d.drop(1) else d
        return if (ten.length == 10) "(${ten.substring(0, 3)}) ${ten.substring(3, 6)}-${ten.substring(6)}" else raw.trim()
    }

    /** "10/10/26" or "10-10-2026" -> 20261010; anything else -> 0. */
    fun dateKey(s: String): Int {
        val m = Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})").find(s.trim()) ?: return 0
        val (mo, da, yr) = m.destructured
        val y = if (yr.length == 2) 2000 + yr.toInt() else yr.toInt()
        return y * 10000 + mo.toInt() * 100 + da.toInt()
    }

    private val CYR = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya",
    )

    /** Lowercase, Cyrillic -> Latin, accents and punctuation removed, so "Андреа" == "Andrea". */
    fun normalize(s: String): String {
        val lower = s.lowercase()
        val latin = buildString { for (ch in lower) append(CYR[ch] ?: ch.toString()) }
        val plain = Normalizer.normalize(latin, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
