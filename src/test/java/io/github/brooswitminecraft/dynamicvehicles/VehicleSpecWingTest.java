package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for wing downforce/drag (MINECRAFT-446): {@link VehicleSpec#wingDownforceNewtons}
 * itself, and that every existing vehicle on the roster keeps the exact pre-this-ticket global drag
 * behaviour (0.4 N per (m/s)^2, no genuine wing downforce) until an operator opts a car into a per-car
 * wing config. {@code SableCarBody}'s own application of these fields in the real rigid-body path needs a
 * real Sable engine and is covered by {@code WingAeroGameTests} instead.
 */
class VehicleSpecWingTest {

    private static final java.util.List<VehicleSpec> ROSTER = java.util.List.of(
            VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT, VehicleSpec.MUSCLE,
            VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK, VehicleSpec.INDY, VehicleSpec.BUS,
            VehicleSpec.CARGO_TRUCK);

    /** Every assertion here is on exact arithmetic, so this only absorbs double representation error. */
    private static final double TOLERANCE = 1e-9;
    /** The identity value of both downforce coefficients: no wing at all. */
    private static final double NO_WING = 0.0;
    /** A unit coefficient, so {@code wingDownforceNewtons}' output IS the speed squared and the
     * speed-squared relationship is readable directly off the expected values below. */
    private static final double UNIT_COEFF = 1.0;
    /** Arbitrary nonzero coefficients, for the cases where the exact value is irrelevant to the claim. */
    private static final double SOME_COEFF = 2.5;
    private static final double ANOTHER_COEFF = 1.3;
    /** A speed at and around this car's own working range (the roster's top speed is INDY's 48 m/s). */
    private static final double SOME_SPEED = 25.0;
    private static final double TOP_END_SPEED = 40.0;
    private static final double STANDSTILL = 0.0;

    /** One plausible "big front splitter, small rear wing, and the extra drag a wing like that brings"
     * setup, for the constructor round-trip below: front deliberately above rear, and a drag coefficient
     * above the roster's own stock {@link VehicleSpec#LEGACY_AIR_DRAG}. */
    private static final double FRONT_HEAVY_FRONT_COEFF = 3.0;
    private static final double FRONT_HEAVY_REAR_COEFF = 1.0;
    private static final double FRONT_HEAVY_DRAG_COEFF = 0.6;

    @Test
    void everyExistingVehicleHasNoWingDownforceAndTheOldGlobalDragValue() {
        for (VehicleSpec spec : ROSTER) {
            assertEquals(NO_WING, spec.frontWingDownforceCoeff(), TOLERANCE, "no vehicle has a wing yet");
            assertEquals(NO_WING, spec.rearWingDownforceCoeff(), TOLERANCE, "no vehicle has a wing yet");
            // Byte-for-byte the old hardcoded SableCarBody#AIR_DRAG constant every vehicle shared before
            // this field existed -- asserted against that constant itself, not a restated number, so this
            // cannot drift away from it. (The DOCUMENTED value is pinned separately, as a literal, by
            // PerCarConfigDocsDefaultsTest -- that one must not follow the constant.)
            assertEquals(VehicleSpec.LEGACY_AIR_DRAG, spec.wingDragCoeff(), TOLERANCE);
        }
    }

    @Test
    void wingDownforceIsZeroAtZeroSpeedRegardlessOfCoefficient() {
        assertEquals(0.0, VehicleSpec.wingDownforceNewtons(SOME_COEFF, STANDSTILL), TOLERANCE);
    }

    @Test
    void wingDownforceIsZeroWhenTheCoefficientIsZeroRegardlessOfSpeed() {
        assertEquals(0.0, VehicleSpec.wingDownforceNewtons(NO_WING, TOP_END_SPEED), TOLERANCE);
    }

    @Test
    void wingDownforceGrowsWithTheSquareOfSpeed() {
        // Deliberately literal: at UNIT_COEFF these expected values ARE speed squared (10^2, 20^2, 40^2),
        // which is the specification under test -- naming them would hide the very relationship asserted.
        double at10 = VehicleSpec.wingDownforceNewtons(UNIT_COEFF, 10.0);
        double at20 = VehicleSpec.wingDownforceNewtons(UNIT_COEFF, 20.0);
        double at40 = VehicleSpec.wingDownforceNewtons(UNIT_COEFF, 40.0);
        assertEquals(100.0, at10, TOLERANCE);
        assertEquals(400.0, at20, TOLERANCE);
        assertEquals(1600.0, at40, TOLERANCE);
        // Doubling speed quadruples downforce (v^2), not merely doubles it.
        assertEquals(4.0 * at10, at20, TOLERANCE);
        assertEquals(4.0 * at20, at40, TOLERANCE);
    }

    @Test
    void wingDownforceDoesNotDependOnTravelDirection() {
        double forward = VehicleSpec.wingDownforceNewtons(ANOTHER_COEFF, SOME_SPEED);
        double reverse = VehicleSpec.wingDownforceNewtons(ANOTHER_COEFF, -SOME_SPEED);
        assertEquals(forward, reverse, TOLERANCE, "a wing's downforce should not flip or vanish in reverse");
        assertTrue(forward > 0.0);
    }

    @Test
    void aCarWithAFrontHeavyWingConfigCanBeBuiltThroughTheCanonicalConstructor() {
        VehicleSpec winged = new VehicleSpec(
                VehicleSpec.CAR.halfX(), VehicleSpec.CAR.halfY(), VehicleSpec.CAR.halfZ(),
                VehicleSpec.CAR.mounts(), VehicleSpec.CAR.wheelRadius(), VehicleSpec.CAR.wheelWidth(),
                VehicleSpec.CAR.rideHeight(), VehicleSpec.CAR.restLength(),
                VehicleSpec.CAR.massKg(), VehicleSpec.CAR.springRate(), VehicleSpec.CAR.dampingRate(), VehicleSpec.CAR.maxSpringForce(),
                VehicleSpec.CAR.wheelbase(), VehicleSpec.CAR.maxSpeed(),
                VehicleSpec.CAR.forceScale(), VehicleSpec.CAR.enginePitch(),
                VehicleSpec.CAR.seatY(), VehicleSpec.CAR.seatZ(),
                VehicleSpec.CAR.looseGrip(), VehicleSpec.CAR.rollingScale(),
                VehicleSpec.CAR.tireTuning(), VehicleSpec.CAR.downforceGripPerSpeed(), VehicleSpec.CAR.antiRollScale(),
                VehicleSpec.CAR.seats(), VehicleSpec.CAR.vehicleTuning(), VehicleSpec.CAR.handbrakeScale(),
                FRONT_HEAVY_FRONT_COEFF, FRONT_HEAVY_REAR_COEFF, FRONT_HEAVY_DRAG_COEFF);

        assertEquals(FRONT_HEAVY_FRONT_COEFF, winged.frontWingDownforceCoeff());
        assertEquals(FRONT_HEAVY_REAR_COEFF, winged.rearWingDownforceCoeff());
        assertEquals(FRONT_HEAVY_DRAG_COEFF, winged.wingDragCoeff());
        // Front-heavier than rear: a plausible "front splitter, no rear wing" configuration.
        assertTrue(winged.frontWingDownforceCoeff() > winged.rearWingDownforceCoeff());
    }
}
