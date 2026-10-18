package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarSoundMathTest {
    @Test
    void idleLoopDominatesAtIdleAndHighLoopNearTheLimiter() {
        double[] idle = CarSoundMath.engineMix(0.0);
        assertEquals(1.0, idle[0], 1e-9);
        assertEquals(0.0, idle[2], 1e-9);
        double[] limiter = CarSoundMath.engineMix(1.0);
        assertEquals(0.0, limiter[0], 1e-9);
        assertTrue(limiter[2] > limiter[1]);
        assertTrue(CarSoundMath.engineMix(0.4)[1] > 0.9);
    }

    @Test
    void theEngineCrossFadeNeverGoesSilentOrLoud() {
        for (double r = 0; r <= 1.0; r += 0.025) {
            double[] mix = CarSoundMath.engineMix(r);
            double sum = mix[0] + mix[1] + mix[2];
            assertTrue(sum > 0.4 && sum < 2.1, "rpmFraction " + r + " mix sum " + sum);
            for (double v : mix) {
                assertTrue(v >= 0.0 && v <= 1.0);
            }
        }
    }

    @Test
    void pitchRisesWithRpmFractionAndRevAndStaysBounded() {
        assertTrue(CarSoundMath.enginePitch(0.8, 0, 0) > CarSoundMath.enginePitch(0.1, 0, 0));
        assertTrue(CarSoundMath.enginePitch(0.5, 1, 0) > CarSoundMath.enginePitch(0.5, 0, 0));
        assertTrue(CarSoundMath.enginePitch(1.0, 1, 0) <= 2.0 + 1e-9);
        assertTrue(CarSoundMath.enginePitch(0.0, 0, 0) >= 0.5 - 1e-9);
    }

    @Test
    void aManualOrAutomaticShiftMovesPitchImmediatelyThroughRpmFraction() {
        // MINECRAFT-401: unlike the removed speed-banded gearPitch, a shift that drops RPM (e.g. an
        // upshift into a taller gear) must drop the pitch right away -- there is no separate per-gear
        // band to re-climb first.
        double beforeUpshift = CarSoundMath.enginePitch(0.9, 0, 0);
        double afterUpshift = CarSoundMath.enginePitch(0.3, 0, 0);
        assertTrue(afterUpshift < beforeUpshift);
    }

    @Test
    void loadDeepensPitchAndLoudensVolume() {
        assertTrue(CarSoundMath.enginePitch(0.3, 1, 1) < CarSoundMath.enginePitch(0.3, 1, 0));
        assertTrue(CarSoundMath.engineVolume(1, 1) > CarSoundMath.engineVolume(1, 0));
    }

    @Test
    void loadFactorIsHighestWhenThrottledWithoutSpeedToShowForIt() {
        assertEquals(0.0, CarSoundMath.loadFactor(0, 0), 1e-9);
        assertEquals(0.0, CarSoundMath.loadFactor(1, 10), 1e-9);
        assertTrue(CarSoundMath.loadFactor(1, 0) > CarSoundMath.loadFactor(1, 5));
        assertTrue(CarSoundMath.loadFactor(1, 0) > CarSoundMath.loadFactor(0.3, 0));
    }

    @Test
    void revChasesThrottleUpFastAndDownSlower() {
        double afterOneTickUp = CarSoundMath.nextRev(0.0, 1.0, 1.0 / 20.0);
        double afterOneTickDown = CarSoundMath.nextRev(1.0, 0.0, 1.0 / 20.0);
        assertTrue(afterOneTickUp > 0.0);
        assertTrue(afterOneTickUp > 1.0 - afterOneTickDown, "rev should chase up faster than it decays down");
        double rev = 0.0;
        for (int i = 0; i < 100; i++) {
            rev = CarSoundMath.nextRev(rev, 1.0, 1.0 / 20.0);
        }
        assertEquals(1.0, rev, 1e-9);
        assertTrue(rev >= 0.0 && rev <= 1.0);
    }

    @Test
    void impactSeverityBinsAndVolumeIncrease() {
        assertEquals(0, CarSoundMath.impactSeverity(1.0));
        assertEquals(1, CarSoundMath.impactSeverity(4.0));
        assertEquals(2, CarSoundMath.impactSeverity(8.0));
        assertEquals(3, CarSoundMath.impactSeverity(12.0));
        assertEquals(4, CarSoundMath.impactSeverity(30.0));
        assertEquals(0.0, CarSoundMath.impactVolume(0), 0);
        assertTrue(CarSoundMath.impactVolume(4) > CarSoundMath.impactVolume(1));
    }

    @Test
    void tireNoiseGrowsWithSpeedAndSkidWithSlip() {
        assertEquals(0.0, CarSoundMath.rollingVolume(0), 0);
        assertTrue(CarSoundMath.rollingVolume(15) > CarSoundMath.rollingVolume(3));
        assertEquals(CarSoundMath.rollingVolume(14), CarSoundMath.rollingVolume(40), 1e-9);
        assertTrue(CarSoundMath.skidVolume(CarSoundMath.slipLevel(3)) > CarSoundMath.skidVolume(CarSoundMath.slipLevel(1)));
        assertEquals(0.0, CarSoundMath.skidVolume(0), 0);
        assertTrue(!CarSoundMath.hardSkid(0.2) && CarSoundMath.hardSkid(0.8));
    }
}
