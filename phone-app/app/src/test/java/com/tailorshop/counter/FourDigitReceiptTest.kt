package com.tailorshop.counter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Owner report 11-01: a four-digit receipt with a leading zero ("0264") must reach the Reader exactly as typed —
// never 264, never padded or prefixed — and repeating it reuses the same durable event (synthetic data only).
class FourDigitReceiptTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun leadingZeroReceiptIsSentAndKeptExactly() {
        val d = tmp.newFolder()
        File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")
        val calls = mutableListOf<JSONObject>()
        val f = Finishes(d, poster = { _, b -> calls += b; JSONObject().put("ok", true).put("status", "ACCEPTED") })
        val item = f.addOrReuse("Andrei", "0264")
        assertNotNull(item)
        assertEquals("0264", item!!.receipt)
        assertEquals(item.scanId, f.addOrReuse("Andrei", "0264")!!.scanId)   // no duplicate event
        assertEquals(1, f.all().size)
        f.sync()
        val add = calls.single { it.getString("mode") == "addScan" }
        assertEquals("0264", add.getJSONObject("scan").getString("receipt"))
        assertEquals("0264", Finishes(d).all().single().receipt)              // survives a restart
        assertTrue(calls.none { it.getString("mode") == "pickup" })          // no SMS asked for
    }

    @Test fun lookupMatchesLeadingZeroReceiptOnlyExactly() {
        val orders = listOf(Order("0264", "A", "7025550101", "", true), Order("264", "B", "7025550102", "", true))
        assertEquals(listOf("0264"), Lookup.find(Query.Receipt("0264"), orders).map { it.receipt })
    }
}
