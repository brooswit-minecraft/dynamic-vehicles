package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WheelMathTest {
    @Test
    void noGroundContactMeansNoForce() {
        assertEquals(0.0, WheelMath.suspensionForce(-0.1, 5.0), 0);
        assertEquals(0.0, WheelMath.suspensionForce(0.0, 5.0), 0);
        assertEquals(0.0, WheelMath.suspensionForce(Double.NaN, 0.0), 0);
    }

    @Test
    void staticLoadGivesAboutQuarterOfTheCarsWeightAtFifteenCentimetres() {
        double quarterWeight = 1200.0 * 9.81 / 4.0;
        assertEquals(quarterWeight, WheelMath.suspensionForce(0.15, 0.0), quarterWeight * 0.01);
    }

    @Test
    void moreCompressionPushesHarder() {
        assertTrue(WheelMath.suspensionForce(0.2, 0) > WheelMath.suspensionForce(0.1, 0));
    }

    @Test
    void theDamperResistsSqueezingAndEasesRebound() {
        double still = WheelMath.suspensionForce(0.15, 0.0);
        assertTrue(WheelMath.suspensionForce(0.15, 1.0) > still);
        assertTrue(WheelMath.suspensionForce(0.15, -1.0) < still);
    }

    @Test
    void aSpringNeverPullsAndNeverExceedsTheCap() {
        assertEquals(0.0, WheelMath.suspensionForce(0.01, -50.0), 0);
        assertEquals(WheelMath.MAX_FORCE, WheelMath.suspensionForce(0.6, 50.0), 0);
    }

    private static final double N = 2943.0; // a quarter of the car at rest
    private static final double DT = 0.05;

    @Test
    void aTireWithNoLoadOrNoGripDoesNothing() {
        assertEquals(0.0, WheelMath.tire(5, 5, 0, 1.1, 1, 1000, 0, DT).longitudinal(), 0);
        assertEquals(0.0, WheelMath.tire(5, 5, N, 0, 1, 1000, 0, DT).lateral(), 0);
    }

    @Test
    void aGrippingTireOpposesSidewaysSlidingAndDoesNotSlip() {
        WheelMath.Tire tire = WheelMath.tire(0, 0.3, N, 1.1, 1, 0, 0, DT);
        assertTrue(tire.lateral() < 0, "force opposes +lateral velocity");
        assertEquals(0.0, tire.slipSpeed(), 1e-9);
    }

    @Test
    void driveForceIsDeliveredWhileGripHolds() {
        WheelMath.Tire tire = WheelMath.tire(0, 0, N, 1.1, 1, 1500, 0, DT);
        assertEquals(1500.0, tire.longitudinal(), 1e-9);
        assertEquals(0.0, tire.slipSpeed(), 1e-9);
    }

    @Test
    void askingForMoreThanTheFrictionCircleSpinsTheWheelAndReportsSlip() {
        WheelMath.Tire tire = WheelMath.tire(0, 0, N, 1.1, 1, 6000, 0, DT);
        assertEquals(1.1 * N, Math.hypot(tire.longitudinal(), tire.lateral()), 1e-6);
        assertTrue(tire.slipSpeed() > 0);
    }

    @Test
    void sidewaysDemandReducesWhatIsLeftForDriving() {
        WheelMath.Tire straight = WheelMath.tire(0, 0, N, 1.1, 1, 3000, 0, DT);
        WheelMath.Tire sliding = WheelMath.tire(0, 6.0, N, 1.1, 1, 3000, 0, DT);
        assertTrue(Math.abs(sliding.longitudinal()) < Math.abs(straight.longitudinal()));
    }

    @Test
    void brakingOpposesMotionAndIsCappedByTheBrake() {
        WheelMath.Tire tire = WheelMath.tire(20, 0, N, 5.0, 1, 0, 1800, DT);
        assertEquals(-1800.0, tire.longitudinal(), 1e-9);
        WheelMath.Tire slow = WheelMath.tire(0.05, 0, N, 5.0, 1, 0, 1800, DT);
        assertTrue(slow.longitudinal() < 0 && slow.longitudinal() > -1800.0);
    }

    @Test
    void rollingResistanceSlowsACoastingCar() {
        WheelMath.Tire tire = WheelMath.tire(10, 0, N, 1.1, 1, 0, 0, DT);
        assertEquals(-WheelMath.ROLLING_RESISTANCE * N, tire.longitudinal(), 1e-9);
        assertEquals(0.0, WheelMath.tire(0, 0, N, 1.1, 1, 0, 0, DT).longitudinal(), 1e-12);
    }

    @Test
    void reducedLateralScaleLetsTheTireSlide() {
        assertTrue(Math.abs(WheelMath.tire(0, 1, N, 1.1, 0.3, 0, 0, DT).lateral())
                < Math.abs(WheelMath.tire(0, 1, N, 1.1, 1.0, 0, 0, DT).lateral()));
    }

    @Test
    void steeringLockNarrowsWithSpeedSoTheCarCannotAskForMoreThanTheTiresHold() {
        double slow = WheelMath.maxSteerAngle(2, 1.1, 2.4, 0.55);
        double medium = WheelMath.maxSteerAngle(12, 1.1, 2.4, 0.55);
        double fast = WheelMath.maxSteerAngle(30, 1.1, 2.4, 0.55);
        assertEquals(0.55, slow, 1e-12);
        assertTrue(medium < slow && fast < medium);
        // Cornering demand at the limit stays within 80% of mu*g.
        assertTrue(30 * 30 * Math.tan(fast) / 2.4 <= 0.8 * 1.1 * 9.81 + 1e-9);
    }

    @Test
    void sandRollsHarderThanPavementAndIceHoldsLess() {
        double pavementDrag = Math.abs(WheelMath.tire(10, 0, N, 1.1, 0.02, 1, 0, 0, DT).longitudinal());
        double sandDrag = Math.abs(WheelMath.tire(10, 0, N, 1.1 * 0.45, 0.16, 1, 0, 0, DT).longitudinal());
        assertTrue(sandDrag > 5 * pavementDrag);
        // On ice (grip ~0.1 of pavement) the same sideways slide cannot be corrected: it reports more slip.
        double road = WheelMath.tire(0, 0.5, N, 1.1, 1, 0, 0, DT).slipSpeed();
        double ice = WheelMath.tire(0, 0.5, N, 1.1 * 0.1, 1, 0, 0, DT).slipSpeed();
        assertTrue(ice > road);
    }

    @Test
    void aParkedBrakeWithGainHoldsHarderAgainstCreep() {
        double normal = WheelMath.tire(0.1, 0, N, 1.1, 0.02, 1, 0, 3000, 1.0, DT).longitudinal();
        double held = WheelMath.tire(0.1, 0, N, 1.1, 0.02, 1, 0, 3000, 6.0, DT).longitudinal();
        assertTrue(held < normal, "more braking force against forward creep");
        assertTrue(held >= -3000.0, "never more than the brake force");
    }

    @Test
    void antiRollPushesUpTheMoreCompressedWheelAndOppositeOnItsPartner() {
        double a = WheelMath.antiRollForce(0.2, 0.1, 10_000.0);
        double b = WheelMath.antiRollForce(0.1, 0.2, 10_000.0);
        assertEquals(1_000.0, a, 1e-9);
        assertEquals(-a, b, 1e-9);
    }

    @Test
    void antiRollIsZeroWhenLevelAndIgnoresAirborneNegatives() {
        assertEquals(0.0, WheelMath.antiRollForce(0.15, 0.15, 10_000.0), 0);
        assertEquals(WheelMath.antiRollForce(0.1, 0.0, 5.0), WheelMath.antiRollForce(0.1, -3.0, 5.0), 0);
    }

    @Test
    void antiRollTransferMatchesTheDesiredForceWhenTheGiverCanAffordIt() {
        double suspA = WheelMath.suspensionForce(0.2, 0, 19_620.0, 3_400.0, 40_000.0);
        double suspB = WheelMath.suspensionForce(0.1, 0, 19_620.0, 3_400.0, 40_000.0);
        double transfer = WheelMath.antiRollTransfer(suspA, 0.2, suspB, 0.1, 10_000.0);
        assertEquals(1_000.0, transfer, 1e-9);
    }

    @Test
    void antiRollTransferCapsAtTheGiversOwnSuspensionForceSoNeitherWheelGoesNegative() {
        // Wheel A is barely touching (little suspension force); its heavily compressed partner B
        // wants a much bigger transfer than A has to give. The transfer must cap at A's own force,
        // not at the raw (unaffordable) desired amount - this is the actual skip-path fix: applying
        // the capped transfer to A leaves it at exactly zero (correctly grip-less, since it has
        // nothing left), never negative, and B must receive that same capped amount, not the full
        // uncapped one, or the axle gains load from nowhere.
        double suspA = WheelMath.suspensionForce(0.01, 0, 19_620.0, 3_400.0, 40_000.0);
        double suspB = WheelMath.suspensionForce(0.3, 0, 19_620.0, 3_400.0, 40_000.0);
        double transferToA = WheelMath.antiRollTransfer(suspA, 0.01, suspB, 0.3, 19_620.0);
        double transferToB = WheelMath.antiRollTransfer(suspB, 0.3, suspA, 0.01, 19_620.0);
        assertEquals(-suspA, transferToA, 1e-9, "A can give up at most its own suspension force");
        assertEquals(suspA, transferToB, 1e-9, "B must receive exactly what A gave, not the raw desired amount");
        assertEquals(0.0, suspA + transferToA, 1e-9, "A's resulting load is exactly zero, never negative");
        assertEquals(0.0, transferToA + transferToB, 1e-9, "the pair is antisymmetric: nothing is created or lost");
    }

    @Test
    void antiRollTransferIsZeroWhenTheGiverHasNothingToGive() {
        // Partner is airborne (ray missed: 0 compression, 0 suspension force). Nothing can be
        // transferred away from a wheel that already carries no load.
        double transfer = WheelMath.antiRollTransfer(2_000.0, 0.2, 0.0, 0.0, 19_620.0);
        assertEquals(0.0, transfer, 1e-9);
    }
}
