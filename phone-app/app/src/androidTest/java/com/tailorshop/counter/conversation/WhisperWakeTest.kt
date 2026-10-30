package com.tailorshop.counter.conversation

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class WhisperWakeTest {
    @Test fun backupFindsNoisyNamesWithoutWakingOnNegativeSpeech() {
        val ins=InstrumentationRegistry.getInstrumentation()
        WhisperDecoder(ins.targetContext).use { decoder ->
            for(lang in listOf("ru","en")) {
                val start=System.nanoTime()
                val text=decoder.decode(wav("$lang-noisy"),lang)
                val ms=(System.nanoTime()-start)/1_000_000
                android.util.Log.i("SofiaFixture","whisper $lang noisy decode=$ms text=$text")
                assertTrue("Backup missed noisy $lang wake: $text",OfflineSpeech.wakeWord(text))
                assertTrue("Backup lost noisy $lang digits: $text","12345" in SafeIntent.numbers(text))
                assertTrue("Auto-language backup missed noisy $lang wake",OfflineSpeech.wakeWord(decoder.decode(wav("$lang-noisy"))))
                assertFalse("Backup false wake $lang",OfflineSpeech.wakeWord(decoder.decode(wav("$lang-negative"),lang)))
            }
        }
    }
    @Test fun fallbackSchedulerWakesAndMutesWithoutAudioBacklog() {
        val hits=java.util.concurrent.atomic.AtomicInteger(0)
        val wake=WhisperWake(InstrumentationRegistry.getInstrumentation().targetContext) { hits.incrementAndGet() }
        val audio=wav("ru-noisy").map { (it*32768).toInt().coerceIn(-32768,32767).toShort() }.toShortArray()
        fun feed() { var offset=0;while(offset<audio.size) {
            val chunk=audio.copyOfRange(offset,minOf(offset+1600,audio.size));wake.feed(chunk,chunk.size,true);offset+=chunk.size
        } }
        try {
            wake.enabled(true);feed()
            val until=android.os.SystemClock.elapsedRealtime()+8000
            while(hits.get()==0 && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50)
            assertEquals("Scheduler did not detect the noisy Russian name",1,hits.get())
            wake.attending(true);feed();Thread.sleep(150);assertEquals(1,hits.get())
            wake.attending(false);wake.mute(true);feed();Thread.sleep(150);assertEquals(1,hits.get())
            wake.mute(false);feed()
            val resumeUntil=android.os.SystemClock.elapsedRealtime()+8000
            while(hits.get()==1 && android.os.SystemClock.elapsedRealtime()<resumeUntil)Thread.sleep(50)
            assertEquals("Scheduler did not recover after mute",2,hits.get())
        } finally { wake.close() }
    }
    private fun wav(name:String):FloatArray {
        val b=InstrumentationRegistry.getInstrumentation().context.assets.open("voice-fixtures/$name.wav").readBytes()
        fun le(i:Int)=(b[i].toInt()and 255) or ((b[i+1].toInt()and 255) shl 8) or ((b[i+2].toInt()and 255) shl 16) or ((b[i+3].toInt()and 255) shl 24)
        var p=12;while(String(b,p,4,Charsets.US_ASCII)!="data") { val n=le(p+4);p+=8+n+(n%2) }
        val n=le(p+4);p+=8
        return FloatArray(n/2) { i->(((b[p+i*2].toInt()and 255) or (b[p+i*2+1].toInt() shl 8)).toShort())/32768f }
    }
}
