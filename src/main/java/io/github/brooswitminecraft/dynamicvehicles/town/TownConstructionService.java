package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
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
import java.util.UUID;

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

    /** Test-only fault injection for the terrain-preparation transaction.  Minecraft's normal
     * world implementation does not offer a deterministic way to reject one intermediate write;
     * this hook lets the focused production GameTest prove that an interruption restores every
     * earlier write before construction can continue. */
    static BlockPos testForceTerrainMutationFailureAt = null;
    /** Test-only fault injection for proving that an unconfirmed terrain rollback remains a
     * durable stop, rather than becoming a later build-through. */
    static BlockPos testForceTerrainRollbackFailureAt = null;
    /**
     * Test-only injection for the administrative quarantine path after a building write lands but
     * before it is debited/recorded.  This verifies serialization of an explicitly requested
     * exclusion; it is NOT crash recovery for a process that dies at this boundary.  Kept
     * package-private so it is not a construction-consumer API.
     */
    static BlockPos testForcePlacementInterruptionAt = null;

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
        runConstructionAndRoadsTick(level, TownStorage.of(level.getServer()), TownRoadConstructionService::buildRoadsForRegion);
    }

    /** The road-work boundary of the production construction tick. Package-private solely so a
     *  disposable GameTest can prove the founded-region/player activation gate with a route that
     *  fits its tiny fixture; production always supplies {@link TownRoadConstructionService}. */
    @FunctionalInterface
    interface RoadWork {
        boolean build(ServerLevel level, RegionCoord region, TownStorage storage, TownResourceStore store);
    }

    static void runConstructionAndRoadsTick(ServerLevel level, TownStorage storage, RoadWork roadWork) {
        // [Review round 4, finding #4] Dynamic Towns (founding, building, and road construction
        // alike) is an Overworld-only feature, matching vanilla's own village generation never
        // occurring in the Nether/End - an explicit, documented scope decision rather than letting
        // the same sea-level-biased road/terrain policy silently misbehave in every dimension.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }

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
            if (roadWork.build(level, region, storage, store)) {
                storage.setDirty();
            }
        }
    }

    /**
     * Records one validated, durable request for construction capacity on behalf of a resident.
     *
     * <p>This boundary intentionally does <strong>not</strong> place a campfire, select a Builder,
     * debit resources, reserve a POI, or assign HOME/JOB_SITE.  Those are transaction effects owned
     * by the later construction/handoff path.  Persisting an admission before any such effect gives
     * duplicate allocation ticks and a save/load cycle one identity to reuse, while failing closed
     * until a later construction continuation can bind the intent to an actual claimed site.
     */
    static Optional<ResidentCapacityAdmission> requestResidentCapacity(ServerLevel level, RegionCoord region,
            UUID beneficiaryId, TownResidentDemand demand, TownStorage storage) {
        if (!level.dimension().equals(Level.OVERWORLD) || demand == TownResidentDemand.NONE
                || (demand.missingHomes() == 0 && demand.missingWorkplaces().isEmpty())) {
            return Optional.empty();
        }
        String townKey = TownKeys.keyFor(level.dimension(), region);
        if (!storage.hasFoundedTown(townKey)) {
            return Optional.empty();
        }

        Villager beneficiary = null;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Villager villager && villager.getUUID().equals(beneficiaryId) && villager.isAlive()) {
                beneficiary = villager;
                break;
            }
        }
        if (beneficiary == null
                || !TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ()).equals(region)
                // A temporary Builder is the real claimant of a site, never an allocation beneficiary.
                || beneficiary.getVillagerData().getProfession() == BuilderProfession.BUILDER.get()) {
            return Optional.empty();
        }

        TownResidentDemand currentDemand = TownResidentAllocationHandler.unmetDemand(level, region, storage);
        if (currentDemand == TownResidentDemand.NONE || demand.missingHomes() > currentDemand.missingHomes()
                || demand.missingWorkplaces().entrySet().stream().anyMatch(entry -> entry.getValue()
                        > currentDemand.missingWorkplaces().getOrDefault(entry.getKey(), 0))) {
            return Optional.empty();
        }
        int requestedHomes = demand.missingHomes() > 0 ? 1 : 0;
        VillagerProfession retainedProfession = beneficiary.getVillagerData().getProfession();
        if (demand.missingWorkplaces().keySet().stream().anyMatch(profession -> profession == VillagerProfession.NONE
                || profession == VillagerProfession.NITWIT || profession == BuilderProfession.BUILDER.get())) {
            return Optional.empty();
        }
        Map<VillagerProfession, Integer> requestedWorkplaces = demand.missingWorkplaces().containsKey(retainedProfession)
                ? Map.of(retainedProfession, 1) : Map.of();
        if (requestedHomes > 0 && hasValidBeneficiaryHome(level, beneficiary, region, storage)) {
            requestedHomes = 0;
        }
        if (!requestedWorkplaces.isEmpty() && hasValidBeneficiaryWorkplace(level, beneficiary, region, storage)) {
            requestedWorkplaces = Map.of();
        }
        if (requestedHomes == 0 && requestedWorkplaces.isEmpty()) {
            return Optional.empty();
        }

        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        String siteOwner = "capacity:" + townKey + ":" + beneficiaryId;
        String planSignature = "capacity-v1:" + plan.storehouseVariant() + ":" + requestedHomes + ":"
                + requestedWorkplaces.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(
                        profession -> net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).toString())))
                .map(entry -> net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(entry.getKey())
                        + "=" + entry.getValue())
                .reduce((left, right) -> left + "," + right).orElse("none");
        ResidentCapacityAdmission admission = new ResidentCapacityAdmission(level.dimension(), townKey, region, beneficiaryId,
                requestedHomes, requestedWorkplaces, siteOwner, planSignature,
                ResidentCapacityAdmission.State.AWAITING_CLAIM);
        Optional<ResidentCapacityAdmission> existing = storage.capacityAdmissionFor(level.dimension(), region, beneficiaryId);
        if (existing.isPresent() && !existing.get().state().terminal()) {
            ResidentCapacityAdmission current = existing.get();
            if (current.requestedHomes() == requestedHomes
                    && current.requestedWorkplaces().equals(requestedWorkplaces)) {
                return existing;
            }
            return storage.replaceAwaitingCapacityAdmission(admission);
        }
        return Optional.of(storage.createOrReuseCapacityAdmission(admission));
    }

    private static boolean hasValidBeneficiaryHome(ServerLevel level, Villager villager, RegionCoord region,
            TownStorage storage) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension().equals(level.dimension()))
                .filter(home -> TownRegion.fromBlockCoords(home.pos().getX(), home.pos().getZ()).equals(region))
                .filter(home -> !storage.isReservedByADifferentVillager(level.dimension(), home.pos(), villager.getUUID()))
                .filter(home -> !TownResidentAllocationHandler.isCurrentlyClaimedByAnotherVillager(level, home.pos(), villager))
                .map(home -> level.getPoiManager().exists(home.pos(), type -> type.is(PoiTypes.HOME))
                        && level.getPoiManager().getType(home.pos())
                                .map(type -> level.getPoiManager().getFreeTickets(home.pos()) < type.value().maxTickets()).orElse(false))
                .orElse(false);
    }

    private static boolean hasValidBeneficiaryWorkplace(ServerLevel level, Villager villager, RegionCoord region,
            TownStorage storage) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        return profession != VillagerProfession.NONE && villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .filter(site -> TownRegion.fromBlockCoords(site.pos().getX(), site.pos().getZ()).equals(region))
                .filter(site -> !storage.isReservedByADifferentVillager(level.dimension(), site.pos(), villager.getUUID()))
                .filter(site -> !TownResidentAllocationHandler.isCurrentlyClaimedByAnotherVillager(level, site.pos(), villager))
                .map(site -> level.getPoiManager().getType(site.pos()).map(profession.heldJobSite()::test).orElse(false)
                        && level.getPoiManager().getType(site.pos())
                                .map(type -> level.getPoiManager().getFreeTickets(site.pos()) < type.value().maxTickets()).orElse(false))
                .orElse(false);
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
        String siteOwner = TownStorage.siteOwner(campfire);
        // A failed terrain rollback quarantines the entire site, not only currently-visible cells:
        // later natural coverage can change the heightmap and hide the exact failed support.
        if (storage.isTerrainSiteBlocked(level.dimension(), campfire)) {
            return;
        }

        RegionCoord region = TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ());
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        boolean isStorehouseSite = Math.abs(campfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(campfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite
                ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(campfire.getX(), campfire.getZ()));

        // [Review round 2, finding #2 / round 5 follow-up] The floor is the tree-excluded ESTIMATED
        // ground height, not the campfire's own (possibly arbitrary - a trunk, a ledge, a tall
        // column) current Y. estimateGroundHeightBelow (not the plain heightmap-driven
        // estimateGroundHeight) is used deliberately: the campfire block ITSELF already registers as
        // motion-blocking once placed, which would otherwise skew a heightmap query taken at its own
        // column. This estimate is PERSISTED per site the first time it is computed, and reused on
        // every later tick, rather than re-derived from the live world each time: once terrain prep
        // clears a trunk the campfire was standing on, a fresh estimate taken on the NEXT tick would
        // no longer see that (now-removed) trunk material to walk down through at all, snapping the
        // floor estimate straight back up to the campfire's own elevated Y mid-build and silently
        // replanning the whole site around a completely different, inconsistent floor.
        Optional<Integer> persistedFloor = storage.floorFor(siteOwner);
        int floorY;
        if (persistedFloor.isPresent()) {
            floorY = persistedFloor.get();
        } else {
            floorY = TownTerrain.estimateGroundHeightBelow(level, campfire);
            if (floorY <= level.getMinBuildHeight() || floorY >= level.getMaxBuildHeight()) {
                // A bounded, validated floor: refuse to plan a site whose estimated ground falls
                // outside the dimension entirely, rather than silently building garbage there.
                return;
            }
            storage.setFloor(siteOwner, floorY);
            storage.setDirty();
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

        // [Review round 7, finding #1] A pure identifier for THIS exact plan (kind, storehouse
        // variant, and final profession all deterministically decide what generate() produces) -
        // combined per-cell with that cell's own expected block/cost in cellSignature below, so a
        // persisted "already placed" record is only ever honored when it was paid for under THIS
        // precise plan, never merely at this owner's position under some other (recreated/upgraded/
        // differently-seeded) plan.
        String planSignature = kind.name() + ":" + plan.storehouseVariant() + ":"
                + finalProfession.map(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION::getKey).map(Object::toString).orElse("none");

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
            // [Review round 5, finding #3 / round 6, finding #2] The campfire's (x, z) is always
            // the template's own CENTER column - generate() only ever plans a FLOOR cell there (at
            // baseY) and a ROOF cell there (at baseY+height); walls are perimeter-only and never
            // placed at the center. Reaching this branch at all means byPosition does NOT already
            // contain the center's floor or roof cell for this exact Y, so the real template voxel
            // here is unconditionally AIR - never an invented wall block, regardless of whether this
            // Y happens to fall between the floor and roof (a genuinely unplanned interior cell) or
            // outside that span entirely (e.g. a campfire well above the planned roof).
            TownBuildingTemplates.Placement extra = new TownBuildingTemplates.Placement(
                    campfire.getX(), campfire.getY(), campfire.getZ(), Blocks.AIR, TownBuildingTemplates.costFor(kind));
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
        // [Review round 11, finding #2 supplement] A cell durably blocked after a DIFFERENT plan's
        // own forward-write-plus-rollback failure (most commonly a road's) must never be silently
        // adopted by a claimed building as ordinary clearable terrain - the whole site aborts, the
        // same way any other protected/foreign content already does.
        // A persisted payment record is not permission to reconstruct a changed cell for free.
        // In particular, AIR is normally safe terrain, so it cannot be left to the generic
        // preflight predicate: a paid floor/wall that was destroyed would otherwise pass the
        // whole-site scan, be restored, and only then discover a later protected cell. Inspect
        // every exact plan-owned paid record before terrain preparation or any building mutation;
        // preserve every mismatch (including AIR) for explicit recovery rather than charging,
        // crediting, restoring, or rewriting its progress record here.
        for (TownBuildingTemplates.Placement placement : template) {
            BlockPos pos = new BlockPos(placement.x(), placement.y(), placement.z());
            String signature = cellSignature(planSignature, placement);
            if (storage.isCellPlaced(level.dimension(), pos, siteOwner, signature)
                    && !level.getBlockState(pos).equals(placement.state())) {
                return;
            }
        }
        if (!TownSitePreflight.isSiteSafe(level, campfire, radius, floorY, plannedTopY,
                pos -> {
                    TownBuildingTemplates.Placement planned = byPosition.get(key(pos.getX(), pos.getY(), pos.getZ()));
                    return planned != null
                            && storage.isCellPlaced(level.dimension(), pos, siteOwner, cellSignature(planSignature, planned));
                }, claimant.getUUID(), pos -> storage.isCellBlocked(level.dimension(), pos))) {
            return;
        }

        TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), region));

        // [Review finding #4] Terrain preparation: estimate real ground (excluding trees) across
        // the footprint and clear/level it to the floor's Y, crediting salvage exactly once (the
        // removal itself makes the same material unavailable to credit again on a later tick,
        // reload, or retry). The campfire's own cell is left untouched here - it keeps its current
        // block (the campfire) until the bottom-up build reaches it last. Already-placed template
        // blocks (non-natural) are left alone, not re-cleared, so this cannot undo earlier progress.
        TerrainPreparation terrainPreparation = prepareTerrain(level, campfire, radius, floorY, store, storage);
        if (terrainPreparation == TerrainPreparation.ABORTED) {
            // An intermediate terrain write was rejected (or its rollback could not be verified).
            // Do not advance the building or debit resources from an incomplete preparation.
            return;
        }
        if (terrainPreparation == TerrainPreparation.MUTATED) {
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
            if (storage.isCellBlocked(level.dimension(), pos)) {
                // [Review round 11, finding #2 supplement] Redundant with the whole-site preflight
                // above but kept here too as an explicit, defensive guard directly at the point of
                // mutation: never place over a cell durably excluded after a different plan's own
                // failed rollback.
                continue;
            }
            String signature = cellSignature(planSignature, placement);
            // [Review round 4, finding #2] "Already placed" is the PERSISTED progress record, not a
            // live-world diff: only a cell THIS mod already placed and paid for (and whose block
            // still matches) is skipped for free - a foreign block that merely matches the planned
            // material is never treated as already-paid progress.
            boolean alreadyPlaced = storage.isCellPlaced(level.dimension(), pos, siteOwner, signature);
            if (alreadyPlaced) {
                if (!level.getBlockState(pos).equals(placement.state())) {
                    // The whole-site pass above rejects a mismatch observed at admission. Keep the
                    // same guard at the actual mutation point in case the live world changed after
                    // admission; never turn a paid-record mismatch into a free rebuild.
                    break;
                }
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
            if (TownSitePreflight.isEntityBlocking(level, pos, campfire, claimant.getUUID())) {
                // Something (other than this site's own EXACT claimant, by identity) is standing
                // exactly here right now; skip just this cell rather than enclose it, and try again
                // next tick.
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
            // [Review round 5, finding #1] The claimant legitimately parks AT the campfire cell
            // while working, which is fine for every OTHER placement (isEntityBlocking exempts the
            // whole column above it) - but the final closing placement here can be a SOLID block
            // landing exactly where the claimant's own body still is. Relocate it to a validated
            // safe, reachable exit just outside the footprint before a solid closing block lands;
            // an AIR target (the elevated-campfire case) never needs this - removing a block cannot
            // place anything through anyone's body.
            if (pos.equals(campfire) && !placement.block().defaultBlockState().isAir()) {
                if (!relocateClaimantAwayFromCampfireIfStandingThere(level, claimant, campfire, radius)) {
                    // No safe exit found this tick - skip this cell rather than risk placing a
                    // solid block through the claimant's body; retry next tick.
                    continue;
                }
                // [Review round 6, finding #1] Re-check this exact cell's AABB immediately before
                // closure, with NO exemption at all (not even the claimant) - relocation just
                // happened, but something else (another entity, or the claimant itself failing to
                // actually move for some reason) could still occupy this cell right now.
                if (!level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(pos)).isEmpty()) {
                    continue;
                }
            }
            // [Review round 2, finding #3] Debit only AFTER a confirmed placement: first check
            // affordability (a non-mutating peek, never itself a charge), attempt the mutation, and
            // re-read the block to confirm it actually landed before the store is ever touched. A
            // rejected/failed mutation costs nothing and places nothing.
            // [Review round 4, finding #2] A cell already recorded as paid-for progress (the
            // griefed-then-rebuilt case above) is never charged again - only a cell not yet in the
            // persisted progress record pays.
            // [Review round 16, finding #1] Checked BEFORE any handoff reservation below (not
            // after): reserving a destination and only THEN discovering this closing cell is
            // unaffordable would leak that reservation on this break - nothing left holding it, but
            // the ticket still occupied, permanently blocking the site's own eventual completion.
            if (!alreadyPlaced && !store.canAfford(block.cost(), TownConstants.CONSTRUCTION_COST_PER_BLOCK)) {
                // Insufficient resources: pause construction at this site for this tick without
                // charging anything, per MINECRAFT-388 ("no free construction, no negative stores").
                break;
            }
            // [Review round 14, finding #1] "Handle the actual Builder-to-final-site transition" -
            // but BEFORE the campfire (the site's own sole discoverable anchor; see
            // findClaimedCampfireSites) is ever allowed to close. Reaching this point for pos ==
            // campfire means literally every OTHER planned cell already matches (bottomUpOrder /
            // hasUnplacedBlocksBefore above), i.e. this closing placement is the LAST thing the site
            // needs - exactly the moment reservation must succeed BEFORE the anchor disappears.
            // Closing first and reserving after (the round-12/13 shape) could leave a contested
            // fixture in a terminal, un-retryable state: once the campfire is gone, no later
            // production tick ever calls buildAt for this site again, regardless of what the handoff
            // itself then decided to do. A contested destination here simply skips this one cell for
            // this tick - the campfire (and the claim) stay fully intact - so the very next tick
            // naturally retries, with no separate pending-handoff bookkeeping needed at all.
            Optional<ReservedHandoff> reservedHandoff = Optional.empty();
            if (pos.equals(campfire)) {
                // [Review round 25, P2] Only THIS plan's own paid fixture cell, still holding the
                // fixture the plan placed there, may be handed to the claimant.
                java.util.function.Predicate<BlockPos> isOwnPlannedFixture = fixturePos -> {
                    TownBuildingTemplates.Placement planned = byPosition.get(key(fixturePos.getX(), fixturePos.getY(), fixturePos.getZ()));
                    return planned != null
                            && storage.isCellPlaced(level.dimension(), fixturePos, siteOwner, cellSignature(planSignature, planned))
                            && TownStorage.isSameFixture(planned.state(), level.getBlockState(fixturePos));
                };
                reservedHandoff = tryReserveHandoffDestinations(level, storage, claimant, campfire, floorY, kind, finalProfession,
                        isOwnPlannedFixture);
                if (reservedHandoff.isEmpty()) {
                    continue;
                }
            }
            boolean mutated = level.setBlock(pos, placement.state(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            if (!mutated || !level.getBlockState(pos).is(placement.block())) {
                // The mutation was rejected (or silently failed) - nothing was placed, so nothing
                // is charged; try again next tick rather than aborting the whole site. Any
                // reservation already secured above is simply held for the next attempt - the next
                // placement attempt will either close the campfire with this SAME reservation once
                // it finally lands, or this loop runs again from scratch next tick.
                if (reservedHandoff.isPresent()) {
                    releaseReservedHandoff(level, storage, reservedHandoff.get());
                }
                continue;
            }
            if (pos.equals(testForcePlacementInterruptionAt)) {
                testForcePlacementInterruptionAt = null;
                // Explicit test quarantine: the world write landed but no debit/paid-progress
                // record has been committed.  This only proves the persisted exclusion path; a
                // genuine process-crash recovery contract remains separately unresolved.
                storage.markCellBlocked(level.dimension(), pos);
                if (reservedHandoff.isPresent()) {
                    releaseReservedHandoff(level, storage, reservedHandoff.get());
                }
                break;
            }
            if (!alreadyPlaced) {
                ConstructionSitePlan.tryPayForBlock(store, block, TownConstants.CONSTRUCTION_COST_PER_BLOCK);
            }
            storage.markCellPlaced(level.dimension(), pos, siteOwner, signature, placement.state());
            placedThisTick++;
            if (reservedHandoff.isPresent()) {
                // The campfire closure just landed with every destination already secured - the
                // site is now genuinely, completely finished.
                commitClaimantLifecycle(level, storage, claimant, finalProfession, reservedHandoff.get());
                // The persisted floor commitment has served its purpose once the site is actually done.
                storage.clearFloor(siteOwner);
            }
        }
        if (placedThisTick > 0) {
            storage.setDirty();
        }
    }

    /** The destinations a claimant's handoff needs, once every one of them has actually been
     *  reserved (a real POI ticket held) - see {@link #tryReserveHandoffDestinations}. Either field
     *  may be {@code null} when this kind/profession combination does not need it. */
    private record ReservedHandoff(BlockPos workstationPos, BlockPos bedPos) {}

    /**
     * MINECRAFT-388 review round 14, finding #1: every destination a claimant's handoff needs must
     * be reserved BEFORE the campfire - the site's own sole discoverable anchor
     * ({@link #findClaimedCampfireSites} requires a real, physically-present {@code CAMPFIRE} block
     * at the claimant's exact {@code JOB_SITE}) - is ever allowed to close. Closing the campfire
     * first and only THEN attempting reservation (the round-12/13 shape) could leave a contested
     * fixture in a terminal, un-retryable state: once the campfire is gone, no later production tick
     * ever calls {@link #buildAt} for this site again, regardless of what the handoff itself then
     * decided to do.
     *
     * <p>Reservation itself is ALL-OR-NOTHING: a claimant that needs both a workstation AND a bed
     * must end up with BOTH or NEITHER - every ticket already taken this attempt is rolled back the
     * moment any required one fails, rather than leaving the other reserved for a handoff that never
     * actually commits.
     *
     * <p>[Review round 25, P2] A destination is only ever reserved when {@code isOwnPlannedFixture}
     * confirms it is this plan's own paid fixture cell still holding the fixture the plan placed -
     * the handoff never reserves a coordinate merely because something paid for it once. (The
     * closing placement already waits until every planned cell holds its planned block, and a
     * different player fixture there is foreign content the build refuses to overwrite, so this
     * cannot normally fail; it keeps the reservation itself honest rather than relying on that.)
     *
     * @return the reserved destinations once every one of them is secured, or empty if any required
     *         one is contested - in which case nothing was committed, nothing stays reserved, and
     *         the caller must leave the campfire itself unclosed so a later tick naturally retries.
     */
    private static Optional<ReservedHandoff> tryReserveHandoffDestinations(ServerLevel level, TownStorage storage,
            Villager claimant, BlockPos campfire, int floorY, TownBuildingTemplates.Kind kind,
            Optional<VillagerProfession> finalProfession, java.util.function.Predicate<BlockPos> isOwnPlannedFixture) {
        boolean needsWorkstation = finalProfession.isPresent();
        boolean needsBed = kind == TownBuildingTemplates.Kind.RESIDENTIAL || kind == TownBuildingTemplates.Kind.COMMERCIAL
                || kind == TownBuildingTemplates.Kind.INDUSTRIAL;
        BlockPos workstationPos = needsWorkstation
                ? TownBuildingTemplates.workstationPosition(campfire.getX(), floorY, campfire.getZ()) : null;
        BlockPos bedPos = needsBed ? TownBuildingTemplates.bedPosition(campfire.getX(), floorY, campfire.getZ()) : null;

        boolean workstationReserved = !needsWorkstation
                || (isOwnPlannedFixture.test(workstationPos) && acquirePoiTicket(level, storage, claimant, workstationPos));
        boolean bedReserved = !needsBed
                || (isOwnPlannedFixture.test(bedPos) && acquirePoiTicket(level, storage, claimant, bedPos));
        if (!workstationReserved || !bedReserved) {
            if (workstationReserved && needsWorkstation) {
                releasePoiTicket(level, storage, workstationPos);
            }
            if (bedReserved && needsBed) {
                releasePoiTicket(level, storage, bedPos);
            }
            return Optional.empty();
        }
        return Optional.of(new ReservedHandoff(workstationPos, bedPos));
    }

    /**
     * Commits the Builder-to-final-site transition, given a {@link ReservedHandoff} whose
     * destinations are ALREADY secured by {@link #tryReserveHandoffDestinations} - called only once
     * the campfire's own closing placement has itself already succeeded, so this always runs for a
     * site that is now genuinely, completely finished.
     * <ul>
     *   <li>[Round 13] {@code HOME} is never blindly erased: a kind with no bed of its own
     *       (storehouse) leaves the claimant's existing home (if any) completely untouched.</li>
     *   <li>[Round 14] The old home's release resolves its {@code GlobalPos} against its OWN actual
     *       {@link ServerLevel} (via {@link net.minecraft.server.MinecraftServer#getLevel}), not just
     *       this level - a same-position release call made against the WRONG level's
     *       {@code PoiManager} would silently do nothing, permanently leaking a cross-dimension
     *       reservation with no memory left to ever release it from.</li>
     *   <li>[Round 13] Stale Builder trade state (offers/XP/level) is reset on EVERY path the
     *       claimant leaves Builder through, not only the hand-off-to-a-real-job branch.</li>
     * </ul>
     *
     * <p>Bounded to this one known claimant per site, exactly as the round-3/4 precedent this
     * extends already was: town-wide allocation of beds/workplaces across OTHER residents (not the
     * site's own claimant) remains a separate, larger, explicitly out-of-scope item.
     */
    private static void commitClaimantLifecycle(ServerLevel level, TownStorage storage, Villager claimant,
            Optional<VillagerProfession> finalProfession, ReservedHandoff reserved) {
        claimant.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        // Reset XP/level on EVERY path, not only a real handoff - stale Builder XP/level must never
        // carry over into a new profession's own trade tier. Offers themselves need no explicit
        // clearing: Villager#setVillagerData already nulls its cached offers whenever the
        // profession actually changes (below), lazily regenerating a fresh, correct trade list for
        // whatever profession ends up set - explicitly clearing them here would be redundant for
        // NONE (which has no trades to regenerate anyway) and actively wrong for a real handoff
        // (it would wipe out the new profession's own legitimately-generated trades).
        claimant.setVillagerXp(0);
        claimant.setVillagerData(claimant.getVillagerData().setLevel(1));

        if (finalProfession.isPresent()) {
            claimant.setVillagerData(claimant.getVillagerData().setProfession(finalProfession.get()));
            claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), reserved.workstationPos()));
        } else {
            claimant.setVillagerData(claimant.getVillagerData().setProfession(VillagerProfession.NONE));
        }

        if (reserved.bedPos() != null) {
            // The new home is already secured (reserved above) - release whatever old one the
            // claimant had, resolved against ITS OWN actual level, never assumed to be this one.
            claimant.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(old -> {
                ServerLevel oldLevel = old.dimension().equals(level.dimension())
                        ? level : level.getServer().getLevel(old.dimension());
                boolean sameAsNewReservation = old.dimension().equals(level.dimension()) && old.pos().equals(reserved.bedPos());
                // [Review round 24: requires POSITIVE proof, mirroring TownResidentAllocationHandler's
                // own identical fix] Only release when this mod's own registry POSITIVELY records
                // this claimant as the current owner - "not known to be someone else's" (registry
                // silent) is not proof it is this claimant's own: an untracked, currently-unloaded
                // real owner would pass that check too, and a stale claimant memory could then steal
                // its reservation. Preferring to PRESERVE an unresolved old home (never releasing it
                // without positive proof) over risking that theft is the deliberate trade-off.
                // [Review round 25] That registry "self" record is HISTORICAL, not live - it can go
                // stale if the ticket is later released and re-acquired by some vanilla villager
                // entirely outside this mod's own bookkeeping. The live scan is restored as an
                // additional veto: even a "self"-recorded reservation is never released if some OTHER
                // currently-loaded villager's own live memory also claims it right now.
                boolean provenOwner = oldLevel != null
                        && storage.isReservedBySelf(old.dimension(), old.pos(), claimant.getUUID());
                boolean liveOwnedByAnother = oldLevel != null
                        && TownResidentAllocationHandler.isCurrentlyClaimedByAnotherVillager(oldLevel, old.pos(), claimant);
                if (oldLevel != null && !sameAsNewReservation && provenOwner && !liveOwnedByAnother) {
                    releasePoiTicket(oldLevel, storage, old.pos());
                } else if (!sameAsNewReservation && provenOwner && liveOwnedByAnother) {
                    // The claimant's record is contradicted by another villager's live claim - drop
                    // it, mirroring TownResidentAllocationHandler#releaseOldReservation, so it cannot
                    // authorize a release later once that villager unloads.
                    storage.clearReservationOwner(old.dimension(), old.pos());
                }
                // oldLevel == null: that dimension no longer exists/isn't loaded - there is no
                // PoiManager left to release anything from; nothing further can be done safely.
                // sameAsNewReservation: the exact same reservation just re-secured above - releasing
                // it here would immediately free the ticket this handoff just took.
            });
            claimant.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), reserved.bedPos()));
        }
        // A kind with no bed of its own (storehouse) touches HOME at all: the claimant keeps
        // whatever home (if any) it already had.
    }

    /**
     * Actually reserves (acquires a ticket for) the POI record at {@code pos} - the real vanilla
     * reservation a villager's AI would otherwise acquire on its own, done explicitly here since
     * this handoff is a direct memory/profession assignment, not a goal a villager walked up and
     * triggered itself. Returns {@code false} (acquiring nothing) when the fixture has no POI type
     * at all, OR when it does but every one of its tickets is already held - e.g. by some other,
     * unrelated villager - in which case the caller must never write a memory claiming it anyway.
     *
     * <p>Registers the record directly (via {@link PoiManager#add}) rather than relying on it
     * already being there: {@link ServerLevel#onBlockStateChange} - the normal hook that would
     * otherwise register it - defers the actual {@code PoiManager} update by one server tick (it
     * runs via {@code getServer().execute(...)}), so a block placed and then handed off within the
     * SAME tick (routine for a small site that finishes in one {@link #buildAt} call) would
     * otherwise still read as unregistered here. {@code add} is idempotent for an already-registered
     * position, so calling it unconditionally is always safe.
     */
    private static boolean acquirePoiTicket(ServerLevel level, TownStorage storage, Villager claimant, BlockPos pos) {
        net.minecraft.world.entity.ai.village.poi.PoiManager poiManager = level.getPoiManager();
        Optional<net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> type =
                net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(level.getBlockState(pos));
        if (type.isEmpty()) {
            return false;
        }
        poiManager.add(pos, type.get());
        boolean acquired = poiManager.take(t -> true, (t, p) -> p.equals(pos), pos, 1).isPresent();
        if (acquired) {
            // [Review round 20, finding #1] Records exactly which villager this mod granted the
            // ticket to - PoiManager itself tracks only a free-ticket count, never per-entity
            // ownership - so a later stale memory elsewhere can be told apart from this claimant's
            // own genuinely-held reservation.
            storage.setReservationOwner(level.dimension(), pos, claimant.getUUID());
        }
        return acquired;
    }

    /** Releases a previously-held ticket at {@code pos}, if its POI record still exists - used when
     *  a claimant's OLD fixture (e.g. a prior home elsewhere) is being given up for a NEW one that
     *  has already been secured. Also clears this mod's own ownership record for {@code pos}, so a
     *  later occupant is never read as still "owned" by whoever held it before. */
    private static void releasePoiTicket(ServerLevel level, TownStorage storage, BlockPos pos) {
        net.minecraft.world.entity.ai.village.poi.PoiManager poiManager = level.getPoiManager();
        if (poiManager.exists(pos, type -> true)) {
            poiManager.release(pos);
        }
        storage.clearReservationOwner(level.dimension(), pos);
    }

    /**
     * [Review round 5, finding #1] If {@code claimant} is currently standing in {@code campfire}'s
     * own column, relocate it to a validated, reachable, safe exit just outside the footprint
     * before a solid block closes over the campfire cell. Returns {@code true} once the claimant is
     * confirmed clear of the column (whether or not relocation was needed), {@code false} if it is
     * still standing there and no safe exit could be found this tick.
     */
    private static boolean relocateClaimantAwayFromCampfireIfStandingThere(ServerLevel level, Villager claimant,
            BlockPos campfire, int radius) {
        if (claimant.blockPosition().getX() != campfire.getX() || claimant.blockPosition().getZ() != campfire.getZ()) {
            return true; // not standing in this column at all - nothing to relocate
        }
        BlockPos exit = findSafeExitNear(level, campfire, radius);
        if (exit == null) {
            return false;
        }
        claimant.teleportTo(exit.getX() + 0.5, exit.getY(), exit.getZ() + 0.5);
        return true;
    }

    /** A nearby, reachable, safe-to-stand-on cell strictly outside the {@code radius} footprint
     *  (so the exit itself is never a cell this site might still build over), searched first just
     *  past the site's own south-facing door (the normal, intended way out) and then in a ring one
     *  cell further out all around the footprint. {@code null} if nothing safe was found nearby. */
    private static BlockPos findSafeExitNear(ServerLevel level, BlockPos campfire, int radius) {
        BlockPos pastDoor = campfire.offset(0, 0, radius + 1);
        if (isSafeStandingSpot(level, pastDoor)) {
            return pastDoor;
        }
        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) <= radius) {
                    continue; // still inside the footprint - never relocate INTO the site itself
                }
                BlockPos candidate = campfire.offset(x, 0, z);
                if (isSafeStandingSpot(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** {@code true} when a villager could actually stand at {@code pos} right now SAFELY: solid,
     *  non-hazardous ground below, non-colliding non-hazardous space at feet and head height, and
     *  no other entity already there.
     *
     * <p>[Review round 6, finding #1] Sturdy support is not the same as SAFE support - a magma
     * block is a perfectly sturdy, full-faced floor that still burns anything standing on it; fire
     * has no collision shape at all (so the feet/head emptiness checks alone never catch it) but is
     * just as damaging. {@link #isHazardous} rejects every such block explicitly, at every one of
     * the three checked cells, rather than relying on "solid"/"empty" alone to imply "safe". */
    private static boolean isSafeStandingSpot(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !withinBuildHeight(level, pos)) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        return below.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
                && !isHazardous(below) && !isHazardous(feet) && !isHazardous(head)
                && feet.getCollisionShape(level, pos).isEmpty()
                && head.getCollisionShape(level, pos.above()).isEmpty()
                && level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(pos)).isEmpty();
    }

    /** {@code true} for any block that damages a villager merely standing on/in it, regardless of
     *  whether it is otherwise sturdy (a magma block) or has no collision shape at all (fire) - see
     *  {@link #isSafeStandingSpot}. */
    private static boolean isHazardous(BlockState state) {
        return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.LAVA) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || !state.getFluidState().isEmpty() && state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    /** Releases every ticket a {@link ReservedHandoff} is actually holding - used when a reservation
     *  succeeded but the campfire's own closing placement then failed to land this same tick, so
     *  nothing stays speculatively reserved against a handoff that never actually committed. */
    private static void releaseReservedHandoff(ServerLevel level, TownStorage storage, ReservedHandoff reserved) {
        if (reserved.workstationPos() != null) {
            releasePoiTicket(level, storage, reserved.workstationPos());
        }
        if (reserved.bedPos() != null) {
            releasePoiTicket(level, storage, reserved.bedPos());
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
     * Every clear/fill belongs to ONE terrain transaction.  Block entities are rejected by
     * preflight (and checked again immediately before a clear), so the journal only ever handles
     * state-only terrain.  Every eligible state is snapshotted before every write.  If any write is rejected, every earlier write is
     * restored before returning, and salvage is credited only after the complete transaction
     * commits.  A rollback that cannot itself be confirmed is durably contained rather than retried
     * against an unknown world state.
     */
    private static TerrainPreparation prepareTerrain(ServerLevel level, BlockPos campfire, int radius, int baseY, TownResourceStore store,
            TownStorage storage) {
        Map<TownClearingYields.ClearedMaterial, Integer> tally = new EnumMap<>(TownClearingYields.ClearedMaterial.class);
        List<TerrainMutation> mutations = new ArrayList<>();
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
                    if (storage.isCellBlocked(level.dimension(), pos)) {
                        // A terrain-prep block is a site-wide transaction hold, not an ignorable
                        // individual cell.  In particular, a blocked below-floor fill is outside
                        // the ordinary building-volume preflight scan and must still prevent a
                        // later attempt from building over an unconfirmed world state.
                        return abortTerrainPreparation(level, campfire, storage, mutations);
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
                    if (level.getBlockEntity(pos) != null) {
                        // Defend against a block entity appearing after whole-site preflight.
                        // No terrain mutation may remove data this journal cannot faithfully
                        // restore; any prior work in this transaction is rolled back first.
                        return abortTerrainPreparation(level, campfire, storage, mutations);
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
                    mutations.add(new TerrainMutation(pos, state));
                    boolean removed = removeTerrainBlock(level, pos) && level.getBlockState(pos).isAir();
                    if (!removed) {
                        return abortTerrainPreparation(level, campfire, storage, mutations);
                    }
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
                    if (storage.isCellBlocked(level.dimension(), pos)) {
                        return abortTerrainPreparation(level, campfire, storage, mutations);
                    }
                    if (level.getBlockState(pos).isAir()) {
                        // isAir() includes CAVE_AIR/VOID_AIR; preserve the exact original state
                        // rather than silently normalising an aborted transaction to plain air.
                        mutations.add(new TerrainMutation(pos, level.getBlockState(pos)));
                        boolean filled = setTerrainBlock(level, pos, Blocks.DIRT.defaultBlockState())
                                && level.getBlockState(pos).is(Blocks.DIRT);
                        if (!filled) {
                            return abortTerrainPreparation(level, campfire, storage, mutations);
                        }
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
        return mutations.isEmpty() ? TerrainPreparation.READY : TerrainPreparation.MUTATED;
    }

    private enum TerrainPreparation { READY, MUTATED, ABORTED }

    private record TerrainMutation(BlockPos pos, BlockState original) {}

    /** Restores in reverse write order.  A failure is permanently contained because retrying could
     * otherwise duplicate salvage from a world state whose exact history is no longer known. */
    private static TerrainPreparation abortTerrainPreparation(ServerLevel level, BlockPos campfire, TownStorage storage,
            List<TerrainMutation> mutations) {
        List<BlockPos> failedRestorations = new ArrayList<>();
        for (int i = mutations.size() - 1; i >= 0; i--) {
            TerrainMutation mutation = mutations.get(i);
            if (!restoreTerrainBlock(level, mutation.pos(), mutation.original())) {
                failedRestorations.add(mutation.pos());
            }
        }
        if (!failedRestorations.isEmpty()) {
            io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                    "Dynamic Towns terrain rollback did not fully restore {} - permanently excluding the transaction cells",
                    failedRestorations);
            for (TerrainMutation mutation : mutations) {
                storage.markCellBlocked(level.dimension(), mutation.pos());
            }
            storage.markTerrainSiteBlocked(level.dimension(), campfire);
            storage.setDirty();
        }
        return TerrainPreparation.ABORTED;
    }

    private static boolean removeTerrainBlock(ServerLevel level, BlockPos pos) {
        if (pos.equals(testForceTerrainMutationFailureAt)) {
            testForceTerrainMutationFailureAt = null;
            return false;
        }
        return level.removeBlock(pos, false);
    }

    private static boolean setTerrainBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (pos.equals(testForceTerrainMutationFailureAt)) {
            testForceTerrainMutationFailureAt = null;
            return false;
        }
        return level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
    }

    private static boolean restoreTerrainBlock(ServerLevel level, BlockPos pos, BlockState original) {
        // The rejected write may have been a clean no-op, in which case this cell is already
        // restored and Minecraft quite correctly returns false for setting the identical state.
        if (level.getBlockState(pos).equals(original)) {
            return true;
        }
        if (pos.equals(testForceTerrainRollbackFailureAt)) {
            return false;
        }
        return level.setBlock(pos, original, net.minecraft.world.level.block.Block.UPDATE_ALL)
                && level.getBlockState(pos).equals(original);
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

    /**
     * [Review round 7, finding #1] The full opaque signature a persisted "already placed" record
     * must match EXACTLY: the site-wide {@code planSignature} (kind/variant/final profession) plus
     * this one cell's own expected block and cost category. Two different plans at the same owner
     * (the campfire anchor never moves, but a recreated/upgraded/differently-seeded plan still can)
     * never share a signature, so a cell paid for under the OLD plan is never silently honored as
     * already-paid progress for a genuinely different NEW plan at the same position.
     */
    private static String cellSignature(String planSignature, TownBuildingTemplates.Placement placement) {
        return planSignature + ":" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(placement.block()) + ":" + placement.cost().name();
    }
}
