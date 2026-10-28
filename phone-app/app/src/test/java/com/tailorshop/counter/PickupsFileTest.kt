package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

// The phone's pickup queue file: synthetic data, temporary folders only.
class PickupsFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun survivesRestart() {
        val d = tmp.newFolder()
        assertTrue(Pickups(d).add(listOf("10001", "10002")))
        assertEquals(setOf("10001", "10002"), Pickups(d).pending())
    }

    @Test fun interruptedWriteKeepsTheLastGoodQueue() {
        val d = tmp.newFolder()
        Pickups(d).add(listOf("10001"))
        File(d, "pickup-queue.tmp").writeText("[{\"id\":\"x\",\"rec") // power lost mid-write
        val p = Pickups(d)
        assertEquals(setOf("10001"), p.pending())
        assertNull(p.warning())
    }

    @Test fun failedReplaceKeepsTheLastGoodFileAndWarns() {
        val d = tmp.newFolder()
        Pickups(d).add(listOf("10001"))
        val good = File(d, "pickup-queue.json").readText()
        val p = Pickups(d, replace = { _, _ -> false }) // the swap-in fails
        assertFalse(p.add(listOf("10002")))
        assertEquals(good, File(d, "pickup-queue.json").readText())
        assertNotNull(p.warning())
        assertTrue(p.pending().contains("10002"))      // still held in memory, still retried
    }

    @Test fun unreadableQueueIsSetAsideAndWarnsUntilChecked() {
        val d = tmp.newFolder()
        File(d, "pickup-queue.json").writeText("not json {")
        val p = Pickups(d)
        assertNotNull(p.warning())
        assertTrue(d.listFiles()!!.any { it.name.startsWith("pickup-queue.unreadable-") && it.readText() == "not json {" })
        p.add(listOf("10003"))
        assertNotNull(Pickups(d).warning())               // still shown after a restart
        Pickups(d).acknowledgeRecovery()
        assertNull(Pickups(d).warning())
        assertTrue(d.listFiles()!!.any { it.name.startsWith("pickup-queue.unreadable-") }) // evidence kept
    }

    @Test fun unreadableQueueThatCantBeSetAsideIsNeverOverwritten() {
        val d = tmp.newFolder()
        File(d, "pickup-queue.json").writeText("not json {")
        val p = Pickups(d, replace = { _, _ -> false })
        assertFalse(p.add(listOf("10004")))
        assertEquals("not json {", File(d, "pickup-queue.json").readText())
        assertNotNull(p.warning())
    }

    @Test fun olderQueueFormatIsKeptOnUpgrade() {
        val d = tmp.newFolder()
        File(d, "pickup-queue.json").writeText(
            "[{\"id\":\"a\",\"receipt\":\"10005\",\"at\":1,\"sent\":true,\"status\":\"QUEUED\"}," +
            "{\"id\":\"b\",\"receipt\":\"10006\",\"at\":2,\"sent\":false,\"status\":\"\"}]")
        val p = Pickups(d)
        assertEquals(setOf("10005", "10006"), p.pending())
        assertEquals("Picked up — sent, waiting for the tablet", Pickups.stateText(p.latest("10005")!!))
        assertNull(p.warning())
    }

    // ---- no transport before the exact tap and its id are saved (synthetic fake server) ----

    private class FakeServer {
        val requests = mutableListOf<String>() // request ids posted with mode pickupRequest
        fun post(url: String, body: JSONObject): JSONObject = when (body.getString("mode")) {
            "pickupRequest" -> { requests += body.getString("requestId"); JSONObject().put("result", "QUEUED") }
            else -> JSONObject().put("result", "OK").put("states", JSONObject())
        }
    }

    private fun configure(d: File) = File(d, "pickup-config.json").writeText("{\"url\":\"https://fake.test\",\"secret\":\"x\"}")

    @Test fun failedFirstSaveMeansNothingIsSentUntilSavedThenSameIdSurvivesRestart() {
        val d = tmp.newFolder(); configure(d)
        val server = FakeServer()
        var storageOk = false
        val p = Pickups(d, { a, b -> storageOk && a.renameTo(b) }, server::post)

        assertFalse(p.add(listOf("10007")))                       // first save fails
        val id = p.latest("10007")!!.id
        assertEquals("Picked up NOT saved on the phone — mark it on the tablet", Pickups.stateText(p.latest("10007")!!))
        assertNotNull(p.warning())
        p.sync(); p.sync()
        assertEquals(0, server.requests.size)                     // zero transport while unsaved

        storageOk = true                                          // storage recovers
        p.sync()
        assertEquals(listOf(id), server.requests)                 // sent once, original id
        assertNull(p.warning())

        val after = Pickups(d, poster = server::post)             // restart
        assertEquals(id, after.latest("10007")!!.id)
        assertTrue(after.latest("10007")!!.sent)
        after.sync()
        assertEquals(listOf(id), server.requests)                 // not sent again
    }

    @Test fun restartBeforeRecoveryLosesNothingThatWasSentBecauseNothingWasSent() {
        val d = tmp.newFolder(); configure(d)
        val server = FakeServer()
        val p = Pickups(d, { _, _ -> false }, server::post)
        p.add(listOf("10008")); p.sync()
        assertEquals(0, server.requests.size)                     // never left the phone, so no orphaned id on the tablet
        assertNull(Pickups(d, poster = server::post).latest("10008")) // it was never saved, and the warning told staff
    }

    @Test fun savedTapIsSentWithItsSavedId() {
        val d = tmp.newFolder(); configure(d)
        val server = FakeServer()
        val p = Pickups(d, poster = server::post)
        assertTrue(p.add(listOf("10009")))
        val id = Pickups(d).latest("10009")!!.id                  // the id on disk
        p.sync()
        assertEquals(listOf(id), server.requests)
        assertEquals("Picked up — sent, waiting for the tablet", Pickups.stateText(p.latest("10009")!!))
    }
}
