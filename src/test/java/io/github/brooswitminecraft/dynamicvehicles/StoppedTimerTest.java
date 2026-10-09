package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link StoppedTimer} (MINECRAFT-211): a vehicle must stay at or below the
 * speed threshold for the full dismount duration before it reports "stopped", and any faster tick in
 * between resets the count.
 */
class StoppedTimerTest {

    @Test
    void notYetStoppedLongEnoughReportsFalse() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            assertFalse(timer.tick(0.0), "tick " + i + " should not yet report stopped");
        }
    }

    @Test
    void stoppedForTheFullDurationReportsTrue() {
        StoppedTimer timer = new StoppedTimer();
        boolean result = false;
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS; i++) {
            result = timer.tick(0.0);
        }
        assertTrue(result);
    }

    @Test
    void stayingStoppedKeepsReportingTrue() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS; i++) {
            timer.tick(0.0);
        }
        assertTrue(timer.tick(0.0), "staying stopped must keep reporting stopped");
    }

    @Test
    void movingAboveTheThresholdResetsTheCount() {
        StoppedTimer timer = new StoppedTimer();
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            timer.tick(0.0);
        }
        assertFalse(timer.tick(StoppedTimer.STOP_SPEED_THRESHOLD + 0.01), "moving resets the stopped count");
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS - 1; i++) {
            assertFalse(timer.tick(0.0), "must count the full duration again after a reset");
        }
        assertTrue(timer.tick(0.0));
    }

    @Test
    void exactlyAtTheThresholdCountsAsStopped() {
        StoppedTimer timer = new StoppedTimer();
        boolean result = false;
        for (int i = 0; i < StoppedTimer.STOPPED_DISMOUNT_TICKS; i++) {
            result = timer.tick(StoppedTimer.STOP_SPEED_THRESHOLD);
        }
        assertTrue(result, "at-or-below the threshold counts as stopped");
    }
}
