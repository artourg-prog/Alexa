package com.tailorshop.counter

import java.text.Normalizer

// Pure logic: sheet rows + what was said -> order cards. No network, no writes.
//
// Source: "Order Ready For Pickup" / tab "All Receipts" (the Pickups mirror: only
// orders not yet picked up). Columns are found by header name, so a moved column
// can't shift data into the wrong field. Only receipt, customer and phone are shown,
// plus ready / not finished: column "M.T." holds the tailor's name once the work is done
// (Art's rule), so a filled M.T. = ready for pickup, blank = not finished yet.

data class Order(val receipt: String, val customer: String, val phone: String, val date: String, val ready: Boolean = false)

data class Card(val receipt: String, val customer: String, val phone: String, val ready: Boolean = false)

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
    )

    // Words people say around the actual name or number.
    private val FILLER = setOf(
        "find", "look", "up", "lookup", "search", "check", "show", "order", "orders", "receipt",
        "receipts", "customer", "client", "phone", "number", "for", "of", "the", "a", "please", "and",
        "last", "four", "digits", "is", "it", "name",
        "найди", "найти", "покажи", "проверь", "поиск", "заказ", "заказы", "квитанция", "квитанцию",
        "клиент", "клиента", "клиентка", "телефон", "номер", "для", "на", "и", "пожалуйста", "имя",
        "последние", "цифры", "это", "ресит", "ресита", "рисит", "квитанции", "with", "by", "on", "my",
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
            else Order(receipt, cell(row, idx["customer"]), cell(row, p), cell(row, idx["date"]), cell(row, idx["tailor"]).isNotEmpty())
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
        val all = groups.joinToString("")
        return when {
            all.length == 4 -> listOf(Query.Last4(all))
            all.length == 5 -> listOf(Query.Receipt(all))
            all.length == 6 -> listOf(Query.Last4(all.takeLast(4)))
            all.length >= 7 -> listOf(Query.FullPhone(all.takeLast(10)))
            else -> emptyList() // 1-3 digits: not a phone ending or receipt
        }
    }

    private val FILLER_N by lazy { FILLER.map(::normalize).toSet() }

    // The command is the wake word (Art's choice): the app acts only on "find receipt / find
    // order …", "check …", "найди заказ / ресит / квитанцию …". Everything else is ignored.
    // "fine"/"faynd" and "chek" are how the recognizer often writes "find" and "check".
    private val FIND = setOf("find", "fine", "faynd", "fayind", "naydi", "nayti", "naydite")
    private val OBJECT_PREFIXES = listOf("receipt", "order", "resi", "risi", "zakaz", "kvitan", "nomer", "number", "namber")
    private val CHECK = setOf("check", "chek", "checks", "prover", "proverte")

    /** Text after the command, or null when there is no command in what was said. */
    fun commandTail(said: String): String? {
        val words = normalize(said).split(' ').filter { it.isNotEmpty() }
        fun isObject(n: String) = OBJECT_PREFIXES.any { n.startsWith(it) }
        for (i in words.indices) {
            val w = words[i]
            val isFind = w in FIND && words.drop(i + 1).take(3).any(::isObject)
            if (isFind || w in CHECK) return words.drop(i + 1).joinToString(" ")
        }
        // The recognizer often splits a pause: "Найди" … "заказ 4742". A bare "find" waits
        // for the rest (empty tail), and a short "order / заказ <number>" counts on its own.
        if (words.isNotEmpty() && words.last() in FIND && words.size <= 2) return ""
        if (words.size <= 5) {
            val o = words.indexOfFirst(::isObject)
            if (o >= 0) {
                val rest = words.drop(o + 1).joinToString(" ")
                if (parseQueries(rest).any { it !is Query.Name }) return rest
            }
        }
        return null
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
            .map { Card(it.receipt, it.customer, prettyPhone(it.phone), it.ready) }
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
     * Spoken answer: once, in English only (Art: a longer answer made the app miss the next
     * customer): the receipt number digit by digit and ready / not finished. Never the
     * phone, never "picked up". Up to 3 receipts; the rest are on screen.
     */
    fun replyEn(cards: List<Card>): String = cards.take(3).joinToString(" ") {
        "Receipt ${spellDigits(it.receipt, false)} — " + (if (it.ready) "ready for pickup." else "work is not finished yet.")
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
