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
 * <p>Not meaningful yet for a level-1 route; see {@link PathfinderRoute}'s
 * caveat about level 1's unsettled in-region layout.
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
        if (timeLimitTicks <= 0) {
            throw new IllegalArgumentException("timeLimitTicks must be > 0: " + timeLimitTicks);
        }
        this.route = route;
        this.startTick = startTick;
        this.timeLimitTicks = timeLimitTicks;
    }

    /** The checkpoint the player must reach next (the closing leg once every checkpoint has been reached). */
    public PathfinderRegion nextExpected() {
        var checkpoints = route.checkpoints();
        return reachedCount < checkpoints.size() ? checkpoints.get(reachedCount) : checkpoints.get(0);
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isExpired() {
        return expired;
    }

    /** Fails the attempt if the time limit has elapsed as of {@code currentTick}. Call before {@link #onReachRegion}. */
    public boolean tick(long currentTick) {
        if (!finished && !expired && currentTick - startTick >= timeLimitTicks) {
            expired = true;
        }
        return expired;
    }

    /**
     * Reports the player reaching {@code region}. Returns {@code true} and
     * advances only if it is the next expected checkpoint (or the closing
     * leg); an out-of-order region is rejected with no state change, per
     * the design's "players must reach checkpoints in sequence".
     */
    public boolean onReachRegion(PathfinderRegion region) {
        if (finished || expired) {
            return false;
        }
        if (!region.equals(nextExpected())) {
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
