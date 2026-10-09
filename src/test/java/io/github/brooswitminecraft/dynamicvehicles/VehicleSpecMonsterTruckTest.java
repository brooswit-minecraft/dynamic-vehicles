package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for the monster truck's {@link VehicleSpec#MONSTER_TRUCK} (MINECRAFT-180). The
 * "huge wheels, tall, bouncy suspension" character is a tuning/feel target, not a hard physics assertion
 * (no Sable engine runs in these unit tests), so these checks are about the spec's own ingredients for
 * that feel - biggest wheels, tallest ride height, longest suspension travel, and an under-damped spring
 * relative to its own mass-scaled rate - rather than a simulated outcome.
 */
class VehicleSpecMonsterTruckTest {

    @Test
    void monsterTruckIsItsOwnDistinctSpec() {
        assertNotEquals(VehicleSpec.CAR, VehicleSpec.MONSTER_TRUCK);
        assertNotEquals(VehicleSpec.TRUCK, VehicleSpec.MONSTER_TRUCK);
        assertNotEquals(VehicleSpec.TROPHY, VehicleSpec.MONSTER_TRUCK);
        assertNotEquals(VehicleSpec.DRIFT, VehicleSpec.MONSTER_TRUCK);
        assertNotEquals(VehicleSpec.MUSCLE, VehicleSpec.MONSTER_TRUCK);
        assertNotEquals(VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK);
    }

    @Test
    void monsterTruckHasTheBiggestWheels() {
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelRadius() > VehicleSpec.CAR.wheelRadius());
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelRadius() > VehicleSpec.TRUCK.wheelRadius());
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelRadius() > VehicleSpec.TROPHY.wheelRadius());
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelRadius() > VehicleSpec.DRIFT.wheelRadius());
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelRadius() > VehicleSpec.MUSCLE.wheelRadius());
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelRadius() > VehicleSpec.ROCK_CRAWLER.wheelRadius(),
                "monster truck should have bigger wheels than the rock crawler");
        assertTrue(VehicleSpec.MONSTER_TRUCK.wheelWidth() > VehicleSpec.ROCK_CRAWLER.wheelWidth(),
                "monster truck should have wider wheels than the rock crawler");
    }

    @Test
    void monsterTruckRidesTallerThanEveryExistingVehicle() {
        assertTrue(VehicleSpec.MONSTER_TRUCK.rideHeight() > VehicleSpec.CAR.rideHeight());
        assertTrue(VehicleSpec.MONSTER_TRUCK.rideHeight() > VehicleSpec.TRUCK.rideHeight());
        assertTrue(VehicleSpec.MONSTER_TRUCK.rideHeight() > VehicleSpec.TROPHY.rideHeight());
        assertTrue(VehicleSpec.MONSTER_TRUCK.rideHeight() > VehicleSpec.DRIFT.rideHeight());
        assertTrue(VehicleSpec.MONSTER_TRUCK.rideHeight() > VehicleSpec.MUSCLE.rideHeight());
        assertTrue(VehicleSpec.MONSTER_TRUCK.rideHeight() > VehicleSpec.ROCK_CRAWLER.rideHeight(),
                "monster truck should ride taller than the rock crawler");
    }

    @Test
    void monsterTruckHasTheLongestSuspensionTravel() {
        // restLength is the distance at which the spring is fully unloaded, i.e. the suspension's travel.
        assertTrue(VehicleSpec.MONSTER_TRUCK.restLength() > VehicleSpec.CAR.restLength());
        assertTrue(VehicleSpec.MONSTER_TRUCK.restLength() > VehicleSpec.TRUCK.restLength());
        assertTrue(VehicleSpec.MONSTER_TRUCK.restLength() > VehicleSpec.TROPHY.restLength());
        assertTrue(VehicleSpec.MONSTER_TRUCK.restLength() > VehicleSpec.DRIFT.restLength());
        assertTrue(VehicleSpec.MONSTER_TRUCK.restLength() > VehicleSpec.MUSCLE.restLength());
        assertTrue(VehicleSpec.MONSTER_TRUCK.restLength() > VehicleSpec.ROCK_CRAWLER.restLength(),
                "monster truck should out-travel the rock crawler's own long-travel suspension");
    }

    @Test
    void monsterTruckSuspensionIsUnderDampedRelativeToTheCarsOwnRatio() {
        // Every other vehicle's spring/damper is the car's scaled to its own mass at the SAME damping
        // ratio (damping/spring), so each sags the same fraction of its travel and settles the same way.
        // The monster truck's "bouncy" character comes from breaking that pattern: a damping ratio clearly
        // below the car's, so it overshoots and settles more slowly instead of feeling critically damped.
        double carRatio = VehicleSpec.CAR.dampingRate() / VehicleSpec.CAR.springRate();
        double monsterTruckRatio = VehicleSpec.MONSTER_TRUCK.dampingRate() / VehicleSpec.MONSTER_TRUCK.springRate();
        assertTrue(monsterTruckRatio < carRatio * 0.75,
                "monster truck's damping ratio should sit well below the car's for a bouncy feel");
        // Every mass-scaled vehicle on the roster keeps the car's own ratio; the monster truck should be
        // the outlier, not accidentally matching one of them.
        double rockCrawlerRatio = VehicleSpec.ROCK_CRAWLER.dampingRate() / VehicleSpec.ROCK_CRAWLER.springRate();
        assertTrue(monsterTruckRatio < rockCrawlerRatio);
    }

    @Test
    void monsterTruckIsTheHeaviestAndStrongestOnTheRoster() {
        assertTrue(VehicleSpec.MONSTER_TRUCK.massKg() > VehicleSpec.TRUCK.massKg(), "monster truck should be the roster's heaviest vehicle");
        assertTrue(VehicleSpec.MONSTER_TRUCK.forceScale() > VehicleSpec.MUSCLE.forceScale(), "monster truck should out-muscle the muscle car's forceScale");
        assertTrue(VehicleSpec.MONSTER_TRUCK.forceScale() > VehicleSpec.ROCK_CRAWLER.forceScale());
    }

    @Test
    void monsterTruckEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.MONSTER_TRUCK.halfX()), VehicleSpec.MONSTER_TRUCK.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.MONSTER_TRUCK.halfY()), VehicleSpec.MONSTER_TRUCK.height(), 1e-9);
        assertTrue(VehicleSpec.MONSTER_TRUCK.width() > 0 && VehicleSpec.MONSTER_TRUCK.height() > 0);
    }

    @Test
    void monsterTruckWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.MONSTER_TRUCK.rideHeight() + VehicleSpec.MONSTER_TRUCK.wheelRadius();
        assertEquals(expected, VehicleSpec.MONSTER_TRUCK.wheelCentreY(), 1e-9);
    }

    @Test
    void monsterTruckHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.MONSTER_TRUCK.mounts().length);
        for (double[] mount : VehicleSpec.MONSTER_TRUCK.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }

    @Test
    void monsterTruckCarriesNoDriftTuning() {
        // This story is about size, mass and bounce, not sliding - the monster truck should route through
        // the plain WheelMath tire path, exactly like CAR/TRUCK/TROPHY/MUSCLE/ROCK_CRAWLER.
        assertTrue(VehicleSpec.MONSTER_TRUCK.tireTuning().isIdentity());
    }
}
