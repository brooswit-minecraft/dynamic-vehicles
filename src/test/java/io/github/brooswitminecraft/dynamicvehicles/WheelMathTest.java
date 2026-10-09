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
    void aTireWithNoLoadReturnsAllZeros() {
        // Pins the contract SableCarBody.tick's `force <= 0` skip relies on: a non-positive normalForce
        // must zero out every component, not just longitudinal() (asserted above for the no-grip case),
        // so skipping such a wheel early is behaviour-equivalent to letting it through this call.
        WheelMath.Tire tire = WheelMath.tire(5, 5, 0, 1.1, 1, 1000, 0, DT);
        assertEquals(0.0, tire.longitudinal(), 0);
        assertEquals(0.0, tire.lateral(), 0);
        assertEquals(0.0, tire.slipSpeed(), 0);
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
    void gripLimitScalesLinearlyWithVerticalLoad() {
        // MUST-HAVE: grip (the friction limit mu*normalForce) scales with the wheel's own vertical load.
        // Demand (6000 N) must overflow the friction circle at every load compared here (mu*load < 6000,
        // i.e. load < ~5454 N for mu=1.1) or the wheel would simply grip and deliver the full demand
        // instead, which would hide whether grip itself is tracking load. quarterLoad/halfLoad/fullLoad are
        // all well under that ceiling.
        double quarterLoad = WheelMath.tire(0, 0, N / 4, 1.1, 1, 6000, 0, DT).longitudinal();
        double halfLoad = WheelMath.tire(0, 0, N / 2, 1.1, 1, 6000, 0, DT).longitudinal();
        double fullLoad = WheelMath.tire(0, 0, N, 1.1, 1, 6000, 0, DT).longitudinal();
        assertEquals(1.1 * N / 4, quarterLoad, 1e-9);
        assertEquals(1.1 * N / 2, halfLoad, 1e-9);
        assertEquals(1.1 * N, fullLoad, 1e-9);
        assertEquals(2.0, halfLoad / quarterLoad, 1e-9, "doubling load must double the grip limit, not clamp it");
        assertEquals(2.0, fullLoad / halfLoad, 1e-9, "doubling load must double the grip limit, not clamp it");
    }

    @Test
    void gripFallsContinuouslyToZeroAsLoadApproachesZeroWithNoClampingAroundIt() {
        // The MINECRAFT-71 lesson, applied to tire() itself rather than anti-roll transfer: a lightly
        // loaded wheel's grip must shrink smoothly to 0, never hold at some non-zero floor and never jump
        // discontinuously - it is demand (6000 N) overflowing a shrinking friction circle the whole way.
        double[] loads = {N, N / 10, N / 100, N / 1000};
        double previous = Double.POSITIVE_INFINITY;
        for (double load : loads) {
            double delivered = WheelMath.tire(0, 0, load, 1.1, 1, 6000, 0, DT).longitudinal();
            assertEquals(1.1 * load, delivered, 1e-9);
            assertTrue(delivered < previous, "grip must keep shrinking as load shrinks");
            previous = delivered;
        }
        // A vanishingly small load gives a vanishingly small grip (still mu*load, no floor)...
        assertEquals(1.1 * N / 1000, previous, 1e-9);
        // ...and at (and below) exactly zero load, the all-zero contract takes over discontinuously only at
        // the single point load == 0, not before it.
        assertEquals(0.0, WheelMath.tire(0, 0, 0, 1.1, 1, 6000, 0, DT).longitudinal(), 0);
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

    // --- Per-wheel spin state (WheelMath.spinRate / slipFromSpin) ---

    private static final double RADIUS = 0.35;

    @Test
    void aGrippingWheelsSpinIsPulledExactlyToGroundSpeedNotIntegratedFromWhateverItLastWas() {
        // commandForce == reactionForce (the tire delivered exactly what the driveline asked for: no
        // friction-circle overflow) means the wheel is gripping right now, regardless of what spin it
        // carried in from a past slip event - it must snap to ground speed, not drift from the stale value.
        double spin = WheelMath.spinRate(999.0, RADIUS, 10.0, 1500.0, 1500.0, WheelMath.WHEEL_INERTIA, DT);
        assertEquals(10.0 / RADIUS, spin, 1e-9);
        assertEquals(0.0, WheelMath.slipFromSpin(spin, RADIUS, 10.0), 1e-9);
    }

    @Test
    void driveExceedingGripSpinsTheWheelFasterThanTheGroundWheelspin() {
        // The tire could only deliver 1100 N of the 6000 N commanded (friction-circle overflow): the
        // 4900 N excess the ground could not absorb spins the wheel up, away from ground speed.
        double spin = WheelMath.spinRate(10.0 / RADIUS, RADIUS, 10.0, 6000.0, 1100.0, WheelMath.WHEEL_INERTIA, DT);
        double slip = WheelMath.slipFromSpin(spin, RADIUS, 10.0);
        assertTrue(slip > 0.0, "wheelspin: the wheel's own surface speed outruns the ground");
        double expectedSpin = 10.0 / RADIUS + (6000.0 - 1100.0) * RADIUS / WheelMath.WHEEL_INERTIA * DT;
        assertEquals(expectedSpin, spin, 1e-9);
    }

    @Test
    void brakeExceedingGripLocksTheWheelSlowerThanTheGroundLockUp() {
        // Commanding -3000 N of brake but the tire only delivering -1100 N (friction-circle overflow in
        // the braking direction) is the lock-up case: the wheel falls behind ground speed, slip negative.
        double spin = WheelMath.spinRate(10.0 / RADIUS, RADIUS, 10.0, -3000.0, -1100.0, WheelMath.WHEEL_INERTIA, DT);
        double slip = WheelMath.slipFromSpin(spin, RADIUS, 10.0);
        assertTrue(slip < 0.0, "lock-up: the wheel's own surface speed falls behind the ground");
    }

    @Test
    void aZeroLoadWheelSpinsFreelyUnderAnUnopposedCommandSinceGripIsZeroNotAFloor() {
        // Ties MUST-HAVE 1 (spin state) to MUST-HAVE 2 (zero load = zero grip): WheelMath.tire returns an
        // all-zero Tire at non-positive normal force, so reactionForce is exactly 0 here, exactly like a
        // real airborne wheel's tire would deliver nothing back no matter what the driveline asks for -
        // the full, unopposed command spins the wheel, not some reduced or clamped rate.
        WheelMath.Tire airborne = WheelMath.tire(10, 0, 0.0, 1.1, 1, 1500, 0, DT);
        assertEquals(0.0, airborne.longitudinal(), 0);
        double spin = WheelMath.spinRate(10.0 / RADIUS, RADIUS, 10.0, 1500.0, airborne.longitudinal(),
                WheelMath.WHEEL_INERTIA, DT);
        double expectedSpin = 10.0 / RADIUS + 1500.0 * RADIUS / WheelMath.WHEEL_INERTIA * DT;
        assertEquals(expectedSpin, spin, 1e-9);
        assertTrue(WheelMath.slipFromSpin(spin, RADIUS, 10.0) > 0.0, "an unopposed command spins it up, not in place");
    }

    @Test
    void repeatedSubStepsAtTheSameTotalDtConvergeTowardTheSameSlipRegardlessOfSubStepCount() {
        // Mirrors the reported-slip N-invariance requirement, but for the NEW state-based slip: summing
        // the same net torque over N sub-steps at dt/N each integrates to (approximately) the same total
        // change as one step at dt, exactly like the body's own velocity integration elsewhere in this
        // file - unlike the OLD demand-vs-limit proxy this state replaces conceptually, nothing here
        // divides by N.
        double groundSpeed = 10.0;
        double commandForce = 6000.0;
        double reactionForce = 1100.0;
        double oneStep = WheelMath.spinRate(groundSpeed / RADIUS, RADIUS, groundSpeed, commandForce, reactionForce,
                WheelMath.WHEEL_INERTIA, DT);
        for (int n = 2; n <= 4; n++) {
            double subDt = DT / n;
            double spin = groundSpeed / RADIUS;
            for (int sub = 0; sub < n; sub++) {
                spin = WheelMath.spinRate(spin, RADIUS, groundSpeed, commandForce, reactionForce,
                        WheelMath.WHEEL_INERTIA, subDt);
            }
            assertEquals(oneStep, spin, 1e-9, "N=" + n + " must match the N=1 integration of the same net torque");
        }
    }
}
