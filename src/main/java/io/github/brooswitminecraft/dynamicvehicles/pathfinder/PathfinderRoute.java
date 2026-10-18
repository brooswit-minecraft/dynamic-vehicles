package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.List;

/**
 * A single deterministic circuit for one origin region and one Pathfinder
 * level: the ordered sequence of {@link PathfinderCheckpoint}s a player
 * must visit in order, finishing by returning to {@code
 * checkpoints().get(0)} (the design's "return to the first checkpoint to
 * finish, not to the villager").
 *
 * <p>The checkpoint count is validated against {@code
 * level.checkpointCount()} (1/8/16/24/32 — level 1's eight-checkpoint
 * local loop is settled, not a placeholder; see {@link PathfinderLevel}):
 * a PR #163 review flagged an earlier, looser version of this type that
 * let a single placeholder checkpoint stand in for an entire unresolved
 * level-1 route, which let a player "complete" it by standing in the
 * origin region once without running any actual circuit.
 */
public record PathfinderRoute(PathfinderRegion origin, PathfinderLevel level, List<PathfinderCheckpoint> checkpoints) {
    public PathfinderRoute {
        if (checkpoints.size() != level.checkpointCount()) {
            throw new IllegalArgumentException(level + " needs " + level.checkpointCount()
                    + " checkpoints, got " + checkpoints.size());
        }
        checkpoints = List.copyOf(checkpoints);
    }
}
