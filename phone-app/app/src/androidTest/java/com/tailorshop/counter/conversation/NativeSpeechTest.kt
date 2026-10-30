package com.tailorshop.counter.conversation

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Synthesized PCM only: no shop credentials, microphone or production writes. */
class NativeSpeechTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private data class Decoded(val text:String,val woke:Boolean,val firstWakeMs:Int,val durationMs:Int,val decodeMs:Long)
    @Test fun bothLanguagesWakeNumbersNoiseSilenceAndNegativeSpeech() {
        val speech=OfflineSpeech(ins.targetContext,object:OfflineSpeech.Listener {
            override fun onState(state:String) {}
            override fun onPartial(ru:String,en:String,level:Float) {}
            override fun onSegment(text:String,russian:Boolean,confidence:Double) {}
            override fun onWake(russian:Boolean) {}
            override fun onFailure(message:String) {}
        })
        try { for(lang in listOf("en","ru")) {
            Model(speech.unpack(lang).path).use { model ->
                for(suffix in listOf("","-noisy","-idle")) {
                    val name="$lang$suffix";val r=Recognizer(model,16000f).use { recognizer ->
                        recognizer.setEndpointerDelays(10f,0.9f,30f);decode(recognizer,name)
                    }
                    android.util.Log.i("SofiaFixture","$name duration=${r.durationMs} decode=${r.decodeMs} wakeAt=${r.firstWakeMs} text=${r.text}")
                    if(!r.woke) {
                        // The fast path is allowed to miss a noisy name only when the independent
                        // local backup recognizes it on the same PCM. Never add the wrong word as an alias.
                        WhisperDecoder(ins.targetContext).use { backup ->
                            assertTrue("Both local paths missed $name",OfflineSpeech.wakeWord(backup.decode(wavSamples(name),lang)))
                        }
                    }
                    assertTrue("$name wrong receipt: ${r.text}","12345" in SafeIntent.numbers(r.text))
                    assertTrue("$name slower than real time",r.decodeMs<r.durationMs)
                    if(suffix=="-idle") assertTrue("Woke during silence",r.firstWakeMs>=30_000)
                }
                Recognizer(model,16000f).use { assertFalse("$lang triggered without a wake name",decode(it,"$lang-negative").woke) }
                // Repeat decoder lifetime after output/mute: the app retains models, not old lattices.
                repeat(12) { Recognizer(model,16000f).use { assertTrue("$lang resume lost digits", "12345" in SafeIntent.numbers(decode(it,lang).text)) } }
            }
        } } finally { speech.close() }
    }
    private fun decode(recognizer:Recognizer,name:String):Decoded {
        val bytes=ins.context.assets.open("voice-fixtures/$name.wav").readBytes()
        var pos=12
        while(String(bytes,pos,4,Charsets.US_ASCII)!="data") { val n=le32(bytes,pos+4);pos+=8+n+(n%2) }
        val size=le32(bytes,pos+4);pos+=8
        val heard=StringBuilder();var woke=false;var first=-1;val start=System.nanoTime();var offset=pos
        while(offset<pos+size) {
            val chunk=bytes.copyOfRange(offset,minOf(offset+3200,pos+size))
            val done=recognizer.acceptWaveForm(chunk,chunk.size)
            val text=JSONObject(if(done)recognizer.result else recognizer.partialResult).optString(if(done)"text" else "partial")
            if(done)heard.append(text).append(' ')
            if(OfflineSpeech.wakeWord(text)) { woke=true;if(first<0)first=(offset-pos)/32 }
            offset+=chunk.size
        }
        heard.append(JSONObject(recognizer.finalResult).optString("text"))
        return Decoded(heard.toString(),woke,first,size/32,(System.nanoTime()-start)/1_000_000)
    }
    private fun wavSamples(name:String):FloatArray {
        val b=ins.context.assets.open("voice-fixtures/$name.wav").readBytes()
        var p=12;while(String(b,p,4,Charsets.US_ASCII)!="data") { val n=le32(b,p+4);p+=8+n+(n%2) }
        val n=le32(b,p+4);p+=8
        return FloatArray(n/2) { i->(((b[p+2*i].toInt()and 255) or (b[p+2*i+1].toInt() shl 8)).toShort())/32768f }
    }
    private fun le32(a:ByteArray,i:Int):Int=(a[i].toInt()and 255) or ((a[i+1].toInt()and 255) shl 8) or ((a[i+2].toInt()and 255) shl 16) or ((a[i+3].toInt()and 255) shl 24)
}
