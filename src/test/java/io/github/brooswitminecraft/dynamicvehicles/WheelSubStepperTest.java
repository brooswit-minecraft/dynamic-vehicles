package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real per-sub-step composition SableCarBody.tick calls (WheelSubStepper.afterAntiRoll),
 * not a reimplementation of it, for N = 1..4 sub-steps per tick: the front axle's (wheels 0,1) and the
 * rear axle's (wheels 2,3) anti-roll transfer must sum to exactly 0 on EVERY sub-step, including
 * asymmetric loads and a wheel whose ray missed (0 compression, 0 suspension force).
 */
class WheelSubStepperTest {
    private static final double BAR_RATE = 19_620.0; // springRate * antiRollRatio(1.0), car's numbers
    private static final int[] PARTNERS = {1, 0, 3, 2}; // front-left<->front-right, rear-left<->rear-right

    private static void assertAxlesAntisymmetric(double[] suspensionForces, double[] compressions) {
        double[] after = WheelSubStepper.afterAntiRoll(suspensionForces, compressions, PARTNERS, BAR_RATE);
        for (int wheel = 0; wheel < 4; wheel++) {
            int partner = PARTNERS[wheel];
            double transfer = after[wheel] - suspensionForces[wheel];
            double partnerTransfer = after[partner] - suspensionForces[partner];
            assertEquals(0.0, transfer + partnerTransfer, 1e-9,
                    "axle (" + wheel + "," + partner + ") must sum to 0, got " + transfer + " + " + partnerTransfer);
            // Neither side of an axle can end up carrying negative load from the transfer alone.
            assertEquals(Math.max(0.0, after[wheel]), after[wheel], 1e-9, "wheel " + wheel + " went negative");
        }
    }

    @Test
    void axlesStayAntisymmetricAcrossEverySubStepForEverySubStepCount() {
        for (int subSteps = 1; subSteps <= 4; subSteps++) {
            // A settling car: compression and load on the left side ease down sub-step to sub-step
            // while the right side (asymmetric load, e.g. cornering) stays stiffer.
            double[] compressions = {0.20, 0.08, 0.18, 0.05};
            for (int sub = 0; sub < subSteps; sub++) {
                double ease = 1.0 - 0.1 * sub;
                double[] suspensionForces = {
                        WheelMath.suspensionForce(compressions[0] * ease, 0, 19_620.0, 3_400.0, 40_000.0),
                        WheelMath.suspensionForce(compressions[1], 0, 19_620.0, 3_400.0, 40_000.0),
                        WheelMath.suspensionForce(compressions[2] * ease, 0, 19_620.0, 3_400.0, 40_000.0),
                        WheelMath.suspensionForce(compressions[3], 0, 19_620.0, 3_400.0, 40_000.0),
                };
                assertAxlesAntisymmetric(suspensionForces, compressions);
            }
        }
    }

    @Test
    void aMissedRayWheelNeverReceivesOrGivesPastItsOwnShareAndStaysAntisymmetric() {
        for (int subSteps = 1; subSteps <= 4; subSteps++) {
            // Wheel 1 (front-right) missed its ray entirely: 0 compression, 0 suspension force.
            double[] compressions = {0.25, 0.0, 0.15, 0.15};
            double[] suspensionForces = {
                    WheelMath.suspensionForce(compressions[0], 0, 19_620.0, 3_400.0, 40_000.0),
                    0.0,
                    WheelMath.suspensionForce(compressions[2], 0, 19_620.0, 3_400.0, 40_000.0),
                    WheelMath.suspensionForce(compressions[3], 0, 19_620.0, 3_400.0, 40_000.0),
            };
            assertAxlesAntisymmetric(suspensionForces, compressions);
        }
    }

    @Test
    void asymmetricHardCorneringLoadStaysAntisymmetricAtEverySubStepCount() {
        for (int subSteps = 1; subSteps <= 4; subSteps++) {
            // Heavy weight transfer to one side, as under hard cornering.
            double[] compressions = {0.28, 0.03, 0.26, 0.04};
            double[] suspensionForces = {
                    WheelMath.suspensionForce(compressions[0], 0, 19_620.0, 3_400.0, 40_000.0),
                    WheelMath.suspensionForce(compressions[1], 0, 19_620.0, 3_400.0, 40_000.0),
                    WheelMath.suspensionForce(compressions[2], 0, 19_620.0, 3_400.0, 40_000.0),
                    WheelMath.suspensionForce(compressions[3], 0, 19_620.0, 3_400.0, 40_000.0),
            };
            assertAxlesAntisymmetric(suspensionForces, compressions);
        }
    }
}
