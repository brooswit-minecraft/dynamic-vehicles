package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

import io.github.brooswitminecraft.dynamicvehicles.delivery.OfferConfig;

/**
 * Server config for Dispatcher destination-offer generation (MINECRAFT-107/120,
 * AC6). Every value here is a placeholder: this slice only has to make the
 * numbers tunable, not correct — MINECRAFT-113 owns picking the final ones.
 * Do not tune these to compensate for the known uncapped-acquisition issue;
 * that is MINECRAFT-113's job too.
 */
public final class DispatcherOfferConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue NOVICE_MAX_RING;
    public static final ModConfigSpec.IntValue APPRENTICE_MAX_RING;
    public static final ModConfigSpec.IntValue JOURNEYMAN_MAX_RING;
    public static final ModConfigSpec.IntValue EXPERT_MAX_RING;
    public static final ModConfigSpec.IntValue MASTER_MAX_RING;

    public static final ModConfigSpec.IntValue NOVICE_SLOTS;
    public static final ModConfigSpec.IntValue APPRENTICE_SLOTS;
    public static final ModConfigSpec.IntValue JOURNEYMAN_SLOTS;
    public static final ModConfigSpec.IntValue EXPERT_SLOTS;
    public static final ModConfigSpec.IntValue MASTER_SLOTS;

    public static final ModConfigSpec.DoubleValue DANGER_MIN;
    public static final ModConfigSpec.DoubleValue DANGER_MAX;
    public static final ModConfigSpec.DoubleValue REWARD_PER_BLOCK;
    public static final ModConfigSpec.DoubleValue MIN_REWARD_DANGER_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MAX_REWARD_DANGER_MULTIPLIER;
    public static final ModConfigSpec.IntValue TIME_ALLOWANCE_BASE_TICKS;
    public static final ModConfigSpec.DoubleValue TIME_ALLOWANCE_TICKS_PER_BLOCK;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        NOVICE_MAX_RING = builder
                .comment("Furthest destination ring (inclusive; each ring is one more village structure "
                        + "placement-region step out) a Novice Dispatcher's offers may reach.")
                .defineInRange("noviceMaxRing", 2, 1, 1000);
        APPRENTICE_MAX_RING = builder
                .comment("Furthest destination ring an Apprentice Dispatcher's offers may reach.")
                .defineInRange("apprenticeMaxRing", 4, 1, 1000);
        JOURNEYMAN_MAX_RING = builder
                .comment("Furthest destination ring a Journeyman Dispatcher's offers may reach.")
                .defineInRange("journeymanMaxRing", 6, 1, 1000);
        EXPERT_MAX_RING = builder
                .comment("Furthest destination ring an Expert Dispatcher's offers may reach.")
                .defineInRange("expertMaxRing", 10, 1, 1000);
        MASTER_MAX_RING = builder
                .comment("Furthest destination ring a Master Dispatcher's offers may reach.")
                .defineInRange("masterMaxRing", 16, 1, 1000);

        NOVICE_SLOTS = builder
                .comment("How many offer slots a Novice Dispatcher fills.")
                .defineInRange("noviceSlots", 1, 1, 50);
        APPRENTICE_SLOTS = builder
                .comment("How many offer slots an Apprentice Dispatcher fills.")
                .defineInRange("apprenticeSlots", 2, 1, 50);
        JOURNEYMAN_SLOTS = builder
                .comment("How many offer slots a Journeyman Dispatcher fills.")
                .defineInRange("journeymanSlots", 3, 1, 50);
        EXPERT_SLOTS = builder
                .comment("How many offer slots an Expert Dispatcher fills.")
                .defineInRange("expertSlots", 4, 1, 50);
        MASTER_SLOTS = builder
                .comment("How many offer slots a Master Dispatcher fills.")
                .defineInRange("masterSlots", 5, 1, 50);

        DANGER_MIN = builder
                .comment("Minimum rolled danger value (inclusive), on a 0..1 scale. Rolled per contract from the "
                        + "destination alone - never a function of distance.")
                .defineInRange("dangerMin", 0.0, 0.0, 1.0);
        DANGER_MAX = builder
                .comment("Maximum rolled danger value (exclusive), on a 0..1 scale. Must be greater than dangerMin.")
                .defineInRange("dangerMax", 1.0, 0.0, 1.0);
        REWARD_PER_BLOCK = builder
                .comment("Reward units granted per block of approximate distance, before the danger multiplier.")
                .defineInRange("rewardPerBlock", 0.2, 0.0, 1000.0);
        MIN_REWARD_DANGER_MULTIPLIER = builder
                .comment("Reward multiplier applied when a contract's rolled danger == dangerMin.")
                .defineInRange("minRewardDangerMultiplier", 1.0, 0.0, 100.0);
        MAX_REWARD_DANGER_MULTIPLIER = builder
                .comment("Reward multiplier applied when a contract's rolled danger == dangerMax. Must be >= "
                        + "minRewardDangerMultiplier.")
                .defineInRange("maxRewardDangerMultiplier", 3.0, 0.0, 100.0);
        TIME_ALLOWANCE_BASE_TICKS = builder
                .comment("Fixed component of a contract's deadline, in game ticks (20 ticks = 1 second), granted "
                        + "regardless of distance.")
                .defineInRange("timeAllowanceBaseTicks", 6000, 0, Integer.MAX_VALUE);
        TIME_ALLOWANCE_TICKS_PER_BLOCK = builder
                .comment("Additional deadline ticks granted per block of approximate distance.")
                .defineInRange("timeAllowanceTicksPerBlock", 4.0, 0.0, 1000.0);

        SPEC = builder.build();
    }

    private DispatcherOfferConfig() {}

    /** Snapshots the current config values into a plain, Minecraft-free {@link OfferConfig}. */
    public static OfferConfig toOfferConfig() {
        return new OfferConfig(
                new int[] {0, NOVICE_MAX_RING.get(), APPRENTICE_MAX_RING.get(), JOURNEYMAN_MAX_RING.get(), EXPERT_MAX_RING.get(), MASTER_MAX_RING.get()},
                new int[] {0, NOVICE_SLOTS.get(), APPRENTICE_SLOTS.get(), JOURNEYMAN_SLOTS.get(), EXPERT_SLOTS.get(), MASTER_SLOTS.get()},
                DANGER_MIN.get(),
                DANGER_MAX.get(),
                REWARD_PER_BLOCK.get(),
                MIN_REWARD_DANGER_MULTIPLIER.get(),
                MAX_REWARD_DANGER_MULTIPLIER.get(),
                TIME_ALLOWANCE_BASE_TICKS.get(),
                TIME_ALLOWANCE_TICKS_PER_BLOCK.get());
    }
}
