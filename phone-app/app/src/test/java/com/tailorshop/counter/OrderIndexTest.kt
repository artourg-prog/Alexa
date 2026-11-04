package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("Not marked finished yet · promised Nov 3, 4:00 PM · as of Nov 1, 9:00 AM", OrderIndex.stateText(idx.first { it.receipt == "50001" }))
        assertEquals("Order was picked up · Nov 1, 11:30 AM", OrderIndex.stateText(idx.first { it.receipt == "50002" }))
        assertEquals("Status unknown — check the tablet · as of Nov 1, 9:00 AM", OrderIndex.stateText(idx.first { it.receipt == "50003" }))
        assertEquals("Not marked finished yet · no promised time on the receipt · as of Oct 29, 8:00 AM", OrderIndex.stateText(idx.first { it.receipt == "50004" }))
    }

    // Picked Up Orders history (owner 11-02, #26438): a collected order is answered from history too.
    private val history = OrderIndex.parsePickedUp(listOf(
        listOf("Collected At", "Receipt #", "Customer", "Phone", "Source"),
        listOf("2026-11-02 13:40", "50005", "Test E", "702 555 0042", "Receipts"),   // only in history
        listOf("", "50006", "Test F", "702 555 0042", "Receipts"),                   // no time recorded
        listOf("2026-11-01 11:30", "50002", "Test B", "702 555 0042", "Receipts"),   // same as the index row
        listOf("2026-11-02 10:00", "50001", "Test A", "702 555 0042", "Receipts"),   // index says otherwise
    ))

    @Test fun historyAnswersCollectedWithItsRecordedTime() {
        val all = OrderIndex.withHistory(idx, history)
        assertEquals("Order was picked up · Nov 2, 1:40 PM", OrderIndex.stateText(all.single { it.receipt == "50005" }))
        assertEquals("Order was picked up · pickup time unknown", OrderIndex.stateText(all.single { it.receipt == "50006" }))
        assertEquals(1, all.count { it.receipt == "50002" })                      // duplicate collected row folded
    }

    @Test fun historyNeverOverwritesAnIndexRow() {
        val all = OrderIndex.withHistory(idx, history).filter { it.receipt == "50001" }
        assertEquals(listOf(OrderIndex.State.NOT_READY, OrderIndex.State.COLLECTED), all.map { it.state })   // both listed
    }

    @Test fun historyFillsAMissingIndexPickupTime() {
        val bare = OrderIndex.parse(listOf(listOf("Receipt #", "Phone", "Status"), listOf("50007", "702 555 0042", "COLLECTED")))
        val all = OrderIndex.withHistory(bare, OrderIndex.parsePickedUp(listOf(listOf("Collected At", "Receipt #", "Phone"), listOf("2026-11-02 12:00", "50007", "702 555 0042"))))
        assertEquals(listOf("Order was picked up · Nov 2, 12:00 PM"), all.map(OrderIndex::stateText))
    }

    @Test fun noTabMeansNothing() = assertTrue(OrderIndex.parse(emptyList()).isEmpty())
}
