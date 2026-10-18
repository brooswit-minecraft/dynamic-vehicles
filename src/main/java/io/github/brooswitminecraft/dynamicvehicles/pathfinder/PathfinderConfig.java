package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * Plain tunables for the remaining balance decisions MINECRAFT-379 flagged as not yet settled
 * (timer, rewards), posted as concrete recommended defaults on the ticket. Deliberately plain
 * constants rather than a {@code ModConfigSpec} for now — follow-up work, not a redesign, to
 * move these onto the same {@code ModConfigSpec} pattern {@code DispatcherOfferConfig} uses, if
 * and when they need to be server-admin-tunable without a rebuild.
 */
public final class PathfinderConfig {
    private PathfinderConfig() {
    }

    /** Game ticks allotted per leg (one leg per checkpoint, plus one for the closing leg back to checkpoint 0). */
    public static final long TICKS_PER_LEG = 20L * 90L;

    /** Emerald reward base at level 1. */
    public static final int REWARD_BASE_EMERALDS = 4;

    /** Additional emerald reward per level above 1. */
    public static final int REWARD_PER_LEVEL_EMERALDS = 16;

    public static long timeLimitTicks(PathfinderRoute route) {
        long legs = route.checkpoints().size() + 1L;
        return TICKS_PER_LEG * legs;
    }

    public static int rewardEmeralds(PathfinderLevel level) {
        return REWARD_BASE_EMERALDS + REWARD_PER_LEVEL_EMERALDS * (level.level() - 1);
    }
}
