package com.tailorshop.counter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptMissingTest {
    private fun item(scan: String, reason: String) = Finishes.Item(
        "fake-scan", "Test Worker", "0264", "2026-11-02T12:00:00Z", 0,
        scan = scan, reason = reason,
    )

    @Test fun explicitMasterMissKeepsLeadingZero() {
        assertTrue(Finishes.receiptNotFound(item("UNVERIFIED",
            "UNVERIFIED: receipt 0264 is not in 2026 RECEIPTS — the Daily Report tablet checks it")))
        assertFalse(Finishes.receiptNotFound(item("UNVERIFIED",
            "UNVERIFIED: receipt 264 is not in 2026 RECEIPTS")))
    }

    @Test fun offlineAndMissingSavedReportAreNotReceiptMisses() {
        val offline = item("UNKNOWN", "Connection unavailable")
        offline.reportState = "UNCONFIRMED"
        assertFalse(Finishes.receiptNotFound(offline))
        val waiting = item("ADDED", "")
        waiting.reportState = "WAITING"
        waiting.reportReason = "Not in the saved worker/day report yet"
        assertFalse(Finishes.receiptNotFound(waiting))
    }

    @Test fun unconfirmedTagAndIdentityRefusalAreDifferent() {
        assertFalse(Finishes.receiptNotFound(item("UNVERIFIED", "TAG_UNCONFIRMED: receipt 0264-abc")))
        assertFalse(Finishes.receiptNotFound(item("REJECTED", "IDENTITY_MISMATCH")))
    }
}
