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
 * <p>Per-value semantics for an attempt already in progress (corrected after an earlier,
 * inaccurate blanket "everything is snapshotted" claim):
 * <ul>
 *   <li>{@code ticksPerLeg} IS genuinely snapshotted: {@code PathfinderAttempts#tryStart} reads
 *       it once into that attempt's own {@code PathfinderAttemptRecord#timeLimitTicks()}, so a
 *       later config change never moves an in-progress deadline.
 *   <li>{@code rewardBaseEmeralds}/{@code rewardPerLevelEmeralds} are read at the moment of
 *       payout ({@code PathfinderAttempts#grantReward}), not at attempt start — a level can't
 *       change mid-attempt, so this just means a config change takes effect on the very next
 *       completion, which is intended, not a bug.
 *   <li>{@code completionsToAdvance} is read at the moment a completion is recorded, same
 *       reasoning as the reward values.
 * </ul>
 */
public final class PathfinderServerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue TICKS_PER_LEG;
    public static final ModConfigSpec.IntValue REWARD_BASE_EMERALDS;
    public static final ModConfigSpec.IntValue REWARD_PER_LEVEL_EMERALDS;
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
