package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Marking an old "not in the sheet" entry reviewed: local only, kept in history, no Reader / SMS call (synthetic data).
class FinishReviewTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val day = 24 * 3600_000L

    private fun entry(id: String, receipt: String, scan: String, ageMs: Long, sendSms: Boolean = false, sms: String = "") =
        JSONObject().put("scanId", id).put("worker", "Andrei").put("receipt", receipt).put("scannedAt", "2026-10-29T06:40:00Z")
            .put("createdMs", now - ageMs).put("sendSms", sendSms).put("scan", scan).put("reason", "").put("appliedCents", -1)
            .put("receiptId", "").put("smsId", if (sendSms) "sms-$id" else "").put("sms", sms)

    private fun folder(vararg entries: JSONObject): File {
        val d = tmp.newFolder()
        File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")
        File(d, "finish-queue.json").writeText(JSONArray(entries.toList()).toString())
        return d
    }

    @Test fun oldUnverifiedWithoutSmsMovesToHistoryAndIsNoLongerChecked() {
        val d = folder(entry("a", "90001", "UNVERIFIED", 3 * day), entry("b", "90002", "UNVERIFIED", 3 * day), entry("c", "90003", "ADDED", 1000))
        val calls = mutableListOf<JSONObject>()
        val f = Finishes(d, poster = { _, b -> calls += b; JSONObject().put("ok", true).put("status", "UNVERIFIED") })
        assertEquals(listOf("a", "b"), f.dismissable(now).map { it.scanId })
        assertTrue(f.dismiss("a", now))
        assertEquals(listOf("b", "c"), f.all().map { it.scanId })
        val history = JSONArray(File(d, "finish-reviewed.json").readText())
        assertEquals(1, history.length())
        assertEquals("90001", history.getJSONObject(0).getString("receipt"))
        assertEquals("UNVERIFIED", history.getJSONObject(0).getString("scan"))
        assertTrue(history.getJSONObject(0).has("reviewedAt"))
        assertEquals(listOf("b", "c"), Finishes(d).all().map { it.scanId })   // survives a restart
        f.sync()
        assertTrue(calls.none { it.optString("scanId") == "a" })                // never re-checked
        assertTrue(calls.none { it.getString("mode") == "pickup" })             // no SMS of any kind
    }

    @Test fun anythingElseCannotBeDismissed() {
        val d = folder(
            entry("young", "90001", "UNVERIFIED", day - 1000),
            entry("sms", "90002", "UNVERIFIED", 3 * day, sendSms = true, sms = "REQUESTED"),
            entry("added", "90003", "ADDED", 3 * day),
            entry("waiting", "90004", "ACCEPTED", 3 * day),
            entry("held", "90005", "HELD", 3 * day))
        val f = Finishes(d)
        assertTrue(f.dismissable(now).isEmpty())
        val before = File(d, "finish-queue.json").readText()
        for (id in listOf("young", "sms", "added", "waiting", "held", "missing")) assertFalse(id, f.dismiss(id, now))
        assertEquals(before, File(d, "finish-queue.json").readText())
        assertFalse(File(d, "finish-reviewed.json").exists())
        assertEquals(5, f.all().size)
    }

    @Test fun dismissingTwiceKeepsOneHistoryEntry() {
        val d = folder(entry("a", "90001", "UNVERIFIED", 3 * day))
        val f = Finishes(d)
        assertTrue(f.dismiss("a", now))
        assertFalse(f.dismiss("a", now))
        assertEquals(1, JSONArray(File(d, "finish-reviewed.json").readText()).length())
        assertTrue(f.all().isEmpty())
    }

    @Test fun historyWriteFailureChangesNothing() {
        val d = folder(entry("a", "90001", "UNVERIFIED", 3 * day))
        val f = Finishes(d, replace = { from, to -> if (to.name == "finish-reviewed.json") false else from.renameTo(to) })
        assertFalse(f.dismiss("a", now))
        assertEquals(listOf("a"), f.all().map { it.scanId })
        assertEquals(listOf("a"), Finishes(d).all().map { it.scanId })
    }
}
