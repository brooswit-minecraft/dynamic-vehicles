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

    /** MINECRAFT-443 review fix (comment 48829): {@link WheelMath#compression} is the exact method {@code
     * SableCarBody.tick}'s raycast loop calls to turn a wheel's own {@code VehicleSpec#restLength} (what a
     * per-car {@code rideHeight} config override actually scales) and raycast distance into a spring
     * compression -- not a parallel reimplementation of the formula. */
    @Test
    void compressionIsRestLengthMinusDistance() {
        assertEquals(0.3, WheelMath.compression(0.65, 0.35), 1e-9);
        assertEquals(-0.1, WheelMath.compression(0.65, 0.75), 1e-9);
    }

    /** The full production seam a rideHeight override reaches, end to end: {@link WheelMath#compression}
     * feeding directly into {@link WheelMath#suspensionForce} (both the exact methods {@code
     * SableCarBody.tick} calls), using the rock crawler's own real restLength/spring/damper numbers scaled
     * exactly the way {@code VehicleSpecOverrides} scales them for a halved rideHeight override -- proving
     * the scaled value still produces a sane, positive, uncapped suspension force at a representative
     * resting distance, not just that the spec field holds the right number. */
    @Test
    void aRideHeightOverridesDerivedRestLengthMakesTheActualRestingHeightMatchIt() {
        VehicleSpec stock = VehicleSpec.ROCK_CRAWLER;
        double mountY = stock.mounts()[0][1];
        double overriddenRideHeight = stock.rideHeight() / 2.0;
        double derivedRestLength = WheelMath.restLengthForRideHeight(overriddenRideHeight, mountY,
                stock.massKg(), stock.springRate(), stock.mounts().length);

        // The exact raycast distance at the REQUESTED resting height (not an approximation): a wheel
        // mounted mountY below the body centre, with the body centre sitting at overriddenRideHeight.
        double distanceAtRequestedRideHeight = overriddenRideHeight + mountY;
        double compression = WheelMath.compression(derivedRestLength, distanceAtRequestedRideHeight);
        double force = WheelMath.suspensionForce(compression, 0.0, stock.springRate(), stock.dampingRate(),
                stock.maxSpringForce());

        // Review fix 48903's whole point: this must be the car's own exact per-wheel static load, not
        // merely "some positive, uncapped force" -- proving the actual resting height really is the
        // requested one, not merely close to it or still bottomed/floating relative to it.
        double expectedStaticLoadPerWheel = stock.massKg() * WheelMath.GRAVITY / stock.mounts().length;
        assertEquals(expectedStaticLoadPerWheel, force, expectedStaticLoadPerWheel * 1e-6);
    }

    /** MINECRAFT-443 review fix (comment 48829): {@link WheelMath#handbrakeForce} is the exact method
     * {@code SableCarBody.tick}'s handbrake branch calls -- not a parallel reimplementation -- so a
     * regression that silently dropped the {@code handbrakeScale} multiplication (the review's own
     * example) fails this test, not just the pure parse/clamp test in {@code VehicleSpecOverridesTest}. */
    @Test
    void handbrakeForceAppliesForceScaleAndHandbrakeScale() {
        double base = 12_000.0;
        assertEquals(base, WheelMath.handbrakeForce(base, 1.0, 1.0), 1e-9, "identity scales must leave the base force unchanged");
        assertEquals(base * 2.0 * 0.5, WheelMath.handbrakeForce(base, 2.0, 0.5), 1e-9);
    }

    /** MINECRAFT-443 review fix (comment 48913): {@link VehicleSpecLimits}' car-agnostic 0.1 m floor is
     * not usable for every car -- it must be rejected per car by {@link WheelMath#isUsableRideHeight},
     * using CAR's own real geometry (halfY 0.5, mount depth 0.4) as a concrete example. */
    @Test
    void lowRideHeightsThatIntersectTheChassisOrSinkTheMountAreNotUsable() {
        double halfY = VehicleSpec.CAR.halfY();
        double mountY = VehicleSpec.CAR.mounts()[0][1];
        assertTrue(!WheelMath.isUsableRideHeight(0.1, halfY, mountY),
                "0.1 m is below CAR's own halfY (0.5) and mount depth (0.4) -- must not be usable");
        assertTrue(!WheelMath.isUsableRideHeight(halfY, halfY, mountY),
                "exactly halfY leaves the chassis bottom AT the ground, not clear of it");
        assertTrue(WheelMath.isUsableRideHeight(0.9, halfY, mountY), "CAR's own stock rideHeight must be usable");
    }

    /** Every existing car's own per-car rideHeight floor (`max(halfY, |mountY|)`), pinned so
     * docs/per-car-config.md's documented per-car floor table cannot silently drift from the real
     * geometry it is computed from. */
    @Test
    void everyExistingCarsRideHeightFloorMatchesItsDocumentedValue() {
        assertRideHeightFloor("car", VehicleSpec.CAR, 0.5);
        assertRideHeightFloor("truck", VehicleSpec.TRUCK, 0.65);
        assertRideHeightFloor("trophy_truck", VehicleSpec.TROPHY, 0.5);
        assertRideHeightFloor("drift_car", VehicleSpec.DRIFT, 0.42);
        assertRideHeightFloor("muscle_car", VehicleSpec.MUSCLE, 0.5);
        assertRideHeightFloor("rock_crawler", VehicleSpec.ROCK_CRAWLER, 0.6);
        assertRideHeightFloor("monster_truck", VehicleSpec.MONSTER_TRUCK, 0.75);
        assertRideHeightFloor("indy_car", VehicleSpec.INDY, 0.35);
        assertRideHeightFloor("bus", VehicleSpec.BUS, 0.9);
        assertRideHeightFloor("cargo_truck", VehicleSpec.CARGO_TRUCK, 0.75);
    }

    private static void assertRideHeightFloor(String id, VehicleSpec spec, double documentedFloor) {
        double floor = Math.max(spec.halfY(), -spec.mounts()[0][1]);
        assertEquals(documentedFloor, floor, 1e-9, id + "'s rideHeight floor (docs/per-car-config.md)");
        assertTrue(!WheelMath.isUsableRideHeight(floor, spec.halfY(), spec.mounts()[0][1]),
                id + ": exactly at the floor must not be usable (strictly greater is required)");
        assertTrue(WheelMath.isUsableRideHeight(floor + 0.01, spec.halfY(), spec.mounts()[0][1]),
                id + ": just above the floor must be usable");
        assertTrue(WheelMath.isUsableRideHeight(spec.rideHeight(), spec.halfY(), spec.mounts()[0][1]),
                id + ": its own stock rideHeight must always be usable");
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
        assertEquals(0.0, tire.commandLongitudinal(), 0);
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
    void frictionLimitReportsTheCircleThisCallActuallyUsed() {
        // Review, round 6: SableCarBody's own wheel-spin-recovery reaction derives the circle's remaining
        // HEADROOM this step from frictionLimit() and the already-delivered longitudinal/lateral force --
        // it must be exactly mu*normalForce while gripping (not 0, and not some other value), or that
        // headroom computation would be silently wrong.
        WheelMath.Tire tire = WheelMath.tire(0, 0, N, 1.1, 1, 1500, 0, DT);
        assertEquals(1.1 * N, tire.frictionLimit(), 1e-9);
    }

    @Test
    void frictionLimitIsZeroWhenNotGripping() {
        WheelMath.Tire airborne = WheelMath.tire(0, 0, 0, 1.1, 1, 1500, 0, DT);
        assertEquals(0.0, airborne.frictionLimit(), 1e-9);
        WheelMath.Tire noGrip = WheelMath.tire(0, 0, N, 0, 1, 1500, 0, DT);
        assertEquals(0.0, noGrip.frictionLimit(), 1e-9);
    }

    @Test
    void frictionLimitTracksTheOverflowingCaseToo() {
        // Even once the circle is saturated (longitudinal+lateral clamped below the raw demand),
        // frictionLimit() is still the circle itself, not the (necessarily smaller) demand.
        WheelMath.Tire tire = WheelMath.tire(0, 0, N, 1.1, 1, 6000, 0, DT);
        assertEquals(1.1 * N, tire.frictionLimit(), 1e-9);
        assertEquals(tire.frictionLimit(), Math.hypot(tire.longitudinal(), tire.lateral()), 1e-6,
                "fully saturated: the circle's headroom (frictionLimit - hypot(...)) must read as exactly 0");
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
    void deformableSurfacesAddRollingDragOnTopOfTheirOwnRollingResistance() {
        double pavement = WheelMath.rollingCoefficient(0.02, 0.0, 1.0);
        assertEquals(0.02, pavement, 1e-9, "zero deformability must not change the surface's own coefficient");
        double sand = WheelMath.rollingCoefficient(0.16, 0.95, 1.0);
        assertTrue(sand > 0.16, "a deformable surface must roll harder than its own rollingResistance alone");
        double softerSand = WheelMath.rollingCoefficient(0.16, 0.5, 1.0);
        assertTrue(sand > softerSand, "more deformability must add more drag");
        double scaled = WheelMath.rollingCoefficient(0.16, 0.5, 2.0);
        assertEquals(2.0 * softerSand, scaled, 1e-9, "the vehicle's rollingScale still applies to the whole coefficient");
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

    /** excessForce as SableCarBody.tick computes it: THIS SAME tire() call's own commandLongitudinal minus longitudinal. */
    private static double excessOf(WheelMath.Tire tire) {
        return tire.commandLongitudinal() - tire.longitudinal();
    }

    @Test
    void aGrippingWheelsSpinIsPulledExactlyToGroundSpeedNotIntegratedFromWhateverItLastWas() {
        // excessForce == 0 (no friction-circle overflow) means the wheel is gripping right now, regardless
        // of what spin it carried in from a past slip event - it must snap to ground speed, not drift.
        double spin = WheelMath.spinRate(999.0, RADIUS, 10.0, 0.0, WheelMath.WHEEL_INERTIA, DT);
        assertEquals(10.0 / RADIUS, spin, 1e-9);
        assertEquals(0.0, WheelMath.slipFromSpin(spin, RADIUS, 10.0), 1e-9);
    }

    @Test
    void driveExceedingGripSpinsTheWheelFasterThanTheGroundWheelspin() {
        // A real tire() call that overflows the friction circle while driving: demand (6000 N) exceeds
        // the grip limit (mu*N = 1.1*2943 = 3237.3 N), so commandLongitudinal (6000) and longitudinal
        // (clamped to the limit) differ - the excess spins the wheel up, away from ground speed.
        WheelMath.Tire tire = WheelMath.tire(10, 0, N, 1.1, 1, 6000, 0, DT);
        double excessForce = excessOf(tire);
        assertTrue(excessForce > 0.0, "driving demand must exceed what the friction circle delivered");
        double spin = WheelMath.spinRate(10.0 / RADIUS, RADIUS, 10.0, excessForce, WheelMath.WHEEL_INERTIA, DT);
        assertTrue(WheelMath.slipFromSpin(spin, RADIUS, 10.0) > 0.0,
                "wheelspin: the wheel's own surface speed outruns the ground");
    }

    @Test
    void brakeExceedingGripLocksTheWheelSlowerThanTheGroundLockUp() {
        // A real tire() call that overflows the friction circle while braking: a brake force (5000 N) that
        // the low-grip surface (mu=0.3) cannot fully deliver is the lock-up case.
        WheelMath.Tire tire = WheelMath.tire(15, 0, N, 0.3, 1, 0, 5000, DT);
        double excessForce = excessOf(tire);
        assertTrue(excessForce < 0.0, "braking demand must exceed what the friction circle delivered");
        double spin = WheelMath.spinRate(15.0 / RADIUS, RADIUS, 15.0, excessForce, WheelMath.WHEEL_INERTIA, DT);
        assertTrue(WheelMath.slipFromSpin(spin, RADIUS, 15.0) < 0.0,
                "lock-up: the wheel's own surface speed falls behind the ground");
    }

    /**
     * Pins the bug the first review round caught: approximating the driveline's command from drive/brake
     * alone (ignoring rolling resistance and the brake's own relaxation target) makes excessForce nonzero
     * even on a genuinely gripping wheel, so spin (and slipFromSpin) drifts away from 0 every step instead
     * of staying there. Using tire()'s own commandLongitudinal (this test's excessOf helper) must NOT drift,
     * across many repeated steps, for a gripping wheel in each of the three cases tire() distinguishes
     * internally: coasting, braking below saturation, and driving - all well under the grip limit (N=2943,
     * mu=1.1, limit=3237.3 N) so none of them ever hits the friction circle.
     */
    private static void assertGrippingWheelNeverDrifts(double vLong, double drive, double brake) {
        double spin = 999.0; // deliberately not matching ground speed, to also prove the snap-to-grip behaviour
        for (int step = 0; step < 50; step++) {
            WheelMath.Tire tire = WheelMath.tire(vLong, 0, N, 1.1, WheelMath.ROLLING_RESISTANCE, 1, drive, brake, DT);
            assertEquals(0.0, tire.slipSpeed(), 1e-9, "this scenario must stay under the friction circle");
            double excessForce = excessOf(tire);
            assertEquals(0.0, excessForce, 1e-9, "a gripping wheel's own commandLongitudinal must match longitudinal exactly");
            spin = WheelMath.spinRate(spin, RADIUS, vLong, excessForce, WheelMath.WHEEL_INERTIA, DT);
            assertEquals(0.0, WheelMath.slipFromSpin(spin, RADIUS, vLong), 1e-9,
                    "step " + step + ": a gripping wheel must report exactly 0 slip, not drift");
        }
    }

    @Test
    void aGrippingCoastingWheelNeverDriftsOverManySteps() {
        assertGrippingWheelNeverDrifts(5.0, 0.0, 0.0);
    }

    @Test
    void aGrippingBrakingWheelBelowSaturationNeverDriftsOverManySteps() {
        assertGrippingWheelNeverDrifts(5.0, 0.0, 1000.0);
    }

    @Test
    void aGrippingDrivingWheelNeverDriftsOverManySteps() {
        assertGrippingWheelNeverDrifts(5.0, 500.0, 0.0);
    }

    @Test
    void repeatedSubStepsAtTheSameTotalDtIntegrateToTheSameSpinRegardlessOfSubStepCount() {
        // The same net torque summed over N sub-steps at dt/N each must integrate to (exactly, for a
        // constant excessForce) the same total change as one step at dt, exactly like the body's own
        // velocity integration elsewhere in this file - nothing here divides by N.
        double groundSpeed = 10.0;
        double excessForce = 4900.0;
        double oneStep = WheelMath.spinRate(groundSpeed / RADIUS, RADIUS, groundSpeed, excessForce,
                WheelMath.WHEEL_INERTIA, DT);
        for (int n = 2; n <= 4; n++) {
            double subDt = DT / n;
            double spin = groundSpeed / RADIUS;
            for (int sub = 0; sub < n; sub++) {
                spin = WheelMath.spinRate(spin, RADIUS, groundSpeed, excessForce, WheelMath.WHEEL_INERTIA, subDt);
            }
            assertEquals(oneStep, spin, 1e-9, "N=" + n + " must match the N=1 integration of the same net torque");
        }
    }

    // --- MINECRAFT-450: rolling resistance through the production tire() seam, coast-down vs.
    // throttle-to-hold, no-energy-injection, no near-rest sign flip, forward/reverse symmetry,
    // finite/stable output, and sub-step total-impulse invariance -----------------------------------

    /** One wheel's share of the car (1200 kg / 4), the same effectiveMass SableCarBody.tick passes. */
    private static final double EFFECTIVE_MASS = WheelMath.EFFECTIVE_MASS;

    /** Integrates one wheel's velocity under WheelMath.tire alone (no lateral motion, no friction-circle
     * saturation expected) for {@code ticks} steps of {@code dt} seconds, coasting (drive = brake = 0). */
    private static double coastDown(double rollingCoefficient, double vLong, double dt, int ticks) {
        double v = vLong;
        for (int i = 0; i < ticks; i++) {
            WheelMath.Tire tire = WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, dt);
            v += tire.longitudinal() / EFFECTIVE_MASS * dt;
        }
        return v;
    }

    @Test
    void coastDownIsSlowerOnAHigherRollingResistanceSurface() {
        // Two "surfaces" by their rollingCoefficient, as WheelMath.rollingCoefficient would hand tire():
        // pavement-like vs. sand-like, same starting speed, same number of ticks.
        double pavementCoefficient = WheelMath.rollingCoefficient(0.02, 0.0, 1.0);
        double sandCoefficient = WheelMath.rollingCoefficient(0.16, 0.95, 1.0);
        double startSpeed = 10.0;
        double afterPavement = coastDown(pavementCoefficient, startSpeed, DT, 40);
        double afterSand = coastDown(sandCoefficient, startSpeed, DT, 40);
        assertTrue(afterSand < afterPavement,
                "coasting on the higher-rollingResistance surface must have lost more speed: pavement=" + afterPavement + " sand=" + afterSand);
        assertTrue(afterPavement < startSpeed && afterSand < startSpeed, "both surfaces must still slow the car down");
    }

    @Test
    void throttleThatHoldsSpeedOnPavementIsNotEnoughToHoldItOnSand() {
        // A drive force sized to exactly cancel pavement's own steady-state rolling force at this speed/load
        // holds pavement at (very nearly) constant speed, but the SAME drive force on the higher-resistance
        // sand surface cannot keep up, so sand still loses speed under that same throttle.
        double startSpeed = 8.0;
        double pavementCoefficient = WheelMath.rollingCoefficient(0.02, 0.0, 1.0);
        double sandCoefficient = WheelMath.rollingCoefficient(0.16, 0.95, 1.0);
        double holdDrive = pavementCoefficient * N; // cancels pavement's rolling force at any speed above the dead zone

        double v = startSpeed;
        for (int i = 0; i < 60; i++) {
            WheelMath.Tire tire = WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, pavementCoefficient, 1, holdDrive, 0, DT);
            v += tire.longitudinal() / EFFECTIVE_MASS * DT;
        }
        assertEquals(startSpeed, v, 0.05, "pavement's own cancelling throttle must hold speed almost exactly");

        double vSand = startSpeed;
        for (int i = 0; i < 60; i++) {
            WheelMath.Tire tire = WheelMath.tire(vSand, 0, N, WheelMath.BASE_FRICTION, sandCoefficient, 1, holdDrive, 0, DT);
            vSand += tire.longitudinal() / EFFECTIVE_MASS * DT;
        }
        assertTrue(vSand < startSpeed - 0.5,
                "the same throttle that holds pavement must not hold sand, which rolls harder: ended at " + vSand);
    }

    @Test
    void coastingDownNeverInjectsEnergySpeedIsMonotonicallyNonIncreasing() {
        double rollingCoefficient = WheelMath.rollingCoefficient(0.1, 0.4, 1.0);
        double v = 12.0;
        double previous = v;
        for (int i = 0; i < 260; i++) {
            WheelMath.Tire tire = WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, DT);
            v += tire.longitudinal() / EFFECTIVE_MASS * DT;
            assertTrue(Math.abs(v) <= Math.abs(previous) + 1e-9,
                    "tick " + i + ": rolling resistance alone must never speed the car up, |v| went from " + previous + " to " + v);
            previous = v;
        }
        // Once decelerated enough, the wheel settles inside (or very near) the 0.05 m/s dead zone and a
        // further step changes it no further - never "near rest" meaning exactly 0 (the dead zone's own
        // residual can be as large as one step's worth of deceleration below 0.05), but stable and small.
        assertTrue(Math.abs(v) < 0.1, "a long enough coast-down must settle near rest, not orbit some nonzero speed: v=" + v);
        double vAfterOneMore = v + WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, DT)
                .longitudinal() / EFFECTIVE_MASS * DT;
        assertEquals(v, vAfterOneMore, 1e-9, "once settled, coasting must stay settled, not keep creeping or oscillating");
    }

    @Test
    void coastingToRestNeverFlipsSignNearZeroSpeed() {
        // The dead zone below 0.05 m/s (WheelMath.tire's own Math.abs(vLong) > 0.05 guard) must stop the
        // wheel from ever being pushed past zero and out the other side by its own rolling resistance.
        double rollingCoefficient = WheelMath.rollingCoefficient(0.2, 0.9, 1.0);
        double v = 0.2; // a few sub-steps' worth above the dead zone at this dt
        boolean everNegative = false;
        for (int i = 0; i < 100; i++) {
            WheelMath.Tire tire = WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, DT);
            v += tire.longitudinal() / EFFECTIVE_MASS * DT;
            if (v < -1e-9) { // a sub-ulp rounding residual at exactly 0 is not a sign flip
                everNegative = true;
            }
        }
        assertTrue(!everNegative, "coasting to rest from a small positive speed must never cross zero into negative");
        assertEquals(0.0, WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, DT).longitudinal(), 1e-9,
                "once inside the dead zone, rolling resistance must be exactly zero, not a residual nonzero push");
    }

    @Test
    void rollingResistanceOpposesBothForwardAndReverseRollingTheSameWay() {
        double rollingCoefficient = WheelMath.rollingCoefficient(0.08, 0.3, 1.0);
        double forward = coastDown(rollingCoefficient, 10.0, DT, 1);
        double reverse = coastDown(rollingCoefficient, -10.0, DT, 1);
        assertTrue(forward < 10.0, "forward rolling must be slowed (resistance opposes +vLong)");
        assertTrue(reverse > -10.0, "reverse rolling must be slowed (resistance opposes -vLong) rather than accelerated further backward");
        assertEquals(10.0 - forward, reverse - (-10.0), 1e-9, "the same coefficient must slow forward and reverse rolling by the same amount");
    }

    @Test
    void airborneWheelsGetNoRollingTerrainDragFromTireAtAll() {
        // SableCarBody.tick never calls WheelMath.tire for a raycast-miss/zero-load wheel at all (see the
        // `force <= 0` skip and its own comment), but tire() itself also pins the all-zero contract for a
        // non-positive normalForce, so even a hypothetical call for an airborne wheel could not inject drag.
        double rollingCoefficient = WheelMath.rollingCoefficient(0.5, 1.0, 1.0); // worst-case surface
        WheelMath.Tire tire = WheelMath.tire(10.0, 0, 0.0, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, DT);
        assertEquals(0.0, tire.longitudinal(), 0);
        assertEquals(0.0, tire.slipSpeed(), 0);
    }

    @Test
    void rollingResistanceOutputStaysFiniteAcrossAWideRangeOfInputs() {
        double[] rollingResistances = {0.0, 0.02, 0.16, 0.5, 1.0};
        double[] deformabilities = {0.0, 0.3, 0.95, 1.0};
        double[] rollingScales = {0.0, 0.5, 1.0, 2.0};
        double[] speeds = {-50.0, -0.05, -0.001, 0.0, 0.001, 0.05, 50.0};
        for (double rr : rollingResistances) {
            for (double def : deformabilities) {
                for (double scale : rollingScales) {
                    double coefficient = WheelMath.rollingCoefficient(rr, def, scale);
                    assertTrue(Double.isFinite(coefficient), "rollingCoefficient produced a non-finite value");
                    for (double v : speeds) {
                        WheelMath.Tire tire = WheelMath.tire(v, 0.1, N, WheelMath.BASE_FRICTION, coefficient, 1, 0, 0, DT);
                        assertTrue(Double.isFinite(tire.longitudinal()), "longitudinal() was not finite for v=" + v);
                        assertTrue(Double.isFinite(tire.lateral()), "lateral() was not finite for v=" + v);
                        assertTrue(Double.isFinite(tire.slipSpeed()), "slipSpeed() was not finite for v=" + v);
                        assertTrue(Double.isFinite(tire.commandLongitudinal()), "commandLongitudinal() was not finite for v=" + v);
                    }
                }
            }
        }
    }

    @Test
    void subSteppingRollingResistanceDeliversTheSameTotalImpulseRegardlessOfWheelSubSteps() {
        // Mirrors SableCarBody.tick's own design comment: "Total impulse per tick stays consistent because
        // every impulse below is force * subDt, summed N times." Pick a speed window where rolling
        // resistance stays constant in sign and magnitude the whole tick (well above the dead zone, no
        // drive/brake, so wantLong is exactly -sign(vLong) * rollingCoefficient * normalForce throughout),
        // so the sum over N sub-steps must equal the single N=1 step exactly.
        double rollingCoefficient = WheelMath.rollingCoefficient(0.1, 0.5, 1.0);
        double tickDt = 0.05;
        double v = 10.0;

        double oneStepImpulse = WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, tickDt)
                .longitudinal() * tickDt;

        for (int subSteps = 2; subSteps <= 4; subSteps++) {
            double subDt = tickDt / subSteps;
            double totalImpulse = 0.0;
            for (int sub = 0; sub < subSteps; sub++) {
                // Velocity barely moves over one tick of rolling resistance alone at this mass/coefficient,
                // so it stays comfortably above the dead zone for every sub-step - the regime the production
                // comment is about (a nearly-constant force spread over more, smaller impulses).
                double force = WheelMath.tire(v, 0, N, WheelMath.BASE_FRICTION, rollingCoefficient, 1, 0, 0, subDt).longitudinal();
                totalImpulse += force * subDt;
                v += force / EFFECTIVE_MASS * subDt;
            }
            assertEquals(oneStepImpulse, totalImpulse, 1e-6,
                    "subSteps=" + subSteps + " must deliver the same total rolling-resistance impulse as one un-sub-stepped step");
            v = 10.0; // reset for the next subSteps count
        }
    }

    /** MINECRAFT-444 review round 3: a wheel off the ground is turned by the whole drivetrain command (no
     * tire reaction), by the same torque balance spinRate applies to the undelivered part of a command. */
    @Test
    void freeSpinRateTurnsAnAirborneWheelByTheWholeDriveCommand() {
        double radius = 0.35;
        double inertia = WheelMath.WHEEL_INERTIA;
        double dt = 0.0125;
        assertEquals(5.0 + 1000.0 * radius / inertia * dt, WheelMath.freeSpinRate(5.0, radius, 1000.0, 0.0, inertia, dt), 1e-12);
        assertEquals(5.0 - 1000.0 * radius / inertia * dt, WheelMath.freeSpinRate(5.0, radius, -1000.0, 0.0, inertia, dt), 1e-12,
                "engine braking slows a free wheel too");
        assertEquals(5.0, WheelMath.freeSpinRate(5.0, radius, 0.0, 0.0, inertia, dt), 0.0, "no torque, no change");
    }

    /** ...and a brake, which replaces the drive exactly as it does in tire(), only ever slows it toward a
     * stop -- it can never wind the wheel up the other way, however long it is held. */
    @Test
    void freeSpinRateBrakesAWheelToAStopButNeverPastIt() {
        double radius = 0.35;
        double inertia = WheelMath.WHEEL_INERTIA;
        double dt = 0.0125;
        double step = 3000.0 * radius / inertia * dt;
        assertEquals(40.0 - step, WheelMath.freeSpinRate(40.0, radius, 2000.0, 3000.0, inertia, dt), 1e-12,
                "a braked wheel slows by the brake torque and ignores the drive, like tire() does");
        assertEquals(-40.0 + step, WheelMath.freeSpinRate(-40.0, radius, 0.0, 3000.0, inertia, dt), 1e-12);
        double spin = 3.0;
        for (int i = 0; i < 100; i++) {
            spin = WheelMath.freeSpinRate(spin, radius, 0.0, 3000.0, inertia, dt);
            assertTrue(spin >= 0.0, "a brake must never reverse a wheel: " + spin);
        }
        assertEquals(0.0, spin, 0.0, "it comes to a stop and stays there");
    }
}
