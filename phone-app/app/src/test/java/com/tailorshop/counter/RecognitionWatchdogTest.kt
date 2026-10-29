package com.tailorshop.counter

import org.junit.Assert.*
import org.junit.Test

class RecognitionWatchdogTest {
    @Test fun missingReadyRecoversAfterDeadline() {
        val w = RecognitionWatchdog(); w.started(100)
        assertFalse(w.stalled(15_099)); assertTrue(w.stalled(15_100))
    }
    @Test fun longIdleWithCallbacksStaysHealthy() {
        val w = RecognitionWatchdog(); w.started(0)
        for (now in 1L..3_600L) { w.heardCallback(now * 1_000); assertFalse(w.stalled(now * 1_000)) }
    }
    @Test fun lostCallbacksEventuallyExpire() {
        val w = RecognitionWatchdog(); w.started(0); w.heardCallback(500)
        assertFalse(w.stalled(60_499)); assertTrue(w.stalled(60_500))
    }
    @Test fun speechEndNeedsTerminalCallbackEvenWithRms() {
        val w = RecognitionWatchdog(); w.started(0); w.endedSpeech(1_000); w.heardCallback(15_000); w.endedSpeech(15_000)
        assertTrue(w.stalled(16_000))
    }
    @Test fun stopDisarmsAndIgnoresLateCallbacks() {
        val w = RecognitionWatchdog(); w.started(0); w.stopped(); w.heardCallback(1_000)
        assertFalse(w.stalled(9_000_000))
    }
    @Test fun newSessionHasNewDeadline() {
        val w = RecognitionWatchdog(); w.started(0); w.stopped(); w.started(100_000)
        assertFalse(w.stalled(110_000)); assertTrue(w.stalled(115_000))
    }
}
