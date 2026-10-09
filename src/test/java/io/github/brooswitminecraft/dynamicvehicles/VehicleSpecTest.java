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

    /**
     * MINECRAFT-172 backward compat, narrowed by MINECRAFT-182: {@code MUSCLE} and {@code ROCK_CRAWLER}
     * still go through the old (seatY, seatZ) constructor untouched, which must derive a single driver seat
     * -- at x = 0, seat index 0 -- from exactly those two numbers. {@code CAR}, {@code TRUCK},
     * {@code TROPHY} and {@code DRIFT} moved to the full constructor with an explicit multi-seat list
     * (MINECRAFT-182); their seat counts are covered by {@code VehicleSpecMultiSeatTest} instead.
     */
    @Test
    void oneSeatVehiclesDeriveTheirDriverSeatFromTheirOldSeatYSeatZ() {
        for (VehicleSpec spec : java.util.List.of(VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER)) {
            assertEquals(1, spec.seatCount());
            VehicleSpec.Seat driver = spec.driverSeat();
            assertEquals(0.0, driver.x(), 1e-9);
            assertEquals(spec.seatY(), driver.y(), 1e-9);
            assertEquals(spec.seatZ(), driver.z(), 1e-9);
        }
    }

    /** MINECRAFT-172: the new seat list must support at least 8 seats (a future bus: driver + 7 passengers). */
    @Test
    void anEightSeatSpecCanBeBuilt() {
        java.util.List<VehicleSpec.Seat> busSeats = new java.util.ArrayList<>();
        busSeats.add(new VehicleSpec.Seat(0.0, VehicleSpec.CAR.seatY(), VehicleSpec.CAR.seatZ()));
        for (int i = 1; i < 8; i++) {
            busSeats.add(new VehicleSpec.Seat(i % 2 == 0 ? 0.5 : -0.5, VehicleSpec.CAR.seatY(), VehicleSpec.CAR.seatZ() - i * 0.9));
        }
        VehicleSpec bus = new VehicleSpec(
                VehicleSpec.CAR.halfX(), VehicleSpec.CAR.halfY(), VehicleSpec.CAR.halfZ(),
                VehicleSpec.CAR.mounts(), VehicleSpec.CAR.wheelRadius(), VehicleSpec.CAR.wheelWidth(),
                VehicleSpec.CAR.rideHeight(), VehicleSpec.CAR.restLength(),
                VehicleSpec.CAR.massKg(), VehicleSpec.CAR.springRate(), VehicleSpec.CAR.dampingRate(), VehicleSpec.CAR.maxSpringForce(),
                VehicleSpec.CAR.wheelbase(), VehicleSpec.CAR.maxSpeed(),
                VehicleSpec.CAR.forceScale(), VehicleSpec.CAR.enginePitch(),
                VehicleSpec.CAR.seatY(), VehicleSpec.CAR.seatZ(),
                VehicleSpec.CAR.looseGrip(), VehicleSpec.CAR.rollingScale(),
                VehicleSpec.CAR.tireTuning(), 0.0, 1.0, busSeats);

        assertEquals(8, bus.seatCount());
        assertEquals(0.0, bus.driverSeat().x(), 1e-9);
    }
}
