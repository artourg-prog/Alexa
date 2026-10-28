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
}
