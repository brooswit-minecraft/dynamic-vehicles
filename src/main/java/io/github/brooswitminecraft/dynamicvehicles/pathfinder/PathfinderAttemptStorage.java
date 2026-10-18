package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-wide persistence for the one-active-attempt-per-player invariant
 * (MINECRAFT-379's multiplayer-isolation requirement, and the review
 * follow-up asking for a correctly-persisted attempt across
 * disconnect/restart). Attached to the overworld's data storage, same as
 * {@code delivery.DeliveryContractStorage}, because an attempt is a
 * per-player, server-wide concept, not tied to whichever dimension the
 * player happens to be in right now.
 */
public final class PathfinderAttemptStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_pathfinder_attempts";

    private final Map<UUID, PathfinderAttemptRecord> attempts = new HashMap<>();

    public static PathfinderAttemptStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PathfinderAttemptStorage::new, PathfinderAttemptStorage::load, null), NAME);
    }

    public Optional<PathfinderAttemptRecord> active(UUID playerId) {
        return Optional.ofNullable(attempts.get(playerId));
    }

    /** @return false, with no change, if {@code playerId} already has an active attempt. */
    public boolean tryStart(PathfinderAttemptRecord record) {
        if (attempts.containsKey(record.playerId())) {
            return false;
        }
        attempts.put(record.playerId(), record);
        setDirty();
        return true;
    }

    public void update(PathfinderAttemptRecord record) {
        attempts.put(record.playerId(), record);
        setDirty();
    }

    /**
     * Removes the attempt. Callers finishing an attempt (reward) or failing it (expiry) must call
     * this BEFORE granting any reward/sending any message, exactly as {@code
     * delivery.ContractTickHandler} does with {@code storage.setDirty()} before the payout — so a
     * crash between the two leaves, at worst, an unpaid completion to resolve by hand, never a
     * re-claimable one that could be reloaded and finished again for a duplicate reward.
     */
    public void clear(UUID playerId) {
        if (attempts.remove(playerId) != null) {
            setDirty();
        }
    }

    private static PathfinderAttemptStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PathfinderAttemptStorage storage = new PathfinderAttemptStorage();
        ListTag list = tag.getList("Attempts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID playerId = new UUID(entry.getLong("PlayerIdHi"), entry.getLong("PlayerIdLo"));
            PathfinderAttemptRecord record = new PathfinderAttemptRecord(
                    playerId,
                    entry.getString("Dimension"),
                    new PathfinderRegion(entry.getInt("OriginX"), entry.getInt("OriginZ")),
                    PathfinderLevel.values()[entry.getInt("Level") - 1],
                    entry.getLong("StartTick"),
                    entry.getLong("TimeLimitTicks"),
                    entry.getInt("ReachedCount"),
                    entry.getBoolean("Finished"),
                    entry.getBoolean("Expired"));
            storage.attempts.put(playerId, record);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (PathfinderAttemptRecord record : attempts.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("PlayerIdHi", record.playerId().getMostSignificantBits());
            entry.putLong("PlayerIdLo", record.playerId().getLeastSignificantBits());
            entry.putString("Dimension", record.dimensionId());
            entry.putInt("OriginX", record.origin().x());
            entry.putInt("OriginZ", record.origin().z());
            entry.putInt("Level", record.level().level());
            entry.putLong("StartTick", record.startTick());
            entry.putLong("TimeLimitTicks", record.timeLimitTicks());
            entry.putInt("ReachedCount", record.reachedCount());
            entry.putBoolean("Finished", record.finished());
            entry.putBoolean("Expired", record.expired());
            list.add(entry);
        }
        tag.put("Attempts", list);
        return tag;
    }
}
