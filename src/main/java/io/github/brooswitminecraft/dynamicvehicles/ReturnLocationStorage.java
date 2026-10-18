package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-wide, per-player persistence (MINECRAFT-397) of each player's
 * departure location for the flat-dimension there-and-back command: where to
 * send a player back to when they leave the flat dimension. Mirrors
 * {@link ReturnLocationCodec}'s field list and order against real NBT, the
 * same mirroring pattern {@code DeliveryContractStorage} uses against
 * {@code DeliveryContractCodec}.
 *
 * <p>Attached to the overworld's own data storage (not any arbitrary
 * level's) because a return location is a per-player, server-wide concept,
 * not tied to the dimension the player happens to be in.
 */
final class ReturnLocationStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_return_locations";

    private final Map<UUID, ReturnLocation> locations = new HashMap<>();

    static ReturnLocationStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(ReturnLocationStorage::new, ReturnLocationStorage::load, null), NAME);
    }

    ReturnLocation get(UUID playerId) {
        return locations.get(playerId);
    }

    void put(UUID playerId, ReturnLocation location) {
        locations.put(playerId, location);
        setDirty();
    }

    void remove(UUID playerId) {
        if (locations.remove(playerId) != null) {
            setDirty();
        }
    }

    private static ReturnLocationStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        ReturnLocationStorage storage = new ReturnLocationStorage();
        ListTag list = tag.getList("Locations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID playerId = new UUID(entry.getLong("PlayerIdHi"), entry.getLong("PlayerIdLo"));
            ReturnLocation location = new ReturnLocation(
                    entry.getString("Dimension"),
                    entry.getDouble("X"),
                    entry.getDouble("Y"),
                    entry.getDouble("Z"),
                    entry.getFloat("Yaw"),
                    entry.getFloat("Pitch"));
            storage.locations.put(playerId, location);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ReturnLocation> mapEntry : locations.entrySet()) {
            UUID playerId = mapEntry.getKey();
            ReturnLocation location = mapEntry.getValue();
            CompoundTag entry = new CompoundTag();
            entry.putLong("PlayerIdHi", playerId.getMostSignificantBits());
            entry.putLong("PlayerIdLo", playerId.getLeastSignificantBits());
            entry.putString("Dimension", location.dimension());
            entry.putDouble("X", location.x());
            entry.putDouble("Y", location.y());
            entry.putDouble("Z", location.z());
            entry.putFloat("Yaw", location.yaw());
            entry.putFloat("Pitch", location.pitch());
            list.add(entry);
        }
        tag.put("Locations", list);
        return tag;
    }
}
