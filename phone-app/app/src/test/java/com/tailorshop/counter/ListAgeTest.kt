package com.tailorshop.counter

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

/** Owner 11-03: a good read of a day-old sheet is not current data. Synthetic Sync Status rows only. */
class ListAgeTest {
    private fun sync(at: String, state: String = "CONNECTED") = listOf(listOf("Key", "Value"), listOf("sync_state", state), listOf("last_success_at", at))
    private fun now(s: String) = ZonedDateTime.parse(s + "-08:00[America/Los_Angeles]")
    private val tue = "2026-11-03T15:38:00"
    private val age = ListAge::of

    @Test fun oldDuringShopHoursWhenPastTheLimit() {
        assertEquals(ListAge.Status(ListAge.Level.OLD, "Nov 2, 3:56 PM"), age(sync("2026-11-02T15:56:13-08:00"), now(tue)))  // the real 11-03 case
        assertEquals(ListAge.Level.CURRENT, age(sync("2026-11-03T15:20:00-08:00"), now(tue)).level)
        assertEquals(ListAge.Level.OLD, age(sync("2026-11-03T15:07:00-08:00"), now(tue)).level)
    }

    @Test fun overnightIsJudgedAtClosing() {
        val late = "2026-11-03T19:10:00-08:00"
        assertEquals(ListAge.Level.CURRENT, age(sync(late), now("2026-11-03T23:00:00")).level)
        assertEquals(ListAge.Level.CURRENT, age(sync(late), now("2026-11-04T09:00:00")).level)
        assertEquals(ListAge.Level.CURRENT, age(sync(late), now("2026-11-04T11:00:00")).level)   // grace just after opening
        assertEquals(ListAge.Level.OLD, age(sync(late), now("2026-11-04T11:30:00")).level)
        assertEquals(ListAge.Level.OLD, age(sync("2026-11-03T15:00:00-08:00"), now("2026-11-03T23:00:00")).level)  // stopped before closing
    }

    @Test fun anythingUnexpectedIsUnknownNeverCurrent() {
        assertEquals(ListAge.Level.UNKNOWN, age(null, now(tue)).level)
        assertEquals(ListAge.Level.UNKNOWN, age(emptyList(), now(tue)).level)
        assertEquals(ListAge.Level.UNKNOWN, age(sync("not a time"), now(tue)).level)
        assertEquals(ListAge.Level.UNKNOWN, age(sync("2026-11-03T15:37:00-08:00", "DISCONNECTED"), now(tue)).level)
    }

    @Test fun aMissSaysCheckTheNumberOnlyOnACurrentList() {
        assertEquals("No matching receipt. Check the number.", ListAge.missText(ListAge.Status(ListAge.Level.CURRENT, "Nov 3, 3:30 PM")))
        assertEquals("Not in the list as of Nov 2, 3:56 PM. The list may be out of date — check the tablet.", ListAge.missText(ListAge.Status(ListAge.Level.OLD, "Nov 2, 3:56 PM")))
        assertFalse(ListAge.missText(ListAge.Status(ListAge.Level.UNKNOWN)).contains("Check the number"))
    }

    @Test fun pickupLineFollowsTheListAge() {
        val old = ListAge.Status(ListAge.Level.OLD, "Nov 2, 3:56 PM"); val unknown = ListAge.Status(ListAge.Level.UNKNOWN)
        val collected = OrderIndex.Entry("26435", "5550101", "Test", OrderIndex.State.COLLECTED, "", "2026-11-02 13:40", "")
        val line = OrderIndex.pickupLine(true, true, true, emptyList(), old)
        assertEquals("Not picked up as of Nov 2, 3:56 PM · may be out of date", line)
        assertEquals(OrderIndex.AGE_UNKNOWN, OrderIndex.pickupLine(true, true, true, emptyList(), unknown))
        assertEquals("Picked up · Nov 2, 1:40 PM", OrderIndex.pickupLine(false, true, true, listOf(collected), old))  // a recorded pickup stands
        assertEquals("Pickup status conflicting — check the tablet", OrderIndex.pickupLine(true, true, true, listOf(collected), old))
        // a tap stays possible on an older list (the tablet decides), never on a pickup, a conflict or a failed read
        assertTrue(OrderIndex.pickupAllowed(line)); assertTrue(OrderIndex.pickupAllowed("Not picked up")); assertTrue(OrderIndex.pickupAllowed(OrderIndex.AGE_UNKNOWN))
        assertFalse(OrderIndex.pickupAllowed("Picked up · Nov 2, 1:40 PM"))
        assertFalse(OrderIndex.pickupAllowed("Pickup status conflicting — check the tablet"))
        assertFalse(OrderIndex.pickupAllowed("Pickup status unavailable — couldn't refresh"))
    }
}
