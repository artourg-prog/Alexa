package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Test

// Synthetic numbers only. A store order is found by its store's number and carries the store's Account name.
class StoreOrderTest {
    private val rows = listOf(
        listOf("Date", "Receipt #", "Total", "Payment Type", "Account", "Description", "Customer", "Phone", "M.T.", "Txn"),
        listOf("11/03/26", "40001", "$50.00", "Account", "Shop @ One", "hem", "Client A", "555 000 1111", "Emma", "t1"),
        listOf("11/03/26", "40002", "$25.00", "Account", "Shop @ Two", "hem", "Client B", "555 000 2222", "Emma", "t2"),
        listOf("11/03/26", "40003", "$20.00", "Card", "Walker", "hem", "Walker", "555 999 1111", "Emma", "t3"),
    )

    @Test fun `a store's last four finds its orders with the store name, and a walk-in with the same four stays apart`() {
        val hits = Lookup.find(Query.Last4("1111"), Lookup.parseOrders(rows)).associateBy { it.receipt }
        assertEquals(setOf("40001", "40003"), hits.keys)
        assertEquals("Shop @ One", hits["40001"]!!.account); assertEquals(Pay.ACCOUNT, hits["40001"]!!.pay)
        assertEquals(Pay.PAID, hits["40003"]!!.pay)
    }

    @Test fun `one location's number never finds another location's order`() {
        assertEquals(listOf("40002"), Lookup.find(Query.Last4("2222"), Lookup.parseOrders(rows)).map { it.receipt })
    }
}
