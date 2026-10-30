package com.tailorshop.counter

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime

// Wi-Fi updates (same file format as Pickups): the private Drive folder "Tailor Counter Builds"
// holds latest.json {package, versionCode, versionName, size, sha256, parts[], partSha256[]} and the
// ~4 MB pieces in parts/. The folder is shared Viewer with the phone's service account only, read with
// drive.readonly. Before installing: right package, higher version, every piece and the whole file
// match their SHA-256, and the APK is signed with the same certificate as the installed app.
// Installs without a tap once the app is its own installer (Android shows "Update?" the first time).
object Updater {
    private fun folderId(ctx:Context):String = ctx.assets.open("private-channel.json").bufferedReader().use {
        JSONObject(it.readText()).getString("folder").also { id->require(id.matches(Regex("[A-Za-z0-9_-]{10,100}"))) }
    }
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val DRIVE = "https://www.googleapis.com/drive/v3/files"
    private const val TAG = "TailorCounter"
    private val shopZone: ZoneId = ZoneId.of("America/Los_Angeles")
    private const val NIGHT_HOUR = 8 // 8:00 AM shop time, well before opening at 10:45
    private const val JOB_ID = 4301

    /** Waiting "Update?" screen from Android (first Wi-Fi update only); MainActivity shows it. */
    @Volatile var confirmIntent: Intent? = null
    @Volatile var lastResult: String = ""

    /** Pure rule: install only our own package, and only a newer version. */
    fun isNewer(pkg: String, code: Long, ourPkg: String, installed: Long) = pkg == ourPkg && code > installed

    fun installedVersion(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun versionName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Network: call off the main thread. Returns a short plain-words result. */
    fun checkAndInstall(ctx: Context): String {
        val auth = GoogleAuth(File(ctx.filesDir, "sheet-key.json"), SCOPE)
        val bearer = "Bearer ${auth.accessToken()}"
        val latestId = findFile(bearer, folderId(ctx), "latest.json") ?: return done("No build published yet")
        val m = JSONObject(String(download(bearer, latestId)))
        val code = m.getLong("versionCode")
        if (!isNewer(m.optString("package"), code, ctx.packageName, installedVersion(ctx)))
            return done("Up to date (v${versionName(ctx)})")

        val partsFolder = findFile(bearer, folderId(ctx), "parts") ?: return done("Update failed: no parts folder")
        val names = m.getJSONArray("parts"); val sums = m.getJSONArray("partSha256")
        val apk = File(ctx.cacheDir, "update.apk")
        apk.outputStream().use { out ->
            for (i in 0 until names.length()) {
                val id = findFile(bearer, partsFolder, names.getString(i)) ?: return done("Update failed: piece missing")
                val bytes = download(bearer, id)
                if (sha256(bytes) != sums.getString(i)) return done("Update failed: piece damaged")
                out.write(bytes)
            }
        }
        if (apk.length() != m.getLong("size") || sha256(apk.readBytes()) != m.getString("sha256"))
            return done("Update failed: file damaged")
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return done("Update failed: not an app")
        if (info.packageName != ctx.packageName || info.longVersionCode != code) return done("Update failed: wrong app")
        val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val theirs = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (mine.isNullOrEmpty() || mine != theirs) return done("Update failed: different signature")

        install(ctx, apk)
        return done("Installing v${m.optString("versionName")}…")
    }

    /** Private service configuration travels through the same authenticated read-only channel. */
    fun intentConfig(ctx:Context):JSONObject? {
        val bearer="Bearer ${GoogleAuth(File(ctx.filesDir,"sheet-key.json"),SCOPE).accessToken()}"
        val id=findFile(bearer,folderId(ctx),"intent-config.json") ?: return null
        val bytes=download(bearer,id)
        require(bytes.size<=4096)
        return JSONObject(String(bytes))
    }

    private fun done(s: String): String { lastResult = s; Log.d(TAG, "update: $s"); return s }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val result = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResult::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(result.intentSender)
        }
    }

    private fun findFile(bearer: String, parent: String, name: String): String? {
        val q = URLEncoder.encode("'$parent' in parents and name = '$name' and trashed = false", "UTF-8")
        val body = String(GoogleAuth.http("$DRIVE?q=$q&orderBy=modifiedTime%20desc&fields=files(id)", null, bearer))
        val files = JSONObject(body).optJSONArray("files") ?: return null
        return if (files.length() == 0) null else files.getJSONObject(0).getString("id")
    }

    private fun download(bearer: String, id: String) = GoogleAuth.http("$DRIVE/$id?alt=media", null, bearer, 30_000)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ---- once a day, on Wi-Fi ----

    fun scheduleNight(ctx: Context) {
        val now = ZonedDateTime.now(shopZone)
        var at = now.withHour(NIGHT_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, NightAlarm::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi) }
        catch (e: SecurityException) { Log.d(TAG, "can't schedule update check: ${e.message}") }
    }

    fun runJobSoon(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).build())
    }

    class NightAlarm : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) { runJobSoon(ctx); scheduleNight(ctx) }
    }

    class UpdateJob : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            Thread {
                try { checkAndInstall(applicationContext) } catch (e: Exception) { done("Update check failed: ${e.message}") }
                jobFinished(params, false)
            }.start()
            return true
        }
        override fun onStopJob(params: JobParameters) = false
    }

    class InstallResult : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    done("Tap Update to install (first time only)")
                }
                PackageInstaller.STATUS_SUCCESS -> done("Updated")
                else -> done("Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}")
            }
        }
    }

    /** After an update or a reboot: keep the night check, and bring the counter screen back. */
    class Restart : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            scheduleNight(ctx)
            Wake.schedule(ctx, System.currentTimeMillis() + 3_000, 3)
        }
    }
}
