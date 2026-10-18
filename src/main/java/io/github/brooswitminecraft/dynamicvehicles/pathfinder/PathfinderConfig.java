package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * Pure, Minecraft-free math over Pathfinder's remaining balance decisions (timer, rewards),
 * posted as concrete recommended defaults on the ticket. The DEFAULT_* constants here are those
 * defaults and are what plain unit tests exercise; the real, server-admin-tunable values live in
 * {@code DynamicVehiclesMod.PathfinderServerConfig} (a {@code ModConfigSpec}, following {@code
 * DispatcherOfferConfig}'s pattern) and are passed into the overloads below at the real
 * Minecraft-facing call sites ({@code PathfinderAttempts}), the same split {@code
 * DispatcherOfferConfig}/{@code OfferConfig} already use.
 */
public final class PathfinderConfig {
    private PathfinderConfig() {
    }

    /** Game ticks allotted per leg (one leg per checkpoint, plus one for the closing leg back to checkpoint 0). */
    public static final long DEFAULT_TICKS_PER_LEG = 20L * 90L;

    /** Emerald reward base at level 1. */
    public static final int DEFAULT_REWARD_BASE_EMERALDS = 4;

    /** Additional emerald reward per level above 1. */
    public static final int DEFAULT_REWARD_PER_LEVEL_EMERALDS = 16;

    public static long timeLimitTicks(PathfinderRoute route) {
        return timeLimitTicks(route, DEFAULT_TICKS_PER_LEG);
    }

    public static long timeLimitTicks(PathfinderRoute route, long ticksPerLeg) {
        long legs = route.checkpoints().size() + 1L;
        return ticksPerLeg * legs;
    }

    public static int rewardEmeralds(PathfinderLevel level) {
        return rewardEmeralds(level, DEFAULT_REWARD_BASE_EMERALDS, DEFAULT_REWARD_PER_LEVEL_EMERALDS);
    }

    public static int rewardEmeralds(PathfinderLevel level, int rewardBaseEmeralds, int rewardPerLevelEmeralds) {
        return rewardBaseEmeralds + rewardPerLevelEmeralds * (level.level() - 1);
    }
}
