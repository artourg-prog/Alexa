package com.tailorshop.counter

/** A short addressed conversation, across separate recognizer results. No room-talk capture or writes. */
internal class SpeechContext {
    private var until = 0L
    private var hardUntil = 0L
    private var worker: String? = null
    fun clear() { until = 0L; hardUntil = 0L; worker = null }
    fun resolve(alts: List<String>, workers: List<String>, waiting: Lookup.Waiting, now: Long): List<String> {
        val best = alts.firstOrNull() ?: return alts
        if (now >= until) clear()
        val addressed = Lookup.addressed(best)
        if (waiting != Lookup.Waiting.NONE && !addressed) { clear(); return alts }
        val n = Lookup.normalize(best)
        val introduced = Regex("(?:^| )(?:eto|ya|this is|i am|i m) ([a-z]+)").find(n)
            ?.groupValues?.get(1)?.let { Lookup.matchWorker(it, workers) }
        val relevant = introduced != null || Lookup.isFinishPhrase(best) || Lookup.isReadOnlyQuestion(best) ||
            Lookup.parseQueries(best).any { it !is Query.Name }
        if (!addressed && (now >= until || !relevant)) return alts
        if (until == 0L) hardUntil = now + 60_000
        until = minOf(now + 30_000, hardUntil)
        if (introduced != null) worker = introduced
        return alts.map { a ->
            if (Lookup.addressed(a)) a
            else "Sofia, " + (worker?.let { "this is $it, " } ?: "") + a
        }
    }
}
