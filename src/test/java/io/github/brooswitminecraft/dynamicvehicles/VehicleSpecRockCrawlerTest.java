package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for the rock crawler's {@link VehicleSpec#ROCK_CRAWLER} (MINECRAFT-176). The
 * "excellent climbing" character is a tuning/feel target, not a hard physics assertion (no Sable engine
 * runs in these unit tests), so these checks are about the spec's own ingredients for that feel - tallest
 * ride height, longest suspension travel, highest grip, and strong low-speed drive at a low top speed -
 * rather than a simulated outcome.
 */
class VehicleSpecRockCrawlerTest {

    @Test
    void rockCrawlerIsItsOwnDistinctSpec() {
        assertNotEquals(VehicleSpec.CAR, VehicleSpec.ROCK_CRAWLER);
        assertNotEquals(VehicleSpec.TRUCK, VehicleSpec.ROCK_CRAWLER);
        assertNotEquals(VehicleSpec.TROPHY, VehicleSpec.ROCK_CRAWLER);
        assertNotEquals(VehicleSpec.DRIFT, VehicleSpec.ROCK_CRAWLER);
        assertNotEquals(VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER);
    }

    @Test
    void rockCrawlerRidesTallerThanEveryExistingVehicle() {
        assertTrue(VehicleSpec.ROCK_CRAWLER.rideHeight() > VehicleSpec.CAR.rideHeight());
        assertTrue(VehicleSpec.ROCK_CRAWLER.rideHeight() > VehicleSpec.TRUCK.rideHeight());
        assertTrue(VehicleSpec.ROCK_CRAWLER.rideHeight() > VehicleSpec.TROPHY.rideHeight(), "rock crawler should ride taller than the trophy truck");
        assertTrue(VehicleSpec.ROCK_CRAWLER.rideHeight() > VehicleSpec.DRIFT.rideHeight());
        assertTrue(VehicleSpec.ROCK_CRAWLER.rideHeight() > VehicleSpec.MUSCLE.rideHeight());
    }

    @Test
    void rockCrawlerHasTheLongestSuspensionTravel() {
        // restLength is the distance at which the spring is fully unloaded, i.e. the suspension's travel.
        assertTrue(VehicleSpec.ROCK_CRAWLER.restLength() > VehicleSpec.CAR.restLength());
        assertTrue(VehicleSpec.ROCK_CRAWLER.restLength() > VehicleSpec.TRUCK.restLength());
        assertTrue(VehicleSpec.ROCK_CRAWLER.restLength() > VehicleSpec.TROPHY.restLength(), "rock crawler should out-travel the trophy truck's own long-travel suspension");
        assertTrue(VehicleSpec.ROCK_CRAWLER.restLength() > VehicleSpec.DRIFT.restLength());
        assertTrue(VehicleSpec.ROCK_CRAWLER.restLength() > VehicleSpec.MUSCLE.restLength());
    }

    @Test
    void rockCrawlerHasTheHighestLooseGrip() {
        assertTrue(VehicleSpec.ROCK_CRAWLER.looseGrip() > VehicleSpec.CAR.looseGrip());
        assertTrue(VehicleSpec.ROCK_CRAWLER.looseGrip() > VehicleSpec.TRUCK.looseGrip());
        assertTrue(VehicleSpec.ROCK_CRAWLER.looseGrip() > VehicleSpec.TROPHY.looseGrip(), "rock crawler should grip loose ground better than the trophy truck");
        assertTrue(VehicleSpec.ROCK_CRAWLER.looseGrip() > VehicleSpec.DRIFT.looseGrip());
        assertTrue(VehicleSpec.ROCK_CRAWLER.looseGrip() > VehicleSpec.MUSCLE.looseGrip());
    }

    @Test
    void rockCrawlerHasAShortWheelbaseAndALowTopSpeed() {
        // Torque-over-speed character: a tight turning circle and a low top speed, not a racer.
        assertTrue(VehicleSpec.ROCK_CRAWLER.wheelbase() < VehicleSpec.CAR.wheelbase(), "rock crawler should have a shorter wheelbase than the car");
        assertTrue(VehicleSpec.ROCK_CRAWLER.maxSpeed() < VehicleSpec.CAR.maxSpeed(), "rock crawler should be slower than the car");
        assertTrue(VehicleSpec.ROCK_CRAWLER.maxSpeed() < VehicleSpec.TRUCK.maxSpeed());
        assertTrue(VehicleSpec.ROCK_CRAWLER.maxSpeed() < VehicleSpec.TROPHY.maxSpeed());
    }

    @Test
    void rockCrawlerEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.ROCK_CRAWLER.halfX()), VehicleSpec.ROCK_CRAWLER.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.ROCK_CRAWLER.halfY()), VehicleSpec.ROCK_CRAWLER.height(), 1e-9);
        assertTrue(VehicleSpec.ROCK_CRAWLER.width() > 0 && VehicleSpec.ROCK_CRAWLER.height() > 0);
    }

    @Test
    void rockCrawlerWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.ROCK_CRAWLER.rideHeight() + VehicleSpec.ROCK_CRAWLER.wheelRadius();
        assertEquals(expected, VehicleSpec.ROCK_CRAWLER.wheelCentreY(), 1e-9);
    }

    @Test
    void rockCrawlerHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.ROCK_CRAWLER.mounts().length);
        for (double[] mount : VehicleSpec.ROCK_CRAWLER.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }

    @Test
    void rockCrawlerCarriesNoDriftTuning() {
        // This story is about climbing grip and articulation, not sliding - the rock crawler should route
        // through the plain WheelMath tire path, exactly like CAR/TRUCK/TROPHY/MUSCLE.
        assertTrue(VehicleSpec.ROCK_CRAWLER.tireTuning().isIdentity());
    }
}
