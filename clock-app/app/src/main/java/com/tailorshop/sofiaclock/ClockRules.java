package com.tailorshop.sofiaclock;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZonedDateTime;
public final class ClockRules {
 public static boolean daytime(ZonedDateTime time) {int m=time.getHour()*60+time.getMinute();return m>=630 && m<1200;}
 public static boolean keepAwake(boolean resumed,boolean powered,boolean day,boolean preview) {return resumed && powered && (day || preview);}
 public static int drift(int minute) {return (minute%7-3)*2;}
 public static final class Weather {
  public final int fahrenheit;public final long at;
  Weather(int f,long t){fahrenheit=f;at=t;}
  public String label(long now){return "Las Vegas · "+fahrenheit+"°F"+(now-at>=0 && now-at<=10800000?"":" · earlier reading");}
  public String json(){try{return new JSONObject().put("f",fahrenheit).put("at",at).toString();}catch(Exception e){return "";}}
 }
 public static Weather observation(String text){try {
  JSONObject p=new JSONObject(text).getJSONObject("properties"),t=p.getJSONObject("temperature");
  if(t.isNull("value") || !t.optString("unitCode").endsWith(":degC"))return null;
  double c=t.getDouble("value");if(!Double.isFinite(c)||c < -80 || c >65)return null;
  return new Weather((int)Math.round(c*9/5+32),Instant.parse(p.getString("timestamp")).toEpochMilli());
 }catch(Exception e){return null;}}
 public static Weather cached(String text){try{JSONObject o=new JSONObject(text);int f=o.getInt("f");if(f < -112 || f>149)return null;return new Weather(f,o.getLong("at"));}catch(Exception e){return null;}}
}
