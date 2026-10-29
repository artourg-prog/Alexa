package com.tailorshop.counter
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class ReportStatusTest {
    private val workers = listOf("Artour", "Andrei")
    @Test fun explicitInquiryIsReadOnly() {
        val r = ReportStatus.parse("София это Артур проверь ресит 26372 он записан в Daily Report",workers)!!
        assertEquals("26372",r.number);assertNull(r.worker)
        assertNull(ReportStatus.parse("София это Артур запиши 26372 в Daily Report",workers))
        assertNull(ReportStatus.parse("София проверь 26372",workers))
        assertNull(ReportStatus.parse("is receipt 26372 saved in daily report",workers))
    }
    @Test fun askingWorkerAndUnclearNumbers() {
        assertEquals("Andrei",ReportStatus.parse("Sofia check receipt 26372 in daily report under Andrei",workers)!!.worker)
        assertNull(ReportStatus.parse("Sofia check 2807 in daily report",workers)!!.number)
        assertNull(ReportStatus.parse("Sofia check 26372 and 26373 in daily report",workers)!!.number)
    }
    @Test fun emptyAndFailureAreDifferent() {
        val r=ReportStatus.Request("26372",null)
        assertTrue(ReportStatus.reply(r,JSONObject("{ok:true,source:savedReports,records:[]}"),false).contains("Not found in saved reports"))
        assertTrue(ReportStatus.reply(r,JSONObject("{ok:false}"),false).contains("couldn't check"))
    }
    @Test fun reportsWorkersAndHistoricalDatesWithoutAssumingToday() {
        val data=JSONObject("{ok:true,source:savedReports,records:[{worker:Artour,date:'2026-10-28'},{worker:Andrei,date:'2026-10-29'}]}")
        val r=ReportStatus.reply(ReportStatus.Request("26372",null),data,false)
        assertTrue(r.contains("Artour"));assertTrue(r.contains("Andrei"));assertTrue(r.contains("2026-10-28"))
        assertTrue(ReportStatus.reply(ReportStatus.Request("26372","Emma"),data,false).contains("Not found under Emma"))
    }
}
