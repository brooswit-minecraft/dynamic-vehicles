package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Persistence seam for passenger seats; vanilla persists the passenger entities themselves recursively. */
class SeatPersistenceTest {

    @Test
    void savedAssignmentsRestoreEachPassengerToItsOriginalSeatNotPassengerListOrder() {
        UUID driver = UUID.randomUUID();
        UUID firstMob = UUID.randomUUID();
        UUID secondMob = UUID.randomUUID();
        Map<UUID, Integer> restored = SeatAssignments.validAssignments(List.of(
                new SeatAssignments.Assignment(driver, 0),
                new SeatAssignments.Assignment(firstMob, 3),
                new SeatAssignments.Assignment(secondMob, 1)), 4);
        assertEquals(0, restored.get(driver));
        assertEquals(3, restored.get(firstMob));
        assertEquals(1, restored.get(secondMob));
    }

    @Test
    void savedSeatCanBeRestoredEvenWhenPassengersReloadInADifferentOrder() {
        Object driver = new Object();
        Object passengerInThirdSeat = new Object();
        Object passengerInFirstNonDriverSeat = new Object();
        SeatAssignment<Object> seats = new SeatAssignment<>(4);

        assertTrue(seats.restore(driver, 0));
        assertTrue(seats.restore(passengerInThirdSeat, 3));
        assertTrue(seats.restore(passengerInFirstNonDriverSeat, 1));
        assertEquals(driver, seats.at(0));
        assertEquals(passengerInFirstNonDriverSeat, seats.at(1));
        assertEquals(passengerInThirdSeat, seats.at(3));
    }

    @Test
    void malformedSeatEntriesAreIgnoredWithoutBlockingPassengerLoading() {
        UUID passenger = UUID.randomUUID();
        assertFalse(SeatAssignments.validAssignments(
                List.of(new SeatAssignments.Assignment(passenger, 9)), 4).containsKey(passenger));
    }
}
