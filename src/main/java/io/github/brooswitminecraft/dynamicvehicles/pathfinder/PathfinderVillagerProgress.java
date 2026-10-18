package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * Pure, Minecraft-free progression rule for a Pathfinder villager's own unlocked level: starts
 * at 1 and advances one level for every {@link #COMPLETIONS_TO_ADVANCE} successful circuits
 * completed AT that villager, independent of vanilla's trade-based {@code VillagerData} level.
 *
 * <p>Review follow-up (MINECRAFT-379, independent review): using {@code
 * villager.getVillagerData().getLevel()} directly as the Pathfinder level was a real anti-bypass
 * hole — {@code VillagerData#setProfession} preserves the existing numeric level across a
 * profession change (confirmed against vanilla source), so a villager trade-leveled to 5 under
 * any OTHER profession and then converted to Pathfinder would instantly unlock level 5. This
 * type is the server-controlled progression Pathfinder completions alone can advance; see {@link
 * PathfinderVillagerLevel} for where it's actually stored (the villager's own persistent data,
 * not {@code VillagerData}).
 */
public final class PathfinderVillagerProgress {
    /** The ticket's posted recommended default; the real, tunable value is {@code PathfinderServerConfig.COMPLETIONS_TO_ADVANCE}. */
    public static final int DEFAULT_COMPLETIONS_TO_ADVANCE = 3;

    private PathfinderVillagerProgress() {
    }

    public record State(int level, int completionsAtLevel) {
        public State {
            if (level < 1 || level > 5) {
                throw new IllegalArgumentException("level must be 1-5: " + level);
            }
            if (completionsAtLevel < 0) {
                throw new IllegalArgumentException("completionsAtLevel must be >= 0: " + completionsAtLevel);
            }
        }
    }

    /** The state after one more successful completion at {@code state.level()}, using the posted default threshold. */
    public static State onCompletion(State state) {
        return onCompletion(state, DEFAULT_COMPLETIONS_TO_ADVANCE);
    }

    public static State onCompletion(State state, int completionsToAdvance) {
        int completions = state.completionsAtLevel() + 1;
        if (state.level() < 5 && completions >= completionsToAdvance) {
            return new State(state.level() + 1, 0);
        }
        return new State(state.level(), completions);
    }
}
