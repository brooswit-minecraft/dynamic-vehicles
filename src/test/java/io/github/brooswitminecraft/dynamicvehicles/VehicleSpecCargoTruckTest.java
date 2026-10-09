package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for the cargo truck's {@link VehicleSpec#CARGO_TRUCK} (MINECRAFT-163/192). The
 * "heavy hauler: high mass, slow acceleration, sluggish steering, long braking, stable" character is a
 * tuning/feel target, not a hard physics assertion (no Sable engine runs in these unit tests), so these
 * checks are about the spec's own ingredients for that feel - heaviest on the roster, weakest
 * power-to-weight, a long wheelbase (the one field {@link WheelMath#maxSteerAngle} reads to widen a
 * turning circle, see {@code VehicleSpec.BUS}'s own javadoc for the mechanism this spec reuses) and a
 * critically-damped (not bouncy) suspension at the car's own ratio - rather than a simulated outcome.
 */
class VehicleSpecCargoTruckTest {

    private static final VehicleSpec[] PRE_EXISTING = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
            VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK,
            VehicleSpec.INDY, VehicleSpec.BUS};

    @Test
    void cargoTruckIsItsOwnDistinctSpec() {
        for (VehicleSpec spec : PRE_EXISTING) {
            assertNotEquals(spec, VehicleSpec.CARGO_TRUCK);
        }
    }

    @Test
    void cargoTruckIsTheHeaviestVehicleOnTheRoster() {
        for (VehicleSpec spec : PRE_EXISTING) {
            assertTrue(VehicleSpec.CARGO_TRUCK.massKg() > spec.massKg(), "cargo truck should be heavier than every pre-existing vehicle");
        }
    }

    @Test
    void cargoTruckHasTheWeakestPowerToWeightOnTheRoster() {
        double cargoRatio = VehicleSpec.CARGO_TRUCK.forceScale() / VehicleSpec.CARGO_TRUCK.massKg();
        for (VehicleSpec spec : PRE_EXISTING) {
            double ratio = spec.forceScale() / spec.massKg();
            assertTrue(cargoRatio < ratio, "cargo truck should out-haul (weaker power-to-weight than) every pre-existing vehicle");
        }
    }

    @Test
    void cargoTruckHasTheLowestTopSpeedOnTheRoster() {
        for (VehicleSpec spec : PRE_EXISTING) {
            assertTrue(VehicleSpec.CARGO_TRUCK.maxSpeed() < spec.maxSpeed(), "cargo truck should be the roster's slowest top speed");
        }
    }

    @Test
    void cargoTruckHasALongWheelbaseForSluggishSteering() {
        // Not necessarily the longest (BUS already claims that), but clearly longer than every vehicle
        // besides the bus - the same mechanism (WheelMath.maxSteerAngle reading wheelbase directly)
        // widens this vehicle's own turning circle with no shared-code change.
        for (VehicleSpec spec : PRE_EXISTING) {
            if (spec == VehicleSpec.BUS) {
                assertTrue(VehicleSpec.CARGO_TRUCK.wheelbase() < spec.wheelbase(), "bus should keep the roster's longest wheelbase");
            } else {
                assertTrue(VehicleSpec.CARGO_TRUCK.wheelbase() > spec.wheelbase(), "cargo truck should out-wheelbase every other pre-existing vehicle");
            }
        }
    }

    @Test
    void cargoTruckSuspensionIsCriticallyDampedLikeTheCar() {
        // "Stable" means NOT the monster truck's deliberately under-damped bounce: this vehicle keeps the
        // car's own damping ratio (damping/spring), scaled only by mass, like every mass-scaled vehicle on
        // the roster besides the monster truck.
        double carRatio = VehicleSpec.CAR.dampingRate() / VehicleSpec.CAR.springRate();
        double cargoRatio = VehicleSpec.CARGO_TRUCK.dampingRate() / VehicleSpec.CARGO_TRUCK.springRate();
        assertEquals(carRatio, cargoRatio, 1e-9, "cargo truck should settle like the car, not bounce like the monster truck");
    }

    @Test
    void cargoTruckHasTwoSeatsWithTheDriverInSeatZero() {
        assertEquals(2, VehicleSpec.CARGO_TRUCK.seatCount());
        VehicleSpec.Seat driver = VehicleSpec.CARGO_TRUCK.driverSeat();
        assertEquals(VehicleSpec.CARGO_TRUCK.seats().get(0), driver, "seat 0 is the driver");
        assertEquals(0.0, driver.x(), 1e-9, "driver seat stays at x = 0");
    }

    @Test
    void everyCargoTruckSeatHasADistinctDismountPointClearOfTheBody() {
        java.util.List<double[]> dismounts = new java.util.ArrayList<>();
        for (VehicleSpec.Seat seat : VehicleSpec.CARGO_TRUCK.seats()) {
            double[] dismount = VehicleSeating.dismountOffset(seat, VehicleSpec.CARGO_TRUCK.halfX());
            assertTrue(Math.abs(dismount[0]) >= VehicleSpec.CARGO_TRUCK.halfX(), "dismount must land outside the body's half-width");
            for (double[] seen : dismounts) {
                assertTrue(seen[0] != dismount[0] || seen[2] != dismount[2], "two seats must not share a dismount point");
            }
            dismounts.add(dismount);
        }
    }

    @Test
    void everyCargoTruckSeatSitsInsideTheVehicleBody() {
        for (VehicleSpec.Seat seat : VehicleSpec.CARGO_TRUCK.seats()) {
            assertTrue(Math.abs(seat.x()) < VehicleSpec.CARGO_TRUCK.halfX(), "seat x must sit inside the body");
            assertTrue(Math.abs(seat.z()) < VehicleSpec.CARGO_TRUCK.halfZ(), "seat z must sit inside the body");
        }
    }

    @Test
    void cargoTruckEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.CARGO_TRUCK.halfX()), VehicleSpec.CARGO_TRUCK.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.CARGO_TRUCK.halfY()), VehicleSpec.CARGO_TRUCK.height(), 1e-9);
        assertTrue(VehicleSpec.CARGO_TRUCK.width() > 0 && VehicleSpec.CARGO_TRUCK.height() > 0);
    }

    @Test
    void cargoTruckWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.CARGO_TRUCK.rideHeight() + VehicleSpec.CARGO_TRUCK.wheelRadius();
        assertEquals(expected, VehicleSpec.CARGO_TRUCK.wheelCentreY(), 1e-9);
    }

    @Test
    void cargoTruckHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.CARGO_TRUCK.mounts().length);
        for (double[] mount : VehicleSpec.CARGO_TRUCK.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }

    @Test
    void cargoTruckCarriesNoDriftTuningAndNoDownforce() {
        assertTrue(VehicleSpec.CARGO_TRUCK.tireTuning().isIdentity());
        assertEquals(0.0, VehicleSpec.CARGO_TRUCK.downforceGripPerSpeed(), 1e-12);
        assertEquals(1.0, VehicleSpec.CARGO_TRUCK.downforceGripMultiplier(25.0), 1e-12);
    }

    @Test
    void everyPreExistingVehicleIsUnchangedByTheCargoTruck() {
        // The regression guarantee this story depends on: adding CARGO_TRUCK as a new VehicleSpec constant
        // must not alter any field of any pre-existing vehicle. Spot-check each roster member's own
        // defining numbers (the ones its own introducing commit asserted) rather than re-deriving the whole
        // spec here.
        assertEquals(1200.0, VehicleSpec.CAR.massKg(), 1e-9);
        assertEquals(1, VehicleSpec.MUSCLE.seatCount());
        assertEquals(2200.0, VehicleSpec.TRUCK.massKg(), 1e-9);
        assertEquals(2, VehicleSpec.TROPHY.seatCount());
        assertEquals(2, VehicleSpec.DRIFT.seatCount());
        assertEquals(1, VehicleSpec.ROCK_CRAWLER.seatCount());
        assertEquals(2600.0, VehicleSpec.MONSTER_TRUCK.massKg(), 1e-9);
        assertEquals(650.0, VehicleSpec.INDY.massKg(), 1e-9);
        assertEquals(0.012, VehicleSpec.INDY.downforceGripPerSpeed(), 1e-12);
        assertEquals(4200.0, VehicleSpec.BUS.massKg(), 1e-9);
        assertEquals(8, VehicleSpec.BUS.seatCount());
    }
}
