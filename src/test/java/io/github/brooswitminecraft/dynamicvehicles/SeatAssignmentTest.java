package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function checks for {@link SeatAssignment} (MINECRAFT-172): fill order, driver slot, and an 8-seat roster. */
class SeatAssignmentTest {

    @Test
    void boardingFillsTheFirstFreeSeatInOrder() {
        SeatAssignment<String> seats = new SeatAssignment<>(4);
        assertEquals(0, seats.add("alice"));
        assertEquals(1, seats.add("bob"));
        assertEquals(2, seats.add("carol"));
    }

    @Test
    void aFreedSeatIsBackfilledBeforeAnyLaterSeat() {
        SeatAssignment<String> seats = new SeatAssignment<>(3);
        seats.add("alice");
        seats.add("bob");
        seats.remove("alice");
        // The next boarder takes seat 0 back, not seat 2 -- fill order is "lowest free seat", never "append".
        assertEquals(0, seats.add("carol"));
        assertEquals(2, seats.add("dave"));
    }

    @Test
    void seatZeroIsAlwaysTheDriverAndNoOneElseEverIs() {
        SeatAssignment<String> seats = new SeatAssignment<>(2);
        seats.add("alice");
        seats.add("bob");
        assertEquals("alice", seats.driver());
        assertEquals(1, seats.indexOf("bob"));

        // The driver leaving does not promote the remaining passenger: only seat 0 is ever "the driver",
        // so a passenger can never end up steering just because the driver stepped out.
        seats.remove("alice");
        assertNull(seats.driver());
        assertEquals(1, seats.indexOf("bob"));
    }

    /** MINECRAFT-211: an auto-boarding mob (excludeDriverSeat = true) never takes seat 0, even empty. */
    @Test
    void excludingTheDriverSeatSkipsSeatZeroEvenWhenEveryoneIsAbsent() {
        SeatAssignment<String> seats = new SeatAssignment<>(3);
        assertEquals(1, seats.add("cow", true));
        assertEquals(2, seats.add("secondCow", true));
        assertEquals(-1, seats.add("thirdCow", true), "no free non-driver seat left");
        assertNull(seats.driver(), "seat 0 must stay empty: it was never offered to an excluded rider");
    }

    /** A rider not excluded from the driver seat (a player) still boards seat 0 first, as before. */
    @Test
    void notExcludingTheDriverSeatStillFillsSeatZeroFirst() {
        SeatAssignment<String> seats = new SeatAssignment<>(2);
        assertEquals(0, seats.add("alice", false));
        assertEquals("alice", seats.driver());
    }

    @Test
    void anEightSeatRosterBoardsAllEightAndThenRefuses() {
        SeatAssignment<String> seats = new SeatAssignment<>(8);
        for (int i = 0; i < 8; i++) {
            assertEquals(i, seats.add("rider" + i));
        }
        assertEquals(-1, seats.add("oneTooMany"));
        for (boolean occupied : seats.occupied()) {
            assertTrue(occupied);
        }
    }
}
