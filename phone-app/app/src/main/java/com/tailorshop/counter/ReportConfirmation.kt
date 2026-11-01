package com.tailorshop.counter

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Read-only evidence, scoped to the receipt by the Reader. No phone-clock workday guesses. */
object ReportConfirmation {
    fun validDay(day: String): Boolean = day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        runCatching { LocalDate.parse(day); true }.getOrDefault(false)

    fun matches(response: JSONObject, workday: String, worker: String): Boolean {
        if(!response.optBoolean("ok",false)||!validDay(workday))return false
        val wanted=worker.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase(Locale.ROOT)
        if(wanted.isBlank())return false
        val rows=response.optJSONArray("records")?:return false
        return (0 until rows.length()).any { n ->
            val row=rows.optJSONObject(n)
            row!=null && row.optString("date")==workday && row.optString("worker").trim().lowercase(Locale.ROOT)==wanted
        }
    }
}
