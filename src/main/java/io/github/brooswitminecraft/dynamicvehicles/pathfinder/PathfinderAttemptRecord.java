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
 * <p>{@code finished}/{@code expired}/{@code died} are the three terminal states, each with its
 * own retryable durable-clear step in {@code PathfinderTickHandler}: once one becomes true, the
 * record is NOT simply discarded if the durable-save verification fails — it goes back into
 * storage still carrying that same terminal flag, so the next tick's {@code evaluate} retries
 * only the clear/verify/(pay) step, never re-evaluates checkpoints against the route. Review
 * follow-up: {@code died} exists specifically because putting the record back in its ORIGINAL,
 * still-"live" shape (every terminal flag false) after a failed death-clear let a later tick, by
 * then past respawn, advance or even complete the attempt normally — a failed write must leave
 * the record in a state later evaluation recognizes as terminal, not accidentally revive it.
 *
 * <p>{@code progressionCredited} exists for the same reason, scoped to the one-time villager
 * progression credit: once {@code finished} becomes true, progression is credited exactly once,
 * immediately, and this flag records that so a payout retry re-attempts the clear/verify/pay
 * step without re-crediting progression a second time.
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
        boolean died,
        boolean progressionCredited) {

    public PathfinderAttemptRecord withProgress(PathfinderProgress progress) {
        return new PathfinderAttemptRecord(playerId, villagerId, dimensionId, origin, level, startTick, timeLimitTicks,
                progress.reachedCount(), progress.isFinished(), progress.isExpired(), died, progressionCredited);
    }

    public PathfinderAttemptRecord withProgressionCredited() {
        return new PathfinderAttemptRecord(playerId, villagerId, dimensionId, origin, level, startTick, timeLimitTicks,
                reachedCount, finished, expired, died, true);
    }

    public PathfinderAttemptRecord withDied() {
        return new PathfinderAttemptRecord(playerId, villagerId, dimensionId, origin, level, startTick, timeLimitTicks,
                reachedCount, finished, expired, true, progressionCredited);
    }
}
