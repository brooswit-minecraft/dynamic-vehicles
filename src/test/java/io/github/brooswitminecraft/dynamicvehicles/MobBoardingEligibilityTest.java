package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link MobBoardingEligibility#isEligible} (MINECRAFT-211): an adult,
 * non-leashed, non-hostile cow touching the bus may auto-board, unless the bus is latched "stopped past
 * threshold" -- anything else must never board. The latch case, together with {@link StoppedTimerTest},
 * covers the full stop -&gt; dismount -&gt; still touching -&gt; no re-board -&gt; bus moves -&gt; can board
 * again sequence a review of this ticket's first version asked to see covered: {@code StoppedTimerTest}
 * proves the latch itself sets once and clears only on movement, and
 * {@link #aVehicleLatchedStoppedPastThresholdNeverAcceptsAnyMob} proves that latch alone blocks boarding
 * regardless of how otherwise-eligible the mob is.
 */
class MobBoardingEligibilityTest {

    @Test
    void anAdultNonLeashedCowOnAMovingBusMayBoard() {
        assertTrue(MobBoardingEligibility.isEligible(true, true, false, false, false, false));
    }

    @Test
    void aMobTypeNotOnTheAllowListNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(false, true, false, false, false, false));
    }

    @Test
    void aVehicleNotOnTheAllowListNeverAcceptsAnAutoBoardingMob() {
        assertFalse(MobBoardingEligibility.isEligible(true, false, false, false, false, false));
    }

    @Test
    void aBabyNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, true, false, false, false));
    }

    @Test
    void aLeashedMobNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, false, true, false, false));
    }

    @Test
    void aHostileMobNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, false, false, true, false));
    }

    /**
     * MINECRAFT-211 review fix: a bus latched "stopped past threshold" (see {@link StoppedTimer}) must
     * refuse every mob, otherwise-eligible or not -- this is what stops a mob that just auto-dismounted,
     * or any mob touching an already-long-stopped bus, from immediately re-boarding every tick.
     */
    @Test
    void aVehicleLatchedStoppedPastThresholdNeverAcceptsAnyMob() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, false, false, false, true));
    }
}
