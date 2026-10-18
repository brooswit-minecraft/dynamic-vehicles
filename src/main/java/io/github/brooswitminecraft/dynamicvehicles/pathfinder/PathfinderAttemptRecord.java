package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.UUID;

/**
 * One player's persisted Pathfinder attempt: everything {@link
 * PathfinderAttemptStorage} needs to round-trip through NBT, plus enough
 * identity ({@code dimensionId}/{@code origin}/{@code level}) to re-resolve
 * the shared, cached {@link PathfinderRoute} via {@link
 * PathfinderRouteStorage} rather than persisting the checkpoints twice.
 *
 * <p>{@code villagerId} identifies the specific villager this attempt was started at, so a
 * completion can award {@link PathfinderVillagerLevel} progression to that villager — not to
 * "whichever Pathfinder villager is nearby" when the attempt finishes.
 *
 * <p>{@code progressionCredited} exists for the retryable durable-payout sequence in {@code
 * PathfinderTickHandler}: once {@code finished} becomes true, villager progression is credited
 * exactly once, immediately, and this flag records that — a durable-save verification failure
 * can roll the record back into storage for a later retry, but the retry must only re-attempt
 * the clear/verify/pay step, never re-credit progression a second time.
 */
public record PathfinderAttemptRecord(
        UUID playerId,
        UUID villagerId,
        String dimensionId,
        PathfinderRegion origin,
        PathfinderLevel level,
        long startTick,
        long timeLimitTicks,
        int reachedCount,
        boolean finished,
        boolean expired,
        boolean progressionCredited) {

    public PathfinderAttemptRecord withProgress(PathfinderProgress progress) {
        return new PathfinderAttemptRecord(playerId, villagerId, dimensionId, origin, level, startTick, timeLimitTicks,
                progress.reachedCount(), progress.isFinished(), progress.isExpired(), progressionCredited);
    }

    public PathfinderAttemptRecord withProgressionCredited() {
        return new PathfinderAttemptRecord(playerId, villagerId, dimensionId, origin, level, startTick, timeLimitTicks,
                reachedCount, finished, expired, true);
    }
}
