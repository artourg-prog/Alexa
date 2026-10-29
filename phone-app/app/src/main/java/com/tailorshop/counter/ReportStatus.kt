package com.tailorshop.counter

import org.json.JSONObject

/** Read-only report inquiry; never parses a command to record another scan. */
object ReportStatus {
    data class Request(val number: String?, val worker: String?)
    fun parse(said: String, workers: List<String>): Request? {
        if (!Lookup.addressed(said)) return null
        val n = Lookup.normalize(said)
        val mentionsReport = listOf("report", "otchet").any { it in n }
        val asksRecordedBy = ("pod kem" in n && ("zapisan" in n || "sokhranen" in n)) || "who recorded" in n
        if (!mentionsReport && !asksRecordedBy) return null
        if (listOf("prover", "check", "saved", "recorded", "sokhranen", "zapisan", "pod kem", "under", "who recorded").none { it in n }) return null
        val nums = Lookup.parseQueries(said).filterIsInstance<Query.Receipt>().map { it.number }.distinct()
        val under = Regex("(?:pod imenem|pod|under|for) (.+)").find(n)?.groupValues?.get(1)
        val worker = under?.split(' ')?.firstNotNullOfOrNull { Lookup.matchWorker(it, workers) }
        return Request(nums.singleOrNull(), worker)
    }

    fun reply(request: Request, result: JSONObject, ru: Boolean): String {
        if (!result.optBoolean("ok") || result.optString("source") != "savedReports")
            return if (ru) "Сейчас не удалось проверить Daily Report." else "I couldn't check Daily Report right now."
        val a = result.optJSONArray("records") ?: return if (ru) "Не удалось проверить отчёт." else "I couldn't check the report."
        val entries = (0 until a.length()).map { a.getJSONObject(it) }
        if (entries.isEmpty()) return if (ru) "В сохранённых отчётах эта квитанция не найдена. Несохранённые записи планшета здесь не видны."
            else "Not found in saved reports. Unsaved tablet entries are not visible here."
        val matching = request.worker?.let { w -> entries.filter { it.optString("worker").equals(w, true) } }.orEmpty()
        val selected = if (matching.isNotEmpty()) matching else entries
        val descriptions = selected.take(4).map { "${it.optString("worker")}, ${it.optString("date")}" }.distinct().joinToString("; ")
        val prefix = when {
            request.worker != null && matching.isEmpty() -> if (ru) "Под именем ${request.worker} не найдена. Есть записи: " else "Not found under ${request.worker}. Recorded under: "
            else -> if (ru) "В сохранённых отчётах: " else "In saved reports: "
        }
        val more = if (selected.size > 4 || result.optBoolean("truncated")) if (ru) ". Есть ещё записи." else ". There are more records." else "."
        return prefix + descriptions + more
    }
}
