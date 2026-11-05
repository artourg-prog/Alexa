package com.tailorshop.counter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Owner 11-04, #4: the Find column. Synthetic receipts and digits only. */
class FindLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("America/Los_Angeles")
    private val noon = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private val current = "CURRENT"
    private val none = { _: FindLog.Entry -> null as Pickups.Req? }

    private fun ready(n: String, txn: String = "", prior: Int = 0) = FindLog.Hit(n, txn, FindLog.READY, prior)
    private fun add(log: FindLog, last4: String, vararg hits: FindLog.Hit, at: Long = noon, list: String = current, asOf: String = "", offline: Boolean = false) =
        log.add(last4, hits.toList(), list, asOf, offline, at)
    private fun tap(n: String, status: String, at: Long, txn: String = "") =
        Pickups.Req("t", n, at, sent = true, status = status, durable = true, txn = txn)

    @Test fun `newest first, with time, number and outcome, and only the last four kept`() {
        val dir = tmp.newFolder(); val log = FindLog(dir)
        add(log, "1111", ready("20001"))
        add(log, "2222", FindLog.Hit("20002", "", FindLog.NOT_READY), at = noon + minute)
        add(log, "3333", FindLog.Hit("", "", FindLog.NOT_FOUND), at = noon + 2 * minute)
        val rows = log.rows(none, noon + 3 * minute)
        assertEquals(listOf("", "20002", "20001"), rows.map { it.entry.receipt })
        assertEquals(listOf("Not found", "Not ready", "Ready for pickup"), rows.map { it.line })
        assertEquals("12:00 PM", rows.last().time)
        assertFalse(File(dir, "find-log.json").readText().contains("phone"))
    }

    @Test fun `a restart keeps the rows`() {
        val dir = tmp.newFolder()
        add(FindLog(dir), "1111", ready("20001"))
        add(FindLog(dir), "3333", FindLog.Hit("", "", FindLog.NOT_FOUND), at = noon + minute)
        assertEquals(listOf("", "20001"), FindLog(dir).rows(none, noon + 2 * minute).map { it.entry.receipt })
    }

    @Test fun `a repeat search moves the request up instead of adding a row`() {
        val log = FindLog(tmp.newFolder())
        add(log, "1111", ready("20001")); add(log, "2222", ready("20002"), at = noon + minute); add(log, "1111", ready("20001"), at = noon + 2 * minute)
        assertEquals(listOf("20001", "20002"), log.rows(none, noon + 3 * minute).map { it.entry.receipt })
    }

    @Test fun `yesterday's requests are not in today's column but stay in the history`() {
        val log = FindLog(tmp.newFolder()); val tomorrow = noon + 24 * 60 * minute
        add(log, "1111", ready("20001"))
        assertTrue(log.rows(none, tomorrow).isEmpty()); assertEquals(1, log.history().size)
    }

    @Test fun `offline, old and unknown lists are said, and a failed search is a check, not a miss`() {
        val log = FindLog(tmp.newFolder())
        add(log, "1111", ready("20001"), offline = true)
        add(log, "2222", ready("20002"), list = "OLD", asOf = "Nov 2, 3:56 PM")
        add(log, "3333", ready("20003"), list = "UNKNOWN")
        add(log, "4444", FindLog.Hit("", "", FindLog.NO_ANSWER), offline = true)
        val by = log.rows(none, noon).associateBy { it.entry.last4 }
        assertEquals("Ready for pickup · offline copy, may be out of date", by["1111"]!!.line)
        assertEquals("Ready for pickup · list as of Nov 2, 3:56 PM, may be out of date", by["2222"]!!.line)
        assertEquals("Ready for pickup · list age unknown", by["3333"]!!.line)
        assertEquals("Not checked — no connection", by["4444"]!!.line)
        assertTrue(by.values.all { it.unsure })
    }

    @Test fun `looking up or matching four digits never confirms a pickup`() {
        val log = FindLog(tmp.newFolder())
        add(log, "1111", ready("20001")); add(log, "1111", ready("20001"), at = noon + minute)
        log.settleTaps(none, noon + 2 * minute)
        log.settleList({ FindLog.Seen(FindLog.READY, 0, "") }, current, "", noon + 2 * minute)
        assertEquals(listOf("Ready for pickup"), log.rows(none, noon + 3 * minute).map { it.line })
    }

    @Test fun `a tap still waiting stays in the column and says so`() {
        val log = FindLog(tmp.newFolder()); add(log, "1111", ready("20001"))
        val waiting = { _: FindLog.Entry -> tap("20001", "QUEUED", noon + minute) }
        log.settleTaps(waiting, noon + 2 * minute)
        val row = log.rows(waiting, noon + 2 * minute).single()
        assertEquals("Picked up — sent, waiting for the tablet", row.line); assertTrue(row.unsure)
    }

    @Test fun `the tablet's answer marks the row picked up and keeps it for today`() {
        val dir = tmp.newFolder(); val log = FindLog(dir); add(log, "1111", ready("20001")); add(log, "2222", ready("20002"))
        log.settleTaps({ e -> if (e.receipt == "20001") tap("20001", "APPLIED", noon + minute) else null }, noon + 2 * minute)
        assertEquals(setOf("Picked up", "Ready for pickup"), log.rows(none, noon + 3 * minute).map { it.line }.toSet())
        val kept = FindLog(dir)
        assertEquals("Picked up", kept.rows(none, noon + 3 * minute).first { it.entry.receipt == "20001" }.line)
        assertTrue(kept.history().map(FindLog::historyLine).any { "#20001" in it && "Picked up — confirmed by the tablet" in it })
    }

    @Test fun `a tap on a reused number from before the request confirms nothing without its Txn`() {
        val log = FindLog(tmp.newFolder()); add(log, "1111", ready("20001"))
        log.settleTaps({ tap("20001", "APPLIED", noon - minute) }, noon + minute)
        assertEquals(1, log.rows(none, noon + minute).size)
        val bound = FindLog(tmp.newFolder()); add(bound, "1111", ready("20001", "TX-A"))
        bound.settleTaps({ tap("20001", "APPLIED", noon + minute, "TX-B") }, noon + minute)
        assertEquals(1, bound.rows(none, noon + minute).size)
        bound.settleTaps({ tap("20001", "APPLIED", noon - minute, "TX-A") }, noon + minute)
        assertEquals("Picked up", bound.rows(none, noon + minute).single().line)
    }

    @Test fun `a new picked-up row on the shared list confirms, one that was already there does not`() {
        val log = FindLog(tmp.newFolder())
        add(log, "1111", ready("20001")); add(log, "2222", ready("20002", prior = 1))
        log.settleList({ e -> FindLog.Seen(FindLog.READY, 1, "2026-11-04 12:30") }, current, "", noon + minute)
        val by = log.rows(none, noon + 2 * minute).associateBy { it.entry.last4 }
        assertEquals("Picked up · Nov 4, 12:30 PM", by["1111"]!!.line); assertTrue(by["2222"]!!.entry.pickedMs == 0L)
        assertTrue(FindLog.historyLine(log.history().first { it.last4 == "1111" }).contains("Picked up — on the shared list · Nov 4, 12:30 PM"))
    }

    @Test fun `leaving the ready list is not a pickup, and a newer answer replaces the old one`() {
        val log = FindLog(tmp.newFolder())
        add(log, "1111", ready("20001")); add(log, "2222", FindLog.Hit("20002", "", FindLog.NOT_READY))
        log.settleList({ e -> if (e.last4 == "1111") FindLog.Seen(null, 0, "") else FindLog.Seen(FindLog.READY, 0, "") }, current, "", noon + minute)
        val by = log.rows(none, noon + minute).associateBy { it.entry.last4 }
        assertEquals("Ready for pickup", by["1111"]!!.line); assertEquals("Ready for pickup", by["2222"]!!.line)
    }

    @Test fun `past pickups beside an open order are left out, alone only the newest is shown`() {
        val log = FindLog(tmp.newFolder()); val old = { n: String -> FindLog.Hit(n, "", FindLog.COLLECTED) }
        add(log, "1111", old("20001"), ready("20002"))
        add(log, "2222", old("20003"), old("20004"))
        val by = log.rows(none, noon).associateBy { it.entry.last4 }
        assertEquals("20002", by["1111"]!!.entry.receipt)
        assertEquals("20003", by["2222"]!!.entry.receipt); assertEquals("Already picked up", by["2222"]!!.line)
        log.settleList({ FindLog.Seen(null, 5, "") }, current, "", noon)
        assertEquals("Already picked up", log.rows(none, noon).first { it.entry.last4 == "2222" }.line) // never a new pickup
    }

    @Test fun `an unreadable file is set aside and said, and a failed save is said`() {
        val dir = tmp.newFolder(); File(dir, "find-log.json").writeText("not json")
        val log = FindLog(dir)
        assertNotNull(log.warning()); assertTrue(dir.list()!!.any { it.startsWith("find-log.unreadable-") })
        val stuck = FindLog(tmp.newFolder(), replace = { _, _ -> false })
        assertFalse(add(stuck, "1111", ready("20001"))); assertNotNull(stuck.warning())
        assertEquals(1, stuck.rows(none, noon).size)
    }

    @Test fun `today's list keeps numbers and amounts newest first, across a restart, and starts empty the next shop day`() {
        val dir = tmp.newFolder(); val log = FindLog(dir)
        add(log, "1111", ready("20001").copy(amount = "$45.00")); add(log, "2222", ready("20002").copy(amount = "$120.50"), at = noon + minute)
        val rows = FindLog(dir).rows(none, noon + 2 * minute)
        assertEquals(listOf("20002" to "$120.50", "20001" to "$45.00"), rows.map { it.entry.receipt to it.entry.amount })
        // Shop midnight, not UTC: 11:59 PM in Los Angeles is still today, a minute later is tomorrow; nothing is deleted.
        val shopDay = java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(noon), java.time.ZoneId.of("America/Los_Angeles"))
        val midnight = shopDay.plusDays(1).atStartOfDay(java.time.ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        assertEquals(2, FindLog(dir).rows(none, midnight - minute).size)
        assertTrue(FindLog(dir).rows(none, midnight).isEmpty()); assertEquals(2, FindLog(dir).history().size)
    }

    @Test fun `searching again keeps one row, its amount survives a list without one, and a confirmed pickup stays one`() {
        val log = FindLog(tmp.newFolder()); add(log, "1111", ready("20001").copy(amount = "$45.00")); add(log, "1111", ready("20001"), at = noon + minute)
        assertEquals("$45.00", log.rows(none, noon + 2 * minute).single().entry.amount)
        log.settleTaps({ tap("20001", "APPLIED", noon + minute) }, noon + 2 * minute)
        add(log, "1111", ready("20001"), at = noon + 3 * minute)
        val row = log.rows(none, noon + 4 * minute).single()
        assertEquals("Picked up", row.line); assertEquals(noon + 3 * minute, row.entry.atMs)
        val early = FindLog(tmp.newFolder()); add(early, "1111", FindLog.Hit("20001", "", FindLog.NOT_READY)) // first on the index, then ready with its Txn
        add(early, "1111", ready("20001", "TX-A").copy(amount = "$45.00"), at = noon + minute)
        assertEquals("TX-A" to "$45.00", early.rows(none, noon + 2 * minute).single().entry.let { it.txn to it.amount })
        val bound = FindLog(tmp.newFolder()); add(bound, "1111", ready("20001", "TX-A").copy(amount = "$45.00"))
        bound.settleTaps({ tap("20001", "APPLIED", noon + minute, "TX-A") }, noon + 2 * minute)
        add(bound, "1111", FindLog.Hit("20001", "", FindLog.COLLECTED), at = noon + 3 * minute) // now on the index only, without its Txn
        assertEquals("$45.00", bound.rows(none, noon + 4 * minute).single().entry.amount)
    }
}
