package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

// Synthetic rows only (made-up names and numbers).
class OrderIndexTest {
    private val rows = listOf(
        listOf("Updated At", "Receipt #", "Customer", "Phone", "Status", "Promised", "Collected At"), // any column order
        listOf("2026-11-01 09:00", "50001", "Test A", "702 555 0042", "NOT_READY", "2026-11-03 16:00", ""),
        listOf("2026-11-01 09:00", "50002", "Test B", "702 555 0042", "COLLECTED", "", "2026-11-01 11:30"),
        listOf("2026-11-01 09:00", "50003", "Test C", "702 555 7777", "weird", "", ""),
        listOf("2026-10-29 08:00", "50004", "Test D", "702 555 0042", "NOT_READY", "", ""),
        listOf("2026-11-01 09:00", "", "No receipt", "702 555 0042", "READY", "", ""),
    )
    private val idx = OrderIndex.parse(rows)

    @Test fun parsesByHeaderAndNeverGuessesStatus() {
        assertEquals(4, idx.size)
        assertEquals(OrderIndex.State.UNKNOWN, idx.first { it.receipt == "50003" }.state)
    }

    @Test fun findKeepsLeadingZeroAndAllMatches() =
        assertEquals(listOf("50004", "50002", "50001"), OrderIndex.find("0042", idx).map { it.receipt })

    @Test fun eachStateIsDistinctAndHonest() {
        assertEquals("Not ready yet · promised Nov 3, 4:00 PM", OrderIndex.stateText(idx.first { it.receipt == "50001" }))
        assertEquals("Already picked up · Nov 1, 11:30 AM", OrderIndex.stateText(idx.first { it.receipt == "50002" }))
        assertEquals("Status unknown — check the tablet", OrderIndex.stateText(idx.first { it.receipt == "50003" }))
        assertEquals("Not ready yet · no promised time on the receipt", OrderIndex.stateText(idx.first { it.receipt == "50004" }))
    }

    @Test fun freshness() {
        val now = LocalDateTime.of(2026, 11, 1, 12, 0)
        assertFalse(OrderIndex.stale(idx.first { it.receipt == "50001" }, now))
        assertTrue(OrderIndex.stale(idx.first { it.receipt == "50004" }, now))
    }

    @Test fun noTabMeansNothing() = assertTrue(OrderIndex.parse(emptyList()).isEmpty())
}
