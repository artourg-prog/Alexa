package com.tailorshop.counter

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
}
