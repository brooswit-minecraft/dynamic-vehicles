package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /** Writes every occupied seat by passenger UUID, independent of vanilla's passenger-list order. */
    public static void write(CompoundTag vehicleTag, List<SeatAssignments.Assignment> assignments) {
        ListTag savedSeats = new ListTag();
        for (SeatAssignments.Assignment assignment : assignments) {
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
        List<SeatAssignments.Assignment> savedAssignments = new ArrayList<>();
        ListTag savedSeats = vehicleTag.getList(TAG_SEATS, Tag.TAG_COMPOUND);
        for (int i = 0; i < savedSeats.size(); i++) {
            CompoundTag savedSeat = savedSeats.getCompound(i);
            if (!savedSeat.hasUUID(TAG_PASSENGER)) {
                continue;
            }
            UUID passenger = savedSeat.getUUID(TAG_PASSENGER);
            savedAssignments.add(new SeatAssignments.Assignment(passenger, savedSeat.getInt(TAG_SEAT)));
        }
        return SeatAssignments.validAssignments(savedAssignments, seatCount);
    }
}
