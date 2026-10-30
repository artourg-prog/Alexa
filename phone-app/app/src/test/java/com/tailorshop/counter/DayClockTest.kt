package com.tailorshop.counter
import com.tailorshop.counter.conversation.DayClock
import org.junit.Test
import org.junit.Assert.*
import java.time.ZonedDateTime
class DayClockTest {
    @Test fun workdayClockEndsAtEightAndNeverRequiresPowerForOrdinaryDisplay() {
        fun at(t:String)=ZonedDateTime.parse("2026-10-29T${t}:00-07:00[America/Los_Angeles]")
        assertFalse(DayClock.daytime(at("10:44")));assertTrue(DayClock.daytime(at("10:45")))
        assertTrue(DayClock.daytime(at("19:59")));assertFalse(DayClock.daytime(at("20:00")))
        assertTrue(DayClock.show(true,true,true));assertFalse(DayClock.show(true,true,false))
        assertFalse(DayClock.show(true,false,true));assertTrue(DayClock.show(true,false,true,true))
        assertFalse(DayClock.keepAwake(true,false));assertTrue(DayClock.keepAwake(true,true));assertFalse(DayClock.keepAwake(false,true))
    }
    @Test fun weatherRejectsMissingAndUnexpectedUnitsAndLabelsOldData() {
        val good="""{"properties":{"temperature":{"value":20,"unitCode":"wmoUnit:degC"},"timestamp":"2026-10-29T19:00:00Z"}}"""
        val w=DayClock.observation(good)!!;assertEquals(68,w.fahrenheit)
        assertEquals(w,DayClock.cached(w.json()))
        assertFalse(w.label(w.observedAt+60000).contains("earlier"))
        assertTrue(w.label(w.observedAt+10800001).contains("earlier"))
        assertNull(DayClock.observation(good.replace("20,","null,")))
        assertNull(DayClock.observation(good.replace(":degC",":degF")))
        assertNull(DayClock.observation("{}"))
    }
    @Test fun minuteDriftStaysInsideClockMargins() {
        assertEquals(7,(0..59).map(DayClock::drift).distinct().size)
        assertTrue((0..59).all { DayClock.drift(it) in -6..6 })
    }
}
