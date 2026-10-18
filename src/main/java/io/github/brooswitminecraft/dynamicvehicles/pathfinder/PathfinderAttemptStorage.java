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
 *
 * <p>Review follow-up on reward-payout crash safety, round 2: {@code
 * IOUtilities#waitUntilIOWorkerComplete()} (round 1's fix) blocks until the queued write TASK
 * finishes, but NeoForge's own {@code SavedData#save(File, ...)} catches any {@code IOException}
 * INSIDE that queued task, logs it, and still clears the dirty flag — so joining the worker
 * proves the write was ATTEMPTED, not that it actually landed on disk; a failed write is silently
 * swallowed from the caller's perspective. {@code PathfinderTickHandler} closes that gap by
 * reading the file straight back off disk afterward ({@code
 * DimensionDataStorage#readTagFromDisk}, bypassing the in-memory cache entirely) and confirming
 * the cleared attempt is actually absent there before granting any reward; if that confirmation
 * fails, the in-memory record is put back (via {@link #update}) so the next tick's evaluation
 * retries the whole clear/save/verify step — see {@code PathfinderTickHandler#attemptDurablePayout}.
 * {@link PathfinderAttemptRecord#progressionCredited()} exists so that retry re-attempts only the
 * clear/verify/pay step, never re-credits villager progression a second time.
 */
public final class PathfinderAttemptStorage extends SavedData {
    /** Package-visible so {@code PathfinderTickHandler} can read this exact file back from disk to verify a save actually landed. */
    static final String NAME = "dynamicvehicles_pathfinder_attempts";

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
     * this BEFORE granting any reward/sending any message — see the class javadoc for the forced
     * save that should accompany this call on the real completion/expiry path.
     */
    public void clear(UUID playerId) {
        if (attempts.remove(playerId) != null) {
            setDirty();
        }
    }

    /** Package-visible for direct round-trip unit testing without a registry/server. */
    static PathfinderAttemptStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PathfinderAttemptStorage storage = new PathfinderAttemptStorage();
        ListTag list = tag.getList("Attempts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID playerId = new UUID(entry.getLong("PlayerIdHi"), entry.getLong("PlayerIdLo"));
            PathfinderAttemptRecord record = new PathfinderAttemptRecord(
                    playerId,
                    new UUID(entry.getLong("VillagerIdHi"), entry.getLong("VillagerIdLo")),
                    entry.getString("Dimension"),
                    new PathfinderRegion(entry.getInt("OriginX"), entry.getInt("OriginZ")),
                    PathfinderLevel.values()[entry.getInt("Level") - 1],
                    entry.getLong("StartTick"),
                    entry.getLong("TimeLimitTicks"),
                    entry.getInt("ReachedCount"),
                    entry.getBoolean("Finished"),
                    entry.getBoolean("Expired"),
                    entry.getBoolean("ProgressionCredited"));
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
            entry.putLong("VillagerIdHi", record.villagerId().getMostSignificantBits());
            entry.putLong("VillagerIdLo", record.villagerId().getLeastSignificantBits());
            entry.putString("Dimension", record.dimensionId());
            entry.putInt("OriginX", record.origin().x());
            entry.putInt("OriginZ", record.origin().z());
            entry.putInt("Level", record.level().level());
            entry.putLong("StartTick", record.startTick());
            entry.putLong("TimeLimitTicks", record.timeLimitTicks());
            entry.putInt("ReachedCount", record.reachedCount());
            entry.putBoolean("Finished", record.finished());
            entry.putBoolean("Expired", record.expired());
            entry.putBoolean("ProgressionCredited", record.progressionCredited());
            list.add(entry);
        }
        tag.put("Attempts", list);
        return tag;
    }
}
