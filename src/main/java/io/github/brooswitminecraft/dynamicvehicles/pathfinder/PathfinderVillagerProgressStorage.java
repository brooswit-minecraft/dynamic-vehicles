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
 */
public final class PathfinderVillagerProgressStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_pathfinder_villager_progress";

    private final Map<UUID, PathfinderVillagerProgress.State> progress = new HashMap<>();

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
        PathfinderVillagerProgress.State current = stateOf(villagerId);
        if (completedLevel != current.level()) {
            return;
        }
        progress.put(villagerId, PathfinderVillagerProgress.onCompletion(current, completionsToAdvance));
        setDirty();
    }

    private static PathfinderVillagerProgressStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        PathfinderVillagerProgressStorage storage = new PathfinderVillagerProgressStorage();
        ListTag list = tag.getList("Villagers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID villagerId = new UUID(entry.getLong("VillagerIdHi"), entry.getLong("VillagerIdLo"));
            storage.progress.put(villagerId, new PathfinderVillagerProgress.State(entry.getInt("Level"), entry.getInt("CompletionsAtLevel")));
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
        return tag;
    }
}
