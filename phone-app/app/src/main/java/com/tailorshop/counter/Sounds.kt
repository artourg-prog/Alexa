package com.tailorshop.counter

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

// Art's sounds (10-28): the phone's own Samsung notification sounds, played from the system at runtime
// (nothing is copied into the app), on the same volume as Sofia's voice so silent mode doesn't mute them.
// One pick per event; a sound missing on the phone plays nothing (Art removed the built-in chimes).
object Sounds {
    enum class Event(val label: String, val key: String, val default: String) {
        WAKE("Wake (Sofia heard)", "snd_wake", "Crystal"),   // Art asked for "Sequence": not on this Fold
        SAVED("Saved in Daily Report", "snd_saved", "Voyager"),
        SMS("SMS sent", "snd_sms", "Crescent"),
        PICKED("Picked up", "snd_picked", "Chaos"),
    }

    /** Art's list, in his order; only the ones this phone has are offered. */
    val WANTED = listOf("Sequence", "Crescent", "Chaos", "Crystal", "Voyager", "Red Dwarf", "Milky Way")
    const val OFF = "Off"
    private const val VOLUME = 0.45f // quiet

    private var found: Map<String, Uri>? = null

    /** Title -> sound on this phone, for Art's list. */
    fun available(ctx: Context): Map<String, Uri> = found ?: run {
        val out = linkedMapOf<String, Uri>()
        try {
            val rm = RingtoneManager(ctx).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
            val c = rm.cursor
            val all = mutableMapOf<String, Uri>()
            while (c.moveToNext()) all[c.getString(RingtoneManager.TITLE_COLUMN_INDEX)] = rm.getRingtoneUri(c.position)
            for (w in WANTED) all.entries.firstOrNull { it.key.equals(w, true) }?.let { out[w] = it.value }
        } catch (e: Exception) { Log.d("TailorCounter", "sound list failed: ${e.message}") }
        found = out
        out
    }

    fun choice(ctx: Context, e: Event): String =
        ctx.getSharedPreferences("sounds", Context.MODE_PRIVATE).getString(e.key, e.default) ?: e.default

    fun choose(ctx: Context, e: Event, title: String) =
        ctx.getSharedPreferences("sounds", Context.MODE_PRIVATE).edit().putString(e.key, title).apply()

    fun play(ctx: Context, e: Event) = playTitle(ctx, choice(ctx, e))

    fun playTitle(ctx: Context, title: String) {
        if (title == OFF) return
        val uri = available(ctx)[title] ?: return
        try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                setVolume(VOLUME, VOLUME)
                setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepare(); start()
            }
        } catch (ex: Exception) { Log.d("TailorCounter", "sound $title failed: ${ex.message}") }
    }
}
