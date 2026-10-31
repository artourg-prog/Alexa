package com.tailorshop.counter

import android.content.Intent
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import com.tailorshop.counter.conversation.OrderCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Dedicated emulator, fake transports only: never needs or writes production credentials. */
class TouchUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun set(a: TouchActivity, name: String, value: Any) = TouchActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(a, value)
    private fun web(a: TouchActivity) = TouchActivity::class.java.getDeclaredField("web").apply { isAccessible = true }.get(a) as WebView
    private fun js(a: TouchActivity, script: String): String {
        val done = CountDownLatch(1); var value = ""
        ins.runOnMainSync { web(a).evaluateJavascript(script) { value = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return value
    }
    private fun waitFor(a: TouchActivity, script: String) {
        val until = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < until) { if (js(a, script) == "true") return; Thread.sleep(80) }
        assertEquals(script, "true", js(a, script))
    }
    private fun click(a: TouchActivity, selector: String) { js(a, "document.querySelector(${JSONObject.quote(selector)}).click()") }
    private fun number(a: TouchActivity, value: String) {
        js(a, "(()=>{const i=document.querySelector('.sf-digits');i.value=${JSONObject.quote(value)};i.dispatchEvent(new Event('input'));})()")
        click(a, ".sf-find")
    }
    private fun screenshot(name: String) {
        ins.waitForIdleSync(); Thread.sleep(180) // let the compositor present the DOM state being verified
        ins.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(ins.targetContext.cacheDir, "touch-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun fixture(scanState: String = "ADDED", smsState: String = "SENT"): Triple<TouchActivity, Finishes, Pickups> {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator only" }
        val a = ins.startActivitySync(Intent(ins.targetContext, TouchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TouchActivity
        val dir = File(ins.targetContext.cacheDir, "touch-fixture-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "finish-config.json").writeText(JSONObject().put("url", "https://invalid.test").put("secret", "synthetic")
            .put("workers", org.json.JSONArray(listOf("Andrei", "Artour", "Carolina", "Emma", "Tsegi", "Tumur"))).toString())
        File(dir, "pickup-config.json").writeText("{\"url\":\"https://invalid.test\",\"secret\":\"synthetic\"}")
        val f = Finishes(dir, poster = { _, body -> when (body.optString("mode")) {
            "addScan", "scanStatus" -> JSONObject().put("ok", true).put("status", scanState).put("appliedCents", 4500)
            "pickup" -> JSONObject().put("ok", true).put("state", smsState)
            else -> error("Unexpected mode")
        } })
        val p = Pickups(dir, poster = { _, body -> when (body.optString("mode")) {
            "pickupRequest" -> JSONObject().put("result", "QUEUED")
            "pickupStatus" -> JSONObject().put("states", JSONObject().apply { val ids = body.getJSONArray("requestIds"); for (i in 0 until ids.length()) put(ids.getString(i), "APPLIED") })
            else -> error("Unexpected mode")
        } })
        val rows = listOf(listOf("Receipt #", "Customer", "Phone", "M.T.", "Payment Type", "Total"), listOf("48321", "Synthetic", "0000005678", "Andrei", "Cash", "45.00"))
        ins.runOnMainSync {
            set(a, "finishes", f); set(a, "pickups", p); set(a, "sheets", Sheets(File(dir, "missing"), reader = { rows }))
            set(a, "orders", Lookup.parseOrders(rows)); set(a, "loadedAt", System.currentTimeMillis()); web(a).reload()
        }
        waitFor(a, "!!document.querySelector('.sf-home-actions')")
        waitFor(a, "document.fonts.check('88px \"Great Vibes\"')")
        screenshot("home")
        return Triple(a, f, p)
    }
    @Test fun pickupNoKeepsOrderAndYesOnlyRecordsExplicitTap() {
        val (a, f, p) = fixture()
        try {
            click(a, "[data-go='lookup']"); waitFor(a, "!!document.querySelector('.sf-digits')"); number(a, "5678")
            waitFor(a, "!!document.querySelector('.sf-not-picked-up')"); click(a, ".sf-not-picked-up")
            waitFor(a, "!!document.querySelector('.sf-home-actions')"); assertTrue(p.pending().isEmpty()); assertTrue(f.all().isEmpty())
            click(a, "[data-go='lookup']"); number(a, "5678"); waitFor(a, "!!document.querySelector('.sf-pickup')"); click(a, ".sf-pickup")
            waitFor(a, "!!document.querySelector('.sf-home-actions')"); assertTrue("48321" in p.pending()); assertTrue(f.all().isEmpty())
        } finally { ins.runOnMainSync { a.finish() } }
    }
    @Test fun saveAndSmsAreSeparateAndRecordedUsesServerConfirmation() {
        val (a, f, p) = fixture()
        try {
            click(a, "[data-go='report']"); number(a, "48321"); waitFor(a, "document.querySelectorAll('.sf-workers button').length===6")
            screenshot("workers")
            click(a, ".sf-workers button"); waitFor(a, "!!document.querySelector('[data-finish=save]')")
            screenshot("sms")
            assertTrue(f.all().isEmpty()); click(a, "[data-finish=save]")
            waitFor(a, "!!document.querySelector('.sf-report-confirmation')")
            screenshot("recorded")
            assertEquals(1, f.all().size); assertFalse(f.all().single().sendSms); assertTrue(p.pending().isEmpty())
            assertEquals("\"48321\"", js(a, "document.querySelector('.sf-receipt-number').textContent"))
            // Re-entering the same work reuses the durable event; an explicit SMS choice adds one SMS request.
            js(a, "document.querySelector('.sf-home').click()")
            click(a, "[data-go='report']"); number(a, "48321"); waitFor(a, "!!document.querySelector('.sf-workers button')"); click(a, ".sf-workers button")
            click(a, "[data-finish=sms]"); waitFor(a, "!!document.querySelector('.sf-report-confirmation')")
            assertEquals(1, f.all().size); assertTrue(f.all().single().sendSms); assertEquals("SENT", f.all().single().sms)
        } finally { ins.runOnMainSync { a.finish() } }
    }
    @Test fun queuedRequestIsNeverShownAsRecordedOrSmsSent() {
        val (a, f, _) = fixture("ACCEPTED", "QUEUED")
        try {
            click(a, "[data-go='report']"); number(a, "48321"); waitFor(a, "!!document.querySelector('.sf-workers button')")
            click(a, ".sf-workers button"); click(a, "[data-finish=sms]")
            waitFor(a, "document.querySelector('.sf-busy-title')?.textContent==='Saving…'")
            assertEquals("ACCEPTED", f.all().single().scan)
            assertEquals("false", js(a, "!!document.querySelector('.sf-report-confirmation')"))
            assertEquals("false", js(a, "document.querySelector('.sf-body').textContent.includes('Customer SMS sent')"))
        } finally { ins.runOnMainSync { a.finish() } }
    }
}
