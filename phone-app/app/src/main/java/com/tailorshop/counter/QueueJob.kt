package com.tailorshop.counter

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

// Saved work and pickup taps finish while Sofia is in the background, the screen is off, or after a restart (owner
// 11-03: Save returns home at once). Android runs this job when a network is available; it sends/asks once per run
// with the same durable ids (nothing new is created here) and schedules itself again while anything is unfinished.
/** One copy of each durable queue per process: the screens and the background job never hold two copies of the same
 *  file (two copies would overwrite each other's progress). [syncing] keeps one send/ask pass at a time. */
object Queues {
    @Volatile private var f: Finishes? = null
    @Volatile private var p: Pickups? = null
    @Volatile private var l: FindLog? = null
    val syncing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Synchronized fun finishes(ctx: Context): Finishes = f ?: Finishes(ctx.applicationContext.filesDir).also { f = it }
    @Synchronized fun pickups(ctx: Context): Pickups = p ?: Pickups(ctx.applicationContext.filesDir).also { p = it }
    @Synchronized fun findLog(ctx: Context): FindLog = l ?: FindLog(ctx.applicationContext.filesDir).also { l = it }
}

class QueueJob : JobService() {
    companion object {
        private const val JOB_ID = 4302

        /** Queue a run (no-op when one is already waiting). Off-network it waits for the network, after a short delay. */
        fun schedule(ctx: Context, delayMs: Long = 15_000) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            runCatching {
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, QueueJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delayMs)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setPersisted(true).build())
            }.onFailure { Log.d("TailorCounter", "queue job not scheduled: ${it.javaClass.simpleName}") }
        }

        /** Anything still to send or confirm in the two durable queues. */
        fun unfinished(finishes: Finishes, pickups: Pickups): Boolean =
            finishes.all().any { (it.scan !in Finishes.SCAN_FINAL || Finishes.reportPending(it) || it.sendSms && it.sms !in Finishes.SMS_FINAL) &&
                !(it.scan == "UNVERIFIED" && it.createdMs < System.currentTimeMillis() - 24 * 3600_000L) } ||
                pickups.pending().any { pickups.latest(it)?.status !in Pickups.DONE }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            var again = false
            try {
                val finishes = Queues.finishes(this); val pickups = Queues.pickups(this)
                if (Queues.syncing.compareAndSet(false, true)) {
                    try { runCatching { finishes.sync() }; runCatching { pickups.sync() } } finally { Queues.syncing.set(false) }
                }
                Queues.findLog(this).settleTaps({ e -> pickups.latest(e.receipt, e.txn) }) // before a settled tap ages out of its queue
                again = unfinished(finishes, pickups)
            } catch (e: Throwable) { again = true; Log.d("TailorCounter", "queue job: ${e.javaClass.simpleName}") }
            jobFinished(params, false)
            if (again) schedule(applicationContext, 60_000)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true // stopped by Android: run again later
}
