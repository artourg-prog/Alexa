package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Synthetic lists only (owner 11-08, Alexa #4 6717921168): the ready state is read fresh on every Find and reopened row;
// a copy is only a fallback that says it is one. No sheet, device or customer data.
class ListReadsTest {
    private val readyHeader = listOf("Date", "Receipt #", "Customer", "Phone", "M.T.", "Txn")
    private val indexHeader = listOf("Receipt #", "Phone", "Customer", "Status", "Promised", "Collected At", "Updated At")
    private val pickedHeader = listOf("Collected At", "Receipt #", "Customer", "Phone")
    private fun sync(at: String) = listOf(listOf("Key", "Value"), listOf("sync_state", "CONNECTED"), listOf("last_success_at", at))

    private val readyRow = listOf("2026-11-08", "1234", "Test A", "7025550101", "Emma", "T1")
    private val ready = Sheets.Lists(listOf(readyHeader, readyRow),
        listOf(indexHeader, listOf("1234", "7025550101", "Test A", "READY", "", "", "2026-11-08 12:00")),
        listOf(pickedHeader), sync("2026-11-08T12:00:00-08:00"))
    private val collected = Sheets.Lists(listOf(readyHeader),
        listOf(indexHeader, listOf("1234", "7025550101", "Test A", "COLLECTED", "", "2026-11-08 12:05", "2026-11-08 12:05")),
        listOf(pickedHeader, listOf("2026-11-08 12:05", "1234", "Test A", "7025550101")), sync("2026-11-08T12:05:00-08:00"))

    // 2026-11-08 12:10 shop time, inside shop hours
    private var now = 1794168600000L
    private var source: () -> Sheets.Lists = { ready }
    private var fetches = 0
    private val kept = mutableListOf<Long>()
    private val reads = ListReads({ fetches++; source() }, { _, at -> kept += at }, { now })

    private fun line(r: ListReads): String {
        val inReady = r.orders.any { it.receipt == "1234" }
        return OrderIndex.pickupLine(inReady, !r.offline, r.indexFresh, r.index.filter { it.receipt == "1234" }, r.listAge)
    }

    @Test fun aRepeatedFindReadsAgainEvenWhenTheNumberIsInTheCopy() {
        reads.refresh(now)
        assertTrue(reads.orders.single().ready); assertEquals("Not picked up", line(reads))
        source = { collected }; now += 5_000 // collected 5 s later: well inside the old one-minute reuse
        reads.refresh(now)
        assertEquals(2, fetches)
        assertTrue(reads.orders.isEmpty())
        assertEquals("Picked up · Nov 8, 12:05 PM", line(reads))
        assertFalse(OrderIndex.pickupAllowed(line(reads)))
    }

    @Test fun putBackShowsReadyAgainOnTheNextFind() {
        source = { collected }; reads.refresh(now)
        assertEquals("Picked up · Nov 8, 12:05 PM", line(reads))
        source = { ready }; now += 1_000
        reads.refresh(now)
        assertTrue(reads.orders.single().ready); assertEquals("Not picked up", line(reads))
    }

    @Test fun aPutBackWhoseHistoryRowIsStillThereIsAConflictNeverNotPickedUp() {
        source = { ready.copy(picked = collected.picked) }
        reads.refresh(now)
        assertEquals("Pickup status conflicting — check the tablet", line(reads))
        assertFalse(OrderIndex.pickupAllowed(line(reads)))
    }

    @Test fun offlineKeepsTheCopyAndSaysHowOldItIsThenReconnects() {
        reads.refresh(now)
        source = { throw java.io.IOException("no network") }; now += 30_000
        reads.refresh(now)
        assertTrue(reads.offline); assertFalse(reads.indexFresh)
        assertTrue(reads.orders.single().ready) // the copy is kept, never emptied
        assertEquals(ListAge.Level.UNKNOWN, reads.listAge.level)
        assertEquals("Nov 8, 12:00 PM", reads.listAge.asOf) // the tablet's last publish, not the time of the failed read
        assertEquals("Pickup status unavailable — couldn't refresh", line(reads))
        assertFalse(OrderIndex.pickupAllowed(line(reads)))
        source = { collected }; now += 30_000
        reads.refresh(now)
        assertFalse(reads.offline); assertTrue(reads.indexFresh)
        assertEquals("Picked up · Nov 8, 12:05 PM", line(reads))
    }

    @Test fun aSavedCopyIsNeverFreshAndItsAgeIsWhenItWasRead() {
        reads.restore(listOf(readyHeader, readyRow), 1794164400000L) // 2026-11-08 11:00 shop time
        source = { throw java.io.IOException("no network") }
        reads.refresh(now)
        assertTrue(reads.offline)
        assertEquals("Nov 8, 11:00 AM", reads.listAge.asOf)
        assertEquals("Pickup status unavailable — couldn't refresh", line(reads))
    }

    @Test fun noCopyAndNoNetworkIsAnErrorNotAnEmptyList() {
        source = { throw java.io.IOException("no network") }
        try { reads.refresh(now); fail("expected the failure") } catch (_: java.io.IOException) {}
        assertTrue(reads.offline); assertEquals(0L, reads.loadedAt)
    }

    @Test fun rapidReopenSharesOneReadThatStartedAfterBothTaps() {
        val tap1 = now; val tap2 = now
        reads.refresh(tap1); reads.refresh(tap2)
        assertEquals(1, fetches)
        val tap3 = now + 1 // asked after that read started: it reads again
        now += 2
        reads.refresh(tap3)
        assertEquals(2, fetches)
    }

    @Test fun aFailedReadIsNeverSharedByTheNextTap() {
        source = { ready }; reads.refresh(now)
        now += 1
        source = { throw java.io.IOException("no network") }; reads.refresh(now)
        source = { collected }; reads.refresh(now) // asked in the same millisecond, but that read failed: read again
        assertEquals(3, fetches); assertFalse(reads.offline)
    }

    @Test fun refusedAccessIsDeniedNotOffline() {
        reads.restore(listOf(readyHeader, readyRow), now - 60_000)
        source = { throw Exception("HTTP 403") }
        reads.refresh(now)
        assertTrue(reads.denied)
        source = { ready }; now += 1
        reads.refresh(now)
        assertFalse(reads.denied)
    }

    @Test fun aCopyThatCantBeSavedDoesNotMakeAGoodReadOffline() {
        val r = ListReads({ ready }, { _, _ -> throw IllegalStateException("disk full") }, { now })
        r.refresh(now)
        assertFalse(r.offline); assertTrue(r.indexFresh); assertEquals(now, r.loadedAt)
    }

    @Test fun theCopyIsSavedWithTheTimeItWasRead() {
        reads.refresh(now)
        assertEquals(listOf(now), kept)
        assertEquals(ListAge.Level.CURRENT, reads.listAge.level)
    }
}
