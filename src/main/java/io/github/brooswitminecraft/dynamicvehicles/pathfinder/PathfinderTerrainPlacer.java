package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.Optional;

/**
 * The real, terrain-safe seam for {@link PathfinderCheckpointPlacer} (MINECRAFT-379 review
 * follow-up: {@code DEFAULT_UNPLACED}'s fixed y=0/"unplaced" dimension were explicitly
 * scaffolding, not playable placement). The candidate X/Z is the same deterministic
 * region-center/circle layout {@code DEFAULT_UNPLACED} uses (a pure function of the region +
 * index/count, no RNG); from there this class searches a small, bounded, deterministic set of
 * nearby columns ({@link PathfinderTerrainSearch#offsets()}) for one that is actually safe to
 * stand on — world-height bounds, no lava underfoot, standable footing, two blocks of headroom —
 * returning {@link Optional#empty()} if every offset fails.
 *
 * <p>Second independent-review follow-up: the previous version both (a) fell back to the
 * original, possibly-unsafe candidate instead of failing, letting a permanently-cached route
 * contain an unsafe checkpoint, and (b) only checked for lava, not whether the footing block
 * actually forms a standable surface (e.g. a fence or slab arrangement can be "motion-blocking"
 * for heightmap purposes without being a place a player can actually stand). Fixed: no safe
 * column found now means no checkpoint — {@link PathfinderRouteGenerator} propagates that as a
 * failed route generation, and {@link PathfinderRouteStorage} never caches a failed attempt, so
 * a later generation attempt (e.g. after the hazard is cleared, or the search finds a different
 * column as the surrounding terrain changes) can still succeed.
 *
 * <p>Read-only: never calls {@link ServerLevel#setBlock} or anything else that mutates the
 * world, per the design's "do not generate roads, pave tracks or automatically reshape terrain"
 * — the same {@code Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} type {@code
 * delivery.SettlementFounder} uses to pick its own board's Y (that class clears/fills terrain
 * for the Dispatcher; this one deliberately does not, since Pathfinder checkpoints are not
 * built structures). The standable-footing check below mirrors {@code SettlementFounder}'s own
 * {@code state.getFluidState().isEmpty() && state.isFaceSturdy(level, pos, Direction.UP)}
 * sturdiness test.
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
        return placeAt(region, Mth.floor(x), Mth.floor(z));
    }

    /**
     * Package-visible so a GameTest can drive the real search/fallback logic with a candidate
     * X/Z it fully controls, instead of one derived from region math whose absolute placement
     * the test can't control (region-center coordinates can be hundreds of blocks from wherever
     * the test's own structure happens to load).
     */
    Optional<PathfinderCheckpoint> placeAt(PathfinderRegion region, int candidateX, int candidateZ) {
        for (int[] offset : PathfinderTerrainSearch.offsets()) {
            int blockX = candidateX + offset[0];
            int blockZ = candidateZ + offset[1];
            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            if (isSafeColumn(blockX, blockZ, surfaceY)) {
                String dimensionId = level.dimension().location().toString();
                PathfinderPosition position = new PathfinderPosition(dimensionId, blockX + 0.5, surfaceY, blockZ + 0.5);
                return Optional.of(new PathfinderCheckpoint(region, position));
            }
        }
        return Optional.empty();
    }

    /** Package-visible for direct GameTest coverage of just the safety predicate. */
    boolean isSafeColumn(int blockX, int blockZ, int surfaceY) {
        if (surfaceY <= level.getMinBuildHeight() || surfaceY >= level.getMaxBuildHeight() - 2) {
            return false;
        }
        BlockPos footingPos = new BlockPos(blockX, surfaceY - 1, blockZ);
        var footing = level.getBlockState(footingPos);
        if (footing.getFluidState().is(Fluids.LAVA)) {
            return false;
        }
        // Standable footing (review follow-up): a column can be "motion-blocking" for heightmap
        // purposes (a fence, a slab) without actually being something a player can stand on.
        // Water is accepted without a sturdy-face check - swimming/treading water is fine, same
        // as the design's own "players build bridges" expectation.
        boolean standable = !footing.getFluidState().isEmpty() || footing.isFaceSturdy(level, footingPos, Direction.UP);
        if (!standable) {
            return false;
        }
        BlockPos headA = new BlockPos(blockX, surfaceY, blockZ);
        BlockPos headB = new BlockPos(blockX, surfaceY + 1, blockZ);
        return level.isEmptyBlock(headA) && level.isEmptyBlock(headB);
    }
}
