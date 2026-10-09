package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-182: {@code DRIFT}/{@code CAR}/{@code TROPHY}/{@code TRUCK} move from the legacy one-seat
 * constructor to an explicit {@link VehicleSpec.Seat} list (2/4/2/4 seats), on the shared base
 * {@code VehicleSpec}/{@code VehicleSeating}/{@code SeatAssignment} already lay down (MINECRAFT-172).
 * Covers seat count, driver = seat 0, first-free-seat fill order, distinct per-seat attachment/dismount
 * points, and that every non-seat field stays byte-identical to the one-seat spec these four replace.
 */
class VehicleSpecMultiSeatTest {

    private static final List<VehicleSpec> ONE_SEAT = List.of(VehicleSpec.TROPHY, VehicleSpec.DRIFT);
    private static final List<VehicleSpec> FOUR_SEAT = List.of(VehicleSpec.CAR, VehicleSpec.TRUCK);

    @Test
    void driftAndTrophyHaveTwoSeats() {
        for (VehicleSpec spec : ONE_SEAT) {
            assertEquals(2, spec.seatCount());
        }
    }

    @Test
    void carAndTruckHaveFourSeats() {
        for (VehicleSpec spec : FOUR_SEAT) {
            assertEquals(4, spec.seatCount());
        }
    }

    @Test
    void driverIsAlwaysSeatZeroAtItsOriginalPosition() {
        for (VehicleSpec spec : List.of(VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT)) {
            VehicleSpec.Seat driver = spec.driverSeat();
            assertEquals(spec.seats().get(0), driver, "seat 0 is the driver");
            assertEquals(0.0, driver.x(), 1e-9, "driver seat stays at x = 0, exactly as the one-seat spec had it");
            assertEquals(spec.seatY(), driver.y(), 1e-9, "driver seat keeps the legacy seatY");
            assertEquals(spec.seatZ(), driver.z(), 1e-9, "driver seat keeps the legacy seatZ");
        }
    }

    @Test
    void firstFreeSeatFillOrderPutsTheDriverInSeatZeroRegardlessOfBoardingOrder() {
        for (VehicleSpec spec : List.of(VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT)) {
            SeatAssignment<String> seats = new SeatAssignment<>(spec.seatCount());
            for (int i = 0; i < spec.seatCount(); i++) {
                assertEquals(i, seats.add("rider" + i));
            }
            assertEquals("rider0", seats.driver());
            assertEquals(-1, seats.add("oneTooMany"));
        }
    }

    @Test
    void everySeatHasADistinctAttachmentPoint() {
        for (VehicleSpec spec : List.of(VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT)) {
            List<VehicleSpec.Seat> seats = spec.seats();
            for (int i = 0; i < seats.size(); i++) {
                for (int j = i + 1; j < seats.size(); j++) {
                    assertNotEquals(seats.get(i), seats.get(j), "seats " + i + " and " + j + " of " + spec + " must sit at different points");
                }
            }
        }
    }

    @Test
    void everySeatHasADistinctDismountPointClearOfTheBody() {
        for (VehicleSpec spec : List.of(VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT)) {
            List<double[]> dismounts = new java.util.ArrayList<>();
            for (VehicleSpec.Seat seat : spec.seats()) {
                double[] dismount = VehicleSeating.dismountOffset(seat, spec.halfX());
                assertTrue(Math.abs(dismount[0]) >= spec.halfX(), "dismount must land outside the body's half-width");
                for (double[] seen : dismounts) {
                    assertTrue(seen[0] != dismount[0] || seen[2] != dismount[2],
                            "two seats of " + spec + " must not share a dismount point");
                }
                dismounts.add(dismount);
            }
        }
    }

    @Test
    void everySeatSitsInsideTheVehicleBody() {
        for (VehicleSpec spec : List.of(VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT)) {
            for (VehicleSpec.Seat seat : spec.seats()) {
                assertTrue(Math.abs(seat.x()) < spec.halfX(), "seat x must sit inside the body");
                assertTrue(Math.abs(seat.z()) < spec.halfZ(), "seat z must sit inside the body");
            }
        }
    }

    @Test
    void muscleAndRockCrawlerRemainOneSeatVehicles() {
        for (VehicleSpec spec : List.of(VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER)) {
            assertEquals(1, spec.seatCount());
        }
    }

    /**
     * Every non-seat field of {@code CAR}/{@code TRUCK}/{@code TROPHY}/{@code DRIFT} must be byte-identical
     * to the one-seat spec it replaces (tuning is not in scope for this ticket): reconstruct each through
     * the untouched legacy constructor with the live spec's own field values and compare every field the
     * legacy constructor can produce.
     */
    @Test
    void nonSeatFieldsAreByteIdenticalToTheLegacyOneSeatConstruction() {
        for (VehicleSpec spec : List.of(VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT)) {
            VehicleSpec legacyShape = new VehicleSpec(
                    spec.halfX(), spec.halfY(), spec.halfZ(),
                    spec.mounts(), spec.wheelRadius(), spec.wheelWidth(),
                    spec.rideHeight(), spec.restLength(),
                    spec.massKg(), spec.springRate(), spec.dampingRate(), spec.maxSpringForce(),
                    spec.wheelbase(), spec.maxSpeed(),
                    spec.forceScale(), spec.enginePitch(),
                    spec.seatY(), spec.seatZ(),
                    spec.looseGrip(), spec.rollingScale(),
                    spec.tireTuning());

            assertEquals(legacyShape.halfX(), spec.halfX(), 1e-12);
            assertEquals(legacyShape.halfY(), spec.halfY(), 1e-12);
            assertEquals(legacyShape.halfZ(), spec.halfZ(), 1e-12);
            assertEquals(legacyShape.wheelRadius(), spec.wheelRadius(), 1e-12);
            assertEquals(legacyShape.wheelWidth(), spec.wheelWidth(), 1e-12);
            assertEquals(legacyShape.rideHeight(), spec.rideHeight(), 1e-12);
            assertEquals(legacyShape.restLength(), spec.restLength(), 1e-12);
            assertEquals(legacyShape.massKg(), spec.massKg(), 1e-12);
            assertEquals(legacyShape.springRate(), spec.springRate(), 1e-12);
            assertEquals(legacyShape.dampingRate(), spec.dampingRate(), 1e-12);
            assertEquals(legacyShape.maxSpringForce(), spec.maxSpringForce(), 1e-12);
            assertEquals(legacyShape.wheelbase(), spec.wheelbase(), 1e-12);
            assertEquals(legacyShape.maxSpeed(), spec.maxSpeed(), 1e-12);
            assertEquals(legacyShape.forceScale(), spec.forceScale(), 1e-12);
            assertEquals(legacyShape.enginePitch(), spec.enginePitch(), 1e-12);
            assertEquals(legacyShape.seatY(), spec.seatY(), 1e-12);
            assertEquals(legacyShape.seatZ(), spec.seatZ(), 1e-12);
            assertEquals(legacyShape.looseGrip(), spec.looseGrip(), 1e-12);
            assertEquals(legacyShape.rollingScale(), spec.rollingScale(), 1e-12);
            assertEquals(legacyShape.tireTuning(), spec.tireTuning());
        }
    }
}
