package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * One reachable checkpoint in a {@link PathfinderRoute}: the region it
 * belongs to (for circuit-ordering purposes), its own concrete position,
 * and the radius within which a player is considered to have reached it.
 * Reaching a checkpoint is "within {@code reachRadius} of {@code position}
 * in the same dimension", never "anywhere in {@code region}" — see
 * {@link PathfinderPosition}'s review-follow-up note.
 *
 * <p>{@code position} here is produced by whatever {@link
 * PathfinderCheckpointPlacer} the route was generated with; the default
 * placer ({@link PathfinderCheckpointPlacer#DEFAULT_UNPLACED}) is an
 * explicit placeholder (region center, fixed height) and is NOT a
 * terrain-safe placement — see that class's javadoc.
 */
public record PathfinderCheckpoint(PathfinderRegion region, PathfinderPosition position, double reachRadius) {
    public PathfinderCheckpoint {
        // Review follow-up (PR #163): a non-finite radius (e.g. Double.POSITIVE_INFINITY) satisfied the
        // old "> 0" check and then matched any same-dimension position at all in isReachedBy.
        if (!Double.isFinite(reachRadius) || reachRadius <= 0) {
            throw new IllegalArgumentException("reachRadius must be a finite number > 0: " + reachRadius);
        }
    }

    public boolean isReachedBy(PathfinderPosition playerPosition) {
        return position.sameDimensionAs(playerPosition)
                && position.distanceSquaredTo(playerPosition) <= reachRadius * reachRadius;
    }
}
