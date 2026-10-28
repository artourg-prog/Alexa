package com.tailorshop.counter

import java.text.Normalizer

// Pure logic: sheet rows + what was said -> order cards. No network, no writes.
//
// Source: "Order Ready For Pickup" / tab "All Receipts" (the Pickups mirror: only
// orders not yet picked up). Columns are found by header name, so a moved column
// can't shift data into the wrong field. Only receipt, customer and phone are shown.

data class Order(val receipt: String, val customer: String, val phone: String, val date: String)

data class Card(val receipt: String, val customer: String, val phone: String)

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
    )

    // Words people say around the actual name or number.
    private val FILLER = setOf(
        "find", "look", "up", "lookup", "search", "check", "show", "order", "orders", "receipt",
        "receipts", "customer", "client", "phone", "number", "for", "of", "the", "a", "please", "and",
        "last", "four", "digits", "is", "it", "name",
        "найди", "найти", "покажи", "проверь", "поиск", "заказ", "заказы", "квитанция", "квитанцию",
        "клиент", "клиента", "клиентка", "телефон", "номер", "для", "на", "и", "пожалуйста", "имя",
        "последние", "цифры", "это",
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
            else Order(receipt, cell(row, idx["customer"]), cell(row, p), cell(row, idx["date"]))
        }
    }

    // ---- what was said ----

    /**
     * One utterance -> one or more queries.
     * "1034" -> last 4; "26240" -> receipt; "1034 and 1203" -> two; "702 555 1034" -> full phone;
     * no digits -> customer name.
     */
    fun parseQueries(said: String): List<Query> {
        val groups = Regex("\\d+").findAll(said).map { it.value }.toList()
        if (groups.isEmpty()) {
            val words = normalize(said).split(' ').filter { it.isNotEmpty() && it !in FILLER.map(::normalize) }
            return if (words.isEmpty()) emptyList() else listOf(Query.Name(words))
        }
        if (groups.all { it.length == 4 || it.length == 5 }) {
            return groups.distinct().take(3).map { if (it.length == 4) Query.Last4(it) else Query.Receipt(it) }
        }
        val all = groups.joinToString("")
        return when {
            all.length == 4 -> listOf(Query.Last4(all))
            all.length == 5 -> listOf(Query.Receipt(all))
            all.length >= 7 -> listOf(Query.FullPhone(all.takeLast(10)))
            else -> listOf(Query.Last4(all.padStart(4, '0')))
        }
    }

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
            .map { Card(it.receipt, it.customer, prettyPhone(it.phone)) }
    }

    fun findAll(said: String, orders: List<Order>): List<Card> =
        parseQueries(said).flatMap { find(it, orders) }.distinctBy { it.receipt }

    /** Every spoken word must match (prefix, or one typo for longer words) some word of the customer name. */
    fun nameMatches(words: List<String>, customer: String): Boolean {
        val target = normalize(customer).split(' ').filter { it.isNotEmpty() }
        if (target.isEmpty()) return false
        return words.all { w -> target.any { t -> t.startsWith(w) || (w.length >= 4 && editDistance(w, t) <= 1) } }
    }

    // ---- speech out ----

    fun reply(cards: List<Card>, russian: Boolean): String {
        if (cards.isEmpty()) return if (russian) "Не могу найти этот заказ." else "I can't find this order."
        val parts = cards.take(3).map { c ->
            val phone = digits(c.phone).takeLast(4).toCharArray().joinToString(" ")
            if (russian) "Квитанция ${c.receipt}, ${c.customer}, телефон на $phone"
            else "Receipt ${c.receipt}, ${c.customer}, phone ending $phone"
        }
        val head = when {
            cards.size == 1 -> ""
            russian -> "${cards.size} ${ordersRu(cards.size)}. "
            else -> "${cards.size} orders. "
        }
        val more = if (cards.size > 3) (if (russian) " И ещё ${cards.size - 3} на экране." else " And ${cards.size - 3} more on screen.") else ""
        return head + parts.joinToString(". ") + "." + more
    }

    private fun ordersRu(n: Int): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "заказ"
            m10 in 2..4 && m100 !in 12..14 -> "заказа"
            else -> "заказов"
        }
    }

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
