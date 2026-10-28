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

// Read-only Google Sheets access, same two locks as the Alexa skill:
//   1. the token is requested with the spreadsheets.readonly scope only;
//   2. only values.get (a GET) is ever called. There is no write code here.
// The service-account key lives in the app's private storage (put there over USB),
// never in the APK or in git.
class Sheets(private val keyFile: File) {
    companion object {
        const val SPREADSHEET_ID = "14NLtaKEBKPLU-lM6SSpXuJx-Wog-qmXltMIjsimh0as" // Order Ready For Pickup
        const val RANGE = "'All Receipts'!A1:Z"
        private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val TIMEOUT_MS = 8000
    }

    class KeyMissing : Exception("KEY_MISSING")

    private var token: String? = null
    private var tokenExpires = 0L

    fun hasKey() = keyFile.isFile

    /** Header row first. Throws on any failure; the caller keeps the last good copy. */
    fun readRows(): List<List<String>> {
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values/" +
            URLEncoder.encode(RANGE, "UTF-8").replace("+", "%20")
        val body = http(url, null, "Bearer ${accessToken()}")
        val values = JSONObject(body).optJSONArray("values") ?: return emptyList()
        return (0 until values.length()).map { i ->
            val row = values.getJSONArray(i)
            (0 until row.length()).map { j -> row.optString(j, "") }
        }
    }

    private fun accessToken(): String {
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
                .put("iss", email).put("scope", SCOPE).put("aud", TOKEN_URL)
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
        val res = JSONObject(http(TOKEN_URL, form, null))
        token = res.getString("access_token")
        tokenExpires = now + res.optLong("expires_in", 3600)
        return token!!
    }

    private fun http(url: String, form: String?, auth: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        auth?.let { c.setRequestProperty("Authorization", it) }
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw Exception("HTTP $code")
        return text
    }
}
