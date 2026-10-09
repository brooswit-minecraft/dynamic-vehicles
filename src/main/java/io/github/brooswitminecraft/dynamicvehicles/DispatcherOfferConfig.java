package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

import io.github.brooswitminecraft.dynamicvehicles.delivery.AmbushConfig;
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

    // MINECRAFT-130: periodic pillager ambush roll knobs. Defaults are starting points (MINECRAFT-113 tunes them).
    public static final ModConfigSpec.IntValue AMBUSH_ROLL_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue AMBUSH_MIN_ROLL_CHANCE;
    public static final ModConfigSpec.DoubleValue AMBUSH_MAX_ROLL_CHANCE;
    public static final ModConfigSpec.IntValue AMBUSH_MIN_ENCOUNTER_SIZE;
    public static final ModConfigSpec.IntValue AMBUSH_MAX_ENCOUNTER_SIZE;
    public static final ModConfigSpec.DoubleValue AMBUSH_SPAWN_MIN_DISTANCE;
    public static final ModConfigSpec.DoubleValue AMBUSH_SPAWN_MAX_DISTANCE;
    public static final ModConfigSpec.DoubleValue AMBUSH_SPAWN_ARC_DEGREES;
    public static final ModConfigSpec.IntValue AMBUSH_MAX_ENCOUNTER_LIFETIME_TICKS;
    public static final ModConfigSpec.DoubleValue AMBUSH_NEARBY_PLAYER_RADIUS;

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

        AMBUSH_ROLL_INTERVAL_TICKS = builder
                .comment("How often (game ticks; 20 ticks = 1 second) an active contract gets one pillager ambush "
                        + "roll attempt. Spec's initial tuning suggestion is ~30 seconds (600 ticks).")
                .defineInRange("ambushRollIntervalTicks", 600, 20, Integer.MAX_VALUE);
        AMBUSH_MIN_ROLL_CHANCE = builder
                .comment("Ambush roll success probability when a contract's danger == dangerMin.")
                .defineInRange("ambushMinRollChance", 0.05, 0.0, 1.0);
        AMBUSH_MAX_ROLL_CHANCE = builder
                .comment("Ambush roll success probability when a contract's danger == dangerMax. Must be >= ambushMinRollChance.")
                .defineInRange("ambushMaxRollChance", 0.6, 0.0, 1.0);
        AMBUSH_MIN_ENCOUNTER_SIZE = builder
                .comment("Pillager count on a successful ambush roll when danger == dangerMin.")
                .defineInRange("ambushMinEncounterSize", 1, 1, 50);
        AMBUSH_MAX_ENCOUNTER_SIZE = builder
                .comment("Pillager count on a successful ambush roll when danger == dangerMax. Must be >= ambushMinEncounterSize.")
                .defineInRange("ambushMaxEncounterSize", 4, 1, 50);
        AMBUSH_SPAWN_MIN_DISTANCE = builder
                .comment("Nearest an ambush spawn offset may land from the traveling player/vehicle, in blocks.")
                .defineInRange("ambushSpawnMinDistance", 10.0, 1.0, 1000.0);
        AMBUSH_SPAWN_MAX_DISTANCE = builder
                .comment("Farthest an ambush spawn offset may land. Must be > ambushSpawnMinDistance.")
                .defineInRange("ambushSpawnMaxDistance", 20.0, 1.0, 1000.0);
        AMBUSH_SPAWN_ARC_DEGREES = builder
                .comment("Total angular spread (degrees), centered on the player's/vehicle's heading, that an "
                        + "ambush spawn offset may land within - the player's AC2 \"ahead matters\" cone.")
                .defineInRange("ambushSpawnArcDegrees", 120.0, 1.0, 360.0);
        AMBUSH_MAX_ENCOUNTER_LIFETIME_TICKS = builder
                .comment("Hard backstop (game ticks): an ambush mob despawns after this long regardless of other "
                        + "state, so a mob that wandered off or whose owner never reconnects cannot accumulate "
                        + "indefinitely (AC4).")
                .defineInRange("ambushMaxEncounterLifetimeTicks", 6000, 20, Integer.MAX_VALUE);
        AMBUSH_NEARBY_PLAYER_RADIUS = builder
                .comment("A non-owner player within this many blocks of an ambush mob blocks its despawn (AC5), "
                        + "so cleanup never pulls a mob out from under someone fighting it.")
                .defineInRange("ambushNearbyPlayerRadius", 24.0, 0.0, 1000.0);

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

    /** Snapshots the current ambush-roll config values into a plain, Minecraft-free {@link AmbushConfig} (MINECRAFT-130). */
    public static AmbushConfig toAmbushConfig() {
        return new AmbushConfig(
                AMBUSH_ROLL_INTERVAL_TICKS.get(),
                AMBUSH_MIN_ROLL_CHANCE.get(),
                AMBUSH_MAX_ROLL_CHANCE.get(),
                AMBUSH_MIN_ENCOUNTER_SIZE.get(),
                AMBUSH_MAX_ENCOUNTER_SIZE.get(),
                AMBUSH_SPAWN_MIN_DISTANCE.get(),
                AMBUSH_SPAWN_MAX_DISTANCE.get(),
                Math.toRadians(AMBUSH_SPAWN_ARC_DEGREES.get()),
                AMBUSH_MAX_ENCOUNTER_LIFETIME_TICKS.get(),
                AMBUSH_NEARBY_PLAYER_RADIUS.get(),
                DANGER_MIN.get(),
                DANGER_MAX.get());
    }
}
