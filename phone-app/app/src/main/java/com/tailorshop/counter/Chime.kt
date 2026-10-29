package com.tailorshop.counter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// The soft "I heard you" chime when Sofia hears her name (Art). Made in code, no sound files: short
// two-note bell tones with a gentle fade, under 1 s, at low volume. Art picks one in Settings.
object Chime {
    data class Option(val name: String, val notes: List<Pair<Double, Double>>) // (frequency Hz, start s)

    val OPTIONS = listOf(
        Option("Ding-dong", listOf(659.25 to 0.0, 523.25 to 0.28)),   // E5 then C5, like a door / elevator chime
        Option("Rising", listOf(523.25 to 0.0, 783.99 to 0.18)),      // C5 then G5, bright "yes?"
        Option("Soft bell", listOf(880.0 to 0.0, 1318.5 to 0.06)),    // A5 + E6 together, one gentle bell
    )

    private const val RATE = 44_100
    private const val LENGTH_S = 0.85
    private const val VOLUME = 0.22 // low: a cue, not an alarm

    private fun samples(o: Option): ShortArray {
        val n = (RATE * LENGTH_S).toInt()
        val out = DoubleArray(n)
        for ((f, start) in o.notes) {
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val env = (1 - exp(-t * 200)) * exp(-t * 5.5) // soft attack, bell-like fade
                out[i] += env * (sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t))
            }
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(n) { (out[it] / peak * VOLUME * Short.MAX_VALUE).toInt().toShort() }
    }

    fun play(index: Int) {
        val o = OPTIONS.getOrNull(index) ?: return // -1 = off
        Thread {
            val pcm = samples(o)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            try {
                track.write(pcm, 0, pcm.size); track.play()
                Thread.sleep((LENGTH_S * 1000).toLong() + 100)
            } catch (_: Exception) {
            } finally { track.release() }
        }.start()
    }
}
