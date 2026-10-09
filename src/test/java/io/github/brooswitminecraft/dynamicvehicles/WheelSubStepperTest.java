package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exercises the real per-sub-step composition SableCarBody.tick calls - WheelSubStepper.afterAntiRoll for
 * the anti-roll invariant, WheelSubStepper.subStepSlipSpeed for the reported-slip one - not a
 * reimplementation of either.
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
        // Wheel 1 (front-right) missed its ray entirely: 0 compression, 0 suspension force.
        // afterAntiRoll takes no sub-step count, so this property does not vary with wheelSubSteps -
        // unlike axlesStayAntisymmetricAcrossEverySubStepForEverySubStepCount above, a loop over N here
        // would just re-assert the same call on the same data and was dropped as decorative.
        double[] compressions = {0.25, 0.0, 0.15, 0.15};
        double[] suspensionForces = {
                WheelMath.suspensionForce(compressions[0], 0, 19_620.0, 3_400.0, 40_000.0),
                0.0,
                WheelMath.suspensionForce(compressions[2], 0, 19_620.0, 3_400.0, 40_000.0),
                WheelMath.suspensionForce(compressions[3], 0, 19_620.0, 3_400.0, 40_000.0),
        };
        assertAxlesAntisymmetric(suspensionForces, compressions);
    }

    @Test
    void asymmetricHardCorneringLoadStaysAntisymmetric() {
        // Heavy weight transfer to one side, as under hard cornering. Same no-N-dependence rationale as
        // the missed-ray case above: afterAntiRoll's antisymmetry does not depend on wheelSubSteps, so
        // this asserts it once rather than looping over N against unchanging data.
        double[] compressions = {0.28, 0.03, 0.26, 0.04};
        double[] suspensionForces = {
                WheelMath.suspensionForce(compressions[0], 0, 19_620.0, 3_400.0, 40_000.0),
                WheelMath.suspensionForce(compressions[1], 0, 19_620.0, 3_400.0, 40_000.0),
                WheelMath.suspensionForce(compressions[2], 0, 19_620.0, 3_400.0, 40_000.0),
                WheelMath.suspensionForce(compressions[3], 0, 19_620.0, 3_400.0, 40_000.0),
        };
        assertAxlesAntisymmetric(suspensionForces, compressions);
    }

    // --- Reported slip (WheelSubStepper.subStepSlipSpeed), N-invariance ---
    //
    // Same scenario parameters as the epic's own measurement table on the MINECRAFT-72 story PR #30
    // review (effectiveMass 300 kg, dt 0.05, mu 1.1, wheel load 3000 N), so these numbers can be checked
    // directly against it: full-throttle wheelspin (drive 6000 N), hard braking (brake 5000 N from
    // 15 m/s), and mild cornering (vLat 0.8, gripping at N=1). That review measured the OLD, buggy
    // tire(..., subDt).slipSpeed() reported 1/N at N=3: 0.1475, 0.0944, and (the opposite failure) 0.2967
    // crossing the 0.3 gate. These tests simulate the real per-sub-step loop SableCarBody.tick runs -
    // velocity re-read between sub-steps, same vLong/vLat snapshot feeding both the actual force call
    // (subDt) and subStepSlipSpeed (tickDt) - and assert the FIXED number stays at the tick-comparable
    // scale (the N=1 value) at every N from 1 to 4.
    private static final double DT = 0.05;
    private static final double MU = 1.1;
    private static final double LOAD = 3000.0;
    private static final double EFFECTIVE_MASS = 300.0;
    private static final double ROLLING_COEFFICIENT = 0.015;

    private static double simulateMaxReportedSlip(double vLong0, double vLat0, double driveForce,
            double brakeForce, int subSteps) {
        double subDt = DT / subSteps;
        double vLong = vLong0;
        double vLat = vLat0;
        double maxReportedSlip = 0.0;
        for (int sub = 0; sub < subSteps; sub++) {
            WheelMath.Tire actual = WheelMath.tire(vLong, vLat, LOAD, MU, ROLLING_COEFFICIENT, 1.0, driveForce,
                    brakeForce, 1.0, EFFECTIVE_MASS, subDt);
            double reportedSlip = WheelSubStepper.subStepSlipSpeed(vLong, vLat, LOAD, MU, ROLLING_COEFFICIENT, 1.0,
                    driveForce, brakeForce, 1.0, EFFECTIVE_MASS, DT);
            maxReportedSlip = Math.max(maxReportedSlip, reportedSlip);
            // Same bookkeeping as SableCarBody.tick's own re-read: the impulse this sub-step just applied
            // changes velocity immediately, and the next sub-step (if any) sees it.
            vLong += actual.longitudinal() * subDt / EFFECTIVE_MASS;
            vLat += actual.lateral() * subDt / EFFECTIVE_MASS;
        }
        return maxReportedSlip;
    }

    @Test
    void reportedWheelspinSlipIsNInvariantAndStillClearsTheGate() {
        // demand (driveForce + rolling) does not scale with dt once rolling resistance's sign is fixed by
        // an already-positive vLong, so the N=1 slip is the invariant every N must match.
        double expected = (6000.0 - 45.0 - MU * LOAD) * DT / EFFECTIVE_MASS; // 0.4425
        for (int n = 1; n <= 4; n++) {
            double slip = simulateMaxReportedSlip(1.0, 0.0, 6000.0, 0.0, n);
            assertEquals(expected, slip, 1e-9, "wheelspin reported slip must match N=1 at N=" + n);
            assertTrue(slip > 0.3, "wheelspin must still clear the SlipReporter/wearSlip > 0.3 gate at N=" + n);
        }
    }

    @Test
    void reportedHardBrakingSlipIsNInvariantAndStillClearsTheGate() {
        // demand is clamped to brakeForce (the car is far too fast for the relaxation target to matter)
        // and stays clamped at every sub-step for every N tested, so it does not scale with dt either.
        double expected = (5000.0 - MU * LOAD) * DT / EFFECTIVE_MASS; // 0.28333...
        for (int n = 1; n <= 4; n++) {
            double slip = simulateMaxReportedSlip(15.0, 0.0, 0.0, 5000.0, n);
            assertEquals(expected, slip, 1e-9, "hard-braking reported slip must match N=1 at N=" + n);
            assertTrue(slip > 0.3, "hard braking must still clear the SlipReporter/wearSlip > 0.3 gate at N=" + n);
        }
    }

    @Test
    void reportedCorneringSlipNeverSpuriouslyCrossesTheGateForAGrippingWheel() {
        // At N=1 this wheel grips (demand under the friction circle, slip exactly 0). The OLD subDt-scaled
        // reporting crossed 0.3 by N=4 purely because the relaxation target's demand grows as 1/subDt;
        // evaluating at the tick's own dt, on a vLat that can only have shrunk by the time any sub-step
        // after the first runs, keeps every sub-step at or under the N=1 reading.
        for (int n = 1; n <= 4; n++) {
            double slip = simulateMaxReportedSlip(0.0, 0.8, 0.0, 0.0, n);
            assertEquals(0.0, slip, 1e-9, "a wheel gripping at N=1 must still report 0 slip at N=" + n);
            assertTrue(slip < 0.3, "a gripping wheel must never spuriously cross the > 0.3 gate at N=" + n);
        }
    }
}
