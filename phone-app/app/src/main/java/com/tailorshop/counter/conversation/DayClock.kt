package com.tailorshop.counter.conversation

import java.time.ZonedDateTime
import java.time.Instant
import org.json.JSONObject
import kotlin.math.roundToInt

/** Presentation policy only: no alarms, microphone, device lock or business writes. */
object DayClock {
    fun daytime(time:ZonedDateTime):Boolean = time.hour*60+time.minute in 645 until 1200
    fun show(enabled:Boolean,day:Boolean,idle:Boolean,preview:Boolean=false)=enabled && (day || preview) && idle
    fun keepAwake(visible:Boolean,powered:Boolean)=visible && powered
    fun drift(minute:Int)=((minute%7)-3)*2
    data class Weather(val fahrenheit:Int,val observedAt:Long) {
        fun json()=JSONObject().put("fahrenheit",fahrenheit).put("observedAt",observedAt).toString()
        fun label(now:Long):String = if(now-observedAt in 0..10_800_000) "Las Vegas · $fahrenheit°F" else "Las Vegas · $fahrenheit°F · earlier reading"
    }
    fun observation(text:String):Weather?=try {
        val p=JSONObject(text).getJSONObject("properties");val t=p.getJSONObject("temperature")
        if(t.isNull("value") || !t.optString("unitCode").endsWith(":degC"))null
        else {
            val c=t.getDouble("value");val at=Instant.parse(p.getString("timestamp")).toEpochMilli()
            if(!c.isFinite() || c !in -80.0..65.0)null else Weather((c*9/5+32).roundToInt(),at)
        }
    } catch(_:Exception) { null }
    fun cached(text:String):Weather?=try { val p=JSONObject(text);Weather(p.getInt("fahrenheit"),p.getLong("observedAt")) } catch(_:Exception) { null }
}
