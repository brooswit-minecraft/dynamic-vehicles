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
 * <p>Review follow-up on reward-payout crash safety: {@link #clear} only marks this {@code
 * SavedData} dirty, same as every other {@code SavedData} in this codebase ({@code
 * delivery.DeliveryContractStorage} included) — {@code setDirty()} alone schedules a write for
 * the next periodic autosave/shutdown, it does not flush to disk. {@code
 * PathfinderTickHandler#onFinished}/{@code #onExpired} additionally call {@code
 * MinecraftServer#overworld()}{@code .getDataStorage().save()} immediately after {@link #clear},
 * AND THEN block on {@code IOUtilities#waitUntilIOWorkerComplete()} before granting the reward —
 * a real persistence barrier, not merely a narrowed window: by the time the reward is granted,
 * the cleared attempt is actually on disk (NeoForge's own {@code IOUtilities#atomicWrite} writes
 * to a temp file, {@code force(true)}s it, then renames it into place, so the write itself can't
 * leave a half-written file either). A crash after that point cannot reload the old record and
 * pay again. See {@code PathfinderTickHandler#forceSave} for exactly which calls do this.
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
            list.add(entry);
        }
        tag.put("Attempts", list);
        return tag;
    }
}
