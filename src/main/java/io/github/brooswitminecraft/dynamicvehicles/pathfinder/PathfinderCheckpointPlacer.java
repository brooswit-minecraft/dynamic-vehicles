package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * Resolves one checkpoint of a region's share of a route into a concrete
 * position. {@code indexInRegion}/{@code countInRegion} exist because a
 * region can own more than one checkpoint: level 1's whole 8-checkpoint
 * local loop lives inside a single region (John approved "an eight-
 * checkpoint local loop" on 2026-10-18), while levels 2-5 give each ring
 * region exactly one checkpoint ({@code countInRegion == 1}).
 *
 * <p>Pulled out as a seam (rather than hard-coded into {@link
 * PathfinderRouteGenerator}) so the real, terrain-safe placement strategy —
 * adapting {@code delivery.SpawnOffsetPicker}/{@code SettlementFounder} to
 * find safe height and avoid obstructions, per MINECRAFT-379's acceptance
 * criteria #4 — can be swapped in later without changing route/circuit
 * math or {@link PathfinderProgress}.
 *
 * <p>{@link #DEFAULT_UNPLACED} is an explicit placeholder: a fixed dummy
 * height/dimension, checkpoints laid out on a circle around the region
 * center (so a multi-checkpoint region at least forms a loop shape rather
 * than stacking every checkpoint on one point), one global reach radius.
 * It exists only so the checkpoint/progress state machine (identity,
 * ordering, proximity validation, persistence shape) can be built and
 * tested now, independently of the terrain-safe placement decision — it
 * is NOT a real placement strategy and must not be shipped as one; a
 * server-facing wiring step must replace it before any Pathfinder
 * checkpoint is reachable in-game.
 */
@FunctionalInterface
public interface PathfinderCheckpointPlacer {

    double DEFAULT_REACH_RADIUS = 3.0;
    double DEFAULT_LOOP_RADIUS_BLOCKS = PathfinderRegion.SIZE_BLOCKS / 4.0;
    String UNPLACED_DIMENSION = "unplaced";

    /**
     * @param indexInRegion  0-based position of this checkpoint among the ones this region owns
     * @param countInRegion  how many checkpoints this region owns in total (always &gt;= 1)
     */
    PathfinderCheckpoint place(PathfinderRegion region, int indexInRegion, int countInRegion);

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
        return new PathfinderCheckpoint(region, placeholder, DEFAULT_REACH_RADIUS);
    };
}
