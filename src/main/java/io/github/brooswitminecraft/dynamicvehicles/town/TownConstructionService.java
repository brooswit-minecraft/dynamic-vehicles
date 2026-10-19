package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ties the planning layer ({@link TownPlan}/{@link TownPlanner}), the resource layer
 * ({@link TownResourceStore}), the claim layer ({@link TownCampfireClaims}), the construction
 * ordering layer ({@link ConstructionSitePlan}), and the safety layer ({@link TownSitePreflight})
 * together into an incremental, per-tick build. Also drives {@link TownRoadConstructionService}
 * for every region that currently has at least one claimed site, since road construction has no
 * single-campfire anchor of its own to iterate from.
 *
 * <p>Per MINECRAFT-388, construction can happen at ANY qualifying claimed campfire anywhere in the
 * planned town, not only outward from the town centre: this walks every currently claimed campfire
 * independently each tick (bounded at {@link TownConstants#CONSTRUCTION_BLOCKS_PER_TICK} blocks per
 * site), rather than chaining from one seed site. "Remaining to place" is computed by diffing the
 * live world against the planned target at each position — stateless and reload-safe, since no
 * separate per-site progress record needs to stay in sync with the world.
 */
public final class TownConstructionService {
    private TownConstructionService() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % TownConstants.CONSTRUCTION_TICK_INTERVAL_TICKS != 0) {
            return;
        }
        runConstructionAndRoadsTick(level);
    }

    /** The core per-tick work, factored out from {@link #onLevelTick} so it is directly GameTest-
     *  able without needing to construct a real {@link LevelTickEvent.Post} - the same shape
     *  {@code TownFounder#tryFoundNear}/{@code #tryFoundAt} already use for their own bootstrap
     *  logic. [Review round 4, finding #3] exercises this directly to prove road construction for a
     *  FOUNDED region keeps progressing with NO claimed campfire present at all. */
    public static void runConstructionAndRoadsTick(ServerLevel level) {
        // [Review round 4, finding #4] Dynamic Towns (founding, building, and road construction
        // alike) is an Overworld-only feature, matching vanilla's own village generation never
        // occurring in the Nether/End - an explicit, documented scope decision rather than letting
        // the same sea-level-biased road/terrain policy silently misbehave in every dimension.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }

        TownStorage storage = TownStorage.of(level.getServer());

        Map<BlockPos, Villager> claimedCampfires = findClaimedCampfireSites(level);
        for (Map.Entry<BlockPos, Villager> entry : claimedCampfires.entrySet()) {
            BlockPos campfire = entry.getKey();
            if (!level.getBlockState(campfire).is(Blocks.CAMPFIRE)) {
                // Already fully built - the campfire cell itself was already replaced by its final
                // planned block, so there is nothing left to do at this site.
                continue;
            }
            buildAt(level, campfire, storage);
        }

        // [Review round 4, finding #3] Road construction must not stop just because the single
        // building whose claimed campfire originally triggered it has since replaced/released that
        // campfire - a ~100-block building finishes in a few dozen ticks while the region's road
        // network needs thousands of cells at ROAD_BLOCKS_PER_TICK. Roads are therefore driven from
        // nearby-player presence in any already-FOUNDED region, the exact same periodic-handler
        // shape {@link TownFounder#onLevelTick} already uses for bootstrap, independent of whether
        // any campfire is currently claimed there.
        for (RegionCoord region : regionsWithNearbyPlayerActivity(level, storage)) {
            TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), region));
            if (TownRoadConstructionService.buildRoadsForRegion(level, region, storage, store)) {
                storage.setDirty();
            }
        }
    }

    /** [Review round 4, finding #3] Which regions currently have a nearby online player AND are
     *  already founded - the sole, campfire-independent driver for road construction, exposed so a
     *  GameTest can prove this selection is genuinely independent of any claimed campfire without
     *  depending on a seed-derived road route actually landing inside a tiny loaded GameTest
     *  structure. */
    static Set<RegionCoord> regionsWithNearbyPlayerActivity(ServerLevel level, TownStorage storage) {
        Set<RegionCoord> regions = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            RegionCoord region = TownRegion.fromBlockCoords(player.blockPosition().getX(), player.blockPosition().getZ());
            if (storage.hasFoundedTown(TownKeys.keyFor(level.dimension(), region))) {
                regions.add(region);
            }
        }
        return regions;
    }

    /**
     * [Review round 3, finding #2] A JOB_SITE memory alone is not enough: vanilla sets it for
     * EVERY workstation a villager employs at (a composter, a smithing table - any ordinary
     * profession), not only a campfire. Only a job-site position whose block is ACTUALLY a
     * campfire right now is a Dynamic Towns construction trigger; an ordinary workstation (even a
     * fully funded one, per {@code TownPassiveProductionHandler}'s own passive credit) must never
     * register as an active region or start road/building construction.
     */
    private static Map<BlockPos, Villager> findClaimedCampfireSites(ServerLevel level) {
        Map<BlockPos, Villager> sites = new HashMap<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager villager) || villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                continue;
            }
            Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty() || !jobSite.get().dimension().equals(level.dimension())) {
                continue;
            }
            BlockPos pos = jobSite.get().pos();
            if (!level.getBlockState(pos).is(Blocks.CAMPFIRE)) {
                continue;
            }
            sites.put(pos, villager);
        }
        return sites;
    }

    /** Exposed {@code public} (mirroring {@code TownFounder#tryFoundAt}'s convention) so a GameTest
     *  can drive a single site deterministically without constructing a real {@link LevelTickEvent.Post}. */
    public static void buildAt(ServerLevel level, BlockPos campfire, TownStorage storage) {
        // [Review round 2, finding #1] The claim is looked up ONCE here, by real JOB_SITE memory
        // (never spatial proximity), and the SAME claimant villager is then reused for every check
        // and every placement below - never re-derived from "whoever happens to be near the
        // campfire right now".
        Optional<Villager> claimantOpt = TownCampfireClaims.findClaimant(level, campfire);
        if (claimantOpt.isEmpty()) {
            return;
        }
        Villager claimant = claimantOpt.get();

        RegionCoord region = TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ());
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        boolean isStorehouseSite = Math.abs(campfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(campfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite
                ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(campfire.getX(), campfire.getZ()));

        // [Review round 2, finding #2] The floor is the tree-excluded ESTIMATED ground height, not
        // the campfire's own (possibly arbitrary - a trunk, a ledge, a tall column) current Y.
        // estimateGroundHeightBelow (not the plain heightmap-driven estimateGroundHeight) is used
        // deliberately: the campfire block ITSELF already registers as motion-blocking once
        // placed, which would otherwise skew a heightmap query taken at its own column.
        int floorY = TownTerrain.estimateGroundHeightBelow(level, campfire);
        if (floorY <= level.getMinBuildHeight() || floorY >= level.getMaxBuildHeight()) {
            // A bounded, validated floor: refuse to plan a site whose estimated ground falls
            // outside the dimension entirely, rather than silently building garbage there.
            return;
        }

        // [Review round 4, finding #8] The workstation (and, on completion, the claimant's own
        // final job) for a commercial/industrial site is the ZONE's own deterministically-assigned
        // final profession - a pure function of this site's own seed/position - never the
        // claimant's current (always, in production, the temporary Builder) profession. "Another
        // campfire" was never a real workstation.
        Optional<VillagerProfession> finalProfession =
                TownProfessionWorkstations.finalProfessionFor(kind, level.getSeed(), campfire.getX(), campfire.getZ());
        List<TownBuildingTemplates.Placement> template = new ArrayList<>(TownBuildingTemplates.generate(
                kind, campfire.getX(), floorY, campfire.getZ(), plan.storehouseVariant(),
                finalProfession.orElse(null)));
        int radius = footprintRadius(kind);

        Map<String, TownBuildingTemplates.Placement> byPosition = new HashMap<>();
        for (TownBuildingTemplates.Placement placement : template) {
            byPosition.put(key(placement.x(), placement.y(), placement.z()), placement);
        }
        if (!byPosition.containsKey(key(campfire.getX(), campfire.getY(), campfire.getZ()))) {
            // The estimated floor and the campfire's own actual cell can differ (exactly the case
            // finding #2 fixes); retaining the original campfire cell "until the end" still means
            // it must eventually be replaced, so an explicit placement for its own exact position
            // is appended - bottomUpOrder (by x/y/z match) defers it to the very last block, same
            // as when it coincided with the floor plane.
            TownBuildingTemplates.Placement extra = new TownBuildingTemplates.Placement(
                    campfire.getX(), campfire.getY(), campfire.getZ(),
                    TownBuildingTemplates.wallBlockFor(kind), TownBuildingTemplates.costFor(kind));
            template.add(extra);
            byPosition.put(key(extra.x(), extra.y(), extra.z()), extra);
        }

        // [Review round 2, finding #3] Validate EVERY planned cell against the dimension's own
        // build height before anything is touched - not just the natural-terrain scan range
        // TownSitePreflight itself bounds its own search by. A campfire near the world ceiling can
        // otherwise plan roof cells strictly above TownTerrain#rawCanopyTop's own scan ceiling,
        // which the preflight below would never even visit, let alone reject.
        for (TownBuildingTemplates.Placement placement : template) {
            if (!withinBuildHeight(level, new BlockPos(placement.x(), placement.y(), placement.z()))
                    || !level.getWorldBorder().isWithinBounds(new BlockPos(placement.x(), placement.y(), placement.z()))) {
                return;
            }
        }

        // [Review finding #1 / round 2 finding #3] Preflight the WHOLE site before mutating
        // anything: a protected, player-built, or otherwise-occupied block anywhere in the
        // footprint (including another live claimed campfire), OR a cell outside the dimension's
        // build height, aborts this site for this tick, with no partial mutation and no resource
        // charge at all. A cell this mod has ALREADY placed and paid for (per
        // {@link TownStorage#isCellPlaced}, a previous tick's own persisted progress) is explicitly
        // allowed, so an in-progress site does not fail its own preflight forever once its walls
        // start going up.
        // [Review round 3, finding #3] The preflight must cover the WHOLE planned footprint
        // volume - floor through the tallest planned cell, walls and roof included - not merely
        // today's terrain/canopy top. Otherwise protected content above the current terrain but
        // still within the planned building (e.g. inside a tree canopy above the roofline, or a
        // player build overhead) is only discovered mid-build, after earlier cells are already
        // placed and charged.
        // [Review round 4, finding #2] "Already done" is now the PERSISTED progress record, never
        // merely "the live block happens to match the plan" - a player who pre-places the template's
        // own wall/floor material no longer bypasses protected-site rejection or resource cost,
        // because that foreign block was never recorded as this mod's own paid-for progress.
        int plannedTopY = floorY + TownBuildingTemplates.heightFor(kind, plan.storehouseVariant());
        if (!TownSitePreflight.isSiteSafe(level, campfire, radius, floorY, plannedTopY,
                pos -> storage.isCellPlaced(level.dimension(), pos))) {
            return;
        }

        TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), region));

        // [Review finding #4] Terrain preparation: estimate real ground (excluding trees) across
        // the footprint and clear/level it to the floor's Y, crediting salvage exactly once (the
        // removal itself makes the same material unavailable to credit again on a later tick,
        // reload, or retry). The campfire's own cell is left untouched here - it keeps its current
        // block (the campfire) until the bottom-up build reaches it last. Already-placed template
        // blocks (non-natural) are left alone, not re-cleared, so this cannot undo earlier progress.
        if (prepareTerrain(level, campfire, radius, floorY, store)) {
            storage.setDirty();
        }

        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(
                template.stream().map(TownBuildingTemplates.Placement::toPlan).toList(),
                campfire.getX(), campfire.getY(), campfire.getZ());

        int placedThisTick = 0;
        for (ConstructionSitePlan.PlannedBlock block : ordered) {
            if (placedThisTick >= TownConstants.CONSTRUCTION_BLOCKS_PER_TICK) {
                break;
            }
            BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
            TownBuildingTemplates.Placement placement = byPosition.get(key(block.x(), block.y(), block.z()));
            if (placement == null) {
                continue; // degenerately, no template entry - nothing to do here
            }
            // [Review round 4, finding #2] "Already placed" is the PERSISTED progress record, not a
            // live-world diff: only a cell THIS mod already placed and paid for (and whose block
            // still matches) is skipped for free - a foreign block that merely matches the planned
            // material is never treated as already-paid progress.
            boolean alreadyPlaced = storage.isCellPlaced(level.dimension(), pos);
            if (alreadyPlaced && level.getBlockState(pos).is(placement.block())) {
                continue;
            }
            if (!claimant.isAlive() || !TownCampfireClaims.isClaimedBy(claimant, level, campfire)) {
                // The claim can be lost mid-site (the villager wandered off its job or died); stop
                // rather than keep building for an unclaimed campfire. Checked against the SAME
                // claimant discovered above - never a fresh proximity re-scan.
                break;
            }
            // Revalidate this exact cell immediately before mutating it: the preflight above ran
            // once for the whole site, but the world (and any entity standing in this one cell)
            // can change between then and this specific placement.
            if (!TownSitePreflight.isSafeToOverwrite(level, pos, campfire)) {
                break;
            }
            if (TownSitePreflight.isEntityBlocking(level, pos, campfire)) {
                // Something (other than this site's own claimant) is standing exactly here right
                // now; skip just this cell rather than enclose it, and try again next tick.
                continue;
            }
            if (!withinBuildHeight(level, pos)) {
                // [Review round 2, finding #3] A planned cell outside the dimension's own build
                // height (e.g. a roof near the world ceiling) can never be placed; skip it without
                // charging, rather than repeatedly debiting for a mutation that cannot land.
                continue;
            }
            if (pos.equals(campfire) && hasUnplacedBlocksBefore(ordered, block, byPosition, level)) {
                // The campfire's own cell is deferred until literally everything else at the site
                // is built, even if this loop would otherwise have reached it first for some
                // other reason; bottomUpOrder already guarantees this ordering, so this is a
                // defensive re-check, not the primary mechanism.
                continue;
            }
            // [Review round 2, finding #3] Debit only AFTER a confirmed placement: first check
            // affordability (a non-mutating peek, never itself a charge), attempt the mutation, and
            // re-read the block to confirm it actually landed before the store is ever touched. A
            // rejected/failed mutation costs nothing and places nothing.
            // [Review round 4, finding #2] A cell already recorded as paid-for progress (the
            // griefed-then-rebuilt case above) is never charged again - only a cell not yet in the
            // persisted progress record pays.
            if (!alreadyPlaced && !store.canAfford(block.cost(), TownConstants.CONSTRUCTION_COST_PER_BLOCK)) {
                // Insufficient resources: pause construction at this site for this tick without
                // charging anything, per MINECRAFT-388 ("no free construction, no negative stores").
                break;
            }
            boolean mutated = level.setBlock(pos, placement.state(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            if (!mutated || !level.getBlockState(pos).is(placement.block())) {
                // The mutation was rejected (or silently failed) - nothing was placed, so nothing
                // is charged; try again next tick rather than aborting the whole site.
                continue;
            }
            if (!alreadyPlaced) {
                ConstructionSitePlan.tryPayForBlock(store, block, TownConstants.CONSTRUCTION_COST_PER_BLOCK);
            }
            storage.markCellPlaced(level.dimension(), pos);
            placedThisTick++;
        }
        if (placedThisTick > 0) {
            storage.setDirty();
        }

        // [Review round 3, finding #5 / round 4, finding #8] "Handle the actual Builder-to-final-
        // site transition": once every planned cell (the former campfire cell included) actually
        // matches the finished build, this claimant's temporary Builder job here is done. A
        // commercial/industrial site hands the claimant off to the zone's own real final profession
        // (job-site memory pointed at the real vanilla workstation this same template just built,
        // per {@link TownBuildingTemplates#workstationPosition}) rather than releasing it to NONE
        // with nothing real to do there; a residential/storehouse site (no workplace to hand off
        // to) still releases the claimant to NONE so vanilla can let it pick up a fresh job site
        // elsewhere.
        if (claimant.isAlive() && isSiteFullyBuilt(level, template)) {
            if (finalProfession.isPresent()) {
                BlockPos workstationPos = TownBuildingTemplates.workstationPosition(campfire.getX(), floorY, campfire.getZ());
                claimant.setVillagerData(claimant.getVillagerData().setProfession(finalProfession.get()));
                claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), workstationPos));
            } else {
                claimant.setVillagerData(claimant.getVillagerData().setProfession(VillagerProfession.NONE));
            }
        }
    }

    /** {@code true} once every planned cell in {@code template} already holds exactly its planned
     *  block - i.e. this site has nothing left to build. */
    private static boolean isSiteFullyBuilt(ServerLevel level, List<TownBuildingTemplates.Placement> template) {
        for (TownBuildingTemplates.Placement placement : template) {
            BlockPos pos = new BlockPos(placement.x(), placement.y(), placement.z());
            if (!level.getBlockState(pos).is(placement.block())) {
                return false;
            }
        }
        return true;
    }

    /** {@code true} when {@code pos} lies strictly within the dimension's own build-height bounds. */
    private static boolean withinBuildHeight(ServerLevel level, BlockPos pos) {
        return pos.getY() >= level.getMinBuildHeight() && pos.getY() < level.getMaxBuildHeight();
    }


    private static int footprintRadius(TownBuildingTemplates.Kind kind) {
        int width = kind == TownBuildingTemplates.Kind.STOREHOUSE ? TownConstants.STOREHOUSE_FOOTPRINT_WIDTH : 5;
        return (width - 1) / 2;
    }

    /**
     * Clears any tree/natural-terrain material strictly above the floor's Y (per column, bounded
     * by that column's own tree-excluded ground estimate) and fills any gap strictly below it with
     * plain dirt, so the template's walls always have solid support regardless of the terrain's
     * original shape. Every block actually removed this way is tallied by
     * {@link TownTerrain#classify} and credited via {@link TownClearingYields} exactly once, since
     * removing the block is what makes it unavailable to tally again.
     *
     * @return {@code true} if anything was mutated (so the caller knows whether to persist storage).
     */
    private static boolean prepareTerrain(ServerLevel level, BlockPos campfire, int radius, int baseY, TownResourceStore store) {
        Map<TownClearingYields.ClearedMaterial, Integer> tally = new EnumMap<>(TownClearingYields.ClearedMaterial.class);
        boolean mutated = false;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int columnX = campfire.getX() + x;
                int columnZ = campfire.getZ() + z;
                // The raw canopy top (trunks AND leaves), not the tree-excluded ground estimate:
                // this bound must actually include the tree material being cleared below.
                int top = TownTerrain.rawCanopyTop(level, columnX, columnZ);
                for (int y = baseY; y < top; y++) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (pos.equals(campfire)) {
                        // [Review round 2, finding #2] Never touch the campfire's own ACTUAL cell
                        // here, regardless of whether it happens to sit on the estimated floor
                        // plane baseY - the floor and the campfire's real position can now differ.
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (!TownTerrain.isNaturalTerrain(state)) {
                        // Not natural terrain (and not air): either this site's own already-placed
                        // template block from an earlier tick, or something the preflight above
                        // already would have aborted the whole site over. Either way, never clear
                        // it here - this pass salvages NATURAL terrain only.
                        continue;
                    }
                    // [Review round 3, finding #4] Classify BEFORE removal (removal destroys the
                    // state needed to classify it), but only actually TALLY the credit after
                    // confirming the removal landed - never before. A rejected/no-op removal
                    // leaves the material still physically there, so crediting it first would let
                    // it be credited again on a later tick/retry once it finally does get removed
                    // (or worse, forever if it never does). Once a cell IS actually removed it
                    // becomes air, so the `state.isAir()` check above already skips it on every
                    // later pass - no separate "already credited" bookkeeping is needed.
                    TownClearingYields.ClearedMaterial material = TownTerrain.classify(state);
                    boolean removed = level.removeBlock(pos, false) && level.getBlockState(pos).isAir();
                    if (!removed) {
                        continue;
                    }
                    mutated = true;
                    if (material != null) {
                        tally.merge(material, 1, Integer::sum);
                    }
                }
                // Fill a gap below the floor (the terrain dips lower than this site's fixed floor
                // height) with plain dirt - a leveling fill, not salvage, so it earns no credit.
                for (int y = top; y < baseY; y++) {
                    BlockPos pos = new BlockPos(columnX, y, columnZ);
                    if (pos.equals(campfire)) {
                        continue;
                    }
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.DIRT.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        mutated = true;
                    }
                }
            }
        }
        for (Map.Entry<TownClearingYields.ClearedMaterial, Integer> entry : tally.entrySet()) {
            TownClearingYields.Yield yield = TownClearingYields.yieldFor(entry.getKey());
            int credit = TownClearingYields.creditsFor(entry.getKey(), entry.getValue());
            if (credit > 0) {
                store.credit(yield.category(), credit);
            }
        }
        return mutated;
    }

    private static boolean hasUnplacedBlocksBefore(List<ConstructionSitePlan.PlannedBlock> ordered,
            ConstructionSitePlan.PlannedBlock current, Map<String, TownBuildingTemplates.Placement> byPosition, ServerLevel level) {
        for (ConstructionSitePlan.PlannedBlock other : ordered) {
            if (other == current) {
                break;
            }
            TownBuildingTemplates.Placement placement = byPosition.get(key(other.x(), other.y(), other.z()));
            if (placement != null && !level.getBlockState(new BlockPos(other.x(), other.y(), other.z())).is(placement.block())) {
                return true;
            }
        }
        return false;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
