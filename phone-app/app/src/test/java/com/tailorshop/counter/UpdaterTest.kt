package com.tailorshop.counter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val pkg = "com.tailorshop.counter"

    @Test fun onlyOurPackageAndOnlyNewer() {
        assertTrue(Updater.isNewer(pkg, 3, pkg, 2))
        assertFalse(Updater.isNewer(pkg, 2, pkg, 2)) // same version: nothing
        assertFalse(Updater.isNewer(pkg, 1, pkg, 2)) // never downgrade
        assertFalse(Updater.isNewer("com.tailoring24.pickups", 99, pkg, 2)) // another app's build
    }

    @Test fun theTabletTakesOnlyItsOwnBuildsAndPhonesNeverTakeThem() {
        assertFalse(Updater.fits("", "SM-T820"))           // a phone-channel manifest on the tablet: refused
        assertTrue(Updater.fits("SM-T820", "SM-T820"))
        assertTrue(Updater.fits("", "SM-F956U"))           // a Fold on its own channel: exactly as before
        assertFalse(Updater.fits("SM-T820", "SM-F956U"))   // a tablet manifest on a Fold: refused
        assertFalse(Updater.fits("SM-T820", "Go103"))
    }

    @Test fun aTabletWithoutItsChannelSaysSo() {
        assertEquals("Update check failed at update channel setting: tablet update channel not set up",
            Updater.failureText(Updater.Failed("update channel setting", Updater.ChannelMissing())))
    }

    @Test fun failureNamesTheStepAndAShortReasonOnly() {
        assertEquals("Update check failed at sign-in: HTTP 400", Updater.failureText(Updater.Failed("sign-in", Exception("HTTP 400"))))
        assertEquals("Update check failed at sign-in: phone key file missing", Updater.failureText(Updater.Failed("sign-in", GoogleAuth.KeyMissing())))
        assertEquals("Update check failed at downloading: timed out", Updater.failureText(Updater.Failed("downloading", java.net.SocketTimeoutException("read timed out"))))
        assertEquals("Update check failed at verifying: not enough memory", Updater.failureText(Updater.Failed("verifying", OutOfMemoryError())))
        assertEquals("Update check failed at finding the update list: no internet", Updater.failureText(Updater.Failed("finding the update list", java.net.UnknownHostException("www.googleapis.com"))))
        // Free-text messages (which could hold ids or paths) are never shown; only the error kind.
        assertEquals("Update check failed at reading the update list: JSONException", Updater.failureText(Updater.Failed("reading the update list", org.json.JSONException("Value 1AbCdEf of type"))))
        assertEquals("Update check failed at unknown step: IllegalStateException", Updater.failureText(IllegalStateException("x")))
    }
}

class TabletBarsTest {
    @Test fun onlyTheHomeTestTabletHidesTheBar() {
        assertTrue(TabletBars.applies("SM-T820"))
        assertFalse(TabletBars.applies("SM-F956U"))  // a Fold
        assertFalse(TabletBars.applies("Go103"))     // the shop tablet
        assertFalse(TabletBars.applies("SM-T825"))   // only the exact model
        assertFalse(TabletBars.applies(""))
    }
}
