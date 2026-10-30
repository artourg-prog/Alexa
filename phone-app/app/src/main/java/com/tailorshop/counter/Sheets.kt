package com.tailorshop.counter

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

// Read-only Google Sheets access, same two locks as the Alexa skill:
//   1. the token is requested with the spreadsheets.readonly scope only;
//   2. only values.get (a GET) is ever called. There is no write code here.
// The service-account key lives in the app's private storage (put there over USB),
// never in the APK or in git.
class Sheets(keyFile: File, private val reader:(()->List<List<String>>)?=null) {
    companion object {
        const val SPREADSHEET_ID = "14NLtaKEBKPLU-lM6SSpXuJx-Wog-qmXltMIjsimh0as" // Order Ready For Pickup
        const val RANGE = "'All Receipts'!A1:Z"
        private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
    }

    class KeyMissing : Exception("KEY_MISSING")

    private val auth = GoogleAuth(keyFile, SCOPE)

    fun hasKey() = reader!=null || auth.hasKey()

    /** Header row first. Throws on any failure; the caller keeps the last good copy. */
    fun readRows(): List<List<String>> {
        reader?.let { return it() }
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values/" +
            URLEncoder.encode(RANGE, "UTF-8").replace("+", "%20")
        val token = try { auth.accessToken() } catch (e: GoogleAuth.KeyMissing) { throw KeyMissing() }
        val body = String(GoogleAuth.http(url, null, "Bearer $token"))
        val values = JSONObject(body).optJSONArray("values") ?: throw IllegalStateException("Order headers unavailable")
        require(values.length()>0) { "Order headers unavailable" }
        return (0 until values.length()).map { i ->
            val row = values.getJSONArray(i)
            (0 until row.length()).map { j -> row.optString(j, "") }
        }
    }
}
