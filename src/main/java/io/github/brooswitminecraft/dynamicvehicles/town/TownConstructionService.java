package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
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

        Map<BlockPos, Villager> claimedCampfires = findClaimedCampfireSites(level);
        if (claimedCampfires.isEmpty()) {
            return;
        }

        TownStorage storage = TownStorage.of(level.getServer());
        Set<RegionCoord> regionsWithActivity = new HashSet<>();
        for (Map.Entry<BlockPos, Villager> entry : claimedCampfires.entrySet()) {
            BlockPos campfire = entry.getKey();
            regionsWithActivity.add(TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()));
            if (!level.getBlockState(campfire).is(Blocks.CAMPFIRE)) {
                // Already fully built - the campfire cell itself was already replaced by its final
                // planned block, so there is nothing left to do at this site.
                continue;
            }
            buildAt(level, campfire, storage);
        }

        for (RegionCoord region : regionsWithActivity) {
            TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), region));
            if (TownRoadConstructionService.buildRoadsForRegion(level, region, store)) {
                storage.setDirty();
            }
        }
    }

    private static Map<BlockPos, Villager> findClaimedCampfireSites(ServerLevel level) {
        Map<BlockPos, Villager> sites = new HashMap<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager villager) || villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                continue;
            }
            Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isPresent() && jobSite.get().dimension().equals(level.dimension())) {
                sites.put(jobSite.get().pos(), villager);
            }
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
        int floorY = TownTerrain.estimateGroundHeight(level, campfire.getX(), campfire.getZ());
        if (floorY <= level.getMinBuildHeight() || floorY >= level.getMaxBuildHeight()) {
            // A bounded, validated floor: refuse to plan a site whose estimated ground falls
            // outside the dimension entirely, rather than silently building garbage there.
            return;
        }

        List<TownBuildingTemplates.Placement> template = new ArrayList<>(TownBuildingTemplates.generate(
                kind, campfire.getX(), floorY, campfire.getZ(), plan.storehouseVariant(),
                claimant.getVillagerData().getProfession()));
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
        // charge at all. A cell that already holds exactly what THIS site's own template planned
        // there (a previous tick's progress) is explicitly allowed, so an in-progress site does
        // not fail its own preflight forever once its walls start going up.
        if (!TownSitePreflight.isSiteSafe(level, campfire, radius, floorY,
                pos -> {
                    TownBuildingTemplates.Placement planned = byPosition.get(key(pos.getX(), pos.getY(), pos.getZ()));
                    return planned != null && level.getBlockState(pos).is(planned.block());
                })) {
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
            if (placement == null || level.getBlockState(pos).is(placement.block())) {
                // Already placed (or, degenerately, no template entry) - nothing to do here.
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
            if (!store.canAfford(block.cost(), TownConstants.CONSTRUCTION_COST_PER_BLOCK)) {
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
            ConstructionSitePlan.tryPayForBlock(store, block, TownConstants.CONSTRUCTION_COST_PER_BLOCK);
            placedThisTick++;
        }
        if (placedThisTick > 0) {
            storage.setDirty();
        }
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
                    TownClearingYields.ClearedMaterial material = TownTerrain.classify(state);
                    if (material != null) {
                        tally.merge(material, 1, Integer::sum);
                    }
                    level.removeBlock(pos, false);
                    mutated = true;
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
