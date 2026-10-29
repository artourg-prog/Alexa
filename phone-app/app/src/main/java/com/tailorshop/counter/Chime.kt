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
    // overtone = how much of the 2nd harmonic (glassy) and 4th (marimba "tok"); all notes 400–800 Hz (Art: warm, not sharp).
    data class Option(val name: String, val notes: List<Pair<Double, Double>>, val second: Double = 0.12, val fourth: Double = 0.0)

    val OPTIONS = listOf(
        Option("Warm ding-dong", listOf(587.33 to 0.0, 440.0 to 0.30)),               // D5 then A4, soft doorbell
        Option("Marimba", listOf(523.25 to 0.0, 659.25 to 0.16), second = 0.0, fourth = 0.25), // C5 then E5, wooden
        Option("Glass", listOf(698.46 to 0.0), second = 0.2),                          // one soft glass tone, F5
        Option("Low bell", listOf(440.0 to 0.0, 659.25 to 0.0), second = 0.05),        // A4 + E5 together, calm
    )

    private const val RATE = 44_100
    private const val LENGTH_S = 0.85
    private const val VOLUME = 0.2 // soft: a cue, not an alarm (Art: the first ones were too aggressive)

    private fun samples(o: Option): ShortArray {
        val n = (RATE * LENGTH_S).toInt()
        val out = DoubleArray(n)
        for ((f, start) in o.notes) {
            // gentle ~40 ms fade-in, then a slow bell fade; the 4th harmonic fades fast (marimba attack only)
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                val env = (1 - exp(-t * 25)) * exp(-t * 4.5)
                out[i] += env * (sin(2 * PI * f * t) + o.second * sin(4 * PI * f * t) + o.fourth * exp(-t * 30) * sin(8 * PI * f * t))
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
                // Same volume as her voice (media). "Sonification" followed the system volume, which is 0 when
                // the phone is on silent / vibrate, so the chime was mute on the Fold (10-28).
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
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
