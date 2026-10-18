package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The real, terrain-safe seam for {@link PathfinderCheckpointPlacer} (MINECRAFT-379 review
 * follow-up: {@code DEFAULT_UNPLACED}'s fixed y=0/"unplaced" dimension were explicitly
 * scaffolding, not playable placement). X/Z are the same deterministic region-center/circle
 * layout {@code DEFAULT_UNPLACED} uses (a pure function of the region + index/count, no RNG);
 * the only thing this class adds is reading the real world's surface height for that X/Z so the
 * checkpoint actually sits on the ground.
 *
 * <p>Read-only: {@link ServerLevel#getHeight} queries the already-generated heightmap and
 * modifies nothing, per the design's "do not generate roads, pave tracks or automatically
 * reshape terrain" — the same {@code Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} type {@code
 * delivery.SettlementFounder} uses to pick its own board's Y (that class clears/fills terrain
 * for the Dispatcher; this one deliberately does not, since Pathfinder checkpoints are not
 * built structures).
 *
 * <p>This placer is only ever consulted once per (dimension, origin, level) — see {@link
 * PathfinderRouteStorage} — so a checkpoint's resolved position, once generated, stays fixed
 * even if the terrain underneath it changes later (a player's bridge/road, erosion, etc.):
 * exactly the "preserving routes across retries/reloads/infrastructure edits" requirement.
 */
public final class PathfinderTerrainPlacer implements PathfinderCheckpointPlacer {
    private final ServerLevel level;

    public PathfinderTerrainPlacer(ServerLevel level) {
        this.level = level;
    }

    @Override
    public PathfinderCheckpoint place(PathfinderRegion region, int indexInRegion, int countInRegion) {
        double centerX = region.x() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
        double centerZ = region.z() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
        double x = centerX;
        double z = centerZ;
        if (countInRegion > 1) {
            double angle = 2 * Math.PI * indexInRegion / countInRegion;
            x += PathfinderCheckpointPlacer.DEFAULT_LOOP_RADIUS_BLOCKS * Math.cos(angle);
            z += PathfinderCheckpointPlacer.DEFAULT_LOOP_RADIUS_BLOCKS * Math.sin(angle);
        }
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        String dimensionId = level.dimension().location().toString();
        PathfinderPosition position = new PathfinderPosition(dimensionId, blockX + 0.5, surfaceY, blockZ + 0.5);
        return new PathfinderCheckpoint(region, position, PathfinderCheckpointPlacer.DEFAULT_REACH_RADIUS);
    }
}
