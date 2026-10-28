package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Every reply word of the Pickups phone API (5c50055).
class PickupsTest {
    @Test fun requestReplies() {
        assertEquals("QUEUED", Pickups.afterRequest("QUEUED"))
        assertEquals("QUEUED", Pickups.afterRequest("ALREADY"))
        assertEquals("BAD_REQUEST", Pickups.afterRequest("BAD_REQUEST"))
        assertNull(Pickups.afterRequest("FULL")) // kept, retried later
        assertNull(Pickups.afterRequest("DENIED"))
    }

    @Test fun finalStates() {
        for (s in listOf("APPLIED", "NOT_FOUND", "AMBIGUOUS", "EXPIRED", "ALREADY_PICKED_UP", "BAD_REQUEST"))
            assertTrue(s, s in Pickups.FINAL)
        assertTrue("UNKNOWN" !in Pickups.FINAL && "QUEUED" !in Pickups.FINAL)
    }

    @Test fun problemTexts() {
        assertEquals("#26314: already picked up", Pickups.problemText("26314", "ALREADY_PICKED_UP"))
        assertEquals("#26314: not marked — check on the tablet", Pickups.problemText("26314", "BAD_REQUEST"))
        assertEquals("#26314: not found on the tablet — mark it on the tablet", Pickups.problemText("26314", "NOT_FOUND"))
    }

    // ---- nothing unconfirmed is dropped (synthetic data only) ----
    private val day = 24 * 3600_000L
    private val t0 = 1_000_000_000_000L
    private fun req(id: String, age: Long, sent: Boolean = false, status: String = "") =
        Pickups.Req(id, "1000$id", t0 - age, sent, status, durable = true) // as read back from the queue file

    @Test fun offlineForDaysKeepsTapsAndNeverSendsThemLate() {
        val q = mutableListOf(req("1", 2 * day), req("2", 60_000))
        val flagged = Pickups.housekeep(q, t0)
        assertEquals(listOf("1"), flagged.map { it.id })
        assertEquals(2, q.size)                          // nothing removed
        assertEquals("NEEDS_CHECK", q[0].status)         // not retried: it's final now
        assertTrue(q[0].status in Pickups.FINAL)
        assertEquals("", q[1].status)                    // the fresh one is still retried
    }

    @Test fun sentButUnansweredIsKeptForever() {
        val q = mutableListOf(req("1", 5 * day, sent = true, status = "QUEUED"))
        assertTrue(Pickups.housekeep(q, t0).isEmpty())
        assertEquals(1, q.size); assertEquals("QUEUED", q[0].status)
    }

    @Test fun absenceFromTheListIsNotProof() {
        // There is no list-based clearing any more: only the tablet's answer or a person's tap settles it.
        val q = mutableListOf(req("1", 3 * day, sent = true, status = "EXPIRED"), req("2", 3 * day, sent = true, status = "NOT_FOUND"))
        Pickups.housekeep(q, t0)
        assertEquals(2, q.size)
        assertTrue(q.all { Pickups.needsCheck(it, t0) })
        q[0].resolved = true                              // a person tapped "Checked"
        Pickups.housekeep(q, t0)
        assertEquals(listOf("2"), q.map { it.id })
    }

    @Test fun sentWithNoAnswerIsShownButKeepsItsId() {
        val r = req("1", 2 * 3600_000L, sent = true, status = "QUEUED").apply { sentAt = t0 - 31 * 60_000L }
        assertTrue(Pickups.needsCheck(r, t0))
        assertEquals("#10001: sent, the tablet hasn't answered — check on the tablet", Pickups.problemText(r.receipt, r.status))
        r.resolved = true                                 // checked by a person: hidden, but still asked about
        assertTrue(!Pickups.needsCheck(r, t0))
        val q = mutableListOf(r); Pickups.housekeep(q, t0 + 10 * day)
        assertEquals(1, q.size); assertEquals("1", q[0].id) // same identity, never reissued
        val fresh = req("2", 0, sent = true, status = "QUEUED").apply { sentAt = t0 - 60_000L }
        assertTrue(!Pickups.needsCheck(fresh, t0))
    }

    @Test fun statusPollingStaysWithinBatchSize() {
        val b = Pickups.batches((1..45).map { "id$it" })
        assertEquals(listOf(20, 20, 5), b.map { it.size })
    }

    @Test fun screenSaysWhatIsTrue() {
        assertEquals("Picked up — saved on the phone, not sent yet", Pickups.stateText(req("1", 0)))
        assertEquals("Picked up — sent, waiting for the tablet", Pickups.stateText(req("1", 0, sent = true, status = "QUEUED")))
        assertEquals("Picked up — confirmed by the tablet", Pickups.stateText(req("1", 0, sent = true, status = "APPLIED")))
        assertEquals("Picked up NOT confirmed — check on the tablet", Pickups.stateText(req("1", 0, status = "NEEDS_CHECK")))
    }

    @Test fun settledRecordsAreCleanedAfterADay() {
        val q = mutableListOf(req("1", 2 * day, sent = true, status = "APPLIED"), req("2", 1000, sent = true, status = "APPLIED"))
        Pickups.housekeep(q, t0)
        assertEquals(listOf("2"), q.map { it.id })
    }

    @Test fun needsCheckText() =
        assertEquals("#26314: not sent in time — check on the tablet", Pickups.problemText("26314", "NEEDS_CHECK"))
}
