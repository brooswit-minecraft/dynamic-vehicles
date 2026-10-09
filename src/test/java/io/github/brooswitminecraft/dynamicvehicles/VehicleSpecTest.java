package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function checks for the drift car's {@link VehicleSpec#DRIFT} (MINECRAFT-143). */
class VehicleSpecTest {

    @Test
    void driftIsItsOwnDistinctSpec() {
        assertNotEquals(VehicleSpec.CAR, VehicleSpec.DRIFT);
        assertNotEquals(VehicleSpec.TRUCK, VehicleSpec.DRIFT);
        assertNotEquals(VehicleSpec.TROPHY, VehicleSpec.DRIFT);
    }

    @Test
    void driftIsLowerAndLighterThanTheCar() {
        assertTrue(VehicleSpec.DRIFT.rideHeight() < VehicleSpec.CAR.rideHeight(), "drift car should sit lower than the car");
        assertTrue(VehicleSpec.DRIFT.massKg() < VehicleSpec.CAR.massKg(), "drift car should be lighter than the car");
    }

    @Test
    void driftEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.DRIFT.halfX()), VehicleSpec.DRIFT.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.DRIFT.halfY()), VehicleSpec.DRIFT.height(), 1e-9);
        // Used to size the entity for registration (EntityType.Builder#sized): must be positive and distinct
        // from the car's so the drift car is registered as its own vehicle, not a copy of CAR's bounding box.
        assertTrue(VehicleSpec.DRIFT.width() > 0 && VehicleSpec.DRIFT.height() > 0);
        assertNotEquals(VehicleSpec.CAR.width(), VehicleSpec.DRIFT.width());
        assertNotEquals(VehicleSpec.CAR.height(), VehicleSpec.DRIFT.height());
    }

    @Test
    void driftWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.DRIFT.rideHeight() + VehicleSpec.DRIFT.wheelRadius();
        assertEquals(expected, VehicleSpec.DRIFT.wheelCentreY(), 1e-9);
    }

    @Test
    void driftDrivesLikeTheCarForNow() {
        // Scope: no drift tire tuning yet (a sibling story adds it) -- drive force, top speed and grip match
        // the car's exactly, so it handles like a normal car until that tuning lands.
        assertEquals(VehicleSpec.CAR.forceScale(), VehicleSpec.DRIFT.forceScale(), 1e-9);
        assertEquals(VehicleSpec.CAR.maxSpeed(), VehicleSpec.DRIFT.maxSpeed(), 1e-9);
        assertEquals(VehicleSpec.CAR.looseGrip(), VehicleSpec.DRIFT.looseGrip(), 1e-9);
        assertEquals(VehicleSpec.CAR.rollingScale(), VehicleSpec.DRIFT.rollingScale(), 1e-9);
    }

    @Test
    void driftHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.DRIFT.mounts().length);
        for (double[] mount : VehicleSpec.DRIFT.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }
}
