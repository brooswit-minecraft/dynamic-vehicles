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
        boolean expired) {

    public PathfinderAttemptRecord withProgress(PathfinderProgress progress) {
        return new PathfinderAttemptRecord(playerId, villagerId, dimensionId, origin, level, startTick, timeLimitTicks,
                progress.reachedCount(), progress.isFinished(), progress.isExpired());
    }
}
