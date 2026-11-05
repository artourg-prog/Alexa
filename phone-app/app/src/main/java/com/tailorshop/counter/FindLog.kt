package com.tailorshop.counter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// What visitors asked at Find and what the answer was (owner 11-04, #4): the right column on home, so a worker can come
// back after the screen went dark or Sofia restarted. Local to this phone and never sent anywhere; only the last four
// digits are kept, never the phone number or the customer's name.
//
// The column shows everything searched today (owner 11-05), shop calendar day, newest first; the next day it starts
// empty and the records stay in the file (Settings → Find history). A row is a memory, never the authority: tapping it
// reads the lists again first. A search or a four-digit match never counts as a pickup. A row says Picked up only on a
// confirmed pickup: the tablet's answer to a tap (Pickups.DONE), or a NEW picked-up row for the same number and last
// four on the shared list. An order merely leaving the ready list proves nothing.
class FindLog(private val dir: File, private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) {
    /** One receipt a search answered with. [priorPicked] = picked-up rows already on the list for this number and last four. */
    data class Hit(val receipt: String, val txn: String, val state: String, val priorPicked: Int = 0, val shown: String = "", val amount: String = "")

    /** [firstMs] = first search of this request today; [atMs] = its latest search. [pickedMs] != 0 = confirmed pickup.
     *  [receipt] is the plain key every comparison uses; [shown] = the recorded spelling when it differs (26-490).
     *  [amount] = the receipt total as the list showed it at the search; empty when that list carries none. */
    data class Entry(val id: String, val firstMs: Long, val atMs: Long, val last4: String, val receipt: String, val txn: String,
                     val state: String, val listState: String, val asOf: String, val offline: Boolean, val priorPicked: Int,
                     val pickedMs: Long = 0L, val pickedBy: String = "", val pickedAt: String = "", val shown: String = "", val amount: String = "") {
        fun number() = shown.ifEmpty { receipt }
    }

    /** What the shared list says now about one entry; [state] null = it no longer lists it, so the answer stays as it was. */
    data class Seen(val state: String?, val picked: Int, val pickedAt: String)

    data class Row(val entry: Entry, val time: String, val line: String, val unsure: Boolean)

    companion object {
        const val READY = "READY"; const val NOT_READY = "NOT_READY"; const val COLLECTED = "COLLECTED"; const val UNKNOWN = "UNKNOWN"
        const val CONFLICT = "CONFLICT"; const val NOT_FOUND = "NOT_FOUND"; const val NO_ANSWER = "NO_ANSWER"; const val NO_ACCESS = "NO_ACCESS"
        const val BY_TABLET = "TABLET"; const val BY_LIST = "LIST"
        private const val KEEP_DAYS = 30L
        private const val KEEP_MAX = 2000
        private const val SHOW_MAX = 100
        private val zone: ZoneId = ZoneId.of("America/Los_Angeles")
        private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val dated = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

        private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun time(ms: Long): String = clock.format(Instant.ofEpochMilli(ms).atZone(zone))

        /** Still waiting for a pickup: a real receipt, not already picked up when it was searched, and not confirmed since. */
        fun open(e: Entry) = e.pickedMs == 0L && e.receipt.isNotEmpty() && e.state != COLLECTED

        /** Past pickups aren't requests: they are logged only when a search found nothing else, and then just the newest. */
        fun kept(hits: List<Hit>): List<Hit> = hits.filter { it.state != COLLECTED }.ifEmpty { hits.take(1) }

        /** This tap is about this request: the same Txn, or (Txn unknown) made after the request, since numbers can repeat. */
        fun belongs(e: Entry, r: Pickups.Req) = r.about(e.receipt, e.txn) &&
            (e.txn.isNotEmpty() && r.txn == e.txn || r.at >= e.firstMs)

        private fun answer(e: Entry) = when (e.state) {
            READY -> "Ready for pickup"
            NOT_READY -> "Not ready"
            COLLECTED -> "Already picked up"
            CONFLICT -> "Pickup records disagree — check the tablet"
            NOT_FOUND -> "Not found"
            NO_ANSWER -> "Not checked — no connection"
            NO_ACCESS -> "Not checked — no access to the orders list"
            else -> "Status unknown — check the tablet"
        }

        /** How far to trust the answer; a past pickup stays true however old the list is, and "not checked" has no list. */
        private fun age(e: Entry) = when {
            e.state == NO_ANSWER || e.state == NO_ACCESS || e.state == COLLECTED -> ""
            e.offline -> " · offline copy, may be out of date"
            e.listState == ListAge.Level.OLD.name -> " · list as of ${e.asOf}, may be out of date"
            e.listState == ListAge.Level.UNKNOWN.name -> " · list age unknown"
            else -> ""
        }

        /** The row's line. A tap still waiting is said as such: it is not a pickup until the tablet answers. */
        fun line(e: Entry, tap: Pickups.Req?): String = when {
            e.pickedMs != 0L -> "Picked up" + if (e.pickedAt.isNotBlank()) " · ${OrderIndex.pretty(e.pickedAt)}" else ""
            tap != null && open(e) && belongs(e, tap) -> Pickups.stateText(tap)
            else -> answer(e) + age(e)
        }

        private fun sure(e: Entry) = e.pickedMs != 0L || e.state in setOf(READY, NOT_READY, NOT_FOUND, COLLECTED) && age(e).isEmpty()

        /** One line of Settings → Find history, confirmed pickups included. */
        fun historyLine(e: Entry): String {
            val who = listOf(e.number().takeIf { it.isNotEmpty() }?.let { "#$it" }, "last four ${e.last4}").filterNotNull().joinToString(" · ")
            val end = when (e.pickedBy) {
                BY_TABLET -> "Picked up — confirmed by the tablet"
                BY_LIST -> "Picked up — on the shared list" + if (e.pickedAt.isNotBlank()) " · ${OrderIndex.pretty(e.pickedAt)}" else ""
                else -> answer(e) + age(e)
            }
            return "${dated.format(Instant.ofEpochMilli(e.atMs).atZone(zone))} · $who · $end"
        }
    }

    private val file = File(dir, "find-log.json")
    private val tmp = File(dir, "find-log.tmp")
    private val entries = mutableListOf<Entry>()
    @Volatile private var saveFailed = false
    @Volatile private var unreadable = false

    init { load() }

    /** Said in the column: the history is not what it should be. Find itself keeps working either way. */
    fun warning(): String? = when {
        saveFailed -> "This history can't be saved on this phone right now."
        unreadable -> "The earlier history couldn't be read; its file was kept aside."
        else -> null
    }

    /** Saves one search. A repeat of the same request today moves it to the top instead of adding a second row. */
    @Synchronized fun add(last4: String, hits: List<Hit>, listState: String, asOf: String, offline: Boolean,
                          now: Long = System.currentTimeMillis()): Boolean {
        kept(hits).forEach { h ->
            // The index carries no Txn: a picked-up order found there, or a row first found there and now on the ready list
            // with its Txn, is the same row, not a second one.
            val at = entries.indexOfLast { it.last4 == last4 && it.receipt == h.receipt && day(it.atMs) == day(now) &&
                (it.txn == h.txn || h.txn.isEmpty() && it.pickedMs != 0L || it.txn.isEmpty() && it.pickedMs == 0L) }
            val was = entries.getOrNull(at)
            if (was != null && was.pickedMs != 0L) entries[at] = was.copy(atMs = now) // a confirmed pickup stays one, on top
            else if (was != null) entries[at] = was.copy(atMs = now, txn = h.txn, state = h.state, listState = listState, asOf = asOf, offline = offline, priorPicked = h.priorPicked,
                shown = h.shown.ifEmpty { was.shown }, amount = h.amount.ifEmpty { was.amount })
            else entries += Entry(UUID.randomUUID().toString(), now, now, last4, h.receipt, h.txn, h.state, listState, asOf, offline, h.priorPicked, shown = h.shown, amount = h.amount)
        }
        return save()
    }

    /** The tablet's answers. Runs on every home refresh and in the background job, before a tap's own record ages out. */
    @Synchronized fun settleTaps(tap: (Entry) -> Pickups.Req?, now: Long = System.currentTimeMillis()) {
        var changed = false
        entries.toList().forEachIndexed { i, e ->
            val r = if (open(e)) tap(e) else null
            if (r != null && r.status in Pickups.DONE && belongs(e, r)) { entries[i] = e.copy(pickedMs = now, pickedBy = BY_TABLET); changed = true }
        }
        if (changed) save()
    }

    /** A good read of the shared list: a new picked-up row confirms a pickup; anything else brings the answer up to date. */
    @Synchronized fun settleList(seen: (Entry) -> Seen?, listState: String, asOf: String, now: Long = System.currentTimeMillis()) {
        var changed = false
        entries.toList().forEachIndexed { i, e ->
            val s = if (open(e)) seen(e) else null
            val next = when {
                s == null -> e
                s.picked > e.priorPicked -> e.copy(pickedMs = now, pickedBy = BY_LIST, pickedAt = s.pickedAt)
                else -> e.copy(state = s.state ?: e.state, listState = listState, asOf = asOf, offline = false)
            }
            if (next != e) { entries[i] = next; changed = true }
        }
        if (changed) save()
    }

    /** Everything searched today, newest first. */
    @Synchronized fun rows(tap: (Entry) -> Pickups.Req?, now: Long = System.currentTimeMillis()): List<Row> =
        entries.filter { day(it.atMs) == day(now) }.sortedByDescending { it.atMs }.take(SHOW_MAX)
            .map { e -> val r = tap(e); Row(e, time(e.atMs), line(e, r), !sure(e) || r != null && open(e) && belongs(e, r)) }

    /** Open requests from today: the ones worth another read of the shared list. */
    @Synchronized fun anyOpen(now: Long = System.currentTimeMillis()) = entries.any { open(it) && day(it.atMs) == day(now) }

    /** Everything kept, newest first, for Settings. */
    @Synchronized fun history(limit: Int = 200): List<Entry> = entries.sortedByDescending { maxOf(it.atMs, it.pickedMs) }.take(limit)

    private fun load() {
        if (!file.isFile) return
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) a.getJSONObject(i).run {
                entries += Entry(getString("id"), getLong("firstMs"), getLong("atMs"), getString("last4"), optString("receipt"), optString("txn"),
                    getString("state"), optString("listState"), optString("asOf"), optBoolean("offline"), optInt("priorPicked"),
                    optLong("pickedMs"), optString("pickedBy"), optString("pickedAt"), optString("shown"), optString("amount"))
            }
        } catch (e: Exception) {
            entries.clear(); unreadable = true
            file.renameTo(File(dir, "find-log.unreadable-${System.currentTimeMillis()}.json"))
        }
    }

    private fun save(now: Long = System.currentTimeMillis()): Boolean {
        entries.removeAll { now - maxOf(it.atMs, it.pickedMs) > KEEP_DAYS * 24 * 3600_000L }
        while (entries.size > KEEP_MAX) entries.removeAt(0)
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("id", it.id).put("firstMs", it.firstMs).put("atMs", it.atMs).put("last4", it.last4)
            .put("receipt", it.receipt).put("txn", it.txn).put("state", it.state).put("listState", it.listState).put("asOf", it.asOf)
            .put("offline", it.offline).put("priorPicked", it.priorPicked).put("pickedMs", it.pickedMs).put("pickedBy", it.pickedBy).put("pickedAt", it.pickedAt).put("shown", it.shown).put("amount", it.amount)) }
        val ok = try {
            FileOutputStream(tmp).use { it.write(a.toString().toByteArray()); it.fd.sync() }
            replace(tmp, file)
        } catch (e: Exception) { false }
        saveFailed = !ok
        return ok
    }
}
