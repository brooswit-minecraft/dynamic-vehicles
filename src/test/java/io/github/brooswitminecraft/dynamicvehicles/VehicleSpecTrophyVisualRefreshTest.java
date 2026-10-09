package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-187 is a purely visual refresh of {@code CarRenderer}'s {@code trophy_truck} branch; it must
 * not move the physics, hitbox, or seats {@code VehicleSpec.TROPHY} already carries. These pin exactly the
 * fields a renderer-only change has no business touching.
 */
class VehicleSpecTrophyVisualRefreshTest {

    @Test
    void trophyHitboxIsUnchanged() {
        assertEquals(1.3, VehicleSpec.TROPHY.halfX());
        assertEquals(0.5, VehicleSpec.TROPHY.halfY());
        assertEquals(1.9, VehicleSpec.TROPHY.halfZ());
        assertEquals(0.6, VehicleSpec.TROPHY.wheelRadius());
        assertEquals(0.5, VehicleSpec.TROPHY.wheelWidth());
        assertEquals(1.6, VehicleSpec.TROPHY.rideHeight());
        assertArrayEquals(new double[] {-1.25, -0.3, 1.4}, VehicleSpec.TROPHY.mounts()[0]);
        assertArrayEquals(new double[] {1.25, -0.3, 1.4}, VehicleSpec.TROPHY.mounts()[1]);
        assertArrayEquals(new double[] {-1.25, -0.3, -1.4}, VehicleSpec.TROPHY.mounts()[2]);
        assertArrayEquals(new double[] {1.25, -0.3, -1.4}, VehicleSpec.TROPHY.mounts()[3]);
    }

    @Test
    void trophySeatsAreUnchanged() {
        assertEquals(2, VehicleSpec.TROPHY.seatCount());
        VehicleSpec.Seat driver = VehicleSpec.TROPHY.driverSeat();
        assertEquals(0.0, driver.x());
        assertEquals(0.5, driver.y());
        assertEquals(0.1, driver.z());
        VehicleSpec.Seat passenger = VehicleSpec.TROPHY.seats().get(1);
        assertEquals(-0.65, passenger.x());
        assertEquals(0.5, passenger.y());
        assertEquals(0.1, passenger.z());
    }

    @Test
    void trophyTruckEntityAndItemIdsAreUnchanged() {
        assertEquals("trophy_truck", VehicleIds.TROPHY_TRUCK);
    }
}
