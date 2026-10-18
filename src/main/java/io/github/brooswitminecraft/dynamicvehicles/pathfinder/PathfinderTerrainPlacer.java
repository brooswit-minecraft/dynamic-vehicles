package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * The real, terrain-safe seam for {@link PathfinderCheckpointPlacer} (MINECRAFT-379 review
 * follow-up: {@code DEFAULT_UNPLACED}'s fixed y=0/"unplaced" dimension were explicitly
 * scaffolding, not playable placement). The candidate X/Z is the same deterministic
 * region-center/circle layout {@code DEFAULT_UNPLACED} uses (a pure function of the region +
 * index/count, no RNG); from there this class searches a small, bounded, deterministic set of
 * nearby columns ({@link PathfinderTerrainSearch#offsets()}) for one that is actually safe to stand on — world-
 * height bounds, no lava underfoot, two blocks of headroom — falling back to the unmodified
 * candidate (still bounded/deterministic, just not guaranteed safe) only if every offset fails.
 *
 * <p>A second independent-review follow-up: the first version of this class sampled exactly one
 * heightmap column and accepted it unconditionally, with no passable-space, footing, or
 * world-height check at all.
 *
 * <p>Read-only: never calls {@link ServerLevel#setBlock} or anything else that mutates the
 * world, per the design's "do not generate roads, pave tracks or automatically reshape terrain"
 * — the same {@code Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} type {@code
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
        int candidateX = Mth.floor(x);
        int candidateZ = Mth.floor(z);

        // Falls back to the unmodified candidate (bounded, deterministic, just not guaranteed safe)
        // if every searched offset fails.
        int[] chosen = {candidateX, candidateZ};
        int chosenY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candidateX, candidateZ);
        for (int[] offset : PathfinderTerrainSearch.offsets()) {
            int blockX = candidateX + offset[0];
            int blockZ = candidateZ + offset[1];
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            if (isSafeColumn(blockX, blockZ, surfaceY)) {
                chosen = new int[] {blockX, blockZ};
                chosenY = surfaceY;
                break;
            }
        }

        String dimensionId = level.dimension().location().toString();
        PathfinderPosition position = new PathfinderPosition(dimensionId, chosen[0] + 0.5, chosenY, chosen[1] + 0.5);
        double reachRadius = io.github.brooswitminecraft.dynamicvehicles.PathfinderServerConfig.REACH_RADIUS.get();
        return new PathfinderCheckpoint(region, position, reachRadius);
    }

    /** Package-visible for direct GameTest coverage of just the safety predicate. */
    boolean isSafeColumn(int blockX, int blockZ, int surfaceY) {
        if (surfaceY <= level.getMinBuildHeight() || surfaceY >= level.getMaxBuildHeight() - 2) {
            return false;
        }
        BlockPos footing = new BlockPos(blockX, surfaceY - 1, blockZ);
        if (level.getBlockState(footing).getFluidState().is(Fluids.LAVA)) {
            return false;
        }
        BlockPos headA = new BlockPos(blockX, surfaceY, blockZ);
        BlockPos headB = new BlockPos(blockX, surfaceY + 1, blockZ);
        return level.isEmptyBlock(headA) && level.isEmptyBlock(headB);
    }
}
