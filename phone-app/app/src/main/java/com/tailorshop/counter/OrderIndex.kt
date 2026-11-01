package com.tailorshop.counter

// The compact "Order Index" tab in Order Ready For Pickup (written only by the Pickups projection; the phone
// reads it, read-only). One row per open-or-recent receipt with its authoritative lifecycle state, so Find
// can also answer for orders that aren't ready yet (promised time) or were already collected — without
// downloading the cumulative MASTER. Contract: Alexa issue #4, 6554141013. Columns are found by header.
object OrderIndex {
    enum class State { READY, NOT_READY, COLLECTED, UNKNOWN }

    data class Entry(val receipt: String, val phone: String, val customer: String, val state: State,
                     val promised: String, val collectedAt: String, val updatedAt: String)

    /** rows[0] = header. Unknown status words are UNKNOWN, never guessed. Rows without a receipt are skipped. */
    fun parse(rows: List<List<String>>): List<Entry> {
        if (rows.isEmpty()) return emptyList()
        val h = rows[0].map { it.trim().lowercase() }
        fun col(name: String) = h.indexOf(name).takeIf { it >= 0 }
        val r = col("receipt #") ?: return emptyList()
        val p = col("phone"); val c = col("customer"); val s = col("status")
        val pr = col("promised"); val ca = col("collected at"); val u = col("updated at")
        fun cell(row: List<String>, i: Int?) = if (i == null) "" else row.getOrNull(i)?.trim().orEmpty()
        return rows.drop(1).mapNotNull { row ->
            val receipt = cell(row, r).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val state = State.values().firstOrNull { it.name == cell(row, s).uppercase() } ?: State.UNKNOWN
            Entry(receipt, cell(row, p), cell(row, c), state, cell(row, pr), cell(row, ca), cell(row, u))
        }
    }

    /** Index rows for these last four phone digits (leading zeros kept), newest receipt first. */
    fun find(last4: String, entries: List<Entry>): List<Entry> =
        entries.filter { Lookup.digits(it.phone).let { d -> d.length >= 4 && d.takeLast(4) == last4 } }
            .sortedByDescending { Lookup.digits(it.receipt).toLongOrNull() ?: 0L }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "2026-11-03 16:00" -> "Nov 3, 4:00 PM" (already shop time); anything else is shown as written. */
    fun pretty(s: String): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{1,2}):(\\d{2}))?").find(s.trim()) ?: return s.trim()
        val (_, mo, d, hh, mm) = m.destructured
        val date = "${MONTHS[mo.toInt() - 1]} ${d.toInt()}"
        if (hh.isEmpty()) return date
        val h = hh.toInt(); val ampm = if (h < 12) "AM" else "PM"; val h12 = if (h % 12 == 0) 12 else h % 12
        return "$date, $h12:$mm $ampm"
    }

    /** One plain line for the screen. A missing promised time is never turned into "late". */
    fun stateText(e: Entry): String = when (e.state) {
        State.READY -> "Ready for pickup"
        State.NOT_READY -> if (e.promised.isNotBlank()) "Not ready yet · promised ${pretty(e.promised)}" else "Not ready yet · no promised time on the receipt"
        State.COLLECTED -> if (e.collectedAt.isNotBlank()) "Already picked up · ${pretty(e.collectedAt)}" else "Already picked up"
        State.UNKNOWN -> "Status unknown — check the tablet"
    }

    /** True when the index row is older than 24 h ("Updated At" in shop time, YYYY-MM-DD HH:MM). */
    fun stale(e: Entry, nowShop: java.time.LocalDateTime): Boolean {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})").find(e.updatedAt.trim()) ?: return true
        val (y, mo, d, hh, mm) = m.destructured
        val at = java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), hh.toInt(), mm.toInt())
        return java.time.Duration.between(at, nowShop).toHours() >= 24
    }
}
