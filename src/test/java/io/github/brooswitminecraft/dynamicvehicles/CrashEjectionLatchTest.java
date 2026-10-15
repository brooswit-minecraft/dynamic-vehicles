package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link CrashEjectionLatch} (MINECRAFT-249): {@code tick} fires {@code true}
 * exactly on the tick speed first crosses {@link CrashEjectionLatch#CRASH_EJECT_SPEED_THRESHOLD}, stays
 * latched for as long as the vehicle remains that fast, and clears the moment it drops back at or under
 * the threshold.
 */
class CrashEjectionLatchTest {

    @Test
    void belowOrAtTheThresholdNeverFiresAndIsNotLatched() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        assertFalse(latch.tick(0.0));
        assertFalse(latch.isLatched());
        assertFalse(latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD));
        assertFalse(latch.isLatched(), "exactly at the threshold must not latch");
    }

    @Test
    void crossingAboveTheThresholdFiresOnceAndLatches() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        assertTrue(latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 0.01));
        assertTrue(latch.isLatched());
    }

    /** Staying fast must never re-fire -- the caller ejects once per crossing, not every tick while fast. */
    @Test
    void stayingAboveTheThresholdNeverFiresAgainButStaysLatched() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 0.01);
        for (int i = 0; i < 100; i++) {
            assertFalse(latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 0.5),
                    "must not fire again while it stays fast");
            assertTrue(latch.isLatched());
        }
    }

    @Test
    void droppingBackAtOrBelowTheThresholdClearsTheLatch() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 0.01);
        assertTrue(latch.isLatched());

        assertFalse(latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD));
        assertFalse(latch.isLatched());
    }

    /** A second crossing after clearing must fire again -- the edge-trigger is per-crossing, not one-shot. */
    @Test
    void crossingAgainAfterClearingFiresAgain() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 0.01);
        latch.tick(0.0);
        assertFalse(latch.isLatched());

        assertTrue(latch.tick(CrashEjectionLatch.CRASH_EJECT_SPEED_THRESHOLD + 0.01));
        assertTrue(latch.isLatched());
    }

    /** The bus's own top speed (MINECRAFT-249 javadoc: 1.0 blocks/tick) must never cross this threshold. */
    @Test
    void ordinaryBusTopSpeedNeverCrossesTheThreshold() {
        CrashEjectionLatch latch = new CrashEjectionLatch();
        assertFalse(latch.tick(1.0));
        assertFalse(latch.isLatched());
    }
}
