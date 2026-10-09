package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link MobBoardingEligibility#isEligible} (MINECRAFT-211): an adult,
 * non-leashed, non-hostile cow touching the bus may auto-board; anything else must not.
 */
class MobBoardingEligibilityTest {

    @Test
    void anAdultNonLeashedCowOnTheBusMayBoard() {
        assertTrue(MobBoardingEligibility.isEligible(true, true, false, false, false));
    }

    @Test
    void aMobTypeNotOnTheAllowListNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(false, true, false, false, false));
    }

    @Test
    void aVehicleNotOnTheAllowListNeverAcceptsAnAutoBoardingMob() {
        assertFalse(MobBoardingEligibility.isEligible(true, false, false, false, false));
    }

    @Test
    void aBabyNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, true, false, false));
    }

    @Test
    void aLeashedMobNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, false, true, false));
    }

    @Test
    void aHostileMobNeverBoards() {
        assertFalse(MobBoardingEligibility.isEligible(true, true, false, false, true));
    }
}
