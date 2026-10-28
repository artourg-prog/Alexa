package com.tailorshop.counter

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

// Service-account token for ONE read-only scope (spreadsheets.readonly for the orders,
// drive.readonly for the app's own builds folder). Never a write scope.
class GoogleAuth(private val keyFile: File, private val scope: String) {
    class KeyMissing : Exception("KEY_MISSING")

    companion object {
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val TIMEOUT_MS = 8000

        fun http(url: String, form: String?, auth: String?, timeoutMs: Int = TIMEOUT_MS): ByteArray {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            auth?.let { c.setRequestProperty("Authorization", it) }
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw Exception("HTTP $code")
            return body
        }
    }

    private var token: String? = null
    private var tokenExpires = 0L

    fun hasKey() = keyFile.isFile

    fun accessToken(): String {
        val now = System.currentTimeMillis() / 1000
        token?.let { if (now < tokenExpires - 60) return it }
        if (!keyFile.isFile) throw KeyMissing()
        val key = JSONObject(keyFile.readText().trimStart('﻿').trim())
        val email = key.getString("client_email")
        val pem = key.getString("private_key")
        val der = Base64.getMimeDecoder().decode(
            pem.replace("\\n", "\n").replace(Regex("-----[A-Z ]+-----"), "").trim(),
        )
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))

        val b64 = Base64.getUrlEncoder().withoutPadding()
        val header = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64.encodeToString(
            JSONObject()
                .put("iss", email).put("scope", scope).put("aud", TOKEN_URL)
                .put("iat", now).put("exp", now + 3600)
                .toString().toByteArray(),
        )
        val unsigned = "$header.$claims"
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey); update(unsigned.toByteArray()); sign()
        }
        val jwt = "$unsigned.${b64.encodeToString(sig)}"

        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=" + jwt
        val res = JSONObject(String(http(TOKEN_URL, form, null)))
        token = res.getString("access_token")
        tokenExpires = now + res.optLong("expires_in", 3600)
        return token!!
    }
}
