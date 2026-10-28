package com.tailorshop.counter

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Brings the counter screen up from the background (10:45 shop opening, relaunch after an update).
// Android 15+ blocks an app's own alarm from opening a screen ("Background activity launch blocked";
// the 10:45 wake on 10-28 was stopped that way), so the alarm posts a full-screen notification,
// the way alarm-clock apps do; Android then opens the counter screen over the lock screen.
object Wake {
    private const val CHANNEL = "wake"
    private const val NOTE_ID = 45

    /** Alarm clock at [atMillis] that wakes the counter screen. */
    fun schedule(ctx: Context, atMillis: Long, requestCode: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val fire = PendingIntent.getBroadcast(ctx, requestCode, Intent(ctx, Fire::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, screen(ctx, requestCode)), fire) }
        catch (e: SecurityException) { Log.d("TailorCounter", "can't schedule wake: ${e.message}") }
    }

    private fun screen(ctx: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(ctx, requestCode,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Called when the counter screen is up: the wake notification has done its job. */
    fun clear(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }

    class Fire : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake the counter screen", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) })
            val pi = screen(ctx, 99)
            Log.d("TailorCounter", "wake fired, full screen allowed=${nm.canUseFullScreenIntent()}")
            nm.notify(NOTE_ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Tailor Counter").setContentText("Opening the counter screen")
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).build())
        }
    }
}
