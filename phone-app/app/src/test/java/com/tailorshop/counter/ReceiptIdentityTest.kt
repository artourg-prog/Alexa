package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Reusable receipt numbers (Alexa #4 6571368558, DR PR #38): a new online submission is bound to the exact receipt by a
// read-only findReceipt before its first addScan; old unbound events never adopt the current receipt. Fake Reader only.
class ReceiptIdentityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val txA = "r-" + "a".repeat(32); private val txB = "r-" + "b".repeat(32)

    private class FakeReader(var liveTx: String?) {
        val calls = mutableListOf<JSONObject>()
        var offline = false; var busy = 0; var identity: String? = "tx-v1"; var scanReply: (JSONObject) -> JSONObject = { JSONObject().put("ok", true).put("status", "ACCEPTED") }
        var statusReply = JSONObject().put("ok", true).put("status", "ADDED").put("workday", "2026-11-01")
        var saved = JSONArray()
        fun post(url: String, b: JSONObject): JSONObject { calls += b; if (offline) throw Exception("offline"); return when (b.getString("mode")) {
            "findReceipt" -> JSONObject().put("ok", true).put("number", b.getString("number")).apply { identity?.let { put("identity", it) } }.put("receipts", JSONArray().apply {
                liveTx?.let { put(JSONObject().put("receiptId", "n:" + b.getString("number") + ":walk-in").put("txId", it)) } })
            "addScan" -> if (busy-- > 0) JSONObject().put("ok", false).put("retry", true)
                else scanReply(b.getJSONObject("scan")).put("scanId", b.getJSONObject("scan").getString("scanId")).put("workday", "2026-11-01")
            "scanStatus" -> statusReply
            "receiptReportStatus" -> JSONObject().put("ok", true).put("records", saved)
            else -> JSONObject().put("ok", true).put("state", "REQUESTED")
        } }
        fun of(mode: String) = calls.filter { it.getString("mode") == mode }
    }

    private fun setup(d: File) = File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")
    private fun echo(tx: String) = { s: JSONObject -> JSONObject().put("ok", true).put("status", "ACCEPTED").put("receiptId", "n:26600:walk-in").put("txId", tx) }

    @Test fun newOnlineSubmissionIsBoundBeforeTheFirstSendAndKeepsItsTxEverywhere() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(txB).apply { busy = 1; scanReply = echo(txB) }
        val f = Finishes(d, poster = r::post)
        val i = f.addOrReuse("Andrei", "26600", resolved = f.resolve("26600"))!!
        assertEquals(txB, i.expectedTx); assertTrue(i.bound)
        assertEquals(1, r.of("findReceipt").size); assertEquals(0, r.of("addScan").size)        // resolved before any send
        assertEquals(txB, Finishes(d, poster = r::post).get(i.scanId)!!.expectedTx)              // persisted at once
        assertTrue(f.requestSms(i.scanId))
        f.sync(); f.sync()                                                                         // busy, then retried
        val scans = r.of("addScan").map { it.getJSONObject("scan") }
        assertEquals(2, scans.size); assertTrue(scans.all { it.getString("txId") == txB && it.getString("scanId") == i.scanId })
        assertEquals(txB, r.of("pickup").last().getJSONObject("request").getString("txId"))
        assertEquals(txB, f.get(i.scanId)!!.replyTx)
        f.sync()                                                                                   // ADDED → report check
        assertEquals(txB, r.of("receiptReportStatus").last().getString("receiptId"))
        assertEquals(1, r.of("findReceipt").size)                                                  // sync never re-resolves
    }

    @Test fun recordedOnlyForTheSavedRecordOfTheSameTxWorkerAndDay() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(txB).apply { scanReply = echo(txB) }
        val f = Finishes(d, poster = r::post)
        val i = f.addOrReuse("Andrei", "26600", resolved = f.resolve("26600"))!!
        r.saved = JSONArray().put(JSONObject().put("date", "2026-11-01").put("worker", "Andrei").put("receiptId", txA))   // old A, same worker/day
            .put(JSONObject().put("date", "2026-11-01").put("worker", "Andrei"))                                         // legacy, unbound
        f.sync(); f.sync()
        assertFalse(Finishes.recorded(f.get(i.scanId)!!))
        r.saved.put(JSONObject().put("date", "2026-10-31").put("worker", "Andrei").put("receiptId", txB)); f.sync()       // B, other day
        assertFalse(Finishes.recorded(f.get(i.scanId)!!))
        r.saved.put(JSONObject().put("date", "2026-11-01").put("worker", "Emma").put("receiptId", txB)); f.sync()         // B, other worker
        assertFalse(Finishes.recorded(f.get(i.scanId)!!))
        r.saved.put(JSONObject().put("date", "2026-11-01").put("worker", "Andrei").put("receiptId", txB)); f.sync()
        assertTrue(Finishes.recorded(f.get(i.scanId)!!))
    }

    @Test fun aReplyOrStatusForAnotherReceiptIsNeverTakenAsOurs() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(txB).apply { scanReply = echo(txA) }
        val f = Finishes(d, poster = r::post)
        val i = f.addOrReuse("Andrei", "26600", resolved = f.resolve("26600"))!!
        f.requestSms(i.scanId); f.sync()
        val got = f.get(i.scanId)!!
        assertEquals("REJECTED", got.scan); assertTrue(got.reason.startsWith("IDENTITY_MISMATCH")); assertEquals("", got.receiptId)
        assertEquals(0, r.of("pickup").size)
        // a status answer naming another receipt is refused the same way
        val d2 = tmp.newFolder(); setup(d2); val r2 = FakeReader(txB).apply { scanReply = echo(txB) }
        val f2 = Finishes(d2, poster = r2::post)
        val j = f2.addOrReuse("Andrei", "26600", resolved = f2.resolve("26600"))!!
        f2.sync(); r2.statusReply = JSONObject().put("ok", true).put("status", "ADDED").put("workday", "2026-11-01").put("txId", txA); f2.sync()
        assertEquals("REJECTED", f2.get(j.scanId)!!.scan); assertFalse(Finishes.recorded(f2.get(j.scanId)!!))
    }

    @Test fun anOldUnboundQueuedEventNeverAdoptsTheCurrentReceipt() {
        val d = tmp.newFolder(); setup(d)
        // a 0.56 queue entry (no identity fields), saved before A was deleted and 26600 reused by B
        File(d, "finish-queue.json").writeText(JSONArray().put(JSONObject().put("scanId", "old-a").put("worker", "Andrei").put("receipt", "26600")
            .put("scannedAt", "2026-11-01T17:00:00Z").put("createdMs", 1_793_552_400_000L).put("sendSms", true).put("smsId", "sms-a")).toString())
        val r = FakeReader(txB).apply { scanReply = { JSONObject().put("ok", true).put("status", "REJECTED").put("reason", "STALE_IDENTITY: receipt 26600 was given to a new receipt") } }
        val f = Finishes(d, poster = r::post)
        assertEquals("", f.get("old-a")!!.expectedTx); assertFalse(f.get("old-a")!!.bound)
        f.sync(); f.sync()
        assertFalse(r.of("addScan").single().getJSONObject("scan").has("txId"))                    // sent as it was, no txId attached
        assertEquals(0, r.of("findReceipt").size)
        assertEquals("REJECTED", f.get("old-a")!!.scan); assertEquals(0, r.of("pickup").size)
        // the owner re-enters it: a new event, bound to B
        r.scanReply = echo(txB)
        val b = f.addOrReuse("Andrei", "26600", nowMs = 1_793_556_000_000L, resolved = f.resolve("26600"))!!
        assertNotEquals("old-a", b.scanId); assertEquals(txB, b.expectedTx)
    }

    @Test fun sameDayReuseOnlyForTheSameReceipt() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(null)
        val f = Finishes(d, poster = r::post); val now = 1_793_552_400_000L
        val legacy = f.addOrReuse("Andrei", "26600", nowMs = now, resolved = f.resolve("26600"))!!   // no txId in the sheet yet
        assertEquals("", legacy.expectedTx)
        assertEquals(legacy.scanId, f.addOrReuse("Andrei", "26600", nowMs = now + 60_000, resolved = f.resolve("26600"))!!.scanId)  // unchanged behaviour
        r.liveTx = txB                                                                                // the number now names B
        val b = f.addOrReuse("Andrei", "26600", nowMs = now + 120_000, resolved = f.resolve("26600"))!!
        assertNotEquals(legacy.scanId, b.scanId); assertEquals(txB, b.expectedTx)
        assertEquals(b.scanId, f.addOrReuse("Andrei", "26600", nowMs = now + 180_000, resolved = f.resolve("26600"))!!.scanId)
    }

    @Test fun offlineSubmissionIsSavedUnboundAndNeverBoundLater() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(txB).apply { offline = true }
        val f = Finishes(d, poster = r::post)
        val i = f.addOrReuse("Andrei", "26600", resolved = f.resolve("26600"))!!
        assertEquals("", i.expectedTx); assertFalse(i.bound)
        r.offline = false; r.calls.clear(); f.sync()
        assertFalse(r.of("addScan").single().getJSONObject("scan").has("txId"))
        assertEquals(0, r.of("findReceipt").size)
        assertEquals("", f.get(i.scanId)!!.expectedTx)
    }

    @Test fun leadingZeroReceiptKeepsItsDigitsThroughResolveAndSend() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(txB).apply { scanReply = echo(txB) }
        val f = Finishes(d, poster = r::post)
        val i = f.addOrReuse("Andrei", "0264", resolved = f.resolve("0264"))!!
        f.sync()
        assertEquals("0264", r.of("findReceipt").single().getString("number"))
        val scan = r.of("addScan").single().getJSONObject("scan")
        assertEquals("0264", scan.getString("receipt")); assertEquals(txB, scan.getString("txId")); assertEquals("0264", i.receipt)
    }

    @Test fun aReaderWithoutTheIdentityContractNeverBinds() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader(txB).apply { identity = null }
        val f = Finishes(d, poster = r::post)
        val i = f.addOrReuse("Andrei", "26600", resolved = f.resolve("26600"))!!
        assertEquals("", i.expectedTx); assertFalse(i.bound)                                          // exactly as 0.56
        f.sync(); assertFalse(r.of("addScan").single().getJSONObject("scan").has("txId"))
        assertEquals(Finishes.Resolution(false), Finishes.resolution(JSONObject("{\"ok\":true,\"identity\":\"tx-v0\",\"receipts\":[{\"txId\":\"$txB\"}]}")))
    }

    @Test fun onlyOneLiveReceiptBinds() {
        fun res(a: String) = Finishes.resolution(JSONObject("{\"ok\":true,\"identity\":\"tx-v1\",\"receipts\":$a}"))
        assertEquals(Finishes.Resolution(true, txB), res("[{\"receiptId\":\"n:1:walk-in\",\"txId\":\"$txB\"}]"))
        assertEquals(Finishes.Resolution(true, ""), res("[{\"receiptId\":\"n:1:walk-in\"}]"))              // older sheet shape
        assertEquals(Finishes.Resolution(true, ""), res("[{\"txId\":\"$txA\"},{\"txId\":\"$txB\"}]"))      // several: the Reader decides
        assertEquals(Finishes.Resolution(true, ""), res("[]"))
        assertEquals(Finishes.Resolution(false), Finishes.resolution(JSONObject("{\"ok\":false,\"error\":\"x\"}")))
    }
}
