package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Resolves a checkpoint's deterministic region-center/circle column (the same pure X/Z math
 * {@link PathfinderCheckpointPlacer#DEFAULT_UNPLACED} uses) against a real dimension, per
 * MINECRAFT-403: "stop rejecting, moving or failing a route because a chosen checkpoint lacks a
 * safe or accessible terrain placement" — reaching the location and making it accessible are the
 * player's job, not this class's. This placer therefore never inspects the world, never searches
 * for an alternative column, and never fails.
 *
 * <p>{@code y} is {@link ServerLevel#getSeaLevel()}: a dimension-type constant that costs no
 * chunk load, used purely as a representative height for presentation (the map/HUD marker).
 * Arrival ({@link PathfinderCheckpoint#isReachedBy}) never reads it, so an inability to resolve a
 * "real" terrain height here can never gate route availability or chunk arrival. This class
 * deliberately never calls anything that forces terrain generation (e.g. {@code
 * ServerLevel#getHeight}) just to approve a checkpoint.
 *
 * <p>This placer is only ever consulted once per (dimension, origin, level) — see {@link
 * PathfinderRouteStorage} — so a checkpoint's resolved position, once generated, stays fixed:
 * exactly the "preserve existing deterministic selected chunks" requirement.
 */
public final class PathfinderTerrainPlacer implements PathfinderCheckpointPlacer {
    private final ServerLevel level;

    public PathfinderTerrainPlacer(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Optional<PathfinderCheckpoint> place(PathfinderRegion region, int indexInRegion, int countInRegion) {
        double centerX = region.x() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
        double centerZ = region.z() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
        double x = centerX;
        double z = centerZ;
        if (countInRegion > 1) {
            double angle = 2 * Math.PI * indexInRegion / countInRegion;
            x += PathfinderCheckpointPlacer.DEFAULT_LOOP_RADIUS_BLOCKS * Math.cos(angle);
            z += PathfinderCheckpointPlacer.DEFAULT_LOOP_RADIUS_BLOCKS * Math.sin(angle);
        }
        return placeAt(region, x, z);
    }

    /**
     * Package-visible test seam (review follow-up): lets a GameTest resolve a checkpoint at an
     * X/Z it fully controls — and so can build real, known terrain (lava, a solid headroom
     * block, water) directly under — rather than one derived from region math whose absolute
     * placement the test cannot control (a region-center candidate is routinely hundreds of
     * blocks from wherever a test's own structure happens to load). Exercises the exact same
     * no-search, never-fails resolution {@link #place} uses.
     */
    Optional<PathfinderCheckpoint> placeAt(PathfinderRegion region, double candidateX, double candidateZ) {
        String dimensionId = level.dimension().location().toString();
        PathfinderPosition position = new PathfinderPosition(dimensionId, candidateX, level.getSeaLevel(), candidateZ);
        return Optional.of(new PathfinderCheckpoint(region, position));
    }
}
