package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config for Pathfinder's remaining balance decisions (MINECRAFT-379 review follow-up:
 * "Ticket-required configurable defaults are plain constants ... Move timer, rewards,
 * profession XP threshold and reach radius to validated server config"). Every value here was
 * posted as a concrete recommended default on the ticket; this slice only makes them
 * server-admin-tunable without a rebuild, the same role {@code DispatcherOfferConfig} plays for
 * the Dispatcher.
 *
 * <p>Changing a value here never affects an attempt already in progress: {@code
 * PathfinderAttempts#tryStart} snapshots the current {@code ticksPerLeg}/{@code
 * reachRadius} into that attempt's own {@code PathfinderAttemptRecord}/generated {@code
 * PathfinderRoute} at start time, so a later config change only takes effect for attempts
 * started after it.
 */
public final class PathfinderServerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue TICKS_PER_LEG;
    public static final ModConfigSpec.IntValue REWARD_BASE_EMERALDS;
    public static final ModConfigSpec.IntValue REWARD_PER_LEVEL_EMERALDS;
    public static final ModConfigSpec.DoubleValue REACH_RADIUS;
    public static final ModConfigSpec.IntValue COMPLETIONS_TO_ADVANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        TICKS_PER_LEG = builder
                .comment("Game ticks (20 ticks = 1 second) allotted per leg of a circuit - one leg per "
                        + "checkpoint, plus one for the closing leg back to the first checkpoint. Posted "
                        + "recommended default: 90 seconds/leg.")
                .defineInRange("ticksPerLeg", 1800, 20, Integer.MAX_VALUE);
        REWARD_BASE_EMERALDS = builder
                .comment("Emerald reward for completing a level-1 circuit.")
                .defineInRange("rewardBaseEmeralds", 4, 0, 1000);
        REWARD_PER_LEVEL_EMERALDS = builder
                .comment("Additional emerald reward per level above 1 (so level N pays rewardBaseEmeralds + "
                        + "(N-1) * this).")
                .defineInRange("rewardPerLevelEmeralds", 16, 0, 1000);
        REACH_RADIUS = builder
                .comment("How close (blocks) a player must be to a checkpoint's own position, in its own "
                        + "dimension, to count as having reached it.")
                .defineInRange("reachRadius", 3.0, 0.5, 100.0);
        COMPLETIONS_TO_ADVANCE = builder
                .comment("Successful circuit completions AT a given Pathfinder villager, at its current "
                        + "Pathfinder level, required before that villager's level advances (see "
                        + "PathfinderVillagerLevel - this is the villager's own progression, independent of "
                        + "its vanilla trade-based VillagerData level).")
                .defineInRange("completionsToAdvance", 3, 1, 1000);

        SPEC = builder.build();
    }

    private PathfinderServerConfig() {
    }
}
