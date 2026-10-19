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

    @Test
    void everyExistingVehicleHasNoWingDownforceAndTheOldGlobalDragValue() {
        for (VehicleSpec spec : ROSTER) {
            assertEquals(0.0, spec.frontWingDownforceCoeff(), 1e-9, "no vehicle has a wing yet");
            assertEquals(0.0, spec.rearWingDownforceCoeff(), 1e-9, "no vehicle has a wing yet");
            // 0.4 N per (m/s)^2: byte-for-byte the old hardcoded SableCarBody#AIR_DRAG constant every
            // vehicle shared before this field existed.
            assertEquals(0.4, spec.wingDragCoeff(), 1e-9);
        }
    }

    @Test
    void wingDownforceIsZeroAtZeroSpeedRegardlessOfCoefficient() {
        assertEquals(0.0, VehicleSpec.wingDownforceNewtons(2.5, 0.0), 1e-9);
    }

    @Test
    void wingDownforceIsZeroWhenTheCoefficientIsZeroRegardlessOfSpeed() {
        assertEquals(0.0, VehicleSpec.wingDownforceNewtons(0.0, 40.0), 1e-9);
    }

    @Test
    void wingDownforceGrowsWithTheSquareOfSpeed() {
        double at10 = VehicleSpec.wingDownforceNewtons(1.0, 10.0);
        double at20 = VehicleSpec.wingDownforceNewtons(1.0, 20.0);
        double at40 = VehicleSpec.wingDownforceNewtons(1.0, 40.0);
        assertEquals(100.0, at10, 1e-9);
        assertEquals(400.0, at20, 1e-9);
        assertEquals(1600.0, at40, 1e-9);
        // Doubling speed quadruples downforce (v^2), not merely doubles it.
        assertEquals(4.0 * at10, at20, 1e-9);
        assertEquals(4.0 * at20, at40, 1e-9);
    }

    @Test
    void wingDownforceDoesNotDependOnTravelDirection() {
        double forward = VehicleSpec.wingDownforceNewtons(1.3, 25.0);
        double reverse = VehicleSpec.wingDownforceNewtons(1.3, -25.0);
        assertEquals(forward, reverse, 1e-9, "a wing's downforce should not flip or vanish in reverse");
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
                3.0, 1.0, 0.6);

        assertEquals(3.0, winged.frontWingDownforceCoeff());
        assertEquals(1.0, winged.rearWingDownforceCoeff());
        assertEquals(0.6, winged.wingDragCoeff());
        // Front-heavier than rear: a plausible "front splitter, no rear wing" configuration.
        assertTrue(winged.frontWingDownforceCoeff() > winged.rearWingDownforceCoeff());
    }
}
