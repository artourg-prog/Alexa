package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Payroll evidence tests use only synthetic data and a fake Reader. */
class ReportConfirmationTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Reader {
        var day = "2026-11-01"
        var worker = "andrei"
        var saved = false
        var offline = false
        var state = "ADDED"
        val calls = mutableListOf<JSONObject>()
        fun post(url: String, b: JSONObject): JSONObject {
            calls += b
            return when(b.getString("mode")) {
                "addScan", "scanStatus" -> JSONObject().put("ok",true).put("status",state).put("workday",day)
                "receiptReportStatus" -> {
                    if(offline)error("synthetic offline")
                    JSONObject().put("ok",true).put("records",JSONArray().apply {
                        if(saved)put(JSONObject().put("date",day).put("worker",worker))
                    })
                }
                "pickup" -> JSONObject().put("ok",true).put("state","FAILED")
                else -> error("Unexpected write")
            }
        }
        fun count(mode:String)=calls.count { it.optString("mode")==mode }
    }
    private fun setup():File = tmp.newFolder().apply {
        File(this,"finish-config.json").writeText("""{"url":"https://fake.test","secret":"synthetic","workers":["Andrei"]}""")
    }
    @Test fun requiresExactSavedWorkerAndDayAndSuccessfulReply() {
        fun reply(day:String,worker:String)=JSONObject().put("ok",true).put("records",JSONArray().put(JSONObject().put("date",day).put("worker",worker)))
        assertTrue(ReportConfirmation.matches(reply("2026-11-01","ANDREI"),"2026-11-01","Andrei"))
        assertFalse(ReportConfirmation.matches(reply("2026-10-31","andrei"),"2026-11-01","Andrei"))
        assertFalse(ReportConfirmation.matches(reply("2026-11-01","artour"),"2026-11-01","Andrei"))
        assertFalse(ReportConfirmation.matches(reply("2026-11-01","andrei2"),"2026-11-01","Andrei"))
        assertFalse(ReportConfirmation.matches(reply("2026-11-01","andrei").put("ok",false),"2026-11-01","Andrei"))
        assertFalse(ReportConfirmation.validDay("2026-02-30"))
        assertFalse(ReportConfirmation.validDay(""))
    }
    @Test fun addedAndAlreadyWaitForPersistenceAcrossRestartWithoutAnotherCredit() {
        for(state in listOf("ADDED","ALREADY")) {
            val dir=setup();val r=Reader().apply { this.state=state }
            var f=Finishes(dir,poster=r::post)
            val item=f.add("Andrei","26316",false)!!;f.sync()
            assertFalse(Finishes.recorded(f.get(item.scanId)!!))
            f=Finishes(dir,poster=r::post);r.saved=true;f.sync()
            assertTrue(Finishes.recorded(f.get(item.scanId)!!))
            assertEquals(1,r.count("addScan"));assertEquals(0,r.count("pickup"))
        }
    }
    @Test fun offlineConfirmationRetainsSameEventAndRecovers() {
        val dir=setup();val r=Reader().apply { offline=true };val f=Finishes(dir,poster=r::post)
        val item=f.add("Andrei","26316",false)!!;f.sync()
        assertEquals("UNCONFIRMED",f.get(item.scanId)!!.reportState)
        r.offline=false;r.saved=true;f.sync()
        assertTrue(Finishes.recorded(f.get(item.scanId)!!));assertEquals(1,r.count("addScan"))
    }
    @Test fun legacyAppliedItemsRecoverReaderDayAndSurviveOldAge() {
        val dir=setup();val old=System.currentTimeMillis()-3*86400000L
        File(dir,"finish-queue.json").writeText(JSONArray().put(JSONObject()
            .put("scanId","old").put("worker","Andrei").put("receipt","26316")
            .put("scannedAt","2026-10-29T19:00:00Z").put("createdMs",old).put("scan","ADDED")).toString())
        val r=Reader();var f=Finishes(dir,poster=r::post);f.sync()
        assertNotNull(f.get("old"));assertEquals("2026-11-01",f.get("old")!!.workday)
        assertEquals(0,r.count("addScan"));assertEquals(1,r.count("scanStatus"))
        f=Finishes(dir,poster=r::post);r.saved=true;f.sync()
        assertTrue(Finishes.recorded(f.get("old")!!))
        assertNotNull(Finishes(dir,poster=r::post).get("old"))
    }
    @Test fun missingWorkdayNeverUsesPhoneClockOrClaimsSaved() {
        val dir=setup();val r=Reader().apply { day="";saved=true };val f=Finishes(dir,poster=r::post)
        val item=f.add("Andrei","26316",false)!!;f.sync();f.sync()
        assertFalse(Finishes.recorded(f.get(item.scanId)!!))
        assertEquals(0,r.count("receiptReportStatus"));assertEquals(1,r.count("addScan"))
    }
    @Test fun sameEventRechecksSavedEvidenceAndSmsFailureDoesNotUndoPayroll() {
        val dir=setup();val r=Reader().apply { saved=true };val f=Finishes(dir,poster=r::post)
        val item=f.addOrReuse("Andrei","26316")!!;f.requestSms(item.scanId);f.sync()
        assertTrue(Finishes.recorded(f.get(item.scanId)!!));assertEquals("FAILED",f.get(item.scanId)!!.sms)
        r.saved=false
        assertEquals(item.scanId,f.addOrReuse("Andrei","26316")!!.scanId)
        f.sync();assertFalse(Finishes.recorded(f.get(item.scanId)!!))
        assertEquals(1,r.count("addScan"));assertEquals(1,r.count("pickup"))
    }
    @Test fun multipleReceiptsRetainDistinctEventsWithOneWriter() {
        val dir=setup();val r=Reader().apply { saved=true };val f=Finishes(dir,poster=r::post)
        val a=f.add("Andrei","26316",false)!!;val b=f.add("Andrei","26317",false)!!;f.sync();f.sync()
        assertNotEquals(a.scanId,b.scanId);assertTrue(f.all().all(Finishes::recorded))
        assertEquals(2,r.count("addScan"));assertEquals(2,r.count("receiptReportStatus"))
    }
}
