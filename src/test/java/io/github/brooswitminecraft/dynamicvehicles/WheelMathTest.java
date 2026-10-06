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
}
