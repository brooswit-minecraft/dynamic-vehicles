package io.github.brooswitminecraft.dynamicvehicles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** NBT codec for the otherwise-runtime-only passenger-to-seat relationship. */
public final class SeatPersistence {
    static final String TAG_SEATS = "DynamicVehiclesSeats";
    private static final String TAG_PASSENGER = "Passenger";
    private static final String TAG_SEAT = "Seat";

    private SeatPersistence() {}

    public record Assignment(UUID passenger, int seat) {}

    /** Writes every occupied seat by passenger UUID, independent of vanilla's passenger-list order. */
    public static void write(CompoundTag vehicleTag, List<Assignment> assignments) {
        ListTag savedSeats = new ListTag();
        for (Assignment assignment : assignments) {
            CompoundTag savedSeat = new CompoundTag();
            savedSeat.putUUID(TAG_PASSENGER, assignment.passenger());
            savedSeat.putInt(TAG_SEAT, assignment.seat());
            savedSeats.add(savedSeat);
        }
        vehicleTag.put(TAG_SEATS, savedSeats);
    }

    /**
     * Reads only valid, unique assignments. Corrupt/outdated entries are ignored so a vehicle can still
     * load and use normal first-free-seat placement for that rider.
     */
    public static Map<UUID, Integer> read(CompoundTag vehicleTag, int seatCount) {
        Map<UUID, Integer> assignments = new HashMap<>();
        Set<Integer> occupiedSeats = new HashSet<>();
        ListTag savedSeats = vehicleTag.getList(TAG_SEATS, Tag.TAG_COMPOUND);
        for (int i = 0; i < savedSeats.size(); i++) {
            CompoundTag savedSeat = savedSeats.getCompound(i);
            if (!savedSeat.hasUUID(TAG_PASSENGER)) {
                continue;
            }
            UUID passenger = savedSeat.getUUID(TAG_PASSENGER);
            int seat = savedSeat.getInt(TAG_SEAT);
            if (seat >= 0 && seat < seatCount && !assignments.containsKey(passenger) && occupiedSeats.add(seat)) {
                assignments.put(passenger, seat);
            }
        }
        return assignments;
    }
}
