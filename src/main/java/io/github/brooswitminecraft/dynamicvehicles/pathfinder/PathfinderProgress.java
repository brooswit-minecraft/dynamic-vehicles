package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * One player's in-progress attempt at a {@link PathfinderRoute}: server-
 * authoritative, in-order checkpoint validation plus an overall completion
 * time limit. Each attempt owns its own mutable state, so concurrently
 * running players never share or corrupt each other's progress (MINECRAFT-379's
 * multiplayer-isolation requirement) as long as the caller creates one
 * instance per player per attempt — this class makes no attempt to dedupe
 * or share state across players itself.
 *
 * <p>Reaching a checkpoint requires matching its {@link PathfinderCheckpoint#isReachedBy}
 * (dimension + reach-radius proximity to its own concrete position), not
 * merely entering its region — a PR #163 review flagged the earlier
 * region-only check as unable to distinguish checkpoints that share a
 * region or reject a wrong position within the correct one.
 *
 * <p>{@link #onReach} takes the current tick and enforces expiry
 * atomically with the reach check (expiry is evaluated first, so a reach
 * reported exactly at or after the deadline is rejected even if it would
 * otherwise match) — per the same review, this replaced a separate {@code
 * tick(long)} call that a caller could skip or call out of order relative
 * to a reach report.
 *
 * <p>Timer start/disconnect/death semantics (does a disconnect pause or
 * fail the attempt?) are not settled — see MINECRAFT-379's acceptance
 * criteria #4 — so {@code startTick} here is simply "whenever the caller
 * constructed this attempt"; the Minecraft-facing integration decides when
 * that is.
 */
public final class PathfinderProgress {
    private final PathfinderRoute route;
    private final long startTick;
    private final long timeLimitTicks;
    private int reachedCount = 0;
    private boolean finished = false;
    private boolean expired = false;

    public PathfinderProgress(PathfinderRoute route, long startTick, long timeLimitTicks) {
        this(route, startTick, timeLimitTicks, 0, false, false);
    }

    /**
     * Full-state constructor for reconstructing an attempt from persisted storage (e.g. after a
     * server restart): the Minecraft-facing layer persists {@link #route}'s identity (origin
     * region + level, re-resolved through the route cache, not re-derived from a live heightmap -
     * see {@code pathfinder}'s terrain-placement follow-up) plus {@link #reachedCount}/{@link
     * #isFinished}/{@link #isExpired}, and rebuilds an equivalent instance with this constructor.
     */
    public PathfinderProgress(PathfinderRoute route, long startTick, long timeLimitTicks,
                               int reachedCount, boolean finished, boolean expired) {
        if (timeLimitTicks <= 0) {
            throw new IllegalArgumentException("timeLimitTicks must be > 0: " + timeLimitTicks);
        }
        if (reachedCount < 0 || reachedCount > route.checkpoints().size()) {
            throw new IllegalArgumentException("reachedCount out of range [0, " + route.checkpoints().size() + "]: " + reachedCount);
        }
        this.route = route;
        this.startTick = startTick;
        this.timeLimitTicks = timeLimitTicks;
        this.reachedCount = reachedCount;
        this.finished = finished;
        this.expired = expired;
    }

    public PathfinderRoute route() {
        return route;
    }

    public long startTick() {
        return startTick;
    }

    public long timeLimitTicks() {
        return timeLimitTicks;
    }

    public int reachedCount() {
        return reachedCount;
    }

    /** The checkpoint the player must reach next (the closing leg once every checkpoint has been reached). */
    public PathfinderCheckpoint nextExpected() {
        var checkpoints = route.checkpoints();
        return reachedCount < checkpoints.size() ? checkpoints.get(reachedCount) : checkpoints.get(0);
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isExpired() {
        return expired;
    }

    /**
     * Reports the player at {@code playerPosition} as of {@code currentTick}. Expiry is checked first and
     * is final: once expired, no further reach is ever accepted, even one reported at the exact same tick
     * as expiry or at a checkpoint the player had legitimately reached. Otherwise, advances only if
     * {@code playerPosition} is within {@link #nextExpected()}'s reach radius and dimension; a wrong
     * position, a wrong dimension, or an out-of-order checkpoint is rejected with no state change.
     */
    public boolean onReach(PathfinderPosition playerPosition, long currentTick) {
        if (finished || expired) {
            return false;
        }
        if (currentTick - startTick >= timeLimitTicks) {
            expired = true;
            return false;
        }
        if (!nextExpected().isReachedBy(playerPosition)) {
            return false;
        }
        if (reachedCount < route.checkpoints().size()) {
            reachedCount++;
            if (reachedCount == route.checkpoints().size() && route.checkpoints().size() == 1) {
                // A single-checkpoint route's own checkpoint IS the closing leg: reaching it once finishes.
                finished = true;
            }
        } else {
            finished = true;
        }
        return true;
    }
}
