package com.tailorshop.counter

import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

// How old the shop tablet's last publish to Order Ready For Pickup is, from the Sync Status tab (owner 11-03). The sheet can
// answer perfectly and still be a day old, with sync_state CONNECTED: a good read says nothing about the age of the data.
// All tabs are written in one publish, so this one age covers the ready list, Order Index and Picked Up Orders alike.
object ListAge {
    enum class Level { CURRENT, OLD, UNKNOWN }
    data class Status(val level: Level, val asOf: String = "")

    const val LIMIT_MINUTES = 30L // above the ~20 min sign-off → sheet path; owner may change it
    private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
    private val open: LocalTime = LocalTime.of(10, 45)
    private val close: LocalTime = LocalTime.of(19, 30)

    /** Nothing changes overnight, so outside shop hours (and just after opening) the list is judged at the last closing time. */
    private fun judgedAt(now: ZonedDateTime): ZonedDateTime {
        val t = now.toLocalTime()
        return when {
            !t.isBefore(close) -> now.with(close)
            t.isBefore(open.plusMinutes(LIMIT_MINUTES)) -> now.minusDays(1).with(close)
            else -> now
        }
    }

    /** rows = the Sync Status tab (Key | Value), null when it couldn't be read. Anything unexpected is UNKNOWN, never CURRENT. */
    fun of(rows: List<List<String>>?, now: ZonedDateTime): Status {
        val keys = rows.orEmpty().associate { it.getOrElse(0) { "" }.trim() to it.getOrElse(1) { "" }.trim() }
        val at = runCatching { OffsetDateTime.parse(keys["last_success_at"]).atZoneSameInstant(zone) }.getOrNull()
            ?: return Status(Level.UNKNOWN)
        val asOf = OrderIndex.pretty(at.toLocalDateTime().toString().replace('T', ' '))
        if (keys["sync_state"] != "CONNECTED") return Status(Level.UNKNOWN, asOf)
        val old = Duration.between(at, judgedAt(now.withZoneSameInstant(zone))).toMinutes() > LIMIT_MINUTES
        return Status(if (old) Level.OLD else Level.CURRENT, asOf)
    }

    /** What a search with no result may honestly say: "check the number" only when the list is current. */
    fun missText(age: Status): String = when (age.level) {
        Level.CURRENT -> "No matching receipt. Check the number."
        Level.OLD -> "Not in the list as of ${age.asOf}. The list may be out of date — check the tablet."
        Level.UNKNOWN -> "Not in the list, but the list's age is unknown. Check the tablet."
    }
}
