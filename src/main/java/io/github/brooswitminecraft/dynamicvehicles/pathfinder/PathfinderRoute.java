package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.List;

/**
 * A single deterministic circuit for one origin region and one Pathfinder
 * level: the ordered sequence of regional checkpoints a player must visit
 * in order, finishing by returning to {@code checkpoints().get(0)} (the
 * design's "return to the first checkpoint to finish, not to the
 * villager").
 *
 * <p>For level 1 ({@link PathfinderLevel#isConfinedToOriginRegion()}),
 * {@code checkpoints} is a single-element placeholder equal to the origin
 * region: level 1's actual in-region waypoint count and layout are not
 * settled yet (see MINECRAFT-379's acceptance criteria #4), so this route
 * only records "the circuit stays inside this region" and nothing about
 * where within it. {@link PathfinderProgress} is not meaningful for a
 * level-1 route until that placement is designed.
 */
public record PathfinderRoute(PathfinderRegion origin, PathfinderLevel level, List<PathfinderRegion> checkpoints) {
    public PathfinderRoute {
        if (checkpoints.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one checkpoint");
        }
        checkpoints = List.copyOf(checkpoints);
    }
}
