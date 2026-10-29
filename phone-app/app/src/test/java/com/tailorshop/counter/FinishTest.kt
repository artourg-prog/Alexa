package com.tailorshop.counter

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Voice Finish: speech parsing + the Reader contract, with a fake Reader only (no real SMS, no production calls).
class FinishTest {
    @get:Rule val tmp = TemporaryFolder()
    private val workers = listOf("Andrei", "Artour", "Carolina", "Emma", "Tsegi", "Tumur")

    @Test fun namesMatchTheApprovedSpelling() {
        assertEquals("Andrei", Lookup.matchWorker("Андрей", workers))
        assertEquals("Andrei", Lookup.matchWorker("Andrey", workers))
        assertEquals("Artour", Lookup.matchWorker("Артур", workers))
        assertEquals("Artour", Lookup.matchWorker("Arthur", workers))
        assertEquals("Carolina", Lookup.matchWorker("Каролина", workers))
        assertEquals("Tsegi", Lookup.matchWorker("Цеги", workers))
        assertNull(Lookup.matchWorker("Michael", workers))
    }

    @Test fun parsesArtsCommands() {
        val f = Lookup.parseFinish("София, это Артур, запиши, пожалуйста, номер 26315 и отправь сообщение", workers)!!
        assertEquals("Artour", f.worker); assertEquals(listOf("26315"), f.receipts); assertTrue(f.sms)
        val g = Lookup.parseFinish("Sofia, this is Andrey, done 26314, 26315 and 26316", workers)!!
        assertEquals("Andrei", g.worker); assertEquals(listOf("26314", "26315", "26316"), g.receipts); assertFalse(g.sms)
        val h = Lookup.parseFinish("Sofia, done 26316, send the message", workers)!!
        assertNull(h.worker); assertTrue(h.sms)                              // no name: she asks "Who is this?"
        assertNull(Lookup.parseFinish("София, найди 26316", workers))        // a lookup, not a finish
    }

    @Test fun yesNoAndReadBack() {
        assertEquals(true, Lookup.yesNo("да")); assertEquals(true, Lookup.yesNo("Yes, correct"))
        assertEquals(false, Lookup.yesNo("нет")); assertEquals(false, Lookup.yesNo("cancel"))
        assertNull(Lookup.yesNo("26317"))
        assertEquals("Andrei, 2 6 3 1 6, верно?", Lookup.readBack("Andrei", "26316", true))
        assertEquals("Доброе утро, Andrei.", Lookup.greeting("Andrei", 9, true))
        assertEquals("Hello, Andrei.", Lookup.greeting("Andrei", 14, false))
    }

    @Test fun numberlessAnswersGetThroughOnlyWhenExpected() {
        assertEquals(Lookup.Reply.NAME, Lookup.expectedReply("Андрей", Lookup.Waiting.NAME, workers))          // after "Кто это?"
        assertEquals(Lookup.Reply.YES_NO, Lookup.expectedReply("да", Lookup.Waiting.YES_NO, workers))          // to "…верно?"
        assertEquals(Lookup.Reply.YES_NO, Lookup.expectedReply("нет", Lookup.Waiting.YES_NO, workers))
        assertEquals(Lookup.Reply.SMS, Lookup.expectedReply("отправь сообщение", Lookup.Waiting.FINISH_WINDOW, workers))
        assertEquals(Lookup.Reply.SMS, Lookup.expectedReply("send the message", Lookup.Waiting.FINISH_WINDOW, workers))
        assertNull(Lookup.expectedReply("да", Lookup.Waiting.NONE, workers))                                    // not waiting: room talk
        assertNull(Lookup.expectedReply("Андрей сказал", Lookup.Waiting.YES_NO, workers))
        assertNull(Lookup.expectedReply("я уже сделал", Lookup.Waiting.FINISH_WINDOW, workers))
        assertNull(Lookup.expectedReply("Майкл", Lookup.Waiting.NAME, workers))
    }

    @Test fun smsCanBeAddedToAnAlreadySavedFinish() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader()
        val f = Finishes(d, poster = r::post)
        val i = f.add("Andrei", "26316", sendSms = false)!!; f.sync()
        assertEquals(0, r.count("pickup"))
        assertTrue(f.requestSms(i.scanId)); f.sync()                                    // "отправь сообщение" + "да"
        assertEquals(1, r.count("pickup"))
        assertEquals(1, r.count("addScan"))                                             // the same event, not a second scan
    }

    // ---- the Reader contract ----

    private class FakeReader {
        val calls = mutableListOf<JSONObject>()
        var scanStatus = "ACCEPTED"; var applied = -1L; var smsState = "REQUESTED"; var busy = 0
        fun post(url: String, b: JSONObject): JSONObject { calls += b; return when (b.getString("mode")) {
            "addScan" -> if (busy-- > 0) JSONObject().put("ok", false).put("retry", true)
                else JSONObject().put("ok", true).put("scanId", b.getJSONObject("scan").getString("scanId")).put("status", "ACCEPTED").put("receiptId", "r-1")
            "scanStatus" -> JSONObject().put("ok", true).put("status", scanStatus).apply { if (applied >= 0) put("appliedCents", applied) }
            else -> JSONObject().put("ok", true).put("state", smsState)
        } }
        fun count(mode: String) = calls.count { it.getString("mode") == mode }
    }

    private fun setup(d: File) = File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")

    @Test fun scanLikeQrNoAmountStableRetryAndOnlyAddedIsSaved() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader().apply { busy = 1 }
        val f = Finishes(d, poster = r::post)
        val i = f.add("Andrei", "26316", sendSms = false)!!
        f.sync()                                                     // sheet busy: retried later, same id
        f.sync()
        val scans = r.calls.filter { it.getString("mode") == "addScan" }.map { it.getJSONObject("scan") }
        assertEquals(2, scans.size)
        assertTrue(scans.all { it.getString("scanId") == i.scanId && it.getString("scannedAt") == i.scannedAt })
        assertTrue(scans.none { it.has("cents") || it.has("phone") })  // exactly like a QR scan: no amount
        assertEquals("Queued — waiting for the tablet", Finishes.scanText(f.get(i.scanId)!!, false))
        r.scanStatus = "ADDED"; r.applied = 0                        // fully claimed by others: $0, not an error
        f.sync()
        assertEquals("Saved for Andrei, $0.00", Finishes.scanText(f.get(i.scanId)!!, false))
        assertEquals(0, r.count("pickup"))                           // no "send the message": no SMS
    }

    @Test fun smsOnlyAfterConfirmAndOnlySentCounts() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader()
        val f = Finishes(d, poster = r::post)
        val i = f.add("Andrei", "26316", sendSms = true)!!
        f.sync()
        val p = r.calls.first { it.getString("mode") == "pickup" }.getJSONObject("request")
        assertEquals("now", p.getString("choice")); assertEquals(i.smsId, p.getString("requestId")); assertEquals("r-1", p.getString("receiptId"))
        assertEquals("SMS: waiting to send", Finishes.smsText(f.get(i.scanId)!!, false))   // REQUESTED is not sent
        r.smsState = "SENT"; f.sync()
        val q = r.calls.last { it.getString("mode") == "pickup" }.getJSONObject("request")
        assertEquals("status", q.getString("choice")); assertEquals(i.smsId, q.getString("requestId"))
        assertEquals("SMS sent", Finishes.smsText(f.get(i.scanId)!!, false))
        val n = r.count("pickup"); f.sync(); assertEquals(n, r.count("pickup"))             // final: never asked again
    }

    @Test fun refusedOrHeldScanNeverRequestsSms() {
        for (st in listOf("REJECTED", "HELD")) {
            val d = tmp.newFolder(); setup(d)
            val r = object { val calls = mutableListOf<String>()
                fun post(u: String, b: JSONObject): JSONObject { calls += b.getString("mode")
                    return JSONObject().put("ok", true).put("status", st).put("reason", "unknown worker").put("state", "SENT") } }
            val f = Finishes(d, poster = r::post)
            f.add("Andrei", "26316", sendSms = true); f.sync(); f.sync()
            assertTrue(st, "pickup" !in r.calls)
        }
    }

    @Test fun eventSurvivesRestartAndIsNotSentUntilSaved() {
        val d = tmp.newFolder(); setup(d); val r = FakeReader()
        assertNull(Finishes(d, { _, _ -> false }, r::post).add("Andrei", "26316", false)) // can't save: no event
        val f = Finishes(d, poster = r::post)
        val i = f.add("Andrei", "26317", false)!!
        assertEquals(i.scanId, Finishes(d, poster = r::post).get(i.scanId)!!.scanId)      // same id after restart
    }

    @Test fun readerStatesShownTruthfully() {
        val base = Finishes.Item("s", "Andrei", "26316", "t", 0)
        assertEquals("Waiting: receipt not in the sheet yet", Finishes.scanText(base.copy(scan = "UNVERIFIED"), false))
        assertEquals("Held for owner review", Finishes.scanText(base.copy(scan = "HELD"), false))
        assertEquals("Already saved", Finishes.scanText(base.copy(scan = "ALREADY"), false))
        assertEquals("Не сохранено: unknown worker", Finishes.scanText(base.copy(scan = "REJECTED", reason = "unknown worker"), true))
        assertEquals("SMS: held (quiet hours)", Finishes.smsText(base.copy(sendSms = true, sms = "HELD"), false))
        assertEquals("SMS: not confirmed", Finishes.smsText(base.copy(sendSms = true, sms = "UNSURE"), false))
    }
}
