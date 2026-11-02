package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Reusable receipt numbers, Picked up side (Pickups 25a539c, Alexa #4 6571822333): the tapped row's Txn goes with the
// request and its retries; taps are keyed by Txn when known, so a reused B never shows A's state. Fake server only.
class PickupTxnTest {
    @get:Rule val tmp = TemporaryFolder()
    private val txA = "r-" + "a".repeat(32); private val txB = "r-" + "b".repeat(32)

    private class FakeServer {
        val calls = mutableListOf<JSONObject>(); var result = "QUEUED"; val states = JSONObject()
        fun post(url: String, b: JSONObject): JSONObject { calls += b; return when (b.getString("mode")) {
            "pickupRequest" -> JSONObject().put("result", result)
            else -> JSONObject().put("states", states)
        } }
        fun requests() = calls.filter { it.getString("mode") == "pickupRequest" }
    }

    private fun setup(d: File) = File(d, "pickup-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\"}")

    @Test fun theTappedRowsTxnGoesWithTheRequestAndItsRetries() {
        val d = tmp.newFolder(); setup(d); val s = FakeServer().apply { result = "FULL" }
        val p = Pickups(d, poster = s::post)
        assertTrue(p.addOrReuse("26600", txB))
        p.sync(); s.result = "QUEUED"; Pickups(d, poster = s::post).sync()                 // retried after a restart
        assertEquals(2, s.requests().size)
        assertTrue(s.requests().all { it.getString("txnId") == txB })
        assertEquals(1, s.requests().map { it.getString("requestId") }.distinct().size)    // the same request id
    }

    @Test fun aTapWithoutTxnIsSentNumberOnlyAndNeverGivenOneLater() {
        val d = tmp.newFolder(); setup(d)
        // a 0.56 queue entry: no "txn" field
        File(d, "pickup-queue.json").writeText(JSONArray().put(JSONObject().put("id", "old-a").put("receipt", "26600")
            .put("at", System.currentTimeMillis()).put("sent", false).put("status", "")).toString())
        val s = FakeServer(); val p = Pickups(d, poster = s::post)
        assertTrue(p.addOrReuse("26600", txB))                                              // B tapped: a new bound request
        p.sync()
        val byId = s.requests().associateBy { it.getString("requestId") }
        assertFalse(byId.getValue("old-a").has("txnId"))
        val fresh = byId.filterKeys { it != "old-a" }.values.single()
        assertEquals(txB, fresh.getString("txnId"))
        assertEquals(1, JSONArray(File(d, "pickup-queue.json").readText()).let { q -> (0 until q.length()).count { q.getJSONObject(it).getString("txn") == "" } })
    }

    @Test fun reusedBIsNotBlockedOrLabelledByAsBoundTap() {
        val d = tmp.newFolder(); setup(d); val s = FakeServer(); val p = Pickups(d, poster = s::post)
        assertTrue(p.addOrReuse("26600", txA)); p.sync()
        val a = p.latest("26600", txA)!!
        s.states.put(a.id, "APPLIED"); p.sync()
        assertTrue(p.pendingFor("26600", txA)); assertEquals("APPLIED", p.latest("26600", txA)!!.status)
        assertFalse(p.pendingFor("26600", txB)); assertNull(p.latest("26600", txB))         // B: its own button, no A state
        assertTrue(p.addOrReuse("26600", txB))
        val b = p.latest("26600", txB)!!.id; assertNotEquals(a.id, b)
        assertTrue(p.addOrReuse("26600", txB)); assertEquals(b, p.latest("26600", txB)!!.id) // repeat tap reuses B's request
    }

    @Test fun rowsWithoutTxnFallBackToNumberOnly() {
        val d = tmp.newFolder(); setup(d); val p = Pickups(d, poster = FakeServer()::post)
        assertTrue(p.addOrReuse("26600", ""))
        assertTrue(p.pendingFor("26600", "")); assertTrue(p.pendingFor("26600", txB))       // an unbound tap still guards the number
        val first = p.latest("26600")!!.id
        assertTrue(p.addOrReuse("26600", "")); assertEquals(first, p.latest("26600")!!.id) // same tap, same id (unchanged behaviour)
    }

    @Test fun txnHeaderIsReadByNameAndMissingMeansNone() {
        val withTxn = Lookup.parseOrders(listOf(listOf("Date", "Receipt #", "Customer", "Phone", "M.T.", "Txn"),
            listOf("2026-11-01", "26600", "C", "7025550000", "Emma", "r-" + "b".repeat(32))))
        assertEquals("r-" + "b".repeat(32), withTxn.single().txn)
        assertEquals("r-" + "b".repeat(32), Lookup.find(Query.Receipt("26600"), withTxn).single().txn)
        val old = Lookup.parseOrders(listOf(listOf("Date", "Receipt #", "Customer", "Phone", "M.T."), listOf("2026-11-01", "0264", "C", "7025550000", "Emma")))
        assertEquals("", old.single().txn); assertEquals("0264", old.single().receipt)
    }
}
