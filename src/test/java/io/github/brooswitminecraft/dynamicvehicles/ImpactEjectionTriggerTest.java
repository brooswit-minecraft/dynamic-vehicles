package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link ImpactEjectionTrigger} (MINECRAFT-249 review round 2): a real impact
 * (severity &gt; 0) triggers ejection exactly when it is not still inside the previous impact's own
 * cooldown -- the identical condition {@code CarEntity.checkImpact} already uses for its own sound.
 */
class ImpactEjectionTriggerTest {

    @Test
    void noImpactNeverEjectsRegardlessOfCooldown() {
        assertFalse(ImpactEjectionTrigger.shouldEject(0, 0));
        assertFalse(ImpactEjectionTrigger.shouldEject(0, 10));
    }

    @Test
    void aRealImpactWithNoCooldownRunningEjects() {
        assertTrue(ImpactEjectionTrigger.shouldEject(1, 0));
        assertTrue(ImpactEjectionTrigger.shouldEject(3, 0));
    }

    /** The core fix this class exists for: a crash is a speed LOSS, so this must fire independently of
     * {@link CrashEjectionLatch}, which can never trip from a crash alone. */
    @Test
    void aRealImpactStillEjectsEvenWhenFarBelowTheCrashSpeedThreshold() {
        assertTrue(ImpactEjectionTrigger.shouldEject(2, 0));
    }

    @Test
    void anImpactStillInsideThePreviousCooldownNeverEjectsAgain() {
        assertFalse(ImpactEjectionTrigger.shouldEject(2, 1));
        assertFalse(ImpactEjectionTrigger.shouldEject(2, 10));
    }
}
