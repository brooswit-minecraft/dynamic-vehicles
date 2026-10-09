package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link MobBoardingRules#isEligible} (MINECRAFT-211): an adult, non-leashed,
 * non-hostile cow touching the bus may auto-board; anything else must not.
 */
class MobBoardingRulesTest {

    @Test
    void anAdultNonLeashedCowOnTheBusMayBoard() {
        assertTrue(MobBoardingRules.isEligible(true, true, false, false, false));
    }

    @Test
    void aMobTypeNotOnTheAllowListNeverBoards() {
        assertFalse(MobBoardingRules.isEligible(false, true, false, false, false));
    }

    @Test
    void aVehicleNotOnTheAllowListNeverAcceptsAnAutoBoardingMob() {
        assertFalse(MobBoardingRules.isEligible(true, false, false, false, false));
    }

    @Test
    void aBabyNeverBoards() {
        assertFalse(MobBoardingRules.isEligible(true, true, true, false, false));
    }

    @Test
    void aLeashedMobNeverBoards() {
        assertFalse(MobBoardingRules.isEligible(true, true, false, true, false));
    }

    @Test
    void aHostileMobNeverBoards() {
        assertFalse(MobBoardingRules.isEligible(true, true, false, false, true));
    }
}
