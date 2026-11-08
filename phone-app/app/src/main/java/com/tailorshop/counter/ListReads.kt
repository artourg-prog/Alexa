package com.tailorshop.counter

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What Sofia knows about the order lists, and how it keeps that fresh (owner 11-08, Alexa #4 6717921168).
 *
 * Every Find, reopened row and Picked up reads all the tabs again, in one request. Before, a Find whose number was already
 * in the copy could be answered from a copy up to a minute old. A copy is now only a fallback: when a read fails the
 * last good copy is kept, marked offline, and its age goes with it (the tablet's last publish if known, saved with the copy, else
 * when this device last read it, labelled as such). Nothing waits on purpose: two requests queued together share the read that started after both,
 * and a Picked up never waits behind another read: reads run side by side and only the newest-started result is kept.
 */
class ListReads(
    private val fetch: () -> Sheets.Lists,
    private val keep: (List<List<String>>, Long, String) -> Unit = { _, _, _ -> }, // rows, read time, publish time
    private val clock: () -> Long = System::currentTimeMillis,
    private val ticks: () -> Long = ::monotonic, // for sharing reads; never moves back when the device clock is corrected
) {
    @Volatile var orders: List<Order> = emptyList(); private set
    /** When the ready list now in use was read (0 = never). */
    @Volatile var loadedAt = 0L; private set
    /** The last read failed: everything shown is the last good copy. */
    @Volatile var offline = false; private set
    /** The last read was refused: no key, or the account has no access (HTTP 403). */
    @Volatile var denied = false; private set
    @Volatile var index: List<OrderIndex.Entry> = emptyList(); private set
    /** Order Index and Picked Up Orders came from the last read (false after a failure or before the first read). */
    @Volatile var indexFresh = false; private set
    @Volatile var listAge = ListAge.Status(ListAge.Level.UNKNOWN); private set
    private var goodStartedAt = Long.MIN_VALUE // ticks when the last good read that is in use started
    private var appliedStartedAt = Long.MIN_VALUE // ticks when the read whose result is in use (good or failed) started

    /** The copy saved on this device, used until the first read; it is never treated as fresh. */
    fun restore(rows: List<List<String>>, at: Long, publishedAsOf: String = "") {
        orders = Lookup.parseOrders(rows); loadedAt = at
        listAge = ListAge.Status(ListAge.Level.UNKNOWN, publishedAsOf.ifEmpty { readHere(at) })
    }

    /**
     * Reads every tab now, unless a read that started at or after [asked] (in [ticks]) has already succeeded. A failure keeps
     * the last good copy and says so; it throws only when there is no copy at all. The lock is held only to look and to
     * keep a result, never across the network, and a result older than the one in use is dropped.
     */
    fun refresh(asked: Long = ticks()) {
        synchronized(this) { if (!offline && loadedAt != 0L && goodStartedAt >= asked) return }
        val start = ticks()
        val lists = try { fetch() } catch (e: Exception) { failed(start, e); return }
        val parsed = try { Lookup.parseOrders(lists.rows) } catch (e: Exception) { failed(start, e); return }
        val idx = lists.index?.let(OrderIndex::parse) ?: emptyList()
        val picked = lists.picked?.let(OrderIndex::parsePickedUp) ?: emptyList()
        val done = clock()
        val age = ListAge.of(lists.sync, ZonedDateTime.ofInstant(Instant.ofEpochMilli(done), ZONE))
        synchronized(this) {
            if (start < appliedStartedAt) return // a read that started later already answered
            runCatching { keep(lists.rows, done, age.asOf) } // a full disk never turns a good read into "offline"
            orders = parsed; loadedAt = done; index = OrderIndex.withHistory(idx, picked); indexFresh = true
            listAge = age
            offline = false; denied = false; goodStartedAt = start; appliedStartedAt = start
        }
    }

    private fun failed(start: Long, e: Exception) {
        synchronized(this) {
            if (start >= appliedStartedAt) {
                offline = true; indexFresh = false; appliedStartedAt = start
                denied = e is Sheets.KeyMissing || e.message == "HTTP 403"
                // The copy's own age: the tablet's last publish when known, else when it was read here, saying so (the data
                // itself can be older than that read).
                listAge = ListAge.Status(ListAge.Level.UNKNOWN, listAge.asOf.ifEmpty { if (loadedAt == 0L) "" else readHere(loadedAt) })
            }
            if (loadedAt == 0L) throw e
        }
    }

    companion object {
        fun monotonic(): Long = System.nanoTime() / 1_000_000
        private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
        fun readTime(ms: Long): String =
            OrderIndex.pretty(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZONE).toLocalDateTime().toString().replace('T', ' '))
        /** Only when the tablet's publish time is unknown: the time this device read the copy, never passed off as the data's age. */
        fun readHere(ms: Long): String = readTime(ms) + " (last read here)"
    }
}
