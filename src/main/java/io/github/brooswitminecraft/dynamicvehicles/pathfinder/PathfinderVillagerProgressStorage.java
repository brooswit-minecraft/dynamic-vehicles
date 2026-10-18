package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

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
 * Server-wide, villager-UUID-keyed persistence for {@link PathfinderVillagerProgress}. Review
 * follow-up: the previous version stored this on the villager entity's own persistent data,
 * which only exists while that entity is actually loaded — but a completed circuit routinely
 * ends multiple 512-block regions away from its own villager, at the first checkpoint, not back
 * at the villager, so "is the source villager currently loaded" is a normal state on completion,
 * not a rare edge case. Storing progress here instead means a completion can always be credited,
 * and a villager (whenever it next loads) just reads whatever this storage already has for its
 * UUID — no dependency on load state in either direction.
 *
 * <p>Review follow-up: this is its own separate {@code SavedData} from {@link
 * PathfinderAttemptStorage} — a save can land the attempt-clear write while the progression
 * write independently fails (or vice versa), so {@code PathfinderTickHandler}'s durable-payout
 * verification reads BOTH files back off disk, not just the attempt one, before treating a
 * completion as durably settled.
 *
 * <p>Review follow-up (round 7): {@code PathfinderTickHandler} must never let the ATTEMPT file's
 * own disk state show this credit as applied (either by clearing the attempt or by persisting
 * {@code progressionCredited=true} on it) before THIS storage's matching increment is itself
 * confirmed durable — otherwise a restart (or an unrelated autosave) landing in between can lose
 * the whole completed run, or settle the attempt as "credited" while the credit itself never
 * actually lands. That still leaves one narrow window open purely in-process (between this
 * storage's own write landing durably and the attempt file's {@code progressionCredited=true}
 * write landing durably) where a crash could cause {@code recordCompletion} to be invoked a
 * second time for the SAME attempt after a restart (reloaded from disk with {@code
 * progressionCredited} still false even though the credit already truly landed) — {@code
 * attemptKey} closes that: the LAST attempt credited per villager is tracked and persisted here,
 * so a second call for that exact same attempt is a safe no-op rather than a double count.
 */
public final class PathfinderVillagerProgressStorage extends SavedData {
    /** Package-visible so {@code PathfinderTickHandler} can read this exact file back from disk to verify a save actually landed. */
    static final String NAME = "dynamicvehicles_pathfinder_villager_progress";

    private final Map<UUID, PathfinderVillagerProgress.State> progress = new HashMap<>();
    private final Map<UUID, String> lastCreditedAttempt = new HashMap<>();

    public static PathfinderVillagerProgressStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PathfinderVillagerProgressStorage::new, PathfinderVillagerProgressStorage::load, null), NAME);
    }

    public PathfinderVillagerProgress.State stateOf(UUID villagerId) {
        return progress.getOrDefault(villagerId, new PathfinderVillagerProgress.State(1, 0));
    }

    /**
     * Applies one completion's worth of progression for a circuit completed AT {@code
     * completedLevel} — ONLY if that still equals the villager's own current level. Review
     * follow-up (a real concurrency exploit): crediting the villager's CURRENT level regardless
     * of which level the completed circuit actually was let many concurrent level-1 attempts
     * (started together before any of them finished) each count toward advancing the villager
     * past level 1 even after it had already advanced from an earlier one of those same
     * completions — twelve concurrent level-1 completions could unlock level 5 at a threshold of
     * 3, without a single level 2-5 circuit ever being run. A completion for a level the villager
     * has already moved past (or, in principle, not yet reached) is "stale": it still counts as
     * a legitimately finished circuit (the caller still grants its reward), it just does not
     * advance progression any further.
     */
    public void recordCompletion(UUID villagerId, int completedLevel, int completionsToAdvance) {
        recordCompletion(villagerId, completedLevel, completionsToAdvance, UUID.randomUUID().toString());
    }

    /**
     * Same as {@link #recordCompletion(UUID, int, int)}, but idempotent against being invoked
     * twice for the SAME real attempt (identified by {@code attemptKey}, stable across a crash -
     * see the class javadoc). Only the real production completion path ({@code
     * PathfinderAttempts#recordVillagerCompletion}) has a durable, stable key to pass here; the
     * plain 3-arg overload (used directly by tests simulating many distinct concurrent
     * completions) generates a fresh, never-matching key each call, preserving its exact prior
     * always-applies behavior.
     */
    public void recordCompletion(UUID villagerId, int completedLevel, int completionsToAdvance, String attemptKey) {
        PathfinderVillagerProgress.State current = stateOf(villagerId);
        if (completedLevel != current.level()) {
            return;
        }
        if (attemptKey.equals(lastCreditedAttempt.get(villagerId))) {
            return;
        }
        progress.put(villagerId, PathfinderVillagerProgress.onCompletion(current, completionsToAdvance));
        lastCreditedAttempt.put(villagerId, attemptKey);
        setDirty();
    }

    /**
     * Package-visible so {@code PathfinderTickHandler} can force a retry of this write on every
     * payout attempt, not just the first. Review follow-up (a real bug found while adding fault
     * coverage for this exact path): {@code SavedData#save} silently clears the dirty flag on a
     * FAILED write too (see the class javadoc), and {@link #recordCompletion} only runs once per
     * attempt (guarded by {@code progressionCredited}) - so without this, a failed write here
     * would never be retried on subsequent ticks even though the attempt file's own clear keeps
     * retrying fine (its dirty flag is naturally re-set every retry by {@code clear}/{@code
     * update}). Content is unchanged; this only re-queues the existing in-memory state for save.
     */
    void markDirtyForRetry() {
        setDirty();
    }

    /** Package-visible for direct round-trip testing and for {@code PathfinderTickHandler}'s on-disk verification read. */
    static PathfinderVillagerProgressStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PathfinderVillagerProgressStorage storage = new PathfinderVillagerProgressStorage();
        ListTag list = tag.getList("Villagers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID villagerId = new UUID(entry.getLong("VillagerIdHi"), entry.getLong("VillagerIdLo"));
            storage.progress.put(villagerId, new PathfinderVillagerProgress.State(entry.getInt("Level"), entry.getInt("CompletionsAtLevel")));
        }
        ListTag creditedList = tag.getList("LastCreditedAttempts", Tag.TAG_COMPOUND);
        for (int i = 0; i < creditedList.size(); i++) {
            CompoundTag entry = creditedList.getCompound(i);
            UUID villagerId = new UUID(entry.getLong("VillagerIdHi"), entry.getLong("VillagerIdLo"));
            storage.lastCreditedAttempt.put(villagerId, entry.getString("AttemptKey"));
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PathfinderVillagerProgress.State> entry : progress.entrySet()) {
            CompoundTag villagerTag = new CompoundTag();
            villagerTag.putLong("VillagerIdHi", entry.getKey().getMostSignificantBits());
            villagerTag.putLong("VillagerIdLo", entry.getKey().getLeastSignificantBits());
            villagerTag.putInt("Level", entry.getValue().level());
            villagerTag.putInt("CompletionsAtLevel", entry.getValue().completionsAtLevel());
            list.add(villagerTag);
        }
        tag.put("Villagers", list);
        ListTag creditedList = new ListTag();
        for (Map.Entry<UUID, String> entry : lastCreditedAttempt.entrySet()) {
            CompoundTag villagerTag = new CompoundTag();
            villagerTag.putLong("VillagerIdHi", entry.getKey().getMostSignificantBits());
            villagerTag.putLong("VillagerIdLo", entry.getKey().getLeastSignificantBits());
            villagerTag.putString("AttemptKey", entry.getValue());
            creditedList.add(villagerTag);
        }
        tag.put("LastCreditedAttempts", creditedList);
        return tag;
    }
}
