package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.Optional;

/**
 * Resolves one checkpoint of a region's share of a route into a concrete
 * position. {@code indexInRegion}/{@code countInRegion} exist because a
 * region can own more than one checkpoint: level 1's whole 8-checkpoint
 * local loop lives inside a single region (John approved "an eight-
 * checkpoint local loop" on 2026-10-18), while levels 2-5 give each ring
 * region exactly one checkpoint ({@code countInRegion == 1}).
 *
 * <p>Pulled out as a seam (rather than hard-coded into {@link
 * PathfinderRouteGenerator}) so {@link PathfinderTerrainPlacer} — the real
 * implementation, which resolves against a real dimension — can be swapped
 * in without changing route/circuit math or {@link PathfinderProgress}.
 * MINECRAFT-403: there is no "safe/valid position" concept any more —
 * terrain accessibility never gates a checkpoint, so {@code place} always
 * returns a present {@link Optional}; the return type stays {@code
 * Optional} only for interface stability.
 *
 * <p>{@link #DEFAULT_UNPLACED} is the same deterministic region-center/
 * circle math as {@link PathfinderTerrainPlacer}, but with a fixed
 * placeholder dimension/height instead of a real one — useful for tests
 * exercising the checkpoint/progress state machine (identity, ordering,
 * chunk-arrival validation, persistence shape) without a real {@code
 * ServerLevel}. It is not reachable by a real player (its dimension never
 * matches a real one) and must not be used as a real route's placer.
 */
@FunctionalInterface
public interface PathfinderCheckpointPlacer {

    double DEFAULT_LOOP_RADIUS_BLOCKS = PathfinderRegion.SIZE_BLOCKS / 4.0;
    String UNPLACED_DIMENSION = "unplaced";

    /**
     * @param indexInRegion  0-based position of this checkpoint among the ones this region owns
     * @param countInRegion  how many checkpoints this region owns in total (always &gt;= 1)
     */
    Optional<PathfinderCheckpoint> place(PathfinderRegion region, int indexInRegion, int countInRegion);

    PathfinderCheckpointPlacer DEFAULT_UNPLACED = (region, indexInRegion, countInRegion) -> {
        double centerX = region.x() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
        double centerZ = region.z() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
        double x = centerX;
        double z = centerZ;
        if (countInRegion > 1) {
            double angle = 2 * Math.PI * indexInRegion / countInRegion;
            x += DEFAULT_LOOP_RADIUS_BLOCKS * Math.cos(angle);
            z += DEFAULT_LOOP_RADIUS_BLOCKS * Math.sin(angle);
        }
        PathfinderPosition placeholder = new PathfinderPosition(UNPLACED_DIMENSION, x, 0, z);
        return Optional.of(new PathfinderCheckpoint(region, placeholder));
    };
}
