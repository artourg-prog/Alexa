package com.tailorshop.counter.conversation

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A bounded background wake check covers noisy names missed by the low-latency streaming model. */
class WhisperWake(context:Context,private val found:(Boolean)->Unit):AutoCloseable {
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private val epoch=AtomicInteger(0)
    private var decoder:WhisperDecoder?=null
    private val ring=ShortArray(16_000*5)
    private var cursor=0;private var size=0
    private var lastWords=0L;private var lastCheck=0L
    @Volatile private var attending=false
    @Volatile private var muted=false
    @Volatile private var enabled=false
    @Volatile private var closed=false
    init { executor.execute { try { decoder=WhisperDecoder(context) } catch(_:Exception) {} } }
    @Synchronized fun enabled(value:Boolean) { enabled=value;epoch.incrementAndGet();size=0;cursor=0;lastWords=0;lastCheck=0 }
    @Synchronized fun attending(value:Boolean) {
        if(attending==value)return
        attending=value;epoch.incrementAndGet();size=0;cursor=0;lastWords=0;lastCheck=0
    }
    @Synchronized fun mute(value:Boolean) {
        if(muted==value)return
        muted=value;epoch.incrementAndGet();size=0;cursor=0;lastWords=0;lastCheck=0
    }
    @Synchronized fun feed(samples:ShortArray,count:Int,changedWords:Boolean) {
        if(closed || !enabled || muted || attending)return
        val now=SystemClock.elapsedRealtime()
        for(i in 0 until count) { ring[cursor]=samples[i];cursor=(cursor+1)%ring.size;size=minOf(size+1,ring.size) }
        if(changedWords)lastWords=now
        if(size<16_000*3 || now-lastWords>1800 || now-lastCheck<2000 || !busy.compareAndSet(false,true))return
        lastCheck=now
        val version=epoch.get();val audio=FloatArray(size)
        for(i in audio.indices)audio[i]=ring[(cursor-size+i+ring.size)%ring.size]/32768f
        executor.execute {
            try {
                if(version!=epoch.get() || closed)return@execute
                val text=decoder?.decode(audio).orEmpty()
                if(version==epoch.get() && !closed && !muted && !attending && OfflineSpeech.wakeWord(text))
                    found(text.any { it in '\u0400'..'\u04ff' })
            } catch(_:Exception) { /* main streaming recognition remains available */ }
            finally { busy.set(false) }
        }
    }
    override fun close() {
        if(closed)return
        closed=true;epoch.incrementAndGet()
        executor.execute { decoder?.close();decoder=null };executor.shutdown()
    }
}
