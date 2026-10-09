package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.Objects;

/**
 * Plain, Minecraft-free tuning values for destination-offer generation
 * (MINECRAFT-107/120, AC6). Every tunable the offer-generation logic reads
 * lives here as a primitive; nothing in this record touches a Minecraft
 * type, a registry, or a server, so it is constructible and testable with a
 * seeded RNG from a plain unit test. The Minecraft-facing config that owns
 * the actual tunable defaults is {@code DispatcherOfferConfig} in the mod's
 * root package; it builds one of these from its {@code ModConfigSpec}
 * values at generation time.
 *
 * <p>Villager profession levels are vanilla's 1 (Novice) .. 5 (Master); the
 * per-level arrays here are indexed directly by that level, so index 0 is
 * unused and both arrays must have length 6.
 *
 * @param maxRingByLevel             furthest destination ring (inclusive) eligible at each level; index 0 unused, length 6
 * @param slotsByLevel               how many offer slots to fill at each level; index 0 unused, length 6
 * @param dangerMin                  minimum rolled danger value (inclusive), on a 0..1 scale
 * @param dangerMax                  maximum rolled danger value (exclusive), on a 0..1 scale; must be &gt; dangerMin
 * @param rewardPerBlock             reward units per block of approximate distance, before the danger multiplier
 * @param minRewardDangerMultiplier  reward multiplier applied when danger == dangerMin
 * @param maxRewardDangerMultiplier  reward multiplier applied when danger == dangerMax; must be &gt;= minRewardDangerMultiplier
 * @param timeAllowanceBaseTicks     fixed component of the deadline, in game ticks, granted regardless of distance
 * @param timeAllowanceTicksPerBlock additional deadline ticks granted per block of approximate distance
 */
public record OfferConfig(
        int[] maxRingByLevel,
        int[] slotsByLevel,
        double dangerMin,
        double dangerMax,
        double rewardPerBlock,
        double minRewardDangerMultiplier,
        double maxRewardDangerMultiplier,
        long timeAllowanceBaseTicks,
        double timeAllowanceTicksPerBlock
) {
    public OfferConfig {
        Objects.requireNonNull(maxRingByLevel, "maxRingByLevel");
        Objects.requireNonNull(slotsByLevel, "slotsByLevel");
        if (maxRingByLevel.length != 6 || slotsByLevel.length != 6) {
            throw new IllegalArgumentException("per-level arrays must have length 6 (index 0 unused, 1..5 used)");
        }
        if (dangerMax <= dangerMin) {
            throw new IllegalArgumentException("dangerMax must be > dangerMin: " + dangerMax + " <= " + dangerMin);
        }
        if (maxRewardDangerMultiplier < minRewardDangerMultiplier) {
            throw new IllegalArgumentException(
                    "maxRewardDangerMultiplier must be >= minRewardDangerMultiplier: "
                            + maxRewardDangerMultiplier + " < " + minRewardDangerMultiplier);
        }
    }

    /** @param level vanilla villager profession level, 1 (Novice) .. 5 (Master) */
    public int maxRingForLevel(int level) {
        return maxRingByLevel[requireValidLevel(level)];
    }

    /** @param level vanilla villager profession level, 1 (Novice) .. 5 (Master) */
    public int slotsForLevel(int level) {
        return slotsByLevel[requireValidLevel(level)];
    }

    private static int requireValidLevel(int level) {
        if (level < 1 || level > 5) {
            throw new IllegalArgumentException("level must be 1..5 (vanilla Novice..Master): " + level);
        }
        return level;
    }
}
