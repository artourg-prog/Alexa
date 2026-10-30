package com.tailorshop.sofiaclock;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.ZonedDateTime;
public class ClockRulesTest {
 private ZonedDateTime at(String t){return ZonedDateTime.parse("2026-10-30T"+t+":00-07:00[America/Los_Angeles]");}
 @Test public void daytimeAndPowerBoundaries(){
  assertFalse(ClockRules.daytime(at("10:44")));assertTrue(ClockRules.daytime(at("10:45")));
  assertTrue(ClockRules.daytime(at("19:59")));assertFalse(ClockRules.daytime(at("20:00")));
  assertTrue(ClockRules.keepAwake(true,true,true,false));assertFalse(ClockRules.keepAwake(true,false,true,false));
  assertFalse(ClockRules.keepAwake(false,true,true,true));assertFalse(ClockRules.keepAwake(true,true,false,false));
  assertTrue(ClockRules.keepAwake(true,true,false,true));
 }
 @Test public void weatherIsMeasuredNotInventedAndOldDataIsMarked(){
  String j="{\"properties\":{\"temperature\":{\"value\":20,\"unitCode\":\"wmoUnit:degC\"},\"timestamp\":\"2026-10-30T06:00:00Z\"}}";
  ClockRules.Weather w=ClockRules.observation(j);assertNotNull(w);assertEquals(68,w.fahrenheit);
  assertEquals(w.fahrenheit,ClockRules.cached(w.json()).fahrenheit);
  assertFalse(w.label(w.at+60000).contains("earlier"));assertTrue(w.label(w.at+10800001).contains("earlier"));
  assertNull(ClockRules.observation(j.replace("20,","null,")));assertNull(ClockRules.observation(j.replace(":degC",":degF")));
  assertNull(ClockRules.observation("{}"));assertNull(ClockRules.cached("corrupt"));
 }
 @Test public void movingClockRemainsInsideMargins(){java.util.Set<Integer> shifts=new java.util.HashSet<>();for(int m=0;m<60;m++){assertTrue(Math.abs(ClockRules.drift(m))<=6);shifts.add(ClockRules.drift(m));}assertEquals(7,shifts.size());}
}
