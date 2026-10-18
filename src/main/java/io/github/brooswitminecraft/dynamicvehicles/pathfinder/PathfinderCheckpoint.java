package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * One reachable checkpoint in a {@link PathfinderRoute}: the region it belongs to (for circuit-
 * ordering purposes) and its own concrete position. Reaching a checkpoint is "within some reach
 * radius of {@code position} in the same dimension", never "anywhere in {@code region}" — see
 * {@link PathfinderPosition}'s review-follow-up note.
 *
 * <p>Deliberately does NOT carry its own reach radius (review follow-up): a route's checkpoint
 * geometry is cached server-wide ({@link PathfinderRouteStorage}) and meant to stay fixed once
 * generated, while the reach radius is a tunable ({@code PathfinderServerConfig.REACH_RADIUS})
 * that should take effect immediately for every in-progress and future attempt. Baking a radius
 * into the cached checkpoint would freeze it at whatever the config said when that route was
 * first generated. {@link #isReachedBy} therefore takes the radius to check against as a
 * parameter, read fresh by the caller ({@link PathfinderProgress#onReach}) every time.
 */
public record PathfinderCheckpoint(PathfinderRegion region, PathfinderPosition position) {

    public boolean isReachedBy(PathfinderPosition playerPosition, double reachRadius) {
        if (!Double.isFinite(reachRadius) || reachRadius <= 0) {
            throw new IllegalArgumentException("reachRadius must be a finite number > 0: " + reachRadius);
        }
        return position.sameDimensionAs(playerPosition)
                && position.distanceSquaredTo(playerPosition) <= reachRadius * reachRadius;
    }
}
