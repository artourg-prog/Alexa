package com.tailorshop.counter.conversation

import android.content.Context
import com.tailorshop.counter.Updater
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Only PrivateTokens.publicJson crosses the boundary; the entity dictionaries stay local. */
class SemanticClient(private val context:Context) {
    private val file=File(context.filesDir,"intent-config.json")
    private var lastRefresh=0L
    fun refresh() {
        if(System.currentTimeMillis()-lastRefresh<3_600_000)return
        lastRefresh=System.currentTimeMillis()
        try {
            val cfg=Updater.intentConfig(context)?:return
            validateConfig(cfg)
            val tmp=File(file.parentFile,"intent-config.tmp")
            FileOutputStream(tmp).use { it.write(cfg.toString().toByteArray());it.fd.sync() }
            check(tmp.renameTo(file))
        } catch(_:Exception) { /* last valid private configuration remains available */ }
    }
    private fun validateConfig(cfg:JSONObject) {
        val url=URL(cfg.getString("url"))
        require(url.protocol=="https" && url.host=="sofia-intent.artourg.workers.dev" && url.path=="/intent" && url.port in listOf(-1,443) && url.userInfo==null)
        require(cfg.getString("token").matches(Regex("[A-Za-z0-9_-]{32,128}")))
    }
    fun classify(text:String,c:IntentContext):Intent? {
        return try {
            val cfg=JSONObject(file.readText());validateConfig(cfg)
            val payload=PrivateTokens.encode(text,c)
            val con=URL(cfg.getString("url")).openConnection() as HttpURLConnection
            try {
                con.requestMethod="POST";con.doOutput=true;con.instanceFollowRedirects=false
                con.connectTimeout=1500;con.readTimeout=5000
                con.setRequestProperty("Content-Type","application/json")
                con.setRequestProperty("Authorization","Bearer ${cfg.getString("token")}")
                con.outputStream.use { it.write(payload.publicJson(c).toString().toByteArray()) }
                if(con.responseCode!=200)return null
                val response=con.inputStream.use { input -> val bytes=input.readNBytes(4097);require(bytes.size<=4096);JSONObject(String(bytes)) }
                SafeIntent.validate(PrivateTokens.decode(response,payload,c),text,c)
            } finally { con.disconnect() }
        } catch(_:Exception) { null }
    }
}
