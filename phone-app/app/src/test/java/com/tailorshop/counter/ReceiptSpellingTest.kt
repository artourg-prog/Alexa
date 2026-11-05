package com.tailorshop.counter

import com.tailorshop.counter.conversation.CardDeck
import com.tailorshop.counter.conversation.IntentContext
import com.tailorshop.counter.conversation.SafeIntent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Owner 11-04 (#4 6649748751, 6650081068): NN-NNN and NNNNN are one receipt for lookup, joins and retries. The number is
// shown as recorded: a dash is never added to a plain number, and old numbers (0264, 264766, 2629311) stay as they are.
class ReceiptSpellingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val header = listOf("Date", "Receipt #", "Customer", "Phone", "M.T.", "Txn")
    private fun row(r: String, phone: String = "7025551034") = listOf("11/04/2026", r, "Test", phone, "Andrei", "t-$r")
    private fun orders(vararg r: String) = Lookup.parseOrders(listOf(header) + r.map { row(it) })

    @Test fun onlyAnExactFiveDigitDashedNumberBecomesPlain() {
        assertEquals("26490", Lookup.plainReceipt("26-490"))
        assertEquals("26490", Lookup.plainReceipt(" 26-490 "))
        listOf("26490", "0264", "264766", "2629311", "2-6490", "264-90", "26-4900", "A26-490").forEach { assertEquals(it, Lookup.plainReceipt(it)) }
    }

    @Test fun bothSpellingsFindTheSameRowInEitherDirection() {
        listOf("26-490", "26490").forEach { recorded ->
            listOf("26-490", "26490", "26–490", "26 490").forEach { said ->
                assertEquals(said, listOf(recorded), Lookup.findAll(said, orders(recorded)).map { it.receipt })
            }
        }
    }

    @Test fun theCardShowsTheRecordedSpellingAndNoDashIsInvented() {
        assertEquals("26-490", Lookup.find(Query.Receipt("26490"), orders("26-490")).single().receipt)
        assertEquals("26484", Lookup.find(Query.Receipt("26484"), orders("26484")).single().receipt)
    }

    @Test fun oldNumbersKeepTheirOwnShapeAndNeverMeetAFiveDigitOne() {
        val all = orders("0264", "264766", "2629311", "26476")
        listOf("0264", "264766", "2629311").forEach { n -> assertEquals(n, Lookup.find(Query.Receipt(n), all).single().receipt) }
        assertEquals("26476", Lookup.find(Query.Receipt("26476"), all).single().receipt)
    }

    @Test fun twoSpellingsOfOneReceiptAreOneAnswer() {
        assertEquals(1, Lookup.findAll("26490", orders("26-490", "26490")).size)
    }

    @Test fun spokenAnswerNeverReadsTheDash() {
        val card = Lookup.find(Query.Receipt("26490"), orders("26-490"))
        assertEquals("Receipt 2 6 4 9 0 is ready for pickup.", Lookup.replyEn(card))
    }

    @Test fun statusAndHistoryJoinAcrossSpellings() {
        val idx = OrderIndex.parse(listOf(listOf("Receipt #", "Phone", "Customer", "Status", "Collected At"),
            listOf("26-490", "7025551034", "Test", "COLLECTED", "")))
        val picked = OrderIndex.parsePickedUp(listOf(listOf("Collected At", "Receipt #", "Customer", "Phone"),
            listOf("2026-11-04 16:00", "26490", "Test", "7025551034")))
        val all = OrderIndex.withHistory(idx, picked)
        assertEquals(1, all.size)                                    // one receipt, not a second row
        assertEquals("26-490", all.single().receipt)                 // the index row's spelling stays
        assertEquals("2026-11-04 16:00", all.single().collectedAt)   // the time came from the other spelling
        assertEquals("Picked up · Nov 4, 4:00 PM", OrderIndex.pickupLine(false, true, true, all))
    }

    @Test fun aTapOnADashedCardIsSentPlainAndRetriesAsOneRequest() {
        val d = tmp.newFolder()
        File(d, "pickup-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\"}")
        val p = Pickups(d)
        assertTrue(p.addOrReuse("26-490", "t"))
        assertTrue(p.addOrReuse("26490", "t"))                       // the same request in the other spelling
        assertEquals(setOf("26490"), p.pending())
        assertTrue(p.pendingFor("26-490", "t")); assertTrue(p.pendingFor("26490", "t"))
        val back = Pickups(d).latest("26490", "t")!!
        assertEquals("26490", back.receipt); assertEquals("26-490", back.number())
    }

    @Test fun aReportEntryIsSentPlainKeepsItsSpellingAndIsNotDuplicated() {
        val d = tmp.newFolder()
        File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")
        val calls = mutableListOf<JSONObject>()
        val f = Finishes(d, poster = { _, b -> calls += b; JSONObject().put("ok", true).put("status", "ACCEPTED") })
        val first = f.addOrReuse("Andrei", "26-490")!!
        assertEquals(first.scanId, f.addOrReuse("Andrei", "26490")!!.scanId)
        f.sync()
        assertEquals("26490", calls.single { it.getString("mode") == "addScan" }.getJSONObject("scan").getString("receipt"))
        val back = Finishes(d).all().single()
        assertEquals("26490", back.receipt); assertEquals("26-490", back.number())
    }

    @Test fun aPlainEntryLaterSeenDashedTakesTheRecordedSpelling() {
        val d = tmp.newFolder()
        File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")
        val f = Finishes(d, poster = { _, _ -> JSONObject().put("ok", true) })
        f.addOrReuse("Andrei", "26490"); f.addOrReuse("Andrei", "26-490")
        assertEquals("26-490", Finishes(d).all().single().number())
    }

    @Test fun aPlainEntryShowsPlain() {
        val d = tmp.newFolder()
        File(d, "finish-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\",\"workers\":[\"Andrei\"]}")
        assertEquals("26484", Finishes(d, poster = { _, _ -> JSONObject() }).addOrReuse("Andrei", "26484")!!.number())
    }

    @Test fun cardsOfOneReceiptReplaceEachOtherAcrossSpellings() {
        val deck = CardDeck()
        deck.add(listOf(Card("26-490", "", "", true)), 0)
        deck.add(listOf(Card("26490", "", "", true)), 1)
        assertEquals(1, deck.size)
        deck.remove("26-490")
        assertEquals(0, deck.size)
    }

    @Test fun sayingTheNumberBackMatchesADashedCard() {
        val c = IntentContext(listOf("Andrei"), receipt = "26-490")
        assertEquals(listOf("26490"), SafeIntent.numbers("26-490"))
        assertFalse(SafeIntent.numbers("26490").any { it != Lookup.digits(c.receipt!!) })
    }
}
