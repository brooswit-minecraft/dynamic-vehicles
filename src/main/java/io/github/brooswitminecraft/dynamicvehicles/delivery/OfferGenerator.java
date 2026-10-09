package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.Random;

/**
 * Pure, Minecraft-free destination-offer math (MINECRAFT-107/120). Danger is
 * rolled per contract from the seed and the candidate region alone — never
 * from distance (AC3) — and reward is derived from both distance and danger
 * (AC4). Nothing here touches a server, a level, or any world-generation
 * API, so it is unit-testable on its own with a seeded RNG (AC7), following
 * {@link RingMath}'s pattern.
 */
public final class OfferGenerator {

    private OfferGenerator() {
    }

    /**
     * Rolls this candidate's danger value. Deliberately takes no distance or
     * ring input — only the seed and the destination region — so danger can
     * never become a function of distance (AC3), regardless of how the
     * caller chose this candidate.
     */
    public static double rollDanger(long seed, RegionCoord region, OfferConfig config) {
        Random random = new Random(mixSeed(seed, region));
        double span = config.dangerMax() - config.dangerMin();
        return config.dangerMin() + random.nextDouble() * span;
    }

    /** Deadline duration, in game ticks, for a contract at the given distance (AC2). */
    public static long timeAllowanceTicks(double approxDistance, OfferConfig config) {
        return config.timeAllowanceBaseTicks() + Math.round(approxDistance * config.timeAllowanceTicksPerBlock());
    }

    /**
     * Reward for a contract at the given distance and danger (AC4):
     * monotonically increasing in distance at any fixed danger, and
     * monotonically increasing in danger at any fixed distance, while still
     * allowing a short/high-risk offer to out-pay a long/low-risk one.
     */
    public static double reward(double approxDistance, double danger, OfferConfig config) {
        return approxDistance * config.rewardPerBlock() * dangerMultiplier(danger, config);
    }

    private static double dangerMultiplier(double danger, OfferConfig config) {
        double span = config.dangerMax() - config.dangerMin();
        double normalized = Math.clamp((danger - config.dangerMin()) / span, 0.0, 1.0);
        return config.minRewardDangerMultiplier()
                + normalized * (config.maxRewardDangerMultiplier() - config.minRewardDangerMultiplier());
    }

    /**
     * Converts a completed contract's computed reward into the integer amount actually
     * paid, never less than {@code floor} (MINECRAFT-131, inherited item 4). Every computed
     * reward below {@code floor} pays identically, flattening the bottom of {@link #reward}'s
     * curve - see {@code docs/delivery-tuning.md}.
     */
    public static int payoutAmount(double reward, int floor) {
        return (int) Math.max(floor, Math.round(reward));
    }

    /** Rolls the full set of contract terms for one candidate (AC2). */
    public static OfferTerms generateTerms(long seed, RegionCoord region, double approxDistance, OfferConfig config) {
        double danger = rollDanger(seed, region, config);
        long timeAllowance = timeAllowanceTicks(approxDistance, config);
        double reward = reward(approxDistance, danger, config);
        return new OfferTerms(danger, timeAllowance, reward);
    }

    /**
     * Combines the world seed and a destination region into a single danger
     * roll seed. Uses different salts than {@link RingMath}'s own
     * ring-shuffle seed mixing so the two independent randomizations never
     * cancel each other out, and the standard 64-bit splitmix finalizer so
     * adjacent regions decorrelate.
     */
    private static long mixSeed(long seed, RegionCoord region) {
        long z = seed ^ (0x9E3779B97F4A7C15L * (long) region.x()) ^ (0xC2B2AE3D27D4EB4FL * (long) region.z());
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
