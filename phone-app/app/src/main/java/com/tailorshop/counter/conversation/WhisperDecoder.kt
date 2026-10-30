package com.tailorshop.counter.conversation

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** CPU-only multilingual second opinion. Audio is an ephemeral RAM window, never a recording file. */
class WhisperDecoder(context:Context):AutoCloseable {
    companion object {
        const val HASH="422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        init { System.loadLibrary("sofia-whisper") }
    }
    private var handle:Long
    init {
        val file=File(context.filesDir,"voice-v1/whisper-base-q5_1.bin")
        val stamp=File(file.parentFile,"whisper.sha256")
        if(!file.isFile || !stamp.isFile || stamp.readText()!=HASH) {
            file.parentFile?.mkdirs()
            val tmp=File(file.parentFile,"whisper.tmp");val digest=MessageDigest.getInstance("SHA-256")
            context.assets.open("voice-models/whisper-base-q5_1.bin").use { input -> FileOutputStream(tmp).use { out ->
                val buffer=ByteArray(65536)
                while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);out.write(buffer,0,n) }
                out.fd.sync()
            } }
            require(digest.digest().joinToString("") { "%02x".format(it) }==HASH)
            require(tmp.renameTo(file));stamp.writeText(HASH)
        }
        handle=nativeOpen(file.path);require(handle!=0L) { "Backup speech model unavailable" }
    }
    fun decode(samples:FloatArray,language:String="auto"):String {
        check(handle!=0L);return nativeDecode(handle,samples,language).trim()
    }
    override fun close() { if(handle!=0L) { nativeClose(handle);handle=0L } }
    private external fun nativeOpen(path:String):Long
    private external fun nativeDecode(handle:Long,pcm:FloatArray,language:String):String
    private external fun nativeClose(handle:Long)
}
