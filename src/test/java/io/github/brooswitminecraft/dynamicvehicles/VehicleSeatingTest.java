package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function checks for {@link VehicleSeating} (MINECRAFT-172): capacity/speed boarding gate and per-seat dismount offsets. */
class VehicleSeatingTest {

    @Test
    void oneSeatSpecBoardsAtAnySpeedJustLikeBeforeThisTicket() {
        // A one-seat occupied array (every existing vehicle) must never be speed-gated: that is today's
        // behavior, and this ticket must not change it.
        boolean[] oneFreeSeat = {false};
        assertTrue(VehicleSeating.canBoard(oneFreeSeat, 0.0));
        assertTrue(VehicleSeating.canBoard(oneFreeSeat, 50.0), "a one-seat vehicle has no speed gate at all");
    }

    @Test
    void oneSeatSpecRefusesOnlyWhenAlreadyOccupied() {
        boolean[] oneTakenSeat = {true};
        assertFalse(VehicleSeating.canBoard(oneTakenSeat, 0.0));
    }

    @Test
    void multiSeatRefusesBoardingAboveTheSpeedLimitButAllowsAtOrBelowIt() {
        boolean[] twoFreeSeats = {false, false};
        assertTrue(VehicleSeating.canBoard(twoFreeSeats, 0.0));
        assertTrue(VehicleSeating.canBoard(twoFreeSeats, VehicleSeating.BOARDING_SPEED_LIMIT));
        assertFalse(VehicleSeating.canBoard(twoFreeSeats, VehicleSeating.BOARDING_SPEED_LIMIT + 0.01));
    }

    @Test
    void multiSeatRefusesBoardingWithNoFreeSeatRegardlessOfSpeed() {
        boolean[] full = {true, true};
        assertFalse(VehicleSeating.canBoard(full, 0.0));
    }

    @Test
    void firstFreeSeatIsTheLowestIndexNotYetOccupied() {
        assertEquals(0, VehicleSeating.firstFreeSeat(new boolean[] {false, true, false}));
        assertEquals(1, VehicleSeating.firstFreeSeat(new boolean[] {true, false, true}));
        assertEquals(-1, VehicleSeating.firstFreeSeat(new boolean[] {true, true}));
    }

    /** MINECRAFT-211: an auto-boarding mob must never be offered seat 0, even when every seat is free. */
    @Test
    void firstFreeNonDriverSeatSkipsSeatZeroEvenWhenItIsFree() {
        assertEquals(1, VehicleSeating.firstFreeNonDriverSeat(new boolean[] {false, false, false}));
        assertEquals(2, VehicleSeating.firstFreeNonDriverSeat(new boolean[] {false, true, false}));
        assertEquals(-1, VehicleSeating.firstFreeNonDriverSeat(new boolean[] {false, true, true}),
                "every non-driver seat full -- the free driver seat must not be offered");
        assertEquals(-1, VehicleSeating.firstFreeNonDriverSeat(new boolean[] {false}),
                "a one-seat vehicle has no non-driver seat at all");
    }

    @Test
    void eachSeatGetsItsOwnDismountOffsetClearOfTheBody() {
        double halfX = 0.9;
        VehicleSpec.Seat driver = new VehicleSpec.Seat(0.0, 0.5, 0.2);
        VehicleSpec.Seat left = new VehicleSpec.Seat(-0.75, 0.42, -1.2);
        VehicleSpec.Seat right = new VehicleSpec.Seat(0.75, 0.42, -1.2);

        double[] driverExit = VehicleSeating.dismountOffset(driver, halfX);
        double[] leftExit = VehicleSeating.dismountOffset(left, halfX);
        double[] rightExit = VehicleSeating.dismountOffset(right, halfX);

        // Every seat lands outside the body's own half-width, and a seat on the left steps out to the left
        // while one on the right (or centred) steps out to the right -- never the same point for two seats.
        assertTrue(Math.abs(driverExit[0]) >= halfX);
        assertTrue(leftExit[0] < -halfX);
        assertTrue(rightExit[0] > halfX);
        assertEquals(left.z(), leftExit[2], 1e-9);
        assertEquals(right.z(), rightExit[2], 1e-9);
    }
}
