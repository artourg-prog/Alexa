package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Owner 11-02 (26448 / a synthetic worker): the first entry converges by itself; home list; pickup line on cards. Fake Reader only. */
class FirstEntryOverviewTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Reader {
        var scan = "ACCEPTED"; var saved = false; var savedAt: String? = null; var offlineReport = false
        val calls = mutableListOf<JSONObject>()
        fun post(url: String, b: JSONObject): JSONObject { calls += b; return when (b.getString("mode")) {
            "addScan", "scanStatus" -> JSONObject().put("ok", true).put("status", scan).put("workday", "2026-11-02")
            "receiptReportStatus" -> { if (offlineReport) error("synthetic timeout")
                JSONObject().put("ok", true).put("records", JSONArray().apply {
                    if (saved) put(JSONObject().put("date", "2026-11-02").put("worker", "Emma").also { r -> savedAt?.let { r.put("savedAt", it) } }) }) }
            else -> JSONObject().put("ok", true)
        } }
        fun of(mode: String) = calls.count { it.getString("mode") == mode }
    }

    private fun finishes(r: Reader): Finishes { val d = tmp.newFolder()
        File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Emma\"]}")
        return Finishes(d, poster = r::post) }

    @Test fun firstEntryReachesRecordedThroughSlowTabletAndTimeoutsWithoutASecondEntry() {
        val r = Reader(); val f = finishes(r)
        val i = f.addOrReuse("Emma", "26448")!!
        f.sync(); assertEquals("ACCEPTED", f.get(i.scanId)!!.scan)                 // Reader accepted, tablet not yet
        r.scan = "ADDED"; f.sync(); assertEquals("WAITING", f.get(i.scanId)!!.reportState)
        r.offlineReport = true; f.sync(); assertEquals("UNCONFIRMED", f.get(i.scanId)!!.reportState) // a timeout is not final
        repeat(20) { r.offlineReport = false; f.sync() }                             // tablet auto-save still pending: keeps checking
        assertFalse(Finishes.recorded(f.get(i.scanId)!!))
        r.saved = true; r.savedAt = "2026-11-03T00:31:00Z"; f.sync()
        val done = f.get(i.scanId)!!
        assertTrue(Finishes.recorded(done))
        assertEquals("Saved · Nov 2, 4:31 PM", Finishes.overviewLine(done))         // the Reader's saved time, shop time
        assertEquals(1, r.of("addScan"))                                             // one scan, same id: no second allocation
        assertEquals(1, f.all().size)
        assertEquals(0, r.of("pickup"))                                              // no SMS asked for
        assertTrue(done.sentMs > 0 && done.appliedMs >= done.sentMs && done.confirmedMs >= done.appliedMs)
    }

    @Test fun savedTimeIsNeverOurOwnClock() {
        val r = Reader().apply { scan = "ADDED"; saved = true }; val f = finishes(r)
        val i = f.addOrReuse("Emma", "26448")!!; f.sync(); f.sync()
        assertEquals("Saved · save time not reported", Finishes.overviewLine(f.get(i.scanId)!!))
        assertEquals("Waiting for Daily Report confirmation", Finishes.overviewLine(Finishes.Item("x", "Emma", "26449", "", 0L, scan = "ACCEPTED")))
    }

    @Test fun savedAtFormats() {
        assertEquals("Nov 2, 4:31 PM", ReportConfirmation.savedAt(JSONObject().put("savedAt", "2026-11-02 16:31")))
        assertEquals("Nov 2, 4:31 PM", ReportConfirmation.savedAt(JSONObject().put("savedAt", "2026-11-02T16:31:00-08:00")))
        assertEquals("", ReportConfirmation.savedAt(JSONObject()))
        assertEquals("", ReportConfirmation.savedAt(null))
    }

    private fun e(state: OrderIndex.State, at: String = "") = OrderIndex.Entry("26435", "5550101", "Test", state, "", at, "2026-11-02 13:21")

    @Test fun pickupLineOnEachCard() {
        // ready-list row, both reads fresh, nothing collected → verified not picked up
        assertEquals("Not picked up", OrderIndex.pickupLine(true, true, true, emptyList()))
        // couldn't refresh either source → never a guess
        assertEquals("Pickup status unavailable — couldn't refresh", OrderIndex.pickupLine(true, false, true, emptyList()))
        assertEquals("Pickup status unavailable — couldn't refresh", OrderIndex.pickupLine(true, true, false, emptyList()))
        // committed pickup with its recorded time, or unknown time
        assertEquals("Picked up · Nov 2, 1:40 PM", OrderIndex.pickupLine(false, true, true, listOf(e(OrderIndex.State.COLLECTED, "2026-11-02 13:40"))))
        assertEquals("Picked up · pickup time unknown", OrderIndex.pickupLine(false, true, true, listOf(e(OrderIndex.State.COLLECTED))))
        // ready row and a picked-up row for the same number (reused or conflicting) → conflict, not resolved
        assertEquals("Pickup status conflicting — check the tablet", OrderIndex.pickupLine(true, true, true, listOf(e(OrderIndex.State.COLLECTED, "2026-11-02 13:40"))))
        // not marked finished (fresh) → not picked up; unknown → unavailable
        assertEquals("Not picked up", OrderIndex.pickupLine(false, true, true, listOf(e(OrderIndex.State.NOT_READY))))
        assertEquals("Pickup status unavailable", OrderIndex.pickupLine(false, true, true, listOf(e(OrderIndex.State.UNKNOWN))))
    }

    @Test fun aWaitingScreenChecksOnlyItsOwnEntryRightAway() {
        val r = Reader(); val f = finishes(r)
        val old = f.addOrReuse("Emma", "26401")!!; f.sync()                       // an older entry still pending
        val now = f.addOrReuse("Emma", "26457")!!
        r.calls.clear(); f.sync(onlyId = now.scanId)
        assertTrue(r.calls.isNotEmpty())
        assertTrue(r.calls.all { c -> c.optJSONObject("scan")?.optString("scanId").let { it == null || it == now.scanId } && c.optString("scanId", now.scanId) == now.scanId })
        assertNotEquals(old.scanId, now.scanId)
    }

    @Test fun saveOnlyReEntryKeepsTheOriginalEventAndItsSmsChoice() {
        val r = Reader(); val f = finishes(r)
        val first = f.addOrReuse("Emma", "26457")!!; assertTrue(f.requestSms(first.scanId))   // Save + SMS
        val smsId = f.get(first.scanId)!!.smsId
        val again = f.addOrReuse("Emma", "26457")!!                                          // owner re-enters, Save only
        assertEquals(first.scanId, again.scanId)
        assertTrue(f.get(first.scanId)!!.sendSms)                                            // the authorized SMS intent stays
        assertEquals(smsId, f.get(first.scanId)!!.smsId)                                     // same request id: never a second message
        assertEquals(1, f.all().size)
    }
}
