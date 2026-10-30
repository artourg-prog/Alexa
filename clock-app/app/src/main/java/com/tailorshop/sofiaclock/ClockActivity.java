package com.tailorshop.sofiaclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.*;

/** A standalone dim counter clock. No microphone, shop keys or business writes. */
public final class ClockActivity extends Activity {
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService network=Executors.newSingleThreadExecutor();
 private final ZoneId zone=ZoneId.of("America/Los_Angeles");
 private Face face;
 private boolean resumed,powered;
 private int battery=-1;
 private long previewUntil,nextWeather;
 private ClockRules.Weather weather;
 private java.time.Clock wallClock=java.time.Clock.system(zone);
 private final BroadcastReceiver power=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
  powered=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
  int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
  battery=level>=0 && scale>0?level*100/scale:-1;
  render();
 }};
 @Override public void onCreate(Bundle b){super.onCreate(b);
  weather=ClockRules.cached(getPreferences(MODE_PRIVATE).getString("weather",""));
  face=new Face();setContentView(face);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(power,new IntentFilter(Intent.ACTION_BATTERY_CHANGED),RECEIVER_NOT_EXPORTED);
  else registerReceiver(power,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
  previewUntil=SystemClock.elapsedRealtime()+300000; // Preview after a deliberate launcher opening, never an alarm.
 }
 @Override public void onResume(){super.onResume();resumed=true;
  getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
  getWindow().getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
  main.post(tick);
 }
 @Override public void onPause(){resumed=false;main.removeCallbacks(tick);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onPause();}
 @Override public void onDestroy(){unregisterReceiver(power);network.shutdownNow();super.onDestroy();}
 private final Runnable tick=new Runnable(){public void run(){if(!resumed)return;render();main.postDelayed(this,1000);}};
 private void render(){if(face==null)return;
  ZonedDateTime time=ZonedDateTime.now(wallClock);boolean day=ClockRules.daytime(time),preview=SystemClock.elapsedRealtime()<previewUntil;
  boolean hold=ClockRules.keepAwake(resumed,powered,day,preview);
  if(hold)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=day||preview?0.08f:0.02f;getWindow().setAttributes(p);
  face.present(time,weather==null?"Las Vegas · weather unavailable":weather.label(System.currentTimeMillis()),battery);
  if(resumed && (day||preview) && SystemClock.elapsedRealtime()>=nextWeather){nextWeather=SystemClock.elapsedRealtime()+900000;fetchWeather();}
 }
 private void fetchWeather(){network.execute(()->{
  ClockRules.Weather value=null;
  try {
   HttpURLConnection c=(HttpURLConnection)new URL("https://api.weather.gov/stations/KLAS/observations/latest").openConnection();
   try {c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","SofiaClock/0.1 (https://github.com/artourg-prog/Alexa)");c.setRequestProperty("Accept","application/geo+json");
    if(c.getResponseCode()==200){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
     try(java.io.InputStream in=c.getInputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>131072)throw new java.io.IOException("Weather response too large");bytes.write(buf,0,n);}}
     value=ClockRules.observation(new String(bytes.toByteArray(),StandardCharsets.UTF_8));}
   }finally{c.disconnect();}
  }catch(Exception ignored){}
  if(value!=null){final ClockRules.Weather fresh=value;getPreferences(MODE_PRIVATE).edit().putString("weather",fresh.json()).apply();main.post(()->{if(!isDestroyed()){weather=fresh;render();}});}
 });}
 private void openSofia(){try{startActivity(new Intent().setComponent(new ComponentName("com.tailorshop.counter","com.tailorshop.counter.MainActivity")));}catch(ActivityNotFoundException e){new AlertDialog.Builder(this).setMessage("Sofia is not installed on this device.").setPositiveButton("Close",null).show();}}
 private void options(){new AlertDialog.Builder(this).setTitle("Sofia Clock 0.1")
  .setItems(new String[]{"Open Sofia","Preview for five minutes","Exit clock"},(d,n)->{if(n==0)openSofia();else if(n==1){previewUntil=SystemClock.elapsedRealtime()+300000;render();}else finish();})
  .setNegativeButton("Close",null).show();}
 private final class Face extends View {
  private final Paint digits=new Paint(Paint.ANTI_ALIAS_FLAG),details=new Paint(Paint.ANTI_ALIAS_FLAG),title=new Paint(Paint.ANTI_ALIAS_FLAG);
  private ZonedDateTime time=ZonedDateTime.now(zone);
  private String sky="Las Vegas · weather unavailable",charge="";
  Face(){super(ClockActivity.this);setBackgroundColor(Color.BLACK);setContentDescription("Sofia Clock. Tap to open Sofia. Hold for clock options.");
   digits.setTypeface(Typeface.create("sans-serif-condensed",Typeface.BOLD));digits.setColor(Color.rgb(166,166,166));digits.setTextAlign(Paint.Align.CENTER);
   details.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));details.setColor(Color.rgb(140,140,140));details.setTextAlign(Paint.Align.CENTER);
   title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));title.setColor(Color.rgb(145,145,145));title.setTextAlign(Paint.Align.CENTER);
   setOnClickListener(v->openSofia());setOnLongClickListener(v->{options();return true;});
  }
  void present(ZonedDateTime t,String s,int b){String percent=b>=0?b+"%":"";boolean changed=t.getMinute()!=time.getMinute() || t.getHour()!=time.getHour() || !t.toLocalDate().equals(time.toLocalDate()) || !sky.equals(s) || !charge.equals(percent);time=t;sky=s;charge=percent;if(changed){setContentDescription("София. "+t.format(DateTimeFormatter.ofPattern("h:mm, EEEE, MMMM d",Locale.US))+". "+s+". "+percent+". Tap to open Sofia. Hold for clock options.");invalidate();}}
  @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),dp=getResources().getDisplayMetrics().density;if(w<=0||h<=0)return;
   float shift=ClockRules.drift(time.getMinute())*dp;c.save();c.translate(shift,-shift);
   title.setTextSize(Math.min(h*0.11f,w*0.12f));c.drawText("София",w/2,h*0.15f,title);
   String value=time.format(DateTimeFormatter.ofPattern("h:mm",Locale.US));digits.setTextScaleX(1);digits.setTextSize(100);Paint.FontMetrics m=digits.getFontMetrics();digits.setTextSize(h*0.54f/(m.descent-m.ascent)*100);
   digits.setTextScaleX((w-32*dp)/digits.measureText(value));m=digits.getFontMetrics();c.drawText(value,w/2,h*0.53f-(m.ascent+m.descent)/2,digits);
   line(c,time.format(DateTimeFormatter.ofPattern("EEEE, MMMM d",Locale.US)),w/2,h*0.87f,Math.min(h*0.058f,w*0.045f),w);
   details.setTextAlign(Paint.Align.LEFT);line(c,sky,w*0.035f,h*0.245f,Math.min(h*0.043f,w*0.035f),w*0.90f);details.setTextAlign(Paint.Align.CENTER);
   line(c,charge,w/2,h*0.97f,h*0.026f,w);
   c.restore();
  }
  private void line(Canvas c,String s,float x,float y,float size,float width){details.setTextSize(size);if(details.measureText(s)>width*0.94f)details.setTextSize(size*width*0.94f/details.measureText(s));c.drawText(s,x,y,details);}
 }
}
