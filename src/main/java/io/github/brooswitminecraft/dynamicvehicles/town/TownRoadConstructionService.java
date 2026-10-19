package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * MINECRAFT-388 review finding #3's production half: {@link TownRoadPlanner} only computes
 * coordinates, so this is what actually turns a region's road routes into placed, resource-gated
 * blocks. Driven from {@link TownConstructionService#onLevelTick} once per tick for every region
 * that currently has at least one claimed site (roads have no single campfire anchor of their own
 * to iterate from the way building sites do).
 *
 * <p>[Review round 2, finding #4] Builds BOTH the four major, town-centre-connecting edge routes
 * (at {@link TownConstants#MAJOR_ROAD_WIDTH}) and, off each of those, minor branch roads into the
 * zoned areas (at {@link TownConstants#MINOR_ROAD_WIDTH} — see
 * {@link TownRoadPlanner#minorBranchesForEdge}); every individual cell placement is now a real,
 * terrain-safe corridor: headroom is cleared above, support is filled below, an occupying entity
 * skips just that cell, and both the world border and the dimension's own build height are
 * checked before any mutation.
 */
public final class TownRoadConstructionService {
    private TownRoadConstructionService() {}

    /** A simple, visually distinct road surface; not a new template catalog, matching this PR's
     *  existing "simple footprints, no catalog" scope decision for buildings. */
    private static final Block ROAD_SURFACE = Blocks.DIRT_PATH;

    /** @return whether anything was actually mutated (so the caller knows whether to persist storage). */
    public static boolean buildRoadsForRegion(ServerLevel level, RegionCoord region, TownResourceStore store) {
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        int rawCenterHeight = TownTerrain.estimateGroundHeight(level, plan.townCenterX(), plan.townCenterZ());
        // [Review round 3, finding #6] Resolve ONE shared centre height every edge can actually
        // reach within the grade policy, instead of letting each edge independently chase the raw
        // terrain height and risk disagreeing with the others at the shared centre point.
        int centerHeight = TownRoadPlanner.resolveSharedCenterHeight(plan, rawCenterHeight);

        boolean mutated = false;
        int placedThisTick = 0;
        for (TownEdge edge : TownEdge.values()) {
            if (placedThisTick >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                break;
            }
            List<TownRoadPlanner.RoadCell> majorCells = TownRoadPlanner.routeForEdge(plan, edge, centerHeight);
            int[] result = buildRoad(level, majorCells, TownConstants.MAJOR_ROAD_WIDTH, store, placedThisTick);
            placedThisTick += result[0];
            mutated |= result[1] != 0;

            for (List<TownRoadPlanner.RoadCell> branch : TownRoadPlanner.minorBranchesForEdge(plan, edge, centerHeight)) {
                if (placedThisTick >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                    break;
                }
                int[] branchResult = buildRoad(level, branch, TownConstants.MINOR_ROAD_WIDTH, store, placedThisTick);
                placedThisTick += branchResult[0];
                mutated |= branchResult[1] != 0;
            }
        }
        return mutated;
    }

    /**
     * Places as many cells of a single already-computed road route as the per-tick budget and
     * available resources allow, at {@link TownConstants#MINOR_ROAD_WIDTH} - the width every
     * existing GameTest/caller of this exposed method already assumes. Exposed {@code public} so
     * a GameTest can drive a small, fully-controlled route directly, without depending on a
     * seed-derived {@link TownPlan}'s town centre happening to land inside the GameTest's own tiny
     * loaded footprint.
     *
     * @return the number of cells actually placed.
     */
    public static int buildRoadCells(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, TownResourceStore store) {
        return buildRoad(level, cells, TownConstants.MINOR_ROAD_WIDTH, store, 0)[0];
    }

    /** As {@link #buildRoadCells}, but lets a GameTest exercise a specific width (major or minor). */
    public static int buildRoadCells(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, int width, TownResourceStore store) {
        return buildRoad(level, cells, width, store, 0)[0];
    }

    /** @return {@code {blocksPlaced, mutatedFlag}} */
    private static int[] buildRoad(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, int width,
            TownResourceStore store, int alreadyPlaced) {
        int halfWidth = (width - 1) / 2;
        int placed = 0;
        boolean mutated = false;
        for (int i = 0; i < cells.size(); i++) {
            if (alreadyPlaced + placed >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                break;
            }
            TownRoadPlanner.RoadCell cell = cells.get(i);
            TownRoadPlanner.RoadCell reference = i > 0 ? cells.get(i - 1)
                    : (cells.size() > 1 ? cells.get(1) : cell);
            // Width extends perpendicular to this segment's own direction of travel, so straight
            // stretches read as a road rather than a single-block-wide scratch line.
            boolean runsAlongX = reference.z() == cell.z() && reference.x() != cell.x();
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                if (alreadyPlaced + placed >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                    break;
                }
                int x = runsAlongX ? cell.x() : cell.x() + offset;
                int z = runsAlongX ? cell.z() + offset : cell.z();
                BlockPos pos = new BlockPos(x, cell.y(), z);
                if (!placeRoadCell(level, pos, store)) {
                    continue;
                }
                placed++;
                mutated = true;
            }
        }
        return new int[] {placed, mutated ? 1 : 0};
    }

    /**
     * [Review round 2, finding #4] A single corridor-safe road cell: world border and build-height
     * bounds are checked first (never place outside the dimension), then headroom above is
     * cleared (natural terrain only - never tunnels through protected content), support below is
     * filled so the surface never floats, any entity standing exactly here makes this cell skip
     * for this tick (never enclosing it), and - per round 2 finding #3 - resources are debited
     * only AFTER the surface placement is confirmed to have actually landed.
     *
     * @return {@code true} iff a road-surface block was actually placed and charged for.
     */
    private static boolean placeRoadCell(ServerLevel level, BlockPos pos, TownResourceStore store) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        // Headroom for a cell, its surface, and the two cells of clearance above it (villager
        // height) must all lie strictly within the dimension's own build limits.
        if (pos.getY() <= level.getMinBuildHeight() || pos.getY() + 2 >= level.getMaxBuildHeight()) {
            return false;
        }
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(ROAD_SURFACE)) {
            return false; // already built here
        }
        if (!TownTerrain.isNaturalTerrain(state)) {
            // Protected/foreign content (including another live campfire) - skip just this cell,
            // never bulldoze it, and never abort the rest of the road over it.
            return false;
        }
        BlockPos above1 = pos.above();
        BlockPos above2 = pos.above(2);
        if (!TownTerrain.isNaturalTerrain(level.getBlockState(above1)) || !TownTerrain.isNaturalTerrain(level.getBlockState(above2))) {
            // Never tunnel through protected/foreign content to clear headroom.
            return false;
        }
        if (isEntityOccupying(level, pos)) {
            // Something is physically standing exactly here right now; skip just this cell and
            // try again next tick rather than enclosing it.
            return false;
        }
        if (!store.canAfford(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK)) {
            return false; // out of funds - pause entirely, no partial charge
        }

        // [Review round 3, finding #4] Snapshot the headroom/support cells BEFORE mutating any of
        // them, so a failed surface placement below can roll every one of them back - headroom
        // clearing and support filling must never be a free, uncharged, uncredited partial
        // mutation that survives a rejected surface placement.
        BlockPos below = pos.below();
        BlockState originalAbove1 = level.getBlockState(above1);
        BlockState originalAbove2 = level.getBlockState(above2);
        BlockState originalBelow = level.getBlockState(below);

        boolean clearedAbove1 = false;
        boolean clearedAbove2 = false;
        boolean filledBelow = false;
        // Clear headroom (villager-height corridor), then ensure solid support below so the
        // surface can never float over open air.
        if (!originalAbove1.isAir()) {
            level.removeBlock(above1, false);
            clearedAbove1 = true;
        }
        if (!originalAbove2.isAir()) {
            level.removeBlock(above2, false);
            clearedAbove2 = true;
        }
        if (originalBelow.isAir() || !originalBelow.isSolidRender(level, below)) {
            level.setBlock(below, Blocks.DIRT.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            filledBelow = true;
        }

        boolean placedOk = level.setBlock(pos, ROAD_SURFACE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        if (!placedOk || !level.getBlockState(pos).is(ROAD_SURFACE)) {
            // The surface placement was rejected or did not land - roll back any headroom/support
            // mutation above so this cell is left exactly as it was found, charge nothing, and try
            // again next tick rather than leaving a free partial mutation behind.
            if (clearedAbove1) {
                level.setBlock(above1, originalAbove1, net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            if (clearedAbove2) {
                level.setBlock(above2, originalAbove2, net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            if (filledBelow) {
                level.setBlock(below, originalBelow, net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            return false;
        }
        store.tryDebit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK);
        return true;
    }

    private static boolean isEntityOccupying(ServerLevel level, BlockPos pos) {
        AABB cell = new AABB(pos);
        return !level.getEntities((Entity) null, cell).isEmpty();
    }
}
