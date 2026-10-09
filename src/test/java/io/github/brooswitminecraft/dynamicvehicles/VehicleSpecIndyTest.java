package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for the Indy car's {@link VehicleSpec#INDY} (MINECRAFT-161/184). The "low, light,
 * very fast, downforce-like grip at speed" character is a tuning/feel target, not a hard physics assertion
 * (no Sable engine runs in these unit tests), so these checks are about the spec's own ingredients for that
 * feel - lowest, lightest, fastest on the roster, plus the new speed-dependent grip multiplier itself as a
 * pure function - rather than a simulated outcome.
 */
class VehicleSpecIndyTest {

    @Test
    void indyIsItsOwnDistinctSpec() {
        assertNotEquals(VehicleSpec.CAR, VehicleSpec.INDY);
        assertNotEquals(VehicleSpec.TRUCK, VehicleSpec.INDY);
        assertNotEquals(VehicleSpec.TROPHY, VehicleSpec.INDY);
        assertNotEquals(VehicleSpec.DRIFT, VehicleSpec.INDY);
        assertNotEquals(VehicleSpec.MUSCLE, VehicleSpec.INDY);
        assertNotEquals(VehicleSpec.ROCK_CRAWLER, VehicleSpec.INDY);
        assertNotEquals(VehicleSpec.MONSTER_TRUCK, VehicleSpec.INDY);
    }

    @Test
    void indyIsTheLowestVehicleOnTheRoster() {
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.CAR.halfY());
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.TRUCK.halfY());
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.TROPHY.halfY());
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.DRIFT.halfY(), "indy car should sit lower than the drift car");
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.MUSCLE.halfY());
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.ROCK_CRAWLER.halfY());
        assertTrue(VehicleSpec.INDY.halfY() < VehicleSpec.MONSTER_TRUCK.halfY());

        assertTrue(VehicleSpec.INDY.rideHeight() < VehicleSpec.CAR.rideHeight());
        assertTrue(VehicleSpec.INDY.rideHeight() < VehicleSpec.DRIFT.rideHeight(), "indy car should ride lower than the drift car");
        assertTrue(VehicleSpec.INDY.rideHeight() < VehicleSpec.TROPHY.rideHeight());
        assertTrue(VehicleSpec.INDY.rideHeight() < VehicleSpec.ROCK_CRAWLER.rideHeight());
        assertTrue(VehicleSpec.INDY.rideHeight() < VehicleSpec.MONSTER_TRUCK.rideHeight());
    }

    @Test
    void indyIsTheLightestVehicleOnTheRoster() {
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.CAR.massKg());
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.TRUCK.massKg());
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.TROPHY.massKg());
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.DRIFT.massKg(), "indy car should be lighter than the drift car");
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.MUSCLE.massKg());
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.ROCK_CRAWLER.massKg());
        assertTrue(VehicleSpec.INDY.massKg() < VehicleSpec.MONSTER_TRUCK.massKg());
    }

    @Test
    void indyIsTheFastestVehicleOnTheRoster() {
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.CAR.maxSpeed());
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.TRUCK.maxSpeed());
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.TROPHY.maxSpeed(), "indy car should be faster than the trophy truck, the roster's previous fastest");
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.DRIFT.maxSpeed());
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.MUSCLE.maxSpeed());
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.ROCK_CRAWLER.maxSpeed());
        assertTrue(VehicleSpec.INDY.maxSpeed() > VehicleSpec.MONSTER_TRUCK.maxSpeed());
    }

    @Test
    void indyHasAnOpenWheelLook() {
        // The wheel mounts sit outside halfX, so the body (rendered within +/- halfX) never covers the
        // wheels - see CarRenderer's INDY branch and VehicleSpec.INDY's own javadoc.
        for (double[] mount : VehicleSpec.INDY.mounts()) {
            assertTrue(Math.abs(mount[0]) > VehicleSpec.INDY.halfX(),
                    "each wheel mount's x should sit outside halfX for the open-wheel look");
        }
    }

    @Test
    void indyEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.INDY.halfX()), VehicleSpec.INDY.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.INDY.halfY()), VehicleSpec.INDY.height(), 1e-9);
        assertTrue(VehicleSpec.INDY.width() > 0 && VehicleSpec.INDY.height() > 0);
    }

    @Test
    void indyWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.INDY.rideHeight() + VehicleSpec.INDY.wheelRadius();
        assertEquals(expected, VehicleSpec.INDY.wheelCentreY(), 1e-9);
    }

    @Test
    void indyHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.INDY.mounts().length);
        for (double[] mount : VehicleSpec.INDY.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }

    @Test
    void indyCarriesNoDriftTuning() {
        // Downforce is this story's own new, separate mechanism (downforceGripPerSpeed), not
        // DriftTireModel's cornering-slip one, so the indy car's cornering grip still routes through the
        // plain WheelMath tire path, exactly like every other existing vehicle.
        assertTrue(VehicleSpec.INDY.tireTuning().isIdentity());
    }

    @Test
    void indyDownforceGripGrowsWithSpeedAndIsZeroAtAStandstill() {
        assertTrue(VehicleSpec.INDY.downforceGripPerSpeed() > 0.0, "indy car should be the roster's only vehicle with nonzero downforce");
        assertEquals(1.0, VehicleSpec.INDY.downforceGripMultiplier(0.0), 1e-9, "no grip bonus at a standstill");
        double at20 = VehicleSpec.INDY.downforceGripMultiplier(20.0);
        double at40 = VehicleSpec.INDY.downforceGripMultiplier(40.0);
        assertTrue(at20 > 1.0, "grip bonus should be positive once moving");
        assertTrue(at40 > at20, "grip bonus should keep growing with speed");
        // Symmetric in direction - reversing should give the same bonus as the same speed forwards.
        assertEquals(at20, VehicleSpec.INDY.downforceGripMultiplier(-20.0), 1e-9);
    }

    @Test
    void everyPreExistingVehicleHasNoDownforceAndAnUnchangedGripMultiplier() {
        // The regression guarantee this story's shared-code change (VehicleSpec.downforceGripPerSpeed +
        // SableCarBody.tick's gripMu multiply) depends on: every vehicle that predates MINECRAFT-184 must
        // carry exactly 0.0 here, so downforceGripMultiplier() is exactly 1.0 for them at any speed and
        // SableCarBody.tick's new multiply is a mathematical no-op - CAR/TRUCK/TROPHY/DRIFT/MUSCLE/
        // ROCK_CRAWLER/MONSTER_TRUCK must all stay byte-for-byte unchanged.
        VehicleSpec[] preExisting = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT,
                VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK};
        for (VehicleSpec spec : preExisting) {
            assertEquals(0.0, spec.downforceGripPerSpeed(), 1e-12, "pre-existing vehicle must carry no downforce");
            assertEquals(1.0, spec.downforceGripMultiplier(0.0), 1e-12);
            assertEquals(1.0, spec.downforceGripMultiplier(25.0), 1e-12, "gripMu multiplier must stay 1.0 at any speed");
            assertEquals(1.0, spec.downforceGripMultiplier(-50.0), 1e-12);
        }
    }
}
