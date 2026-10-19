package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-445: the four-rate (slow/fast compression, slow/fast rebound) damping plus bump-stop
 * {@code suspensionForce} overload, independent of any production wiring through {@code SableCarBody} or
 * {@code VehicleSpecOverrides} (covered by {@code VehicleSpecOverridesTest} instead).
 */
class WheelMathSuspensionTuningTest {

    @Test
    void reducesToSingleRateWhenEveryDampingRateMatches() {
        double compression = 0.2;
        double springRate = 19_620.0;
        double maxForce = 40_000.0;
        for (double rate : new double[] {0.0, 10.0}) {
            double single = WheelMath.suspensionForce(compression, rate, springRate, 3_400.0, maxForce);
            double fourRate = WheelMath.suspensionForce(compression, rate, springRate,
                    3_400.0, 3_400.0, 3_400.0, 3_400.0, 1.0, maxForce, Double.MAX_VALUE, 0.0);
            assertEquals(single, fourRate, 1e-9, "compressionRate=" + rate);
        }
    }

    @Test
    void reducesToSingleRateForAnyKneeSpeedWhenEveryDampingRateMatches() {
        // The algebraic reduction must not depend on where the knee sits, since every rate on both sides of
        // it is identical.
        double compression = 0.2;
        double compressionRate = 2.5;
        double single = WheelMath.suspensionForce(compression, compressionRate, 19_620.0, 3_400.0, 40_000.0);
        for (double knee : new double[] {0.0, 0.5, 1.0, 5.0, 100.0}) {
            double fourRate = WheelMath.suspensionForce(compression, compressionRate, 19_620.0,
                    3_400.0, 3_400.0, 3_400.0, 3_400.0, knee, 40_000.0, Double.MAX_VALUE, 0.0);
            assertEquals(single, fourRate, 1e-9, "kneeSpeed=" + knee);
        }
    }

    @Test
    void fastCompressionDampingOnlyAppliesAboveTheKnee() {
        double compression = 0.1;
        double springRate = 19_620.0;
        double maxForce = 100_000.0;
        double slow = 1_000.0;
        double fast = 9_000.0;
        double knee = 1.0;

        // At exactly the knee: only the slow rate has applied so far.
        double atKnee = WheelMath.suspensionForce(compression, knee, springRate, slow, fast, slow, fast, knee,
                maxForce, Double.MAX_VALUE, 0.0);
        assertEquals(springRate * compression + slow * knee, atKnee, 1e-6);

        // Just below the knee uses only the slow rate, scaling linearly with speed.
        double belowKnee = WheelMath.suspensionForce(compression, knee / 2, springRate, slow, fast, slow, fast,
                knee, maxForce, Double.MAX_VALUE, 0.0);
        assertEquals(springRate * compression + slow * knee / 2, belowKnee, 1e-6);

        // Above the knee, the extra speed is damped at the FAST rate, continuous with the value at the knee.
        double aboveKnee = WheelMath.suspensionForce(compression, knee + 1.0, springRate, slow, fast, slow, fast,
                knee, maxForce, Double.MAX_VALUE, 0.0);
        assertEquals(springRate * compression + slow * knee + fast * 1.0, aboveKnee, 1e-6);
    }

    @Test
    void reboundUsesItsOwnRatesIndependentlyOfCompression() {
        double compression = 0.1;
        double springRate = 19_620.0;
        double maxForce = 100_000.0;
        double knee = 1.0;
        double slowRebound = 2_000.0;
        double fastRebound = 12_000.0;

        // compressionRate negative (-2.0 m/s, 1.0 m/s past the knee): rebound-only rates must apply, not the
        // compression ones (10x the rebound values here, so a mix-up would be obvious).
        double force = WheelMath.suspensionForce(compression, -2.0, springRate,
                50_000.0, 50_000.0, slowRebound, fastRebound, knee, maxForce, Double.MAX_VALUE, 0.0);
        double expectedDampingForce = -(slowRebound * knee + fastRebound * 1.0);
        assertEquals(Math.max(0.0, springRate * compression + expectedDampingForce), force, 1e-6);
    }

    @Test
    void bumpStopAddsNoForceBelowItsOwnTravelThreshold() {
        double withoutBumpStop = WheelMath.suspensionForce(0.1, 0.0, 19_620.0, 3_400.0, 3_400.0, 3_400.0,
                3_400.0, 1.0, 100_000.0, Double.MAX_VALUE, 50_000.0);
        double belowThreshold = WheelMath.suspensionForce(0.1, 0.0, 19_620.0, 3_400.0, 3_400.0, 3_400.0,
                3_400.0, 1.0, 100_000.0, 0.2, 50_000.0);
        assertEquals(withoutBumpStop, belowThreshold, 1e-9);
    }

    @Test
    void bumpStopAddsProgressiveForcePastItsOwnTravelThreshold() {
        double springRate = 19_620.0;
        double bumpStopTravel = 0.2;
        double bumpStopRate = 50_000.0;
        double compression = 0.25;

        double force = WheelMath.suspensionForce(compression, 0.0, springRate, 3_400.0, 3_400.0, 3_400.0,
                3_400.0, 1.0, 1_000_000.0, bumpStopTravel, bumpStopRate);

        double expected = springRate * compression + bumpStopRate * (compression - bumpStopTravel);
        assertEquals(expected, force, 1e-6);
    }

    @Test
    void bumpStopNeverPullsForceNegativeAndStaysCappedAtMaxForce() {
        double force = WheelMath.suspensionForce(5.0, 0.0, 19_620.0, 3_400.0, 3_400.0, 3_400.0, 3_400.0, 1.0,
                40_000.0, 0.1, 500_000.0);
        assertTrue(force >= 0.0);
        assertEquals(40_000.0, force, 0);
    }

    @Test
    void notTouchingTheGroundIsStillZeroRegardlessOfTireKitFields() {
        assertEquals(0.0,
                WheelMath.suspensionForce(-0.1, 5.0, 19_620.0, 1.0, 2.0, 3.0, 4.0, 0.5, 40_000.0, 0.1, 50_000.0),
                0);
    }
}
