package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    /**
     * [Review round 11, finding #2] Test-only fault injection. The real Minecraft API gives no
     * deterministic way for a GameTest to force {@code Level#setBlock}/{@code #removeBlock} to
     * fail on demand (every block-to-different-block mutation genuinely succeeds; there is no
     * permission/validation layer at this level to exploit) - these two hooks are the seam a
     * regression uses to engineer an actual forward-write failure (via
     * {@link #testForceSurfaceFailureAt}, forcing the final surface placement to be rejected so
     * the rollback path actually runs) and an actual rollback-restoration failure (via
     * {@link #testForceRollbackFailureAt}, forcing one specific cell's own restoration to fail),
     * so {@link #placeRoadCell}'s real failure/rollback/durable-block branch can be exercised
     * end-to-end rather than only unit-tested as an isolated storage primitive. Each hook fires
     * (and clears itself) at most once, matching exactly one targeted cell. {@code null} in
     * normal (non-test) operation - zero behavioural effect on production.
     */
    static BlockPos testForceSurfaceFailureAt = null;
    static BlockPos testForceRollbackFailureAt = null;

    /**
     * A production outcome from the road builder's own core, retained separately from the boolean
     * wrapper so callers that need the reason for a no-op do not have to infer it from unrelated
     * availability and terrain guards.
     */
    enum RoadBuildOutcome {
        EXCLUDED_DIMENSION,
        NO_MUTATION,
        MUTATED
    }

    /** @return whether anything was actually mutated (so the caller knows whether to persist storage). */
    public static boolean buildRoadsForRegion(ServerLevel level, RegionCoord region, TownStorage storage, TownResourceStore store) {
        return buildRoadsForRegionOutcome(level, region, storage, store) == RoadBuildOutcome.MUTATED;
    }

    /** The real road-builder core; the public boolean wrapper intentionally retains its behavior. */
    static RoadBuildOutcome buildRoadsForRegionOutcome(ServerLevel level, RegionCoord region, TownStorage storage, TownResourceStore store) {
        // [Review round 4, finding #4] Dynamic Towns roads are an Overworld-only feature (see
        // TownConstructionService's own top-level gate) - this call path is reachable from a
        // GameTest directly, so it is re-checked here too rather than trusted to always come
        // through the gated production tick.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return RoadBuildOutcome.EXCLUDED_DIMENSION;
        }
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        if (!level.hasChunkAt(new BlockPos(plan.townCenterX(), level.getMinBuildHeight(), plan.townCenterZ()))
                || !TownRoadPlanner.hasLoadedBoundaryTerrain(level, plan)) {
            return RoadBuildOutcome.NO_MUTATION; // safe retry: never derive a route from an unloaded heightmap fallback
        }
        int rawCenterHeight = centerTerrainHeight(level, plan, storage);
        // [Review round 3, finding #6] Resolve ONE shared centre height every edge can actually
        // reach within the grade policy, instead of letting each edge independently chase the raw
        // terrain height and risk disagreeing with the others at the shared centre point.
        // [Review round 4, finding #4] Resolved against each edge's own REAL terrain-sampled
        // height, not a fixed ~64 base, so the convergence invariant holds against real terrain too.
        // [Review round 9, finding #1] When no single height is reachable by all four edges at all
        // (genuinely extreme real terrain), safely reject building ANY road for this region this
        // tick rather than build some edges to a height the others cannot actually reach too -
        // retried every tick, so a later pass can still recover.
        java.util.OptionalInt centerHeightResolution = TownRoadPlanner.resolveSharedCenterHeight(level, plan, rawCenterHeight);
        if (centerHeightResolution.isEmpty()) {
            return RoadBuildOutcome.NO_MUTATION;
        }
        int centerHeight = centerHeightResolution.getAsInt();

        boolean mutated = false;
        int placedThisTick = 0;
        for (TownEdge edge : TownEdge.values()) {
            if (placedThisTick >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                break;
            }
            List<TownRoadPlanner.RoadCell> majorCells = TownRoadPlanner.routeForEdge(level, plan, edge, centerHeight);
            int[] result = buildRoad(level, majorCells, TownConstants.MAJOR_ROAD_WIDTH, storage, store, placedThisTick);
            placedThisTick += result[0];
            mutated |= result[1] != 0;

            for (List<TownRoadPlanner.RoadCell> branch : TownRoadPlanner.minorBranchesForEdge(level, plan, edge, centerHeight)) {
                if (placedThisTick >= TownConstants.ROAD_BLOCKS_PER_TICK) {
                    break;
                }
                int[] branchResult = buildRoad(level, branch, TownConstants.MINOR_ROAD_WIDTH, storage, store, placedThisTick);
                placedThisTick += branchResult[0];
                mutated |= branchResult[1] != 0;
            }
        }
        return mutated ? RoadBuildOutcome.MUTATED : RoadBuildOutcome.NO_MUTATION;
    }

    /**
     * The production centre sample. A live DIRT_PATH is only discounted when this exact regional
     * road plan has already paid for it, so a completed centre cannot make the first-free
     * heightmap ratchet the next plan upward while foreign/path-like terrain retains natural
     * height semantics.
     */
    static int centerTerrainHeight(ServerLevel level, TownPlan plan, TownStorage storage) {
        int height = TownTerrain.estimateGroundHeight(level, plan.townCenterX(), plan.townCenterZ());
        String owner = TownStorage.roadOwner(level.dimension(), TownRegion.fromBlockCoords(plan.townCenterX(), plan.townCenterZ()));
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ROAD_SURFACE)
                + ":" + TownResourceCategory.STONE.name();
        // The writer clears only two cells of headroom. A tree can therefore leave higher logs
        // above that owned road with an air gap between them; TownTerrain correctly stops at the
        // gap, but the resulting first-free height must still find the paid centre below it.
        // Scan only that short air/tree clearing corridor and only accept an exact paid road record.
        for (int depth = 1; depth <= TownConstants.ROAD_SUPPORT_FILL_MAX_DEPTH; depth++) {
            BlockPos candidate = new BlockPos(plan.townCenterX(), height - depth, plan.townCenterZ());
            BlockState state = level.getBlockState(candidate);
            if (state.is(ROAD_SURFACE) && storage.isCellPlaced(level.dimension(), candidate, owner, signature)) {
                return candidate.getY();
            }
            if (!state.isAir() && !state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
                break;
            }
        }
        return height;
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
    public static int buildRoadCells(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, TownStorage storage, TownResourceStore store) {
        return buildRoad(level, cells, TownConstants.MINOR_ROAD_WIDTH, storage, store, 0)[0];
    }

    /** As {@link #buildRoadCells}, but lets a GameTest exercise a specific width (major or minor). */
    public static int buildRoadCells(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, int width, TownStorage storage, TownResourceStore store) {
        return buildRoad(level, cells, width, storage, store, 0)[0];
    }

    /** @return {@code {blocksPlaced, mutatedFlag}} */
    private static int[] buildRoad(ServerLevel level, List<TownRoadPlanner.RoadCell> cells, int width,
            TownStorage storage, TownResourceStore store, int alreadyPlaced) {
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
                if (!placeRoadCell(level, pos, storage, store)) {
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
     * <p>[Review round 4, finding #2] "Already built here" is now the PERSISTED progress record
     * ({@link TownStorage#isCellPlaced}), never "the live block happens to already be the road
     * surface" - a pre-existing, foreign dirt path this mod never itself placed/paid for is treated
     * like any other foreign content (skipped, never adopted as free progress).
     *
     * <p>[Review round 4, finding #4] Support is now filled DOWN until real solid ground is found
     * (bounded by {@link TownConstants#ROAD_SUPPORT_FILL_MAX_DEPTH}), not just one cell, so a route
     * crossing a shallow valley/gap does not leave the surface floating.
     *
     * <p>[Review round 4, finding #5] Every intermediate headroom-clear/support-fill mutation (and
     * every rollback restoration) is itself confirmed, not merely attempted; a successful headroom
     * clearing now credits {@link TownClearingYields} salvage, exactly once, the same way building
     * clearing already does - never silently discarded.
     *
     * @return {@code true} iff a road-surface block was actually placed and charged for.
     */
    private static boolean placeRoadCell(ServerLevel level, BlockPos pos, TownStorage storage, TownResourceStore store) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        // [Review round 10, finding #1] A cell previously left in a genuinely unknown,
        // partially-mutated state (a mutation AND its own rollback both failed - see the
        // bottom of this method) is durably, permanently excluded from any further construction.
        // This mod can no longer trust what is actually there, so it must never be silently
        // retried - that could re-clear/re-charge/re-credit work it is no longer sure it ever
        // cleanly paid for.
        if (storage.isCellBlocked(level.dimension(), pos)) {
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
        // [Review round 5, finding #2] Owned by whichever region this cell's own coordinates fall
        // in - computed from the cell itself rather than threaded through every caller, so a
        // building site's own "site:" owner at this same coordinate (a road cell later re-planned
        // as part of a site's footprint, or vice versa) never reads as this road's already-paid
        // progress merely because the live block happens to still match.
        RegionCoord ownerRegion = TownRegion.fromBlockCoords(pos.getX(), pos.getZ());
        String roadOwner = TownStorage.roadOwner(level.dimension(), ownerRegion);
        // [Review round 7, finding #1] A road's own plan never varies (always the same surface
        // block and cost category), but the signature is still carried through explicitly, the
        // same shape building sites use, rather than special-cased - any future road-plan
        // versioning (a different surface material, say) then gets this same protection for free.
        String roadSignature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ROAD_SURFACE) + ":"
                + TownResourceCategory.STONE.name();
        boolean alreadyBuilt = storage.isCellPlaced(level.dimension(), pos, roadOwner, roadSignature);
        BlockState state = level.getBlockState(pos);
        if (alreadyBuilt && state.is(ROAD_SURFACE)) {
            return false; // already built and paid for by this mod - nothing to do here
        }
        if (!TownTerrain.isNaturalTerrain(state)) {
            // Protected/foreign content (including another live campfire, or a pre-existing dirt
            // path this mod never itself placed/paid for) - skip just this cell, never bulldoze or
            // silently adopt it, and never abort the rest of the road over it.
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

        // [Review round 3, finding #4 / round 4, finding #4] Snapshot every headroom/support cell
        // BEFORE mutating any of them (support now spans down to real solid ground, not just one
        // cell), so a failed surface placement below can roll every one of them back - headroom
        // clearing and support filling must never be a free, uncharged, uncredited partial
        // mutation that survives a rejected surface placement.
        BlockState originalAbove1 = level.getBlockState(above1);
        BlockState originalAbove2 = level.getBlockState(above2);
        List<BlockPos> supportCells = new ArrayList<>();
        List<BlockState> supportOriginals = new ArrayList<>();
        BlockPos probe = pos.below();
        boolean foundSolidGround = false;
        for (int depth = 0; depth < TownConstants.ROAD_SUPPORT_FILL_MAX_DEPTH; depth++, probe = probe.below()) {
            BlockState probeState = level.getBlockState(probe);
            if (!probeState.isAir() && probeState.isSolidRender(level, probe)) {
                foundSolidGround = true;
                break; // real solid ground found - nothing more to fill below this
            }
            supportCells.add(probe);
            supportOriginals.add(probeState);
        }
        if (!foundSolidGround) {
            // [Review round 5, finding #4] The support scan exhausted its whole bounded depth
            // without ever finding real ground (a deep ravine/cave/void below this cell) - filling
            // ROAD_SUPPORT_FILL_MAX_DEPTH cells of dirt on top of nothing still leaves the surface
            // floating over whatever lies beyond that depth. Reject this cell entirely rather than
            // building an impossible, ungrounded support column; no mutation, no charge.
            return false;
        }

        boolean clearedAbove1 = false;
        boolean clearedAbove2 = false;
        boolean mutationsOk = true;
        if (!originalAbove1.isAir()) {
            mutationsOk &= level.removeBlock(above1, false) && level.getBlockState(above1).isAir();
            clearedAbove1 = true;
        }
        if (!originalAbove2.isAir()) {
            mutationsOk &= level.removeBlock(above2, false) && level.getBlockState(above2).isAir();
            clearedAbove2 = true;
        }
        for (BlockPos supportCell : supportCells) {
            mutationsOk &= level.setBlock(supportCell, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL)
                    && level.getBlockState(supportCell).is(Blocks.DIRT);
        }

        boolean surfaceForcedToFail = pos.equals(testForceSurfaceFailureAt);
        if (surfaceForcedToFail) {
            testForceSurfaceFailureAt = null;
        }
        boolean placedOk = mutationsOk && !surfaceForcedToFail
                && level.setBlock(pos, ROAD_SURFACE.defaultBlockState(), Block.UPDATE_ALL)
                && level.getBlockState(pos).is(ROAD_SURFACE);
        if (!placedOk) {
            // The surface placement (or an earlier intermediate mutation) was rejected or did not
            // land - roll back everything mutated above, confirming each restoration too, so this
            // cell is left exactly as it was found, charge nothing, and try again next tick rather
            // than leaving a free partial mutation behind.
            // [Review round 5, finding #4] Each restoration is itself confirmed (never assumed) -
            // a rollback that itself fails to land means this cell is left in a genuinely unknown,
            // partially-mutated state; that is surfaced via the logger (there is no safe automatic
            // recovery once BOTH the forward mutation and its own rollback have failed) rather than
            // silently treated as a clean no-op.
            List<BlockPos> failedRestorations = new ArrayList<>();
            if (clearedAbove1 && !restoreCell(level, above1, originalAbove1)) {
                failedRestorations.add(above1);
            }
            if (clearedAbove2 && !restoreCell(level, above2, originalAbove2)) {
                failedRestorations.add(above2);
            }
            for (int i = supportCells.size() - 1; i >= 0; i--) {
                if (!restoreCell(level, supportCells.get(i), supportOriginals.get(i))) {
                    failedRestorations.add(supportCells.get(i));
                }
            }
            if (!failedRestorations.isEmpty()) {
                // [Review round 10, finding #1 / round 11, finding #2] Logging alone is not
                // durable - a fresh tick (or a server restart) would simply retry this exact cell
                // from scratch against a world state this mod can no longer verify, risking a
                // double charge or double salvage credit for work it is no longer sure it ever
                // cleanly paid for. Persist a permanent block on EVERY cell whose own restoration
                // actually failed (not merely the road surface cell itself) - a neighbouring road
                // cell's own headroom/support can legitimately overlap this one, and would
                // otherwise see whatever foreign material was left behind as ordinary clearable
                // natural terrain, risking phantom salvage for material that was never really
                // there to begin with.
                io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                        "Dynamic Towns road rollback at {} did not fully restore {} after a rejected surface placement"
                                + " - permanently excluding {} from further construction",
                        pos, failedRestorations, failedRestorations);
                storage.markCellBlocked(level.dimension(), pos);
                for (BlockPos failed : failedRestorations) {
                    storage.markCellBlocked(level.dimension(), failed);
                }
                storage.setDirty();
            }
            return false;
        }

        store.tryDebit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK);
        storage.markCellPlaced(level.dimension(), pos, roadOwner, roadSignature, ROAD_SURFACE.defaultBlockState());

        // [Review round 4, finding #5 / round 5, finding #4] Credit clearing salvage for the
        // headroom AND the original surface block actually removed, exactly once (only now that
        // the whole cell transaction has committed) - support fills are leveling, not salvage
        // (nothing was removed there), so they earn no credit, matching building terrain prep's own
        // "fill is leveling, not salvage" rule.
        Map<TownClearingYields.ClearedMaterial, Integer> tally = new EnumMap<>(TownClearingYields.ClearedMaterial.class);
        tallyClearedMaterial(tally, state);
        if (clearedAbove1) {
            tallyClearedMaterial(tally, originalAbove1);
        }
        if (clearedAbove2) {
            tallyClearedMaterial(tally, originalAbove2);
        }
        for (Map.Entry<TownClearingYields.ClearedMaterial, Integer> entry : tally.entrySet()) {
            TownClearingYields.Yield yield = TownClearingYields.yieldFor(entry.getKey());
            int credit = TownClearingYields.creditsFor(entry.getKey(), entry.getValue());
            if (credit > 0) {
                store.credit(yield.category(), credit);
            }
        }
        return true;
    }

    /** Restores {@code pos} to {@code original}, confirming the restoration actually landed - see
     *  {@link #testForceRollbackFailureAt} for how a test engineers this to deterministically fail. */
    private static boolean restoreCell(ServerLevel level, BlockPos pos, BlockState original) {
        if (pos.equals(testForceRollbackFailureAt)) {
            testForceRollbackFailureAt = null;
            return false;
        }
        return level.setBlock(pos, original, Block.UPDATE_ALL) && level.getBlockState(pos).equals(original);
    }

    private static void tallyClearedMaterial(Map<TownClearingYields.ClearedMaterial, Integer> tally, BlockState removedState) {
        TownClearingYields.ClearedMaterial material = TownTerrain.classify(removedState);
        if (material != null) {
            tally.merge(material, 1, Integer::sum);
        }
    }

    private static boolean isEntityOccupying(ServerLevel level, BlockPos pos) {
        AABB cell = new AABB(pos);
        return !level.getEntities((Entity) null, cell).isEmpty();
    }
}
