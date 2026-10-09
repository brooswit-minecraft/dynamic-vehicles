package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link StoppedTimer} (MINECRAFT-211): {@code tick} fires {@code true} exactly
 * once per stop -- never again while the vehicle stays stopped -- while {@link StoppedTimer#isLatched}
 * stays set for the whole stopped stretch, clearing only once the vehicle moves again. This is the fix
 * for a review finding on this ticket's first version: {@code tick} returning {@code true} on EVERY
 * tick while stopped made the caller dismount a passenger every tick, and a mob still touching the
 * vehicle re-boarded immediately -- a visible board/dismount flicker.
 */
class StoppedTimerTest {

    @Test
    void notYetStoppedLongEnoughNeverFiresAndIsNotLatched() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            assertFalse(timer.tick(0.0), "tick " + i + " should not yet fire");
            assertFalse(timer.isLatched());
        }
    }

    @Test
    void crossingTheDurationFiresOnceAndLatches() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            timer.tick(0.0);
        }
        assertTrue(timer.tick(0.0), "the tick that crosses the duration fires exactly once");
        assertTrue(timer.isLatched());
    }

    /** The core fix: staying stopped (e.g. a dismounted mob still touching the bus) must never re-fire. */
    @Test
    void stayingStoppedNeverFiresAgainButStaysLatched() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS; i++) {
            timer.tick(0.0);
        }
        for (int i = 0; i < 100; i++) {
            assertFalse(timer.tick(0.0), "must not fire again while it stays stopped");
            assertTrue(timer.isLatched(), "the latch must stay set the whole time it stays stopped");
        }
    }

    @Test
    void movingAboveTheThresholdClearsTheLatchAndResetsTheCount() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS; i++) {
            timer.tick(0.0);
        }
        assertTrue(timer.isLatched());

        assertFalse(timer.tick(StoppedTimer.STOP_SPEED_THRESHOLD + 0.01), "moving resets the count");
        assertFalse(timer.isLatched(), "moving clears the latch -- boarding is allowed again");

        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            assertFalse(timer.tick(0.0), "must count the full duration again after a reset");
        }
        assertTrue(timer.tick(0.0), "fires again after stopping long enough a second time");
    }

    @Test
    void exactlyAtTheThresholdCountsAsStopped() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            timer.tick(StoppedTimer.STOP_SPEED_THRESHOLD);
        }
        assertTrue(timer.tick(StoppedTimer.STOP_SPEED_THRESHOLD), "at-or-below the threshold counts as stopped");
    }
}
