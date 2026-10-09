package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MINECRAFT-130 AC1: periodic roll windows, measured from a contract's own acceptedAtTick. */
class AmbushScheduleTest {

    private static final long SWEEP = 20;
    private static final long INTERVAL = 600;

    @Test
    void isRollTick_false_beforeOneFullIntervalHasElapsed() {
        assertFalse(AmbushSchedule.isRollTick(0, 0, SWEEP, INTERVAL), "must not roll on the sweep tick a contract is accepted");
        assertFalse(AmbushSchedule.isRollTick(0, 300, SWEEP, INTERVAL));
        assertFalse(AmbushSchedule.isRollTick(0, 580, SWEEP, INTERVAL));
    }

    @Test
    void isRollTick_true_onTheSweepTickThatCrossesTheFirstWindowBoundary() {
        assertTrue(AmbushSchedule.isRollTick(0, 600, SWEEP, INTERVAL));
    }

    @Test
    void isRollTick_false_onTheNextSweepTickInsideTheSameWindow() {
        assertTrue(AmbushSchedule.isRollTick(0, 600, SWEEP, INTERVAL));
        assertFalse(AmbushSchedule.isRollTick(0, 620, SWEEP, INTERVAL));
    }

    @Test
    void isRollTick_true_onlyOncePerSubsequentWindowBoundary() {
        assertTrue(AmbushSchedule.isRollTick(0, 1200, SWEEP, INTERVAL));
        assertFalse(AmbushSchedule.isRollTick(0, 1180, SWEEP, INTERVAL));
        assertFalse(AmbushSchedule.isRollTick(0, 1220, SWEEP, INTERVAL));
    }

    @Test
    void isRollTick_measuresFromAcceptedAtTick_notFromZero() {
        assertFalse(AmbushSchedule.isRollTick(1000, 1580, SWEEP, INTERVAL));
        assertTrue(AmbushSchedule.isRollTick(1000, 1600, SWEEP, INTERVAL));
    }
}
