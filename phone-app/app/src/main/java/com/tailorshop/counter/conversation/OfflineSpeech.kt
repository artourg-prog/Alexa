package com.tailorshop.counter.conversation

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.math.log10
import kotlin.math.sqrt

/** One app-owned PCM stream. Models and decoders stay alive across pauses; no OS speech-session restarts. */
class OfflineSpeech(private val context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun onState(state: String)
        fun onPartial(ru: String, en: String, level: Float)
        fun onSegment(text: String, russian: Boolean, confidence: Double)
        fun onWake(russian: Boolean)
        fun onFailure(message: String)
    }
    companion object {
        const val RATE = 16_000
        private val MODEL_HASH = mapOf("en" to "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
            "ru" to "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4")
        fun wakeWord(text: String): Boolean = Regex("(?<!\\p{L})(?:sofia|sophia|sofiya|sophie|sofya|софия|софья|софи)(?!\\p{L})",RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val requested = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    @Volatile private var muted = false
    @Volatile private var closed = false
    @Volatile private var recorder: AudioRecord? = null
    private var models: Pair<Model,Model>? = null
    private var decoders: List<Recognizer> = emptyList()
    private var suppressor: NoiseSuppressor? = null
    private var lastWake = 0L
    private var lastPartialAt = 0L
    private var lastRu = ""; private var lastEn = ""
    private data class Final(val text:String,val ru:Boolean,val confidence:Double,val at:Long)
    private val recent=mutableListOf<Final>()
    private val backup=WhisperWake(context) { ru ->
        if(requested.get() && !closed && !muted) {
            listener.onWake(ru)
            val now=android.os.SystemClock.elapsedRealtime()
            val finals=synchronized(recent) { recent.filter { now-it.at<5000 }.toList() }
            // Replay genuine completed streaming segments; Whisper itself never proposes a business action.
            for(language in listOf(true,false)) {
                val segments=finals.filter { it.ru==language }
                if(segments.isNotEmpty())listener.onSegment(segments.joinToString(" ") { it.text },language,segments.minOf { it.confidence })
            }
        }
    }

    fun start() {
        if (closed || !requested.compareAndSet(false,true)) return
        val session = generation.incrementAndGet()
        backup.enabled(true)
        executor.execute {
            try {
                listener.onState("Preparing local speech…")
                if (models == null) {
                    val ruModel=Model(unpack("ru").path)
                    try { models=ruModel to Model(unpack("en").path) } catch(e:Exception) { ruModel.close();throw e }
                }
                if (!requested.get() || closed || session != generation.get()) return@execute
                // A name-only grammar falsely forces ordinary Russian speech to "Sofia".
                // Detect names in the full streaming transcript, without an OS session restart.
                decoders = newDecoders()
                val min = AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0) { "Unsupported microphone sample rate" }
                val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min*2,8192)).build()
                require(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                recorder=audio
                if (NoiseSuppressor.isAvailable()) suppressor=NoiseSuppressor.create(audio.audioSessionId)?.apply { enabled=true }
                audio.startRecording(); listener.onState("Microphone ready · waiting for Sofia")
                val pcm=ShortArray(1600); var wasMuted=false;val wakeSeen=BooleanArray(2)
                while(requested.get() && !closed && session == generation.get()) {
                    val count=audio.read(pcm,0,pcm.size,AudioRecord.READ_BLOCKING)
                    if(count<=0) { if(requested.get()) throw IllegalStateException("Microphone read interrupted"); break }
                    if(muted) {
                        if(!wasMuted) { decoders.forEach { it.close() };decoders=emptyList();wakeSeen.fill(false);lastRu="";lastEn="" }
                        wasMuted=true;continue
                    }
                    // A fresh decoder discards self-speech and avoids reusing finalized native lattices.
                    // Acoustic models remain resident; only the small conversation decoder is renewed.
                    if(wasMuted) { decoders=newDecoders();wasMuted=false }
                    val beforeRu=lastRu;val beforeEn=lastEn
                    decoders.forEachIndexed { index,r ->
                        val ended=r.acceptWaveForm(pcm,count)
                        val obj=JSONObject(if(ended) r.result else r.partialResult)
                        val text=obj.optString(if(ended) "text" else "partial").trim()
                        if(wakeWord(text) && !wakeSeen[index]) {
                            wakeSeen[index]=true
                            val stamp=android.os.SystemClock.elapsedRealtime()
                            if(stamp-lastWake>1800) { lastWake=stamp;listener.onWake(index%2==0) }
                        }
                        if(ended)wakeSeen[index]=false
                        if(index<2) {
                            if(index==0) lastRu=text else lastEn=text
                            if(ended && text.isNotEmpty()) {
                                val words=obj.optJSONArray("result")
                                val conf=if(words==null || words.length()==0) 0.0 else {
                                    val tokens=(0 until words.length()).map { words.getJSONObject(it) }
                                    val numeric=tokens.filter { com.tailorshop.counter.SpokenNumbers.toDigits(com.tailorshop.counter.Lookup.normalize(it.optString("word"))).any(Char::isDigit) }
                                    // A clear verb must not hide uncertainty in a single receipt digit.
                                    if(numeric.isNotEmpty()) numeric.minOf { it.optDouble("conf",0.0) } else tokens.map { it.optDouble("conf",0.0) }.average()
                                }
                                synchronized(recent) {
                                    val now=android.os.SystemClock.elapsedRealtime()
                                    recent.removeAll { now-it.at>5000 }
                                    recent+=Final(text,index==0,conf,now)
                                }
                                listener.onSegment(text,index==0,conf)
                            }
                        }

                    }
                    backup.feed(pcm,count,WakeCandidate.needsVerification(lastRu,lastEn) && ((lastRu.isNotBlank() && lastRu!=beforeRu) || (lastEn.isNotBlank() && lastEn!=beforeEn)))
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-lastPartialAt>=150) {
                        var energy=0.0; for(i in 0 until count) energy+=pcm[i].toDouble()*pcm[i]
                        val db=20*log10((sqrt(energy/count)/32768.0).coerceAtLeast(0.00001))
                        listener.onPartial(lastRu,lastEn,((db+65)/55).toFloat().coerceIn(0f,1f));lastPartialAt=now
                    }
                }
            } catch(e:Exception) {
                if(requested.get() && !closed) listener.onFailure("Microphone interrupted. Tap Restart listening.")
            } finally {
                try { recorder?.stop() } catch(_:Exception) {}
                suppressor?.release();suppressor=null
                recorder?.release();recorder=null
                decoders.forEach { try { it.close() } catch(_:Exception) {} };decoders=emptyList()
                if(session == generation.get()) requested.set(false)
            }
        }
    }
    private fun newDecoders():List<Recognizer> {
        val (ru,en)=models!!
        val made=mutableListOf<Recognizer>()
        try {
            for(model in listOf(ru,en))made+=Recognizer(model,RATE.toFloat()).apply { setWords(true);setEndpointerDelays(10f,0.9f,30f) }
            return made
        } catch(e:Exception) { made.forEach { it.close() };throw e }
    }
    fun attending(value:Boolean) { backup.attending(value) }
    fun mute(value:Boolean) { muted=value;backup.mute(value);if(value)synchronized(recent) { recent.clear() } }
    fun stop() { backup.enabled(false);synchronized(recent) { recent.clear() };generation.incrementAndGet(); requested.set(false); try { recorder?.stop() } catch(_:Exception) {} }
    override fun close() {
        if(closed) return
        closed=true;stop();backup.close()
        executor.execute { models?.first?.close();models?.second?.close();models=null }
        executor.shutdown()
    }
    internal fun unpack(language:String):File {
        val target=File(context.filesDir,"voice-v1/$language")
        if(File(target,".ready").isFile && File(target,".ready").readText()==MODEL_HASH.getValue(language) && File(target,"am/final.mdl").isFile) return target
        listener.onState("Preparing $language speech model…")
        val staging=File(target.parentFile,"$language.tmp");staging.deleteRecursively();staging.mkdirs()
        context.assets.open("voice-models/$language.zip").use { input ->
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    val relative=entry.name.substringAfter('/',"")
                    if(relative.isBlank()) continue
                    val out=File(staging,relative)
                    require(out.canonicalPath.startsWith(staging.canonicalPath+File.separator)) { "Invalid model archive" }
                    if(entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs();out.outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        // Verify the complete packaged archive independently of ZipInputStream buffering.
        val actual=context.assets.open("voice-models/$language.zip").use { input ->
            val d=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer);if(n<0)break;d.update(buffer,0,n) }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        require(actual==MODEL_HASH.getValue(language)) { "Speech model checksum mismatch" }
        require(File(staging,"am/final.mdl").isFile) { "Incomplete speech model" }
        target.deleteRecursively();require(staging.renameTo(target)) { "Speech model installation failed" }
        File(target,".ready").writeText(actual)
        return target
    }
}
