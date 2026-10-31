package com.tailorshop.counter
import org.junit.Test
import org.junit.Assert.*
class OldReceiptsLookupTest {
    @Test fun ownerEnteredOldReceiptIsReadyWithoutInventingAWorker() {
        val order=Lookup.parseOrders(listOf(listOf("Receipt #","Phone","Customer","M.T.","Source"),
            listOf("00123","7025550100","Demo old customer","","Old Receipts"))).single()
        assertTrue(order.ready)
        assertEquals("",order.tailor)
    }
    @Test fun ordinaryReceiptStillRequiresItsExistingSignOff() {
        val order=Lookup.parseOrders(listOf(listOf("Receipt #","Phone","M.T.","Source"),
            listOf("12345","7025550100","","Daily Report"))).single()
        assertFalse(order.ready)
    }
}
