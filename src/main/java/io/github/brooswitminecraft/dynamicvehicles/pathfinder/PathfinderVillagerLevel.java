package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.world.entity.npc.Villager;

/**
 * Stores a Pathfinder villager's own progression ({@link PathfinderVillagerProgress}) on its
 * persistent data — the same {@code villager.getPersistentData()} mechanism {@code
 * delivery.BuilderCampfireHandler}/{@code BuilderSettlementService} already use for villager-
 * attached state — so it survives save/reload with the entity automatically, with no separate
 * persistence code needed. Deliberately NOT {@code VillagerData}: see {@link
 * PathfinderVillagerProgress}'s javadoc for why trusting that would be an anti-bypass hole.
 */
public final class PathfinderVillagerLevel {
    private static final String LEVEL_TAG = "dynamicvehicles.pathfinderLevel";
    private static final String COMPLETIONS_TAG = "dynamicvehicles.pathfinderLevelCompletions";

    private PathfinderVillagerLevel() {
    }

    public static PathfinderLevel levelOf(Villager villager) {
        return PathfinderLevel.values()[state(villager).level() - 1];
    }

    public static PathfinderVillagerProgress.State state(Villager villager) {
        var data = villager.getPersistentData();
        int level = data.contains(LEVEL_TAG) ? data.getInt(LEVEL_TAG) : 1;
        int completions = data.getInt(COMPLETIONS_TAG);
        return new PathfinderVillagerProgress.State(Math.max(1, Math.min(5, level)), Math.max(0, completions));
    }

    /** Records one successful completion at this villager, using the posted default threshold. */
    public static void recordCompletion(Villager villager) {
        recordCompletion(villager, PathfinderVillagerProgress.DEFAULT_COMPLETIONS_TO_ADVANCE);
    }

    /** Records one successful completion at this villager, advancing its level per {@link PathfinderVillagerProgress}. */
    public static void recordCompletion(Villager villager, int completionsToAdvance) {
        PathfinderVillagerProgress.State next = PathfinderVillagerProgress.onCompletion(state(villager), completionsToAdvance);
        var data = villager.getPersistentData();
        data.putInt(LEVEL_TAG, next.level());
        data.putInt(COMPLETIONS_TAG, next.completionsAtLevel());
    }
}
