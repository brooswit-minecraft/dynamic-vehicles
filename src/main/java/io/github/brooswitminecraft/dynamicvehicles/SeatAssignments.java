package io.github.brooswitminecraft.dynamicvehicles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Minecraft-free validation of saved passenger-to-seat assignments. */
public final class SeatAssignments {
    private SeatAssignments() {}

    public record Assignment(UUID passenger, int seat) {}

    /**
     * Keeps only valid, unique assignments. Invalid or duplicated entries fall back to normal boarding
     * when their passenger loads, rather than preventing the vehicle from loading.
     */
    public static Map<UUID, Integer> validAssignments(List<Assignment> savedAssignments, int seatCount) {
        Map<UUID, Integer> assignments = new HashMap<>();
        Set<Integer> occupiedSeats = new HashSet<>();
        for (Assignment assignment : savedAssignments) {
            int seat = assignment.seat();
            if (seat >= 0 && seat < seatCount && !assignments.containsKey(assignment.passenger())
                    && occupiedSeats.add(seat)) {
                assignments.put(assignment.passenger(), seat);
            }
        }
        return assignments;
    }
}
