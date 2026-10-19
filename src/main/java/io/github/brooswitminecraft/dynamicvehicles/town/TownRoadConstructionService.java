package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * MINECRAFT-388 review finding #3's production half: {@link TownRoadPlanner} only computes
 * coordinates, so this is what actually turns a region's four edge-to-centre road spurs into
 * placed, resource-gated blocks - the "production road-placement consumer" the review found
 * entirely absent. Driven from {@link TownConstructionService#onLevelTick} once per tick for
 * every region that currently has at least one claimed site (roads have no single campfire anchor
 * of their own to iterate from the way building sites do).
 */
public final class TownRoadConstructionService {
    private TownRoadConstructionService() {}

    /** A simple, visually distinct road surface; not a new template catalog, matching this PR's
     *  existing "simple footprints, no catalog" scope decision for buildings. */
    private static final Block ROAD_SURFACE = Blocks.DIRT_PATH;

    /** @return whether anything was actually mutated (so the caller knows whether to persist storage). */
    public static boolean buildRoadsForRegion(ServerLevel level, RegionCoord region, TownResourceStore store) {
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        int centerHeight = TownTerrain.estimateGroundHeight(level, plan.townCenterX(), plan.townCenterZ());

        boolean mutated = false;
        int placedThisTick = 0;
        for (TownEdge edge : TownEdge.values()) {
            if (placedThisTick >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                break;
            }
            List<TownRoadPlanner.RoadCell> cells = TownRoadPlanner.routeForEdge(plan, edge, centerHeight);
            int[] result = buildRoad(level, cells, store, placedThisTick);
            placedThisTick += result[0];
            mutated |= result[1] != 0;
        }
        return mutated;
    }

    /**
     * Places as many cells of a single already-computed road route as the per-tick budget and
     * available resources allow, applying the same per-cell safety/pacing/debit rules
     * {@link #buildRoadsForRegion} uses internally. Exposed {@code public} (the same
     * {@code TownFounder#tryFoundAt} / {@code TownConstructionService#buildAt} convention this PR
     * already follows) so a GameTest can drive a small, fully-controlled route directly, without
     * depending on a seed-derived {@link TownPlan}'s town centre happening to land inside the
     * GameTest's own tiny loaded footprint.
     *
     * @return the number of cells actually placed.
     */
    public static int buildRoadCells(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, TownResourceStore store) {
        return buildRoad(level, cells, store, 0)[0];
    }

    /** @return {@code {blocksPlaced, mutatedFlag}} */
    private static int[] buildRoad(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, TownResourceStore store, int alreadyPlaced) {
        int halfWidth = (TownConstants.MINOR_ROAD_WIDTH - 1) / 2;
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
                if (!level.hasChunkAt(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.is(ROAD_SURFACE)) {
                    continue; // already built here
                }
                if (!TownTerrain.isNaturalTerrain(state)) {
                    // Protected/foreign content (including another live campfire) - skip just this
                    // cell, never bulldoze it, and never abort the rest of the road over it.
                    continue;
                }
                if (!store.tryDebit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK)) {
                    return new int[] {placed, mutated ? 1 : 0}; // out of funds - pause entirely, no partial charge
                }
                level.setBlock(pos, ROAD_SURFACE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                placed++;
                mutated = true;
            }
        }
        return new int[] {placed, mutated ? 1 : 0};
    }
}
