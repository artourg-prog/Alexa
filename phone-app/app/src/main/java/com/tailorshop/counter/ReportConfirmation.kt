package com.tailorshop.counter

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Read-only evidence, scoped to the receipt by the Reader. No phone-clock workday guesses. */
object ReportConfirmation {
    fun validDay(day: String): Boolean = day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
        runCatching { LocalDate.parse(day); true }.getOrDefault(false)

    /** With a txId, only a saved record bound to exactly that receipt counts (a reused number never inherits "Recorded"). */
    fun matches(response: JSONObject, workday: String, worker: String, txId: String = ""): Boolean = match(response, workday, worker, txId) != null

    /** The saved record that confirms this worker/day (and receipt id), or null. */
    fun match(response: JSONObject, workday: String, worker: String, txId: String = ""): JSONObject? {
        if(!response.optBoolean("ok",false)||!validDay(workday))return null
        val wanted=worker.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase(Locale.ROOT)
        if(wanted.isBlank())return null
        val rows=response.optJSONArray("records")?:return null
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull { row ->
            row.optString("date")==workday && row.optString("worker").trim().lowercase(Locale.ROOT)==wanted &&
                (txId.isEmpty() || row.optString("receiptId").trim()==txId)
        }
    }

    /** The Reader's "Saved at" for a record, as shop time "Nov 2, 4:31 PM"; "" when it doesn't report one (never guessed). */
    fun savedAt(record: JSONObject?): String {
        val raw = record?.optString("savedAt").orEmpty().trim()
        if (raw.isEmpty()) return ""
        val shop = runCatching { java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.of("America/Los_Angeles")) }
            .recoverCatching { java.time.Instant.parse(raw).atZone(java.time.ZoneId.of("America/Los_Angeles")) }.getOrNull()
        return if (shop != null) OrderIndex.pretty(shop.toLocalDateTime().toString().replace('T', ' ')) else OrderIndex.pretty(raw)
    }
}
