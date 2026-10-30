package com.tailorshop.counter.conversation

import com.tailorshop.counter.Lookup
import com.tailorshop.counter.Order
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Private last-good mirror: an unavailable network never becomes an empty order list. */
class OrderCache(dir:File) {
    private val file=File(dir,"orders-cache-v1.json")
    data class Snapshot(val rows:List<List<String>>,val at:Long) { fun orders():List<Order> = Lookup.parseOrders(rows) }
    fun load():Snapshot?=try {
        val j=JSONObject(file.readText());val a=j.getJSONArray("rows")
        Snapshot((0 until a.length()).map { i->val r=a.getJSONArray(i);(0 until r.length()).map { r.getString(it) } },j.getLong("at"))
    } catch(_:Exception) { null }
    fun save(rows:List<List<String>>,at:Long) {
        Lookup.parseOrders(rows) // reject invalid headers before replacing the last good copy
        val j=JSONObject().put("at",at).put("rows",JSONArray().apply { rows.forEach { put(JSONArray(it)) } })
        val tmp=File(file.parentFile,"${file.name}.tmp")
        FileOutputStream(tmp).use { it.write(j.toString().toByteArray());it.fd.sync() }
        check(tmp.renameTo(file)) { "Cache persistence failed" }
    }
}
