package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for the muscle car's {@link VehicleSpec#MUSCLE} (MINECRAFT-165). The "nearly pops a
 * wheelie on a hard launch" character is a tuning/feel target, not a hard physics assertion (no Sable engine
 * runs in these unit tests), so these checks are about the spec's own ingredients for that feel - heavier,
 * harder-launching and shorter-coupled than every existing car - rather than a simulated outcome.
 */
class VehicleSpecMuscleTest {

    @Test
    void muscleIsItsOwnDistinctSpec() {
        assertNotEquals(VehicleSpec.CAR, VehicleSpec.MUSCLE);
        assertNotEquals(VehicleSpec.TRUCK, VehicleSpec.MUSCLE);
        assertNotEquals(VehicleSpec.TROPHY, VehicleSpec.MUSCLE);
        assertNotEquals(VehicleSpec.DRIFT, VehicleSpec.MUSCLE);
    }

    @Test
    void muscleIsHeavierThanTheCar() {
        assertTrue(VehicleSpec.MUSCLE.massKg() > VehicleSpec.CAR.massKg(), "muscle car should be heavier than the car");
    }

    @Test
    void muscleOutGunsEveryExistingVehicle() {
        // forceScale is a direct multiplier on SableCarBody's per-wheel drive force, so this is the hard-launch
        // "power" knob: the muscle car should launch harder than every vehicle on the roster so far.
        assertTrue(VehicleSpec.MUSCLE.forceScale() > VehicleSpec.CAR.forceScale(), "muscle car should out-accelerate the car");
        assertTrue(VehicleSpec.MUSCLE.forceScale() > VehicleSpec.TRUCK.forceScale(), "muscle car should out-accelerate the truck");
        assertTrue(VehicleSpec.MUSCLE.forceScale() > VehicleSpec.TROPHY.forceScale(), "muscle car should out-accelerate the trophy truck");
        assertTrue(VehicleSpec.MUSCLE.forceScale() > VehicleSpec.DRIFT.forceScale(), "muscle car should out-accelerate the drift car");
    }

    @Test
    void musclePowerToWeightBeatsEveryExistingVehicle() {
        // forceScale/massKg is a proxy for launch acceleration (force is forceScale * a shared per-wheel
        // constant, so mass is the only other variable): the muscle car should lead the roster on this
        // ratio too, not just on raw forceScale, so a heavier car couldn't quietly cancel the power claim.
        double musclePowerToWeight = VehicleSpec.MUSCLE.forceScale() / VehicleSpec.MUSCLE.massKg();
        assertTrue(musclePowerToWeight > VehicleSpec.CAR.forceScale() / VehicleSpec.CAR.massKg());
        assertTrue(musclePowerToWeight > VehicleSpec.TRUCK.forceScale() / VehicleSpec.TRUCK.massKg());
        assertTrue(musclePowerToWeight > VehicleSpec.TROPHY.forceScale() / VehicleSpec.TROPHY.massKg());
        assertTrue(musclePowerToWeight > VehicleSpec.DRIFT.forceScale() / VehicleSpec.DRIFT.massKg());
    }

    @Test
    void muscleIsShorter_coupledAndTallerRidingThanTheCar() {
        // Both ingredients of front-lift leverage: less wheelbase to resist a nose-up pitching moment, and
        // more ride height, so the centre of mass sits higher above the wheels for the same moment arm.
        assertTrue(VehicleSpec.MUSCLE.wheelbase() < VehicleSpec.CAR.wheelbase(), "muscle car should have a shorter wheelbase than the car");
        assertTrue(VehicleSpec.MUSCLE.rideHeight() > VehicleSpec.CAR.rideHeight(), "muscle car should ride taller than the car");
    }

    @Test
    void muscleEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.MUSCLE.halfX()), VehicleSpec.MUSCLE.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.MUSCLE.halfY()), VehicleSpec.MUSCLE.height(), 1e-9);
        assertTrue(VehicleSpec.MUSCLE.width() > 0 && VehicleSpec.MUSCLE.height() > 0);
        assertNotEquals(VehicleSpec.CAR.width(), VehicleSpec.MUSCLE.width());
    }

    @Test
    void muscleWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.MUSCLE.rideHeight() + VehicleSpec.MUSCLE.wheelRadius();
        assertEquals(expected, VehicleSpec.MUSCLE.wheelCentreY(), 1e-9);
    }

    @Test
    void muscleHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.MUSCLE.mounts().length);
        for (double[] mount : VehicleSpec.MUSCLE.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }

    @Test
    void muscleCarriesNoDriftTuning() {
        // This story is about straight-line launch character, not sliding - the muscle car should route
        // through the plain WheelMath tire path, exactly like CAR/TRUCK/TROPHY.
        assertTrue(VehicleSpec.MUSCLE.tireTuning().isIdentity());
    }
}
