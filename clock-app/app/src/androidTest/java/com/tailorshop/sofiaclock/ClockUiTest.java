package com.tailorshop.sofiaclock;
import org.junit.Test;
import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.*;
import java.time.*;
import java.io.*;
public class ClockUiTest {
 private Object get(Object a,String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
 private void set(Object a,String n,Object v)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
 private void render(ClockActivity a)throws Exception{Method m=ClockActivity.class.getDeclaredMethod("render");m.setAccessible(true);m.invoke(a);}
 @Test public void independentClockHasNoMicAndDimsAndReleasesScreenAtNight()throws Exception{
  var ins=InstrumentationRegistry.getInstrumentation();
  ClockActivity a=(ClockActivity)ins.startActivitySync(new Intent(ins.getTargetContext(),ClockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try {
   final Throwable[] failure={null};ins.runOnMainSync(()->{try {
    for(String p:ins.getTargetContext().getPackageManager().getPackageInfo(ins.getTargetContext().getPackageName(),PackageManager.GET_PERMISSIONS).requestedPermissions)assertNotEquals("android.permission.RECORD_AUDIO",p);
    set(a,"nextWeather",Long.MAX_VALUE);set(a,"previewUntil",0L);set(a,"powered",true);
    set(a,"wallClock",java.time.Clock.fixed(Instant.parse("2026-10-30T19:00:00Z"),ZoneId.of("America/Los_Angeles")));render(a);
    assertEquals(0.08f,a.getWindow().getAttributes().screenBrightness,0.001f);assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0);
    set(a,"powered",false);render(a);assertEquals(0,a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    set(a,"powered",true);set(a,"wallClock",java.time.Clock.fixed(Instant.parse("2026-10-31T03:00:00Z"),ZoneId.of("America/Los_Angeles")));render(a);
    assertEquals(0,a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);assertEquals(0.02f,a.getWindow().getAttributes().screenBrightness,0.001f);
    set(a,"previewUntil",android.os.SystemClock.elapsedRealtime()+300000);render(a);assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0);
   }catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
   ins.waitForIdleSync();ins.runOnMainSync(()->{try{
    View face=(View)get(a,"face");assertTrue(face.getWidth()>0);assertTrue(face.getContentDescription().toString().contains("София"));
    Bitmap b=Bitmap.createBitmap(face.getWidth(),face.getHeight(),Bitmap.Config.ARGB_8888);face.draw(new Canvas(b));
    try(FileOutputStream out=new FileOutputStream(new File(a.getCacheDir(),"standalone-clock.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
   }catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);
  }finally{ins.runOnMainSync(a::finish);}
 }
}
