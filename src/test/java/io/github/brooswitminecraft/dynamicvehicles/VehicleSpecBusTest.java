package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for the bus's {@link VehicleSpec#BUS} (MINECRAFT-162/191). The "long, heavy,
 * slow-turning" character is a tuning/feel target, not a hard physics assertion (no Sable engine runs in
 * these unit tests), so these checks are about the spec's own ingredients for that feel - longest, heaviest
 * and longest-wheelbase on the roster (wheelbase being the one spec field {@link WheelMath#maxSteerAngle}
 * reads to widen a turning circle, see {@code VehicleSpec.BUS}'s own javadoc) - plus the 8-seat layout
 * MINECRAFT-172's multi-seat base exists to carry, rather than a simulated outcome.
 */
class VehicleSpecBusTest {

    private static final VehicleSpec[] PRE_EXISTING = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
            VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK, VehicleSpec.INDY};

    @Test
    void busIsItsOwnDistinctSpec() {
        for (VehicleSpec spec : PRE_EXISTING) {
            assertNotEquals(spec, VehicleSpec.BUS);
        }
    }

    @Test
    void busHasEightSeatsWithTheDriverInSeatZero() {
        assertEquals(8, VehicleSpec.BUS.seatCount());
        VehicleSpec.Seat driver = VehicleSpec.BUS.driverSeat();
        assertEquals(VehicleSpec.BUS.seats().get(0), driver, "seat 0 is the driver");
        assertEquals(0.0, driver.x(), 1e-9, "driver seat stays at x = 0");
    }

    @Test
    void everyBusSeatHasADistinctAttachmentPoint() {
        java.util.List<VehicleSpec.Seat> seats = VehicleSpec.BUS.seats();
        for (int i = 0; i < seats.size(); i++) {
            for (int j = i + 1; j < seats.size(); j++) {
                assertNotEquals(seats.get(i), seats.get(j), "seats " + i + " and " + j + " must sit at different points");
            }
        }
    }

    @Test
    void everyBusSeatHasADistinctDismountPointClearOfTheBody() {
        java.util.List<double[]> dismounts = new java.util.ArrayList<>();
        for (VehicleSpec.Seat seat : VehicleSpec.BUS.seats()) {
            double[] dismount = VehicleSeating.dismountOffset(seat, VehicleSpec.BUS.halfX());
            assertTrue(Math.abs(dismount[0]) >= VehicleSpec.BUS.halfX(), "dismount must land outside the body's half-width");
            for (double[] seen : dismounts) {
                assertTrue(seen[0] != dismount[0] || seen[2] != dismount[2], "two seats must not share a dismount point");
            }
            dismounts.add(dismount);
        }
    }

    @Test
    void everyBusSeatSitsInsideTheVehicleBody() {
        for (VehicleSpec.Seat seat : VehicleSpec.BUS.seats()) {
            assertTrue(Math.abs(seat.x()) < VehicleSpec.BUS.halfX(), "seat x must sit inside the body");
            assertTrue(Math.abs(seat.z()) < VehicleSpec.BUS.halfZ(), "seat z must sit inside the body");
        }
    }

    @Test
    void busIsTheLongestVehicleOnTheRoster() {
        for (VehicleSpec spec : PRE_EXISTING) {
            assertTrue(VehicleSpec.BUS.halfZ() > spec.halfZ(), "bus should be longer than every pre-existing vehicle");
        }
    }

    @Test
    void busIsTheHeaviestVehicleOnTheRoster() {
        for (VehicleSpec spec : PRE_EXISTING) {
            assertTrue(VehicleSpec.BUS.massKg() > spec.massKg(), "bus should be heavier than every pre-existing vehicle");
        }
    }

    @Test
    void busHasTheLongestWheelbaseOnTheRoster() {
        // The one ingredient WheelMath.maxSteerAngle reads to widen a turning circle - see this vehicle's
        // own javadoc for why a long wheelbase alone gives the "slow to turn" character with no shared-code
        // change at all.
        for (VehicleSpec spec : PRE_EXISTING) {
            assertTrue(VehicleSpec.BUS.wheelbase() > spec.wheelbase(), "bus should have a longer wheelbase than every pre-existing vehicle");
        }
    }

    @Test
    void busEntitySizeMatchesItsHalfExtents() {
        assertEquals((float) (2 * VehicleSpec.BUS.halfX()), VehicleSpec.BUS.width(), 1e-9);
        assertEquals((float) (2 * VehicleSpec.BUS.halfY()), VehicleSpec.BUS.height(), 1e-9);
        assertTrue(VehicleSpec.BUS.width() > 0 && VehicleSpec.BUS.height() > 0);
    }

    @Test
    void busWheelCentreMatchesRideHeightAndWheelRadius() {
        double expected = -VehicleSpec.BUS.rideHeight() + VehicleSpec.BUS.wheelRadius();
        assertEquals(expected, VehicleSpec.BUS.wheelCentreY(), 1e-9);
    }

    @Test
    void busHasFourWheelMounts() {
        assertEquals(4, VehicleSpec.BUS.mounts().length);
        for (double[] mount : VehicleSpec.BUS.mounts()) {
            assertEquals(3, mount.length, "each mount is an (x, y, z) triple");
        }
    }

    @Test
    void busCarriesNoDriftTuningAndNoDownforce() {
        assertTrue(VehicleSpec.BUS.tireTuning().isIdentity());
        assertEquals(0.0, VehicleSpec.BUS.downforceGripPerSpeed(), 1e-12);
        assertEquals(1.0, VehicleSpec.BUS.downforceGripMultiplier(25.0), 1e-12);
    }

    @Test
    void everyPreExistingVehicleIsUnchangedByTheBus() {
        // The regression guarantee this story depends on: adding BUS as a new VehicleSpec constant must not
        // alter any field of any pre-existing vehicle. Spot-check each roster member's own defining numbers
        // (the ones its own introducing commit asserted) rather than re-deriving the whole spec here.
        assertEquals(1200.0, VehicleSpec.CAR.massKg(), 1e-9);
        assertEquals(1, VehicleSpec.MUSCLE.seatCount());
        assertEquals(2200.0, VehicleSpec.TRUCK.massKg(), 1e-9);
        assertEquals(2, VehicleSpec.TROPHY.seatCount());
        assertEquals(2, VehicleSpec.DRIFT.seatCount());
        assertEquals(1, VehicleSpec.ROCK_CRAWLER.seatCount());
        assertEquals(2600.0, VehicleSpec.MONSTER_TRUCK.massKg(), 1e-9);
        assertEquals(650.0, VehicleSpec.INDY.massKg(), 1e-9);
        assertEquals(0.012, VehicleSpec.INDY.downforceGripPerSpeed(), 1e-12);
    }
}
