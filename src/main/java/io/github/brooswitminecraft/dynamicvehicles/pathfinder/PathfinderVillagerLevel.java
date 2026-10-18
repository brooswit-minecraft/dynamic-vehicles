package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.UUID;

/**
 * A Pathfinder villager's own progression ({@link PathfinderVillagerProgress}), backed by {@link
 * PathfinderVillagerProgressStorage} (server-wide, keyed by villager UUID) rather than the
 * villager entity's own persistent data — review follow-up: a completion routinely happens with
 * its source villager long unloaded (circuits end regions away, at the first checkpoint, not
 * back at the villager), so crediting progress only when the entity is loaded silently dropped
 * it in the normal case, not just a rare edge case.
 *
 * <p>Deliberately NOT {@code VillagerData}: see {@link PathfinderVillagerProgress}'s javadoc for
 * why trusting that would be an anti-bypass hole.
 */
public final class PathfinderVillagerLevel {
    private PathfinderVillagerLevel() {
    }

    /** Convenience for callers that already have a loaded, in-world villager (e.g. the interaction handler). */
    public static PathfinderLevel levelOf(ServerLevel level, Villager villager) {
        return levelOf(level.getServer(), villager.getUUID());
    }

    public static PathfinderLevel levelOf(MinecraftServer server, UUID villagerId) {
        return PathfinderLevel.values()[stateOf(server, villagerId).level() - 1];
    }

    public static PathfinderVillagerProgress.State stateOf(MinecraftServer server, UUID villagerId) {
        return PathfinderVillagerProgressStorage.of(server).stateOf(villagerId);
    }

    /**
     * Records one successful completion for this villager, independent of whether the entity is
     * currently loaded. {@code completedLevel} is the level the FINISHED circuit was actually
     * for, not the villager's current level — see {@link PathfinderVillagerProgressStorage#recordCompletion}'s
     * javadoc for the concurrency exploit that distinction closes.
     */
    public static void recordCompletion(MinecraftServer server, UUID villagerId, int completedLevel, int completionsToAdvance) {
        PathfinderVillagerProgressStorage.of(server).recordCompletion(villagerId, completedLevel, completionsToAdvance);
    }
}
