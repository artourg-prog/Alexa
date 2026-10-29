package com.tailorshop.counter

/** Monotonic callback deadlines. Silence with live RMS callbacks is a healthy session. */
internal class RecognitionWatchdog {
    private var deadline: Long? = null
    private var awaitingResult = false
    fun started(now: Long) { awaitingResult = false; deadline = now + 15_000 }
    fun heardCallback(now: Long) { if (deadline != null && !awaitingResult) deadline = now + 60_000 }
    fun endedSpeech(now: Long) { if (deadline != null && !awaitingResult) { awaitingResult = true; deadline = now + 15_000 } }
    fun stopped() { deadline = null; awaitingResult = false }
    fun stalled(now: Long): Boolean = deadline?.let { now >= it } ?: false
}
