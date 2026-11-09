package com.tailorshop.counter

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

// Read-only Google Sheets access, same two locks as the Alexa skill:
//   1. the token is requested with the spreadsheets.readonly scope only;
//   2. only values.get and values.batchGet (both GETs) are ever called. There is no write code here.
// The service-account key lives in the app's private storage (put there over USB),
// never in the APK or in git.
class Sheets(keyFile: File, private val reader:(()->List<List<String>>)?=null) {
    companion object {
        const val SPREADSHEET_ID = "14NLtaKEBKPLU-lM6SSpXuJx-Wog-qmXltMIjsimh0as" // Order Ready For Pickup
        const val RANGE = "'All Receipts'!A1:Z"
        const val INDEX = "'Order Index'!A1:Z"
        const val SYNC = "'Sync Status'!A1:B30"
        private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
    }

    class KeyMissing : Exception("KEY_MISSING")

    private val auth = GoogleAuth(keyFile, SCOPE)

    fun hasKey() = reader!=null || auth.hasKey()

    /**
     * The "Order Index" tab (see OrderIndex), read-only. Null when the tab doesn't exist yet (the API answers
     * 400 for an unknown range), so the phone keeps working from All Receipts alone. Other failures throw.
     */
    fun readIndex(): List<List<String>>? = readTab(INDEX)


    /** The "Sync Status" tab (Key | Value): when the tablet last published. See ListAge. Null when it doesn't exist. */
    fun readSync(): List<List<String>>? = readTab(SYNC)

    private fun readTab(range: String): List<List<String>>? {
        if (reader != null) return null
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values/" +
            URLEncoder.encode(range, "UTF-8").replace("+", "%20")
        val token = try { auth.accessToken() } catch (e: GoogleAuth.KeyMissing) { throw KeyMissing() }
        val body = try { String(GoogleAuth.http(url, null, "Bearer $token")) }
            catch (e: Exception) { if (e.message == "HTTP 400") return null else throw e }
        val values = JSONObject(body).optJSONArray("values") ?: return emptyList()
        return (0 until values.length()).map { i -> val row = values.getJSONArray(i); (0 until row.length()).map { j -> row.optString(j, "") } }
    }

    /** Every tab Find needs, read together: the ready list plus Order Index and Sync Status (null = no such tab). The
     *  "Picked Up Orders" tab was removed on purpose (Pickups, 11-09): picked-up status is Order Index Status = COLLECTED,
     *  so it is not read any more and [picked] is always null. */
    data class Lists(val rows: List<List<String>>, val index: List<List<String>>?, val picked: List<List<String>>?, val sync: List<List<String>>?)

    /**
     * All four tabs in one request (owner 11-08: a Find reads the ready state fresh every time, and one request keeps it quick).
     * If a tab doesn't exist yet the API refuses the whole batch with 400; then each tab is read on its own, as before.
     * Throws on any other failure; the caller keeps the last good copy.
     */
    fun readAll(): Lists {
        reader?.let { return Lists(it(), null, null, null) }
        val ranges = listOf(RANGE, INDEX, SYNC)
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$SPREADSHEET_ID/values:batchGet?" +
            ranges.joinToString("&") { "ranges=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val token = try { auth.accessToken() } catch (e: GoogleAuth.KeyMissing) { throw KeyMissing() }
        val body = try { String(GoogleAuth.http(url, null, "Bearer $token")) }
            catch (e: Exception) { if (e.message == "HTTP 400") return Lists(readRows(), readIndex(), null, readSync()) else throw e }
        val parts = JSONObject(body).optJSONArray("valueRanges") ?: throw IllegalStateException("Order lists unavailable")
        check(parts.length() == ranges.size) { "Order lists incomplete" }
        fun table(i: Int): List<List<String>> {
            val values = parts.getJSONObject(i).optJSONArray("values") ?: return emptyList()
            return (0 until values.length()).map { r -> val row = values.getJSONArray(r); (0 until row.length()).map { j -> row.optString(j, "") } }
        }
        val rows = table(0)
        require(rows.isNotEmpty()) { "Order headers unavailable" }
        return Lists(rows, table(1), null, table(2))
    }

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
