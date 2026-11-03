package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Focused persistence/idempotency evidence for the M454-owned admission boundary. */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownResidentCapacityAdmissionGameTests {
    private TownResidentCapacityAdmissionGameTests() {}

    private static final BlockPos RESIDENT = new BlockPos(5, 2, 5);
    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    /** [coordinator review 51378/51383] Four interior corners of the settlement_test template
     *  (confirmed 24x8x24, no block entities), each with footprint radius 2 (fits the interior
     *  1..22, never the framework's own StructureBlockEntity marker at relative (0,0,0)) and at
     *  least 17 blocks from every other corner - more than the storehouse footprint's own diameter
     *  (2 * {@code TownConstants.STOREHOUSE_FOOTPRINT_WIDTH} = 14), so AT MOST ONE corner can ever
     *  resolve STOREHOUSE for any given seed/placement. Tried in this fixed order; the first that
     *  BOTH resolves non-STOREHOUSE (still independently verified, never assumed) AND has a
     *  same-region, non-blocking companion position (see {@link #findSameRegionNonBlockingCompanion})
     *  is used. [coordinator review 51383] Colocating the beneficiary exactly on the claimant's own
     *  anchor - this file's immediately prior correction - was itself wrong: {@code
     *  TownSitePreflight}'s own blocking-occupant check exempts ONLY the exact claimant in the
     *  campfire column, so a colocated beneficiary is correctly treated as a blocking occupant and
     *  construction never completes. Acknowledged and corrected here; production safety (the
     *  preflight itself) is unchanged. */
    private static final BlockPos[] HANDOFF_CANDIDATE_ANCHORS = {
            new BlockPos(3, 2, 3), new BlockPos(20, 2, 3), new BlockPos(3, 2, 20), new BlockPos(20, 2, 20)
    };

    /** [coordinator review 51383] Deterministically searches template-interior relative X/Z
     *  positions 1..22 (never the framework's own (0,0,0) marker) for a companion position that
     *  resolves to the SAME region as {@code anchor} and sits OUTSIDE the anchor's own radius-2
     *  building footprint plus an entity-width margin - Chebyshev distance at least 4 along X or Z
     *  - so it can never be a blocking occupant in the construction footprint. Returns empty if no
     *  such companion exists for this anchor, so the caller tries the next candidate anchor
     *  instead of hardcoding an offset that might itself straddle a region boundary. */
    private static Optional<BlockPos> findSameRegionNonBlockingCompanion(GameTestHelper helper, BlockPos anchor, RegionCoord anchorRegion) {
        java.util.List<BlockPos> found = findSameRegionNonBlockingCompanions(helper, anchor, anchorRegion, 1);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** [MINECRAFT-455 postmerge CI 41443560723] Same deterministic in-template, same-region,
     *  non-blocking search {@link #findSameRegionNonBlockingCompanion} already performs, generalized
     *  to collect up to {@code count} DISTINCT candidates in one scan - needed by fixtures that place
     *  more than one beneficiary (e.g. two competing admissions) and must never reuse the exact same
     *  cell for both. Returns fewer than {@code count} entries, never throws, if the template does
     *  not have that many qualifying cells. */
    private static java.util.List<BlockPos> findSameRegionNonBlockingCompanions(GameTestHelper helper, BlockPos anchor, RegionCoord anchorRegion, int count) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        for (int x = 1; x <= 22 && found.size() < count; x++) {
            for (int z = 1; z <= 22 && found.size() < count; z++) {
                int dx = Math.abs(x - anchor.getX());
                int dz = Math.abs(z - anchor.getZ());
                if (dx < 4 && dz < 4) {
                    continue;
                }
                BlockPos candidate = new BlockPos(x, anchor.getY(), z);
                BlockPos candidateAbsolute = helper.absolutePos(candidate);
                if (TownRegion.fromBlockCoords(candidateAbsolute.getX(), candidateAbsolute.getZ()).equals(anchorRegion)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    /** [coordinator review 51383] Safe single-column footing at a companion position outside the
     *  anchor's own flattened footprint - the SAME per-column dirt/air convention {@link
     *  #prepareFlatGroundAround} already uses, so the beneficiary stands on solid ground rather
     *  than falling through unprepared template terrain. */
    private static void prepareSafeFooting(GameTestHelper helper, BlockPos pos) {
        for (int y = 0; y <= 7; y++) {
            helper.setBlock(new BlockPos(pos.getX(), y, pos.getZ()), y < 2 ? Blocks.DIRT : Blocks.AIR);
        }
    }

    /** [coordinator review 51391/51392/51394] Independently confirmed cause of the continued
     *  handoff failure: {@code TownConstructionService}'s own {@code
     *  relocateClaimantAwayFromCampfireIfStandingThere} -> {@code findSafeExitNear} tries exactly
     *  ONE cell FIRST, before ever falling back to a wider ring - {@code campfire.offset(0, 0,
     *  radius + 1)}, i.e. this anchor's own {@code offset(0, 0, 3)} for this fixture's radius-2
     *  footprint (every {@link #HANDOFF_CANDIDATE_ANCHORS} entry resolves non-STOREHOUSE, so
     *  radius is always exactly 2 - independently verified below via {@link
     *  #assertNonStorehouseFixture}, never assumed). {@link #prepareFlatGroundAround} only ever
     *  prepared out to radius 2, one short of this exact cell, so the claimant could never vacate
     *  the campfire for its own closing placement - confirmed directly from a fresh-world,
     *  instrumented buildAt run (relocation-no-safe-exit, call#26, claimant position unchanged).
     *  This narrowly owns ONLY this single column (one support block, two empty cells) - never
     *  the whole ring {@code findSafeExitNear} could also try, and never a radius change to
     *  {@link #prepareFlatGroundAround} itself. No production file is touched by this fixture-only
     *  correction. */
    private static void prepareSafeRelocationExit(GameTestHelper helper, BlockPos exit) {
        helper.setBlock(exit.below(), Blocks.DIRT);
        helper.setBlock(exit, Blocks.AIR);
        helper.setBlock(exit.above(), Blocks.AIR);
    }

    /** [coordinator review 51370] The SAME "is this exact UUID visible to production code's own
     *  iteration" check the existing isolated passive-production fixture already relies on
     *  ({@code TownPassiveProductionHandlerGameTests#appearsInProductionIterable}, kept {@code
     *  private} there) - duplicated here, not reused across files, to keep this correction scoped
     *  to this one file. {@code requestResidentCapacity} itself scans exactly this same {@code
     *  level.getEntities().getAll()} iterable. */
    private static boolean appearsInProductionIterable(ServerLevel level, Villager villager) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity.getUUID().equals(villager.getUUID())) return true;
        }
        return false;
    }

    /** [coordinator review 51370] Diagnostic UUID/position/region/demand detail for the wait-for-
     *  visibility step below, so an admission that is still absent after the wait reports its exact
     *  rejection preconditions rather than a bare "found Optional.empty." */
    private static String handoffDiagnostics(ServerLevel level, Villager claimant, Villager beneficiary, TownStorage storage, RegionCoord region) {
        return "claimantUuid=" + claimant.getUUID() + " claimantPos=" + claimant.blockPosition()
                + " claimantIterable=" + appearsInProductionIterable(level, claimant)
                + " claimantProfession=" + claimant.getVillagerData().getProfession()
                + " beneficiaryUuid=" + beneficiary.getUUID() + " beneficiaryPos=" + beneficiary.blockPosition()
                + " beneficiaryIterable=" + appearsInProductionIterable(level, beneficiary)
                + " beneficiaryProfession=" + beneficiary.getVillagerData().getProfession()
                + " beneficiaryRegion=" + TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ())
                + " region=" + region + " founded=" + storage.hasFoundedTown(TownKeys.keyFor(level.dimension(), region))
                + " unmetDemandNow=" + TownResidentAllocationHandler.unmetDemand(level, region, storage);
    }

    /** [coordinator review 51378/51379] Bounded 5x5 (radius 2) in-template footprint around
     *  {@code anchor} - fits entirely within the template's own 0..23 X/Z bounds for every one of
     *  the four {@link #HANDOFF_CANDIDATE_ANCHORS}, never touching a block outside the template.
     *  The verified settlement_test template is 24x8x24, so this NEW helper's own vertical range
     *  is kept to 0..7 - deliberately NOT the legacy 0..12 {@link #prepareFlatGround} still uses,
     *  which is out of scope for this fixture-only correction. Uses the normal template-relative
     *  {@code helper.setBlock}, so no manual restoration is needed - the framework's own structure
     *  reset handles it. */
    private static void prepareFlatGroundAround(GameTestHelper helper, BlockPos anchor) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int y = 0; y <= 7; y++) {
                    helper.setBlock(new BlockPos(anchor.getX() + dx, y, anchor.getZ() + dz), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    private static TownResourceStore fundStore(GameTestHelper helper, TownStorage storage, BlockPos absoluteCampfire, int amount) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
        store.credit(TownResourceCategory.STONE, amount);
        store.credit(TownResourceCategory.WOOD, amount);
        return store;
    }

    /** The exact kind/final-profession this site resolves to, computed the same way production
     *  itself does in {@link TownConstructionService#buildAt}. */
    private record ResolvedSite(TownBuildingTemplates.Kind kind, Optional<VillagerProfession> finalProfession) {}

    private static ResolvedSite resolveSite(GameTestHelper helper, BlockPos absoluteCampfire) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        Optional<VillagerProfession> finalProfession = TownProfessionWorkstations.finalProfessionFor(
                kind, helper.getLevel().getSeed(), absoluteCampfire.getX(), absoluteCampfire.getZ());
        return new ResolvedSite(kind, finalProfession);
    }

    /** Fails loudly rather than silently substituting a weaker test when this fixture's own
     *  resolved kind cannot exercise admission binding at all - [coordinator review, comment
     *  51266]: "use deterministic eligible fixtures and fail invalid setup," not a graceful
     *  fallback. This exact ANCHOR/structure already underlies
     *  {@code TownGameTests#realBuilderClaimantCompletesASiteAndIsReleasedFromItsJobAfterward}'s own
     *  non-STOREHOUSE production assertions. */
    private static void assertNonStorehouseFixture(GameTestHelper helper, ResolvedSite site) {
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE,
                "test setup: this fixture must resolve to a non-STOREHOUSE kind to exercise admission binding at all; got "
                        + site.kind());
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51266 P1]: binding happens BEFORE the
     * first construction effect, not deferred to the closing placement - proven here by checking
     * the admission is already {@code BUILDING}, recorded against the EXACT claimant and concrete
     * plan, after just the first tick. A beneficiary is NOT automatically the temporary Builder that
     * claims and finishes a site: the admission's BENEFICIARY - never the Builder - receives the
     * final HOME/workplace, the Builder is unconditionally released to {@code NONE}, and the
     * admission only reaches {@code COMPLETED} once that positive handoff actually happened.
     */
    // [coordinator review 51387] skyAccess=true asks the framework to omit the barrier roof
    // StructureUtils#encaseStructure otherwise places over the structure (GameTestInfo's own
    // encaseStructure(bounds, level, !testFunction.skyAccess()) call) - TownSitePreflight's real
    // canopy-top scan correctly rejected that barrier as an obstruction; the legacy 0..13 helper
    // this test no longer uses happened to erase it, masking the issue. No production preflight
    // change, no barrier deletion - only this test now asks for real sky above its structure.
    @GameTest(template = "settlement_test", batch = "capacityAdmissionHandoffIsolated", skyAccess = true)
    public static void boundAdmissionHandsOffToItsBeneficiaryNotTheBuilderClaimant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // MINECRAFT-454 [coordinator review 51378/51380]: the prior remote-region/forced-chunk
        // fixture correctly proved admission sequencing (51370-51372), but focused controls
        // (51376-51378) showed unresolved shared-suite fixture readiness/interaction - NOT proven
        // independent of production code either way. Rebuilt entirely IN-TEMPLATE instead: try each of the
        // four interior corners above, in this fixed order, and use the first that resolves
        // non-STOREHOUSE through the REAL planner - still independently verified via
        // assertNonStorehouseFixture below, never assumed or skipped. This delegates chunk/entity
        // readiness entirely to the framework's own already-ticking template chunks.
        BlockPos foundAnchor = null;
        ResolvedSite foundSite = null;
        BlockPos foundCompanion = null;
        for (BlockPos candidate : HANDOFF_CANDIDATE_ANCHORS) {
            BlockPos candidateAbsolute = helper.absolutePos(candidate);
            ResolvedSite candidateSite = resolveSite(helper, candidateAbsolute);
            if (candidateSite.kind() == TownBuildingTemplates.Kind.STOREHOUSE) {
                continue;
            }
            RegionCoord candidateRegion = TownRegion.fromBlockCoords(candidateAbsolute.getX(), candidateAbsolute.getZ());
            Optional<BlockPos> companion = findSameRegionNonBlockingCompanion(helper, candidate, candidateRegion);
            if (companion.isPresent()) {
                foundAnchor = candidate;
                foundSite = candidateSite;
                foundCompanion = companion.get();
                break;
            }
        }
        helper.assertTrue(foundAnchor != null,
                "test setup: at least one of the four interior corners must resolve non-STOREHOUSE AND have a "
                        + "same-region, non-blocking companion position for the beneficiary");
        final BlockPos chosenAnchor = foundAnchor;
        final ResolvedSite site = foundSite;
        final BlockPos chosenCompanion = foundCompanion;
        final BlockPos absoluteCampfire = helper.absolutePos(chosenAnchor);
        final BlockPos absoluteCompanion = helper.absolutePos(chosenCompanion);
        assertNonStorehouseFixture(helper, site);
        // [coordinator review 51383] Explicit, redundant nonblocking-placement assertion - the
        // SELECTION above already filters for this, but this is checked again directly rather than
        // only implied by construction.
        helper.assertTrue(Math.max(Math.abs(chosenCompanion.getX() - chosenAnchor.getX()), Math.abs(chosenCompanion.getZ() - chosenAnchor.getZ())) >= 4,
                "test setup: the beneficiary's companion position must sit outside the anchor's own building footprint plus entity-width margin");

        prepareFlatGroundAround(helper, chosenAnchor);
        helper.setBlock(chosenAnchor, Blocks.CAMPFIRE);
        prepareSafeFooting(helper, chosenCompanion);

        // [coordinator review 51394] The ONE in-template standing exit buildAt's own relocation
        // step tries FIRST, just past the normal south door - prepared and independently verified
        // here, never assumed from placement alone.
        final BlockPos chosenExit = chosenAnchor.offset(0, 0, 3);
        helper.assertTrue(chosenExit.getX() >= 0 && chosenExit.getX() <= 23 && chosenExit.getZ() >= 0 && chosenExit.getZ() <= 23,
                "test setup: the relocation exit must stay within the settlement_test template's own 24-wide bounds, found " + chosenExit);
        helper.assertTrue(Math.max(Math.abs(chosenExit.getX() - chosenAnchor.getX()), Math.abs(chosenExit.getZ() - chosenAnchor.getZ())) > 2,
                "test setup: the relocation exit must sit strictly outside the anchor's own radius-2 building footprint, found " + chosenExit);
        // [coordinator review 51396] An invented Chebyshev >=4 margin against the companion
        // position here was stronger than production needs and not guaranteed by the existing
        // companion search (which only guarantees >=4 from the ANCHOR/building footprint, kept
        // unchanged above) - e.g. anchor(3,3)/companion(1,7)/exit(3,6) is a valid combination this
        // would have wrongly rejected before construction was ever exercised. Replaced below, once
        // the beneficiary actually exists, with the real occupancy contract: distinct positions
        // plus an actual AABB non-intersection/empty-entities check over the exit's own feet/head
        // standing volume - the same contract production's own isSafeStandingSpot ultimately relies
        // on - rather than an arbitrary block-distance threshold.

        prepareSafeRelocationExit(helper, chosenExit);

        BlockPos absoluteExit = helper.absolutePos(chosenExit);
        helper.assertTrue(level.getBlockState(absoluteExit.below()).is(Blocks.DIRT)
                        && level.getBlockState(absoluteExit.below()).isFaceSturdy(level, absoluteExit.below(), Direction.UP),
                "test setup: the relocation exit's support block must actually be sturdy, non-hazardous dirt, found "
                        + level.getBlockState(absoluteExit.below()));
        helper.assertTrue(level.getBlockState(absoluteExit).getCollisionShape(level, absoluteExit).isEmpty()
                        && level.getBlockState(absoluteExit.above()).getCollisionShape(level, absoluteExit.above()).isEmpty(),
                "test setup: the relocation exit's feet and head space must both actually be empty, found feet="
                        + level.getBlockState(absoluteExit) + " head=" + level.getBlockState(absoluteExit.above()));

        TownPassiveProductionHandlerGameTests.OwnedFixtureCleanup cleanup = new TownPassiveProductionHandlerGameTests.OwnedFixtureCleanup();
        helper.testInfo.addListener(cleanup);

        // MINECRAFT-454 [coordinator review 51370, explicitly retained per 51378]: forcing/loading
        // readiness is no longer in play (this is fully in-template now), but the explicit
        // successful-insertion check and production-iterable visibility wait are kept regardless,
        // exactly as instructed, rather than assuming in-template entities are always immediately
        // visible.
        Villager claimant = EntityType.VILLAGER.create(level, null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.assertTrue(claimant != null, "test setup: claimant Builder must be constructible");
        cleanup.add(claimant::discard);
        helper.assertTrue(level.addFreshEntity(claimant), "test setup: claimant Builder must be accepted by ServerLevel");

        // [coordinator review 51383] The beneficiary spawns at the SEARCHED, SAME-region companion
        // position - never colocated with the claimant (that prior "fix" made the beneficiary a
        // blocking occupant in the campfire column, which TownSitePreflight's own exemption only
        // ever covers for the exact claimant, and construction never completed) and never a
        // hardcoded offset that might itself straddle a region boundary.
        Villager beneficiary = EntityType.VILLAGER.create(level, null, absoluteCompanion, MobSpawnType.EVENT, false, false);
        helper.assertTrue(beneficiary != null, "test setup: beneficiary villager must be constructible");
        cleanup.add(beneficiary::discard);
        helper.assertTrue(level.addFreshEntity(beneficiary), "test setup: beneficiary villager must be accepted by ServerLevel");

        // [coordinator review 51396] The real occupancy contract for the prepared relocation exit,
        // now that the beneficiary actually exists - distinct positions, the beneficiary's own
        // actual bounding box not intersecting the exit's feet/head standing volume, and no entity
        // at all found over that exact volume at this pre-build point (mirroring production's own
        // isSafeStandingSpot empty-entities check). Never an invented block-distance margin.
        AABB exitStandingVolume = new AABB(absoluteExit.getX(), absoluteExit.getY(), absoluteExit.getZ(),
                absoluteExit.getX() + 1, absoluteExit.getY() + 2, absoluteExit.getZ() + 1);
        helper.assertTrue(!absoluteExit.equals(absoluteCompanion),
                "test setup: the relocation exit and the beneficiary's companion position must be distinct, found " + absoluteExit);
        helper.assertTrue(!beneficiary.getBoundingBox().intersects(exitStandingVolume),
                "test setup: the relocation exit's feet/head standing volume must not intersect the beneficiary's own bounding box, found exit="
                        + exitStandingVolume + " beneficiary=" + beneficiary.getBoundingBox());
        helper.assertTrue(level.getEntities((Entity) null, exitStandingVolume).isEmpty(),
                "test setup: the relocation exit's feet/head standing volume must be empty of entities at the pre-build point, found "
                        + level.getEntities((Entity) null, exitStandingVolume));

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        helper.assertTrue(TownRegion.fromBlockCoords(claimant.blockPosition().getX(), claimant.blockPosition().getZ()).equals(region)
                        && TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ()).equals(region),
                "test setup: both the claimant and the beneficiary must resolve to the exact same region as the anchor");
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));

        // [coordinator review 51370] Owned cleanup is kept alive (via the testInfo listener already
        // registered above) until this ENTIRE sequence - the wait included - actually completes or
        // fails; it is deliberately NOT wrapped in a surrounding try/finally here, which would run
        // cleanup.cleanup() the instant startSequence() returns (registration, not completion),
        // long before the wait or the lifecycle work below ever actually ran.
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                appearsInProductionIterable(level, claimant) && appearsInProductionIterable(level, beneficiary),
                "both the claimant and the beneficiary must become visible to the production iterable: "
                        + handoffDiagnostics(level, claimant, beneficiary, storage, region)))
        .thenExecute(() -> {
            try {
                claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
                claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), absoluteCampfire));

                if (site.finalProfession().isPresent()) {
                    beneficiary.setVillagerData(beneficiary.getVillagerData().setProfession(site.finalProfession().get()));
                }
                Map<VillagerProfession, Integer> requestedWorkplaces = site.finalProfession()
                        .map(profession -> Map.of(profession, 1)).orElse(Map.of());
                TownResidentDemand demand = new TownResidentDemand(1, requestedWorkplaces);
                Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                        level, region, beneficiary.getUUID(), demand, storage);
                helper.assertTrue(admission.isPresent() && admission.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                        "test setup: the beneficiary's admission must be created and unbound before construction begins: "
                                + handoffDiagnostics(level, claimant, beneficiary, storage, region));

                TownConstructionService.buildAt(level, absoluteCampfire, storage);
                Optional<ResidentCapacityAdmission> afterFirstTick = storage.capacityAdmissionFor(
                        level.dimension(), region, beneficiary.getUUID());
                helper.assertTrue(afterFirstTick.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                                && value.builderId().equals(claimant.getUUID())
                                && value.boundSiteOwner().equals(TownStorage.siteOwner(absoluteCampfire))).isPresent(),
                        "[P1] binding must happen before/at the FIRST construction effect, recorded against the exact claimant - found "
                                + afterFirstTick);

                for (int i = 1; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
                    TownConstructionService.buildAt(level, absoluteCampfire, storage);
                }

                Optional<ResidentCapacityAdmission> afterBuild = storage.capacityAdmissionFor(
                        level.dimension(), region, beneficiary.getUUID());
                helper.assertTrue(claimant.getVillagerData().getProfession() == VillagerProfession.NONE,
                        "the temporary Builder must always be fully released, never inheriting an admission-bound site's final profession");
                helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty(),
                        "the released Builder must never keep a stale JOB_SITE memory");
                helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                        "the released Builder must never receive the admission's beneficiary-bound home");
                helper.assertTrue(afterBuild.filter(value -> value.state() == ResidentCapacityAdmission.State.COMPLETED).isPresent(),
                        "the bound admission must reach COMPLETED only once the beneficiary's own handoff actually happened, found "
                                + afterBuild.map(ResidentCapacityAdmission::state).orElse(null));

                int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
                BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
                helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.HOME)
                                .map(gp -> gp.pos().equals(expectedBed)).orElse(false),
                        "the ADMISSION'S BENEFICIARY, not the Builder, must receive the final HOME this build placed");
                helper.assertTrue(level.getPoiManager().getFreeTickets(expectedBed) == 0,
                        "the beneficiary's new bed ticket must actually be held, not merely referenced by memory");
                if (site.finalProfession().isPresent()) {
                    helper.assertTrue(beneficiary.getVillagerData().getProfession() == site.finalProfession().get(),
                            "the beneficiary must receive the zone's assigned final profession, not the Builder");
                    BlockPos expectedWorkstation = TownBuildingTemplates.workstationPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
                    helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                                    .map(gp -> gp.pos().equals(expectedWorkstation)).orElse(false),
                            "the beneficiary's JOB_SITE must point at the real workstation this build placed");
                    helper.assertTrue(level.getPoiManager().getFreeTickets(expectedWorkstation) == 0,
                            "the beneficiary's new workstation ticket must actually be held, not merely referenced by memory");
                }

                // [coordinator review, comment 51266 P2] "duplicate request/tick/charge assertions"
                // - a COMPLETED site's own anchor is already gone, so no later tick can even find a
                // claimant here again, but a redundant extra tick must still be harmless: no further
                // debit, no admission state change, no POI change.
                int stoneAfterCompletion = store.balance(TownResourceCategory.STONE);
                int woodAfterCompletion = store.balance(TownResourceCategory.WOOD);
                for (int i = 0; i < 5; i++) {
                    TownConstructionService.buildAt(level, absoluteCampfire, storage);
                }
                helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneAfterCompletion
                                && store.balance(TownResourceCategory.WOOD) == woodAfterCompletion
                                && storage.capacityAdmissionFor(level.dimension(), region, beneficiary.getUUID())
                                        .filter(afterBuild.get()::equals).isPresent(),
                        "redundant ticks after completion must never duplicate a charge or change the completed admission");
            } catch (RuntimeException primary) {
                cleanup.cleanupPreserving(primary);
                throw primary;
            } finally {
                cleanup.cleanup();
            }
        }).thenSucceed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51266 P1]: "Bind before first
     * construction effects... validate immutable Builder/site/plan identity before subsequent
     * effects... Only authorized original binding may safely resume; no automatic inheritance."
     * Drives partial construction -&gt; BUILDING (bound to the FIRST Builder) -&gt; save/load -&gt;
     * claimant loss/swap to a SECOND, competing Builder, and proves that competing claimant causes
     * NO additional blocks, debit, paid records, or POI ownership changes at all - the site simply
     * stops advancing. Only once the ORIGINAL authorized Builder resumes does construction - and the
     * ORIGINAL beneficiary's handoff - proceed correctly.
     */
    @GameTest(template = "settlement_test")
    public static void aLostClaimantSwappedForACompetingObserverNeverStealsABoundAdmissionsHandoff(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager firstBuilder = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(firstBuilder);
        firstBuilder.setVillagerData(firstBuilder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        firstBuilder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: beneficiary admission must be created");

        // Drive a FEW ticks with the first Builder - enough to place some progress (and, per [P1],
        // bind on the very first tick), not enough to finish.
        for (int i = 0; i < 3; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "test setup: the site must not have finished yet under the first claimant");
        Optional<ResidentCapacityAdmission> boundToFirst = storage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(boundToFirst.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.builderId().equals(firstBuilder.getUUID())).isPresent(),
                "test setup: the admission must already be BUILDING, bound to the first Builder, before any swap - found " + boundToFirst);
        int stoneAfterFirstBuilder = store.balance(TownResourceCategory.STONE);
        int woodAfterFirstBuilder = store.balance(TownResourceCategory.WOOD);
        helper.assertTrue(stoneAfterFirstBuilder < 100000 || woodAfterFirstBuilder < 100000,
                "test setup: the first Builder must have actually paid for some progress");

        // Partial construction -> BUILDING -> save/load, per the coordinator review's own sequence.
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
        Optional<ResidentCapacityAdmission> reloadedBinding = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(reloadedBinding.filter(boundToFirst.get()::equals).isPresent(),
                "the BUILDING binding (site/builder/concrete plan) must survive save/load exactly, byte for byte");

        // The first Builder loses its claim - a SECOND, competing villager then takes over the exact
        // same campfire.
        firstBuilder.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        firstBuilder.setVillagerData(firstBuilder.getVillagerData().setProfession(VillagerProfession.NONE));
        Villager secondBuilder = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(secondBuilder);
        secondBuilder.setVillagerData(secondBuilder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        secondBuilder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        for (int i = 0; i < 50; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "[P1] a competing claimant that never bound this site must cause NO further progress at all - the site must still be exactly as it was");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == stoneAfterFirstBuilder
                        && reloadedStore.balance(TownResourceCategory.WOOD) == woodAfterFirstBuilder,
                "a competing claimant must cause no additional debit at all");
        helper.assertTrue(secondBuilder.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "a competing claimant that is refused authority over a BUILDING site must never be released/handed off either - it simply never advances it");
        Optional<ResidentCapacityAdmission> afterSwapAttempt = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(afterSwapAttempt.filter(boundToFirst.get()::equals).isPresent(),
                "the ORIGINAL binding (still the first Builder's) must be retained unchanged - no automatic inheritance, no rebind");
        helper.assertTrue(reloaded.capacityAdmissionCount() == 1,
                "a claimant swap must never create a second, competing admission for the same beneficiary/site");

        // [coordinator review 51268, P2] Only the ORIGINAL authorized Builder may safely resume -
        // but a sole-owner resume test must not leave the refused competing claimant's own JOB_SITE
        // memory still pointing at this exact campfire: TownCampfireClaims#findClaimant (M452's own
        // claim-identity policy, out of scope here) selects the FIRST loaded matching villager, so a
        // still-claiming secondBuilder left in place could make this resume ambiguous by accident
        // rather than by the deliberate design this step means to test. Cleared explicitly first.
        secondBuilder.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        secondBuilder.setVillagerData(secondBuilder.getVillagerData().setProfession(VillagerProfession.NONE));
        // [coordinator review 51284/51282] Clearing the claim above does NOT move the entity: it
        // was created AT absoluteCampfire and, with the claim gone, is no longer the production
        // code's own "claimant" that relocateClaimantAwayFromCampfireIfStandingThere (TownConstruction
        // Service) teleports before a solid closing block lands - it would simply sit there forever.
        // The final closing placement (buildAt, same file, ~645-656) then checks the closing cell's
        // AABB with NO exemption at all, not even for the claimant, so a non-claiming entity left in
        // that exact cell blocks the site from ever completing - not a production defect (the
        // no-exemption check is itself the correct safety rule for an unidentified occupant), but an
        // unmoved fixture. A real villager that lost its claim would walk off on its own; this
        // fixture makes that explicit rather than relying on it.
        BlockPos observerSafeSpot = helper.absolutePos(new BlockPos(9, 2, 9));
        secondBuilder.teleportTo(observerSafeSpot.getX() + 0.5, observerSafeSpot.getY(), observerSafeSpot.getZ() + 0.5);
        helper.assertTrue(!TownCampfireClaims.isClaimedBy(secondBuilder, helper.getLevel(), absoluteCampfire),
                "test setup: the competing claimant's claim must be cleared before testing sole-owner resume");
        // [coordinator review 51285] firstBuilder is legitimately still parked at this exact cell
        // (it was created there and never moved away) - production itself only relocates it at the
        // moment of final closure, not before. Asserting the WHOLE cell is empty was therefore
        // wrong on its own terms (it can never pass with firstBuilder still there); what this setup
        // actually needs is that NO entity other than the resuming first Builder occupies the cell.
        helper.assertTrue(helper.getLevel().getEntities((net.minecraft.world.entity.Entity) null, new AABB(absoluteCampfire))
                        .stream().allMatch(entity -> entity == firstBuilder),
                "test setup: the closing cell must contain no entity other than the resuming first Builder itself");
        firstBuilder.setVillagerData(firstBuilder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        firstBuilder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        // [coordinator review 51281] Explicit diagnostics BEFORE resuming, so a future failure here
        // identifies the EXACT broken precondition instead of only the downstream "did it finish"
        // symptom: the claim must actually be re-established, the beneficiary must actually still
        // be loaded/alive/in-region (required for the eventual handoff), and the authority gate's
        // own three conditions (builder/plan/token) must all still independently hold.
        helper.assertTrue(TownCampfireClaims.isClaimedBy(firstBuilder, helper.getLevel(), absoluteCampfire),
                "test setup: the original Builder's claim must be re-established before resuming");
        helper.assertTrue(beneficiary.isAlive()
                        && TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ()).equals(region),
                "test setup: the beneficiary must still be alive and in-region before resuming, or the eventual handoff can never find it");
        Optional<ResidentCapacityAdmission> boundBeforeResume = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(boundBeforeResume.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.builderId().equals(firstBuilder.getUUID())).isPresent(),
                "test setup: the binding must still be BUILDING, bound to the original Builder, immediately before resuming - found "
                        + boundBeforeResume);

        for (int i = 0; i < 500 && firstBuilder.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        }

        helper.assertTrue(!helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "the ORIGINAL authorized Builder resuming must still be able to finish the site");
        helper.assertTrue(firstBuilder.getVillagerData().getProfession() == VillagerProfession.NONE,
                "the resumed, finishing Builder must still be fully released, never inheriting the handoff");
        helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "the ORIGINAL admission's beneficiary - never either Builder - must receive the final handoff");
        helper.assertTrue(secondBuilder.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "the competing Builder, refused authority throughout, must never inherit the beneficiary's reserved home");
        Optional<ResidentCapacityAdmission> completed = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(completed.filter(value -> value.state() == ResidentCapacityAdmission.State.COMPLETED
                        && value.builderId().equals(firstBuilder.getUUID())).isPresent(),
                "exactly one admission, bound to and completed by the ORIGINAL authorized Builder, never the competing one");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51271/51274]: "remove to air and
     * recreate CAMPFIRE at same coordinates with SAME Builder/plan... must reject." This is the
     * exact regression test for the previously-reported, now-closed gap: builderId/planSignature
     * alone could not tell a genuinely-resumed anchor apart from a brand-new one that merely shares
     * them. Drives a REAL partial build to a REAL BUILDING binding (with its own live anchor
     * token), removes the campfire to air (destroying that BlockEntity and its token) and places a
     * FRESH campfire at the EXACT SAME coordinate (CampfireBlock#newBlockEntity's own brand-new,
     * empty-persistent-data instance), keeps the SAME claimant/plan, and asserts the real buildAt
     * causes ZERO further mutation/debit/change - proving the recreated anchor is correctly refused.
     */
    @GameTest(template = "settlement_test")
    public static void recreatingTheSameCampfireAtTheSameCoordinateWithTheSameBuilderAndPlanIsRejected(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

        for (int i = 0; i < 3; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must not have finished yet");
        Optional<ResidentCapacityAdmission> boundBefore = storage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(boundBefore.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.anchorGenerationToken() != null).isPresent(),
                "test setup: a real BUILDING binding with a real anchor token must already exist - found " + boundBefore);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        // Remove to air (destroying the old CampfireBlockEntity and its token), then place a FRESH
        // campfire at the EXACT SAME coordinate - a brand-new BlockEntity, per the matched NeoForge
        // source, with EMPTY persistent data. Same claimant (same builderId), same world/seed (same
        // planSignature) - "including between ticks": the claim memory is re-set exactly as a real
        // claim re-acquisition would.
        helper.setBlock(ANCHOR, Blocks.AIR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        for (int i = 0; i < 10; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a recreated anchor (same Builder/plan, but a BRAND-NEW BlockEntity) must cause NO further progress at all");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a recreated anchor must cause no further debit at all");
        helper.assertTrue(claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the claimant attempting to continue under a recreated anchor must never be processed/released at all");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(boundBefore.get()::equals).isPresent(),
                "the original binding record must remain exactly as it was before the recreation attempt");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51271/51274]: "missing/mismatched live
     * token must fail closed without retagging." Corrupts the LIVE anchor's own token directly
     * (without removing/recreating the block at all) and proves buildAt refuses to continue AND
     * never repairs/retags the live token back to the originally-bound value.
     */
    @GameTest(template = "settlement_test")
    public static void aMismatchedLiveAnchorTokenFailsClosedWithoutRetagging(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

        for (int i = 0; i < 3; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must not have finished yet");
        Optional<ResidentCapacityAdmission> boundBefore = storage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(boundBefore.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.anchorGenerationToken() != null).isPresent(),
                "test setup: a real BUILDING binding with a real anchor token must already exist - found " + boundBefore);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteCampfire) instanceof CampfireBlockEntity,
                "test setup: a live CampfireBlockEntity must exist to corrupt");
        CampfireBlockEntity campfireEntity = (CampfireBlockEntity) helper.getLevel().getBlockEntity(absoluteCampfire);
        UUID corruptedToken = UUID.randomUUID();
        campfireEntity.getPersistentData().putUUID("dynamicvehicles:admission_generation", corruptedToken);
        campfireEntity.setChanged();

        for (int i = 0; i < 10; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a mismatched live anchor token must cause NO further progress at all");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a mismatched live anchor token must cause no further debit at all");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(boundBefore.get()::equals).isPresent(),
                "the stored binding record must remain exactly as it was - never silently repaired to match the corrupted live token");
        helper.assertTrue(corruptedToken.equals(campfireEntity.getPersistentData().getUUID("dynamicvehicles:admission_generation")),
                "the LIVE anchor's own corrupted token must never be retagged/repaired back to the originally-bound value");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51278]: "A live marker with no ledger
     * binding... is unexplained prior identity, not permission to retag." Places a VALID UUID
     * under the anchor-generation key on a live campfire BEFORE any admission ever binds there at
     * all (no corresponding TownStorage ledger record whatsoever), then drives a real matching
     * demand/claimant through buildAt - proving the preexisting marker blocks EVERY effect
     * (mutation, debit, floor persistence included) and is never overwritten/retagged.
     */
    @GameTest(template = "settlement_test")
    public static void buildAtRefusesToRetagAPreexistingLiveUuidMarkerWithNoLedgerBinding(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteCampfire) instanceof CampfireBlockEntity,
                "test setup: a live CampfireBlockEntity must exist");
        CampfireBlockEntity campfireEntity = (CampfireBlockEntity) helper.getLevel().getBlockEntity(absoluteCampfire);
        UUID preexistingMarker = UUID.randomUUID();
        campfireEntity.getPersistentData().putUUID("dynamicvehicles:admission_generation", preexistingMarker);
        campfireEntity.setChanged();

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission (a real, matching demand) must be created");
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a preexisting unexplained live marker must block ALL effects, including the closing placement");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a preexisting unexplained live marker must cause zero debit");
        helper.assertTrue(storage.floorFor(siteOwner).isEmpty(), "a preexisting unexplained live marker must block floor persistence too");
        helper.assertTrue(claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the claimant must never be processed/released against an unexplained prior marker");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(admission.get()::equals).isPresent(),
                "the matching admission must remain exactly AWAITING_CLAIM, completely untouched");
        helper.assertTrue(preexistingMarker.equals(campfireEntity.getPersistentData().getUUID("dynamicvehicles:admission_generation")),
                "the preexisting live marker must remain EXACTLY as it was - never overwritten/retagged by a refused bind attempt");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51278]: companion to the valid-UUID
     * preexisting-marker test above, for a MALFORMED value under the same key (same key present,
     * wrong NBT type) - {@code contains()}, not {@code hasUUID()}, is what must catch this, since
     * {@code hasUUID()} alone would report false and silently let the bind fall through to
     * overwrite it.
     */
    @GameTest(template = "settlement_test")
    public static void buildAtRefusesToRetagAPreexistingMalformedLiveMarkerWithNoLedgerBinding(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteCampfire) instanceof CampfireBlockEntity,
                "test setup: a live CampfireBlockEntity must exist");
        CampfireBlockEntity campfireEntity = (CampfireBlockEntity) helper.getLevel().getBlockEntity(absoluteCampfire);
        campfireEntity.getPersistentData().putString("dynamicvehicles:admission_generation", "not-a-real-uuid");
        campfireEntity.setChanged();
        helper.assertTrue(!campfireEntity.getPersistentData().hasUUID("dynamicvehicles:admission_generation")
                        && campfireEntity.getPersistentData().contains("dynamicvehicles:admission_generation"),
                "test setup: the marker must be present under the key but NOT a valid UUID - hasUUID() alone must not catch this");

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission (a real, matching demand) must be created");
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a preexisting malformed live marker must block ALL effects, including the closing placement");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a preexisting malformed live marker must cause zero debit");
        helper.assertTrue(storage.floorFor(siteOwner).isEmpty(), "a preexisting malformed live marker must block floor persistence too");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(admission.get()::equals).isPresent(),
                "the matching admission must remain exactly AWAITING_CLAIM, completely untouched");
        helper.assertTrue("not-a-real-uuid".equals(campfireEntity.getPersistentData().getString("dynamicvehicles:admission_generation")),
                "the preexisting malformed live marker must remain EXACTLY as it was - never overwritten/retagged by a refused bind attempt");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51278]: "a focused denied-storage-bind
     * supplier test proving supplier is never invoked when reverse-fenced/quarantined." Directly
     * exercises {@code TownStorage#bindCapacityAdmissionToSite} with a supplier that records
     * whether it was ever called, against both a quarantined site and an already everBound
     * (reverse-fenced) site - neither denial may ever reach the supplier at all.
     */
    @GameTest(template = "settlement_test")
    public static void deniedStorageBindNeverInvokesTheAnchorTokenSupplierWhenFencedOrQuarantined(GameTestHelper helper) {
        Villager beneficiary = resident(helper, new BlockPos(5, 2, 5), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: admission must be created");

        // Quarantined site: built by tagging an entry in a persisted QuarantinedAdmissionSites list
        // and reloading, mirroring the existing malformed/conflicting persistence test's own
        // established pattern.
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag quarantineList = saved.getList("QuarantinedAdmissionSites", Tag.TAG_COMPOUND);
        CompoundTag quarantineEntry = new CompoundTag();
        quarantineEntry.putString("Key", helper.getLevel().dimension().location() + "@site:quarantine-supplier-test");
        quarantineList.add(quarantineEntry);
        saved.put("QuarantinedAdmissionSites", quarantineList);
        TownStorage quarantined = TownStorage.load(saved, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> awaitingInQuarantined = quarantined.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(awaitingInQuarantined.isPresent(), "test setup: the admission must still be loaded in the quarantined storage");

        boolean[] quarantinedSupplierInvoked = {false};
        Optional<ResidentCapacityAdmission> quarantinedBind = quarantined.bindCapacityAdmissionToSite(
                awaitingInQuarantined.get(), "site:quarantine-supplier-test", UUID.randomUUID(), "plan-x", () -> {
                    quarantinedSupplierInvoked[0] = true;
                    return Optional.of(UUID.randomUUID());
                });
        helper.assertTrue(quarantinedBind.isEmpty(), "test setup: the quarantined bind must be refused");
        helper.assertTrue(!quarantinedSupplierInvoked[0], "a quarantined site's bind must never invoke the anchor-token supplier at all");

        // Reverse-fenced (everBound) site: one admission binds successfully, then a SECOND,
        // distinct admission attempts the SAME site.
        // [coordinator review 51399] This is storage-only supplier/reverse-fence testing, never
        // construction/occupancy, so colocating the second beneficiary at the SAME relative
        // (5,2,5) as the first is safe here - and avoids the real region-boundary straddle the
        // prior distinct relative(8,2,8) position produced for this structure's own placement
        // (REGION_SIZE_BLOCKS=384: absolute X 8567807 -> region 22311, 8567810 -> region 22312),
        // confirmed from a misselected broad run. Both colocation preconditions are asserted
        // explicitly below, never assumed from the shared position alone.
        Villager secondBeneficiary = resident(helper, new BlockPos(5, 2, 5), VillagerProfession.ARMORER);
        RegionCoord secondBeneficiaryRegion = TownRegion.fromBlockCoords(
                secondBeneficiary.blockPosition().getX(), secondBeneficiary.blockPosition().getZ());
        helper.assertTrue(secondBeneficiaryRegion.equals(region),
                "test setup: the second beneficiary must resolve to the exact same region as the first for this reverse-fence case, found "
                        + secondBeneficiaryRegion + " vs " + region);
        helper.assertTrue(!secondBeneficiary.getUUID().equals(beneficiary.getUUID()),
                "test setup: the two beneficiaries must still be distinct entities despite sharing a position");
        Optional<ResidentCapacityAdmission> secondAdmission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, secondBeneficiary.getUUID(), demand, storage);
        helper.assertTrue(secondAdmission.isPresent(), "test setup: second admission must be created");
        Optional<ResidentCapacityAdmission> firstBind = storage.bindCapacityAdmissionToSite(
                admission.get(), "site:ever-bound-supplier-test", UUID.randomUUID(), "plan-y", () -> Optional.of(UUID.randomUUID()));
        helper.assertTrue(firstBind.isPresent(), "test setup: the first bind must succeed");

        boolean[] secondSupplierInvoked = {false};
        Optional<ResidentCapacityAdmission> secondBind = storage.bindCapacityAdmissionToSite(
                secondAdmission.get(), "site:ever-bound-supplier-test", UUID.randomUUID(), "plan-y", () -> {
                    secondSupplierInvoked[0] = true;
                    return Optional.of(UUID.randomUUID());
                });
        helper.assertTrue(secondBind.isEmpty(), "test setup: the second bind to an already-ever-bound site must be refused");
        helper.assertTrue(!secondSupplierInvoked[0],
                "a reverse-fenced (everBound) site's bind must never invoke the anchor-token supplier either");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51276]: "a production buildAt
     * rejection case with matching demand but unavailable/invalid anchor identity; do not merely
     * assert resolver empty." A real claimant/demand/region match exists, but NO campfire block (so
     * no live CampfireBlockEntity) is ever placed at the claimed position at all - a DENIED first
     * bind (not NoMatch), proven through the REAL {@code buildAt} entry point: zero mutation, zero
     * debit, and the matching admission left completely untouched (never silently consumed by a
     * site whose anchor could never actually carry a token).
     */
    @GameTest(template = "settlement_test")
    public static void buildAtRejectsAMatchingDemandWhenTheAnchorItselfIsUnavailable(GameTestHelper helper) {
        prepareFlatGround(helper);
        // Deliberately NO campfire placed here - ANCHOR stays whatever prepareFlatGround left it
        // (air above the flat ground), an invalid/unavailable anchor.
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission (a real, matching demand) must be created");
        helper.assertTrue(!(helper.getLevel().getBlockEntity(absoluteCampfire) instanceof CampfireBlockEntity),
                "test setup: there must be NO live CampfireBlockEntity at the claimed position at all");
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a matching demand whose anchor is unavailable must cause zero debit");
        helper.assertTrue(claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the claimant must never be processed/released when the anchor itself could never carry a token");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(admission.get()::equals).isPresent(),
                "the matching admission must remain completely untouched - a DENIED bind must never be silently treated as NoMatch and consumed");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51276]: companion to the mismatched-
     * token test above - this exercises a MISSING (not mismatched) token on an EXISTING bound live
     * BlockEntity, without removing/recreating the block at all, and proves the token remains
     * ABSENT afterward (never backfilled/retagged into existence by a rejected tick).
     */
    @GameTest(template = "settlement_test")
    public static void aMissingLiveAnchorTokenFailsClosedWithoutBeingBackfilled(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

        for (int i = 0; i < 3; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must not have finished yet");
        Optional<ResidentCapacityAdmission> boundBefore = storage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(boundBefore.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.anchorGenerationToken() != null).isPresent(),
                "test setup: a real BUILDING binding with a real anchor token must already exist - found " + boundBefore);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteCampfire) instanceof CampfireBlockEntity,
                "test setup: a live CampfireBlockEntity must exist to strip");
        CampfireBlockEntity campfireEntity = (CampfireBlockEntity) helper.getLevel().getBlockEntity(absoluteCampfire);
        campfireEntity.getPersistentData().remove("dynamicvehicles:admission_generation");
        campfireEntity.setChanged();

        for (int i = 0; i < 10; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "a missing live anchor token must cause NO further progress at all");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a missing live anchor token must cause no further debit at all");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(boundBefore.get()::equals).isPresent(),
                "the stored binding record must remain exactly as it was - never silently repaired to add a token");
        helper.assertTrue(!campfireEntity.getPersistentData().hasUUID("dynamicvehicles:admission_generation"),
                "the LIVE anchor's own missing token must never be backfilled/retagged into existence by a rejected tick");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51276]: "Required physical-BE
     * persistence round trip is missing... saves/loads ONLY TownStorage while retaining the
     * original live BE. It therefore cannot prove NeoForgeData token survival or catch omitting the
     * BE save hook." This drives a real partial bound build, then performs an ACTUAL {@code
     * CampfireBlockEntity} serialization/reconstruction cycle - {@code BlockEntity#saveWithFullMetadata}
     * to a CompoundTag, {@code BlockEntity#loadStatic} to build a BRAND-NEW instance from it, and
     * {@code Level#setBlockEntity} to install that reconstructed instance in place of the original
     * live one - exactly the same component-level save/load machinery a real chunk reload uses
     * (confirmed against the matched NeoForge source), never a process kill/restart. TownStorage is
     * independently round-tripped too, then buildAt is driven to completion against the
     * RECONSTRUCTED BlockEntity, proving valid continuation with no token change. [coordinator
     * review 51278] The final balance check below proves only that continuation/debit actually
     * happened (resources went down) - a plain inequality cannot detect double-charging, so this
     * does NOT prove "no duplicate paid progress"; that stronger property is deliberately left
     * unproven here rather than overclaimed.
     */
    @GameTest(template = "settlement_test")
    public static void physicalAnchorTokenSurvivesRealBlockEntitySerializationAndReconstruction(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

        for (int i = 0; i < 3; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must not have finished yet");
        Optional<ResidentCapacityAdmission> boundBefore = storage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(boundBefore.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.anchorGenerationToken() != null).isPresent(),
                "test setup: a real BUILDING binding with a real anchor token must already exist - found " + boundBefore);

        // Real BlockEntity serialization + reconstruction - component save/load, NOT a process
        // restart. Proves the token survives NeoForgeData's OWN round trip, not merely TownStorage's
        // separate copy of it.
        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteCampfire) instanceof CampfireBlockEntity,
                "test setup: a live CampfireBlockEntity must exist to serialize");
        CampfireBlockEntity live = (CampfireBlockEntity) helper.getLevel().getBlockEntity(absoluteCampfire);
        CompoundTag savedBe = live.saveWithFullMetadata(helper.getLevel().registryAccess());
        BlockState campfireState = helper.getLevel().getBlockState(absoluteCampfire);
        BlockEntity reconstructed = BlockEntity.loadStatic(absoluteCampfire, campfireState, savedBe, helper.getLevel().registryAccess());
        helper.assertTrue(reconstructed instanceof CampfireBlockEntity,
                "the reconstructed BlockEntity must still be a CampfireBlockEntity after a real save/loadStatic cycle");
        helper.assertTrue(reconstructed.getPersistentData().hasUUID("dynamicvehicles:admission_generation")
                        && boundBefore.get().anchorGenerationToken()
                                .equals(reconstructed.getPersistentData().getUUID("dynamicvehicles:admission_generation")),
                "the anchor token must survive REAL BlockEntity#saveWithFullMetadata/loadStatic serialization, not merely TownStorage's own copy");
        helper.getLevel().setBlockEntity(reconstructed);

        // TownStorage itself round-tripped too, per the existing established pattern.
        CompoundTag savedStorage = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloadedStorage = TownStorage.load(savedStorage, helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloadedStorage.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));

        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloadedStorage);
        }

        helper.assertTrue(!helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "valid continuation against the RECONSTRUCTED BlockEntity + reloaded storage must still be able to finish the site");
        helper.assertTrue(claimant.getVillagerData().getProfession() == VillagerProfession.NONE,
                "the Builder must still be fully released upon completion");
        helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "the beneficiary must still receive its home after continuing against the reconstructed BE");
        Optional<ResidentCapacityAdmission> completed = reloadedStorage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(completed.filter(value -> value.state() == ResidentCapacityAdmission.State.COMPLETED
                        && boundBefore.get().anchorGenerationToken().equals(value.anchorGenerationToken())).isPresent(),
                "completion must happen WITHOUT any token change from the one established before reconstruction, found " + completed);
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) < 100000 || reloadedStore.balance(TownResourceCategory.WOOD) < 100000,
                // [coordinator review 51278] Narrowed deliberately: this proves only that SOME
                // debit/continuation happened after the reconstruction, not "exactly once" or "no
                // duplication" - a plain inequality cannot distinguish a single charge from a
                // duplicated one. That stronger property is NOT claimed proven by this test.
                "continuation against the reconstructed BlockEntity must have actually debited some resource category");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51266/51268 P1/P2]: "A home+farmer
     * request can match bed-only or wrong-profession site... Require all requested capacities."
     * [51268]: "Test312 covers either no-job OR wrong-job, not both... Supply explicit eligible...
     * fixtures." Rather than hoping this fixture's own world-generated zone happens to be one case
     * or the other, this calls {@link TownConstructionService#resolveCapacityAdmissionForSite}
     * DIRECTLY with explicit, synthetic (never physically built) site identities - deterministically
     * covering BOTH "no-job" (RESIDENTIAL, no profession at all) and "wrong-profession" (COMMERCIAL
     * offering ARMORER, never the requested FARMER) in the same run, plus a sanity check that a site
     * offering EXACTLY what was requested still binds (the predicate is a genuine full match, not
     * simply always false).
     */
    @GameTest(template = "settlement_test")
    public static void admissionNeverBindsToASiteThatCannotDeliverEveryRequestedCapacity(GameTestHelper helper) {
        // A single real campfire (so a live CampfireBlockEntity exists for the one sub-case that
        // actually reaches the token-writing bind step) - the "no-job"/"wrong-job" sub-cases are
        // refused by the capacity-matching predicate itself, before any bind/token attempt, so they
        // never touch this BlockEntity at all.
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(new BlockPos(5, 2, 5));
        Villager beneficiary = resident(helper, new BlockPos(9, 2, 1), VillagerProfession.FARMER);
        RegionCoord region = TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.FARMER, 1));
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home+FARMER admission must be created");
        UUID builderId = UUID.randomUUID();

        // Explicit "no-job" fixture: a RESIDENTIAL site offers a bed but no profession at all - the
        // capacity predicate itself never matches, so this is NoMatch, never Denied.
        TownConstructionService.CapacityBindResolution noJobResolution = TownConstructionService.resolveCapacityAdmissionForSite(
                helper.getLevel(), storage, absoluteCampfire, region, "site:no-job-synthetic", TownBuildingTemplates.Kind.RESIDENTIAL,
                Optional.empty(), "synthetic-plan-no-job", builderId);
        helper.assertTrue(noJobResolution instanceof TownConstructionService.CapacityBindResolution.NoMatch,
                "a home+FARMER admission must never bind to a RESIDENTIAL site that offers no profession at all - must be NoMatch, found "
                        + noJobResolution);
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(admission.get()::equals).isPresent(),
                "a rejected no-job match attempt must leave the admission completely untouched");

        // Explicit "wrong-profession" fixture: a COMMERCIAL site offers a DIFFERENT profession -
        // also NoMatch (the capacity predicate itself never matches), never Denied.
        TownConstructionService.CapacityBindResolution wrongJobResolution = TownConstructionService.resolveCapacityAdmissionForSite(
                helper.getLevel(), storage, absoluteCampfire, region, "site:wrong-job-synthetic", TownBuildingTemplates.Kind.COMMERCIAL,
                Optional.of(VillagerProfession.ARMORER), "synthetic-plan-wrong-job", builderId);
        helper.assertTrue(wrongJobResolution instanceof TownConstructionService.CapacityBindResolution.NoMatch,
                "a home+FARMER admission must never bind to a site offering a DIFFERENT profession (ARMORER) - must be NoMatch, found "
                        + wrongJobResolution);
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(admission.get()::equals).isPresent(),
                "a rejected wrong-job match attempt must leave the admission completely untouched");

        // Sanity: a site that DOES offer exactly what was requested must still bind - this is the
        // ONLY sub-case that actually reaches the live-anchor token-writing bind step.
        TownConstructionService.CapacityBindResolution matchingResolution = TownConstructionService.resolveCapacityAdmissionForSite(
                helper.getLevel(), storage, absoluteCampfire, region, "site:matching-synthetic", TownBuildingTemplates.Kind.COMMERCIAL,
                Optional.of(VillagerProfession.FARMER), "synthetic-plan-matching", builderId);
        helper.assertTrue(matchingResolution instanceof TownConstructionService.CapacityBindResolution.Bound(ResidentCapacityAdmission value)
                        && value.state() == ResidentCapacityAdmission.State.BUILDING
                        && "site:matching-synthetic".equals(value.boundSiteOwner())
                        && value.builderId().equals(builderId) && value.anchorGenerationToken() != null,
                "test sanity: a site that DOES offer exactly what was requested must still bind, with a real anchor token - found "
                        + matchingResolution);
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51266/51268/51270 P1/P2]: "home-only can
     * overwrite unrelated existing workplace." [51270]: a world-position-dependent fixture "does not
     * select the required eligible fixture" even behind a failing precondition assertion. Rather
     * than hoping (or requiring) this GameTest's own world-generated zone happens to offer a
     * profession, this calls {@link TownConstructionService#tryReserveHandoffDestinations} and
     * {@link TownConstructionService#commitClaimantLifecycle} DIRECTLY (both widened to package-
     * private for exactly this) with an EXPLICIT, hand-picked COMMERCIAL kind/FARMER profession - a
     * real bed is placed (so the REAL POI ticket machinery is exercised, not mocked), but the
     * profession/workstation side is pure explicit input, deterministic on every run regardless of
     * seed or structure placement.
     */
    @GameTest(template = "settlement_test")
    public static void homeOnlyAdmissionNeverTouchesAPreexistingUnrelatedWorkplace(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        TownBuildingTemplates.Kind explicitKind = TownBuildingTemplates.Kind.COMMERCIAL;
        VillagerProfession siteOfferedProfession = VillagerProfession.FARMER;
        VillagerProfession preexistingProfession = VillagerProfession.ARMORER;

        BlockPos bedHead = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
        // Mirrors TownBuildingTemplates#addBed exactly: FOOT sits one cell toward -Z from HEAD.
        BlockPos bedFoot = new BlockPos(bedHead.getX(), bedHead.getY(), bedHead.getZ() - 1);
        helper.getLevel().setBlockAndUpdate(bedHead, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
        helper.getLevel().setBlockAndUpdate(bedFoot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);
        BlockPos oldWorkplace = helper.absolutePos(new BlockPos(0, 2, 9));
        takeWorkplace(helper, oldWorkplace, poiKeyFor(preexistingProfession));
        beneficiary.setVillagerData(beneficiary.getVillagerData().setProfession(preexistingProfession));
        beneficiary.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), oldWorkplace));

        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        // Home-only request - never mentions a workplace, regardless of what this EXPLICIT kind
        // offers.
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> awaiting = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(awaiting.isPresent(), "test setup: the home-only admission must be created");
        ResidentCapacityAdmission boundAdmission = awaiting.get().boundTo("site:direct-call-test", UUID.randomUUID(),
                "plan-direct-call-test", UUID.randomUUID());

        Optional<TownConstructionService.ReservedHandoff> reserved = TownConstructionService.tryReserveHandoffDestinations(
                helper.getLevel(), storage, beneficiary, absoluteCampfire, floorY, explicitKind, Optional.of(siteOfferedProfession),
                Optional.of(boundAdmission), pos -> true);
        helper.assertTrue(reserved.isPresent() && reserved.get().bedPos() != null && reserved.get().workstationPos() == null,
                "a home-only admission against an EXPLICIT COMMERCIAL+FARMER site must reserve ONLY the bed, never a workstation it never requested, found "
                        + reserved);

        TownConstructionService.commitClaimantLifecycle(helper.getLevel(), storage, claimant, beneficiary,
                Optional.of(siteOfferedProfession), reserved.get());

        helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(bedHead)).orElse(false),
                "the home-only admission must still receive its own requested home");
        helper.assertTrue(beneficiary.getVillagerData().getProfession() == preexistingProfession,
                "a home-only admission must never overwrite the beneficiary's pre-existing, unrelated profession");
        helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(oldWorkplace)).orElse(false),
                "a home-only admission must never overwrite the beneficiary's pre-existing, unrelated JOB_SITE memory");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(oldWorkplace) == 0,
                "a home-only admission must never release the beneficiary's pre-existing, unrelated workplace ticket");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51266 P2]: "Add... competing-admission
     * reverse exclusivity." Two beneficiaries each request home-only capacity in the same region;
     * only one site exists. Exactly one admission binds/completes; the other remains AWAITING_CLAIM,
     * untouched, with no home granted.
     */
    @GameTest(template = "settlement_test")
    public static void onlyOneOfTwoCompetingAdmissionsBindsToTheOneAvailableSite(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        // [MINECRAFT-455 postmerge CI 41443560723] The old fixed (9,2,1)/(1,2,9) offsets straddled
        // the 384-block region boundary under this run's template translation (anchor x=-2787839,
        // beneficiaryB x=-2787843 vs boundary -2787840): production correctly rejected the
        // foreign-region request, which this test's own setup assertion then reported as "both
        // competing admissions must be created." Reusing the same deterministic same-region,
        // non-blocking search boundAdmissionHandsOffToItsBeneficiaryNotTheBuilderClaimant already
        // relies on - never a hardcoded offset that might itself straddle a region boundary.
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        java.util.List<BlockPos> beneficiarySpots = findSameRegionNonBlockingCompanions(helper, ANCHOR, region, 2);
        helper.assertTrue(beneficiarySpots.size() == 2,
                "test setup: two distinct same-region, non-blocking companion positions must exist for the two competing beneficiaries");
        prepareSafeFooting(helper, beneficiarySpots.get(0));
        prepareSafeFooting(helper, beneficiarySpots.get(1));

        Villager beneficiaryA = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(beneficiarySpots.get(0)),
                MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiaryA);
        Villager beneficiaryB = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(beneficiarySpots.get(1)),
                MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiaryB);
        helper.assertTrue(TownRegion.fromBlockCoords(beneficiaryA.blockPosition().getX(), beneficiaryA.blockPosition().getZ()).equals(region)
                        && TownRegion.fromBlockCoords(beneficiaryB.blockPosition().getX(), beneficiaryB.blockPosition().getZ()).equals(region),
                "test setup: both beneficiaries must resolve to the exact same region as the anchor");

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admissionA = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiaryA.getUUID(), demand, storage);
        Optional<ResidentCapacityAdmission> admissionB = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiaryB.getUUID(), demand, storage);
        helper.assertTrue(admissionA.isPresent() && admissionB.isPresent(), "test setup: both competing admissions must be created");

        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(!helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the one site must have completed");
        boolean aGotHome = beneficiaryA.getBrain().getMemory(MemoryModuleType.HOME).isPresent();
        boolean bGotHome = beneficiaryB.getBrain().getMemory(MemoryModuleType.HOME).isPresent();
        helper.assertTrue(aGotHome != bGotHome, "exactly one of the two competing admissions must receive the one available site's home, never both, never neither");
        Optional<ResidentCapacityAdmission> finalA = storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiaryA.getUUID());
        Optional<ResidentCapacityAdmission> finalB = storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiaryB.getUUID());
        helper.assertTrue(aGotHome
                        ? finalA.filter(v -> v.state() == ResidentCapacityAdmission.State.COMPLETED).isPresent()
                                && finalB.filter(v -> v.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM).isPresent()
                        : finalB.filter(v -> v.state() == ResidentCapacityAdmission.State.COMPLETED).isPresent()
                                && finalA.filter(v -> v.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM).isPresent(),
                "the winning admission must be COMPLETED and the loser must remain untouched at AWAITING_CLAIM - foundA=" + finalA + " foundB=" + finalB);
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void residentCapacityAdmissionPersistsAndDuplicateRequestReusesIt(GameTestHelper helper) {
        helper.setBlock(RESIDENT.below(), Blocks.DIRT);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(RESIDENT),
                MobSpawnType.EVENT, false, false);
        helper.assertTrue(villager != null, "test setup: beneficiary villager must be constructible");
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.ARMORER));

        RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
        TownStorage storage = new TownStorage();
        String townKey = TownKeys.keyFor(helper.getLevel().dimension(), region);
        storage.markTownFounded(townKey);
        storage.creditStore(townKey, TownResourceCategory.WOOD, 7);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));

        Optional<ResidentCapacityAdmission> first = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, storage);
        Optional<ResidentCapacityAdmission> duplicate = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, storage);
        helper.assertTrue(first.isPresent() && duplicate.isPresent() && first.get().equals(duplicate.get())
                        && first.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && first.get().beneficiaryId().equals(villager.getUUID())
                        && first.get().dimension().equals(helper.getLevel().dimension())
                        && first.get().region().equals(region)
                        && first.get().requestedHomes() == 1
                        && first.get().requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1)),
                "a duplicate tick must reuse one dimension-qualified beneficiary admission without advancing it to a POI/build effect");

        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        CompoundTag malformed = saved.copy();
        ListTag malformedAdmissions = malformed.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        malformedAdmissions.getCompound(0).getList("Workplaces", Tag.TAG_COMPOUND).getCompound(0)
                .putString("Profession", "not a resource location");
        TownStorage malformedReload = TownStorage.load(malformed, helper.getLevel().registryAccess());
        helper.assertTrue(malformedReload.hasFoundedTown(townKey)
                        && malformedReload.storeFor(townKey).balance(TownResourceCategory.WOOD) == 7
                        && malformedReload.capacityAdmissionCount() == 0,
                "a malformed nested profession must reject only that admission without aborting unrelated TownStorage data");
        CompoundTag malformedDimension = saved.copy();
        malformedDimension.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND).getCompound(0)
                .putString("Dimension", "not a resource location");
        TownStorage malformedDimensionReload = TownStorage.load(malformedDimension, helper.getLevel().registryAccess());
        helper.assertTrue(malformedDimensionReload.hasFoundedTown(townKey)
                        && malformedDimensionReload.storeFor(townKey).balance(TownResourceCategory.WOOD) == 7
                        && malformedDimensionReload.capacityAdmissionCount() == 0,
                "a malformed admission Dimension must reject only that admission without aborting unrelated TownStorage data");
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> loaded = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, villager.getUUID());
        helper.assertTrue(loaded.filter(first.get()::equals).isPresent() && reloaded.capacityAdmissionCount() == 1,
                "save/load must restore the admission before any retry is allowed to recreate it");
        Optional<ResidentCapacityAdmission> afterReload = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, reloaded);
        helper.assertTrue(afterReload.isPresent() && afterReload.get().equals(first.get())
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(first.get()::equals).isPresent()
                        && reloaded.capacityAdmissionCount() == 1
                        && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(RESIDENT)) == 0,
                "save/load and a second request must preserve the single nonterminal admission and create no POI reservation");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void capacityAdmissionTargetsOnlyTheBeneficiaryWhoseCapacityIsMissing(GameTestHelper helper) {
        Villager fulfilledA = resident(helper, new BlockPos(3, 2, 3), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(fulfilledA.blockPosition().getX(), fulfilledA.blockPosition().getZ());
        // [coordinator review 51284/51282, source-grounded, corrects the diagnostic-only 51281
        // candidate] A fixed structure-RELATIVE offset (the previous unfulfilledB at (7,2,7) versus
        // fulfilledA at (3,2,3)) can straddle a TownConstants.REGION_SIZE_BLOCKS (384) region
        // boundary depending on where the GameTest batch runner's own floating structure origin
        // lands in the world for a given run - demonstrated, not hypothetical: the actual failed
        // run's origin had floorMod(z, 384) == 380, putting A's relative z=3 at region remainder
        // 383 and a naive z=7 at remainder 3 in the ADJACENT region, so requestResidentCapacity
        // correctly refused a beneficiary outside the requested region. Deriving unfulfilledB's
        // (and both POIs') positions from fulfilledA's own ACTUAL absolute position via
        // nearbyWithinSameRegion - which flips the offset's sign on whichever axis would otherwise
        // cross that boundary - removes the dependency on the batch's floating placement entirely,
        // rather than merely asserting around it.
        BlockPos unfulfilledBSpot = nearbyWithinSameRegion(fulfilledA.blockPosition(), 4, 4);
        Villager unfulfilledB = EntityType.VILLAGER.create(helper.getLevel(), null, unfulfilledBSpot, MobSpawnType.EVENT, false, false);
        helper.assertTrue(unfulfilledB != null, "test setup: unfulfilled B villager must be constructible");
        helper.getLevel().addFreshEntity(unfulfilledB);
        unfulfilledB.setVillagerData(unfulfilledB.getVillagerData().setProfession(VillagerProfession.ARMORER));
        helper.assertTrue(TownRegion.fromBlockCoords(unfulfilledB.blockPosition().getX(), unfulfilledB.blockPosition().getZ())
                        .equals(region),
                "test setup: both villagers must resolve to the SAME region for this fixture to mean what it claims");

        TownStorage storage = foundedStorage(helper, region);
        // [coordinator review 51285] (-1,-1) and (-1,+1) are NOT guaranteed distinct:
        // nearbyWithinSameRegion flips a component's sign independently per axis whenever the
        // plain offset would cross the region boundary, so at the observed A.z remainder 383 the
        // +1 flips to -1 and both calls collapse to the exact SAME cell - silently replacing the
        // HOME POI with the ARMORER one there, while both getFreeTickets==0 checks still pass for
        // that single wrong-typed POI. Using offsets of different MAGNITUDE (1 vs 2 on both axes)
        // means a same-axis sign flip can never make them equal, regardless of which direction
        // either one flips.
        BlockPos home = nearbyWithinSameRegion(fulfilledA.blockPosition(), -1, -1);
        BlockPos workplace = nearbyWithinSameRegion(fulfilledA.blockPosition(), -2, -2);
        helper.assertTrue(!home.equals(workplace),
                "test setup: home and workplace must be distinct cells, never collapsed by a boundary sign-flip");
        helper.assertTrue(TownRegion.fromBlockCoords(home.getX(), home.getZ()).equals(region)
                        && TownRegion.fromBlockCoords(workplace.getX(), workplace.getZ()).equals(region),
                "test setup: fulfilled A's own home and workplace POIs must resolve into the SAME region too");
        takeHome(helper, home);
        takeArmorerWorkplace(helper, workplace);
        helper.assertTrue(helper.getLevel().getPoiManager().getType(home).filter(type -> type.is(PoiTypes.HOME)).isPresent()
                        && helper.getLevel().getPoiManager().getType(workplace).filter(type -> type.is(PoiTypes.ARMORER)).isPresent(),
                "test setup: home and workplace must each actually hold their own distinct POI type, not a single collapsed cell");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(home) == 0
                        && helper.getLevel().getPoiManager().getFreeTickets(workplace) == 0,
                "test setup: fulfilled A's home and workplace tickets must actually be held, not merely present");
        fulfilledA.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
        fulfilledA.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), workplace));

        TownResidentDemand observedDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));
        Optional<ResidentCapacityAdmission> forFulfilledA = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, fulfilledA.getUUID(), demand, storage);
        Optional<ResidentCapacityAdmission> forUnfulfilledB = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, unfulfilledB.getUUID(), demand, storage);
        helper.assertTrue(observedDemand.missingHomes() >= 1
                        && observedDemand.missingWorkplaces().getOrDefault(VillagerProfession.ARMORER, 0) >= 1
                        && forFulfilledA.isEmpty()
                        && forUnfulfilledB.filter(admission -> admission.requestedHomes() == 1
                                && admission.requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                                && admission.beneficiaryId().equals(unfulfilledB.getUUID())).isPresent(),
                "town-wide unmet demand must not create capacity for fulfilled A; it must be attributed to unfulfilled B");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void capacityAdmissionRejectsAChangedProfessionsStaleWorkplace(GameTestHelper helper) {
        Villager villager = resident(helper, new BlockPos(5, 2, 5), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand originalDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        Optional<ResidentCapacityAdmission> original = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), originalDemand, storage);
        CompoundTag savedBeforeProfileChange = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(savedBeforeProfileChange, helper.getLevel().registryAccess());
        Optional<ResidentCapacityAdmission> loadedOriginal = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, villager.getUUID());
        BlockPos home = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos oldArmorerWorkplace = helper.absolutePos(new BlockPos(2, 2, 4));
        takeHome(helper, home);
        takeArmorerWorkplace(helper, oldArmorerWorkplace);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), home));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), oldArmorerWorkplace));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));

        TownResidentDemand demand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, reloaded);
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, villager.getUUID(), demand, reloaded);
        // [coordinator review 51284/51282] unmetDemand (M455, never modified here) is a genuine
        // TOWN-WIDE aggregate over every loaded villager in the region, not scoped to this test's
        // own fixture - a GameTest batch can run several tests' fixtures in the same 384-block
        // region at once, so asserting this aggregate equals EXACTLY {FARMER: 1} (or exactly zero
        // missing homes) is not actually what this test is about, and is contaminated by whatever
        // else happens to share the region in a given run. What this test actually proves -
        // requestResidentCapacity correctly filtering that aggregate down to THIS beneficiary's own
        // exact current need, overriding its stale ARMORER admission - is the per-admission check
        // below, unchanged and still exact. Only the aggregate observation itself is loosened to
        // what the test setup can actually guarantee: that this villager's own stale-workplace
        // mismatch contributes a real FARMER entry to it.
        helper.assertTrue(original.isPresent() && loadedOriginal.filter(original.get()::equals).isPresent()
                        && original.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && original.get().requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))
                        && demand.missingWorkplaces().getOrDefault(VillagerProfession.FARMER, 0) >= 1
                        && admission.filter(value -> value.requestedHomes() == 0
                                && value.requestedWorkplaces().equals(Map.of(VillagerProfession.FARMER, 1))
                                && value.beneficiaryId().equals(villager.getUUID())
                                && value.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM).isPresent()
                        && reloaded.capacityAdmissionCount() == 1
                        && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, villager.getUUID())
                                .filter(admission.get()::equals).isPresent(),
                "a loaded, unbound ARMORER admission must reconcile at its same identity to current FARMER need, never return stale success or create a second site");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51268 P2]: "separately test
     * simultaneous claims with explicit refusal/selection contract." Documents - without changing -
     * {@link TownCampfireClaims}'s own existing selection behavior (M452's claim-identity policy,
     * out of scope here): with two villagers BOTH holding a valid {@code JOB_SITE} claim on the SAME
     * campfire at once, exactly one is selected (never both, never neither, never an exception).
     */
    @GameTest(template = "settlement_test")
    public static void simultaneousCompetingClaimsResolveToExactlyOneSelectedClaimant(GameTestHelper helper) {
        BlockPos campfireCell = new BlockPos(5, 2, 5);
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager first = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(first);
        first.setVillagerData(first.getVillagerData().setProfession(VillagerProfession.FARMER));
        first.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        Villager second = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(second);
        second.setVillagerData(second.getVillagerData().setProfession(VillagerProfession.ARMORER));
        second.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        helper.assertTrue(TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "two simultaneous valid claimants must still read as claimed");
        Optional<Villager> selected = TownCampfireClaims.findClaimant(helper.getLevel(), absoluteCampfire);
        helper.assertTrue(selected.isPresent() && (selected.get() == first || selected.get() == second),
                "exactly one of the two simultaneous claimants must be selected - found " + selected);
        Optional<Villager> selectedAgain = TownCampfireClaims.findClaimant(helper.getLevel(), absoluteCampfire);
        helper.assertTrue(selectedAgain.isPresent() && selectedAgain.get() == selected.get(),
                "repeated selection against the SAME unchanged simultaneous-claim state must be stable, not re-roll");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51270 P1]: "the exact claimant-only
     * fallback prohibited by 51268... Test actual funded buildAt after completion, replacement
     * demand, save/load and coordinate reuse; compare world, paid records, balances and POI
     * ownership." Unlike {@link #aCompletedSitesFenceSurvivesItsOwnBeneficiarysLaterUnrelatedRequest}
     * (which only proved {@code resolveCapacityAdmissionForSite} itself returns empty), this drives
     * a REAL funded build to COMPLETED, replaces the beneficiary's own record with an unrelated
     * demand, saves/reloads, places a BRAND-NEW campfire at the EXACT SAME coordinate with a NEW
     * claimant (coordinate reuse), and proves the real {@code buildAt} causes ZERO further effect of
     * any kind - not merely that a new admission fails to bind.
     */
    @GameTest(template = "settlement_test")
    public static void aCompletedSitesFenceBlocksRealBuildAtAfterReplacementDemandAndCoordinateReuse(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager firstClaimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(firstClaimant);
        firstClaimant.setVillagerData(firstClaimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        firstClaimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

        for (int i = 0; i < 500 && firstClaimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
        helper.assertTrue(!helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must have actually completed");
        helper.assertTrue(beneficiary.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "test setup: the beneficiary must have actually received its home");
        int stoneAfterCompletion = store.balance(TownResourceCategory.STONE);
        int woodAfterCompletion = store.balance(TownResourceCategory.WOOD);

        // The SAME beneficiary now has a brand-new, unrelated need (it already has a home, so this
        // requests only a workplace) - requestResidentCapacity's own existing
        // createOrReuseCapacityAdmission legitimately replaces the now-terminal record.
        beneficiary.setVillagerData(beneficiary.getVillagerData().setProfession(VillagerProfession.FARMER));
        TownResidentDemand newDemand = new TownResidentDemand(0, Map.of(VillagerProfession.FARMER, 1));
        Optional<ResidentCapacityAdmission> replaced = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), newDemand, storage);
        helper.assertTrue(replaced.isPresent() && replaced.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                "test setup: the beneficiary's own new, unrelated request must replace its terminal record");

        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));

        // Coordinate reuse: a BRAND-NEW campfire at the EXACT SAME coordinate, claimed by a NEW
        // villager - as if someone destroyed the finished building and started fresh there.
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        Villager secondClaimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(secondClaimant);
        secondClaimant.setVillagerData(secondClaimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        secondClaimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        reloadedStore.credit(TownResourceCategory.STONE, 100000);
        reloadedStore.credit(TownResourceCategory.WOOD, 100000);
        int stoneBeforeReuseAttempt = reloadedStore.balance(TownResourceCategory.STONE);
        int woodBeforeReuseAttempt = reloadedStore.balance(TownResourceCategory.WOOD);

        for (int i = 0; i < 20; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a completed site's permanent fence must block a brand-new campfire at the SAME coordinate from being built on AT ALL - the world must be exactly as the reuse attempt left it");
        helper.assertTrue(secondClaimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the new claimant attempting coordinate reuse must never be processed, released, or handed off at all");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == stoneBeforeReuseAttempt
                        && reloadedStore.balance(TownResourceCategory.WOOD) == woodBeforeReuseAttempt,
                "a completed site's permanent fence must cause zero further debit on coordinate reuse");
        helper.assertTrue(reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(replaced.get()::equals).isPresent(),
                "the beneficiary's new, unrelated admission record must remain completely untouched by the refused reuse attempt");
        helper.assertTrue(reloaded.capacityAdmissionBoundToSite(helper.getLevel().dimension(), TownStorage.siteOwner(absoluteCampfire)).isEmpty(),
                "no admission - the new unrelated one, or any other - may ever report itself bound to this permanently fenced site again");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51268/51270 P1]: this is the
     * TERMINAL-record-replacement scenario specifically - {@code
     * TownStorage#createOrReuseCapacityAdmission} legitimately replacing a TERMINAL admission's own
     * record at the SAME beneficiary identity with a fresh, unbound one for a later, unrelated
     * request, erasing the old record's {@code boundSiteOwner} fence held only there. [51270]
     * explicitly distinguishes this from the SEPARATE, still-unresolved "active BUILDING same-
     * coordinate/same-plan/same-Builder recreation" problem (see {@code TownConstructionService}'s
     * own documented gap at its builderId/boundPlanSignature comparison) - do not conflate the two.
     * This storage-level test proves only that {@code resolveCapacityAdmissionForSite} itself
     * refuses to rebind; {@link #aCompletedSitesFenceBlocksRealBuildAtAfterReplacementDemandAndCoordinateReuse}
     * proves the full, real {@code buildAt} integration additionally causes zero effect.
     */
    @GameTest(template = "settlement_test")
    public static void aCompletedSitesFenceSurvivesItsOwnBeneficiarysLaterUnrelatedRequest(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(new BlockPos(5, 2, 5));
        Villager beneficiary = resident(helper, new BlockPos(9, 2, 1), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ());
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the original admission must be created");
        UUID builderId = UUID.randomUUID();
        TownConstructionService.CapacityBindResolution boundResolution = TownConstructionService.resolveCapacityAdmissionForSite(
                helper.getLevel(), storage, absoluteCampfire, region, "site:completed-fence-test", TownBuildingTemplates.Kind.COMMERCIAL,
                Optional.of(VillagerProfession.ARMORER), "synthetic-plan-fence-test", builderId);
        helper.assertTrue(boundResolution instanceof TownConstructionService.CapacityBindResolution.Bound,
                "test setup: the admission must bind to its matching site, found " + boundResolution);
        ResidentCapacityAdmission bound = ((TownConstructionService.CapacityBindResolution.Bound) boundResolution).admission();
        Optional<ResidentCapacityAdmission> completed = storage.completeCapacityAdmission(bound);
        helper.assertTrue(completed.isPresent(), "test setup: the admission must reach COMPLETED");

        // The SAME beneficiary now has a brand-new, unrelated need - requestResidentCapacity's own
        // existing createOrReuseCapacityAdmission legitimately replaces the terminal record.
        beneficiary.setVillagerData(beneficiary.getVillagerData().setProfession(VillagerProfession.FARMER));
        TownResidentDemand newDemand = new TownResidentDemand(1, Map.of(VillagerProfession.FARMER, 1));
        Optional<ResidentCapacityAdmission> replaced = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), newDemand, storage);
        helper.assertTrue(replaced.isPresent() && replaced.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                "test setup: the beneficiary's own new, unrelated request must be created and unbound");
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(replaced.get()::equals).isPresent()
                        && !storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                                .filter(completed.get()::equals).isPresent(),
                "test setup: the beneficiary's own record must now be the NEW unrelated one - the old COMPLETED record is gone from that key");

        // The OLD site's fence must still hold: no admission - the new unrelated one, or any other,
        // same builder/plan or not - can ever bind there again.
        helper.assertTrue(storage.hasSiteEverBeenBoundToAnAdmission(helper.getLevel().dimension(), "site:completed-fence-test"),
                "the completed site's permanent fence must survive its own beneficiary's record being replaced");
        TownConstructionService.CapacityBindResolution reboundAttempt = TownConstructionService.resolveCapacityAdmissionForSite(
                helper.getLevel(), storage, absoluteCampfire, region, "site:completed-fence-test", TownBuildingTemplates.Kind.COMMERCIAL,
                Optional.of(VillagerProfession.FARMER), "synthetic-plan-fence-test", builderId);
        // [coordinator review 51276] The capacity WOULD match (home+FARMER vs COMMERCIAL+FARMER) -
        // this must be Denied (the permanent fence), never NoMatch.
        helper.assertTrue(reboundAttempt instanceof TownConstructionService.CapacityBindResolution.Denied,
                "the new unrelated admission must never bind to a site that was EVER bound before, same builder/plan or not - "
                        + "must be Denied, found " + reboundAttempt);
        helper.assertTrue(storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                        .filter(replaced.get()::equals).isPresent(),
                "the new unrelated admission must remain completely untouched after the refused rebind attempt");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51268/51270 P1/P2]: "Test changed plan
     * after reload" - "Drive a real partial funded bound build, alter/reload identity, invoke
     * buildAt, and assert no effects." Unlike the earlier version of this test (string/UUID
     * comparison only, which passed even with the production guard removed), this drives a REAL
     * campfire through real partial construction to a real BUILDING binding, tampers the PERSISTED
     * {@code BoundPlanSignature} to simulate a changed plan (e.g. a mod-version zoning change) ,
     * reloads, and calls the REAL {@code buildAt} again - asserting ZERO further mutation or debit,
     * through the production authority gate itself, not a hand-written comparison of it.
     */
    @GameTest(template = "settlement_test")
    public static void aChangedPlanSignatureAfterReloadNeverContinuesTheOldBinding(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        // The old (9,2,1) spot crossed the town boundary in CI: campfire x=-1584004,
        // beneficiary x=-1584000. Spawn at the same anchor so translation cannot change town.
        BlockPos beneficiarySpot = absoluteCampfire;
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        claimant.setNoAi(true);
        beneficiary.setNoAi(true);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertTrue(TownRegion.fromBlockCoords(beneficiary.blockPosition().getX(), beneficiary.blockPosition().getZ()).equals(region),
                    "test setup: beneficiary must belong to the campfire's exact town");
            helper.assertTrue(java.util.stream.StreamSupport.stream(helper.getLevel().getEntities().getAll().spliterator(), false)
                            .anyMatch(entity -> entity == beneficiary),
                    "test setup: beneficiary must be visible in the exact production entity iterable");
            Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                    helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
            helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

            for (int i = 0; i < 3; i++) {
                TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
            }
            helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must not have finished yet");
            Optional<ResidentCapacityAdmission> boundBeforeTamper = storage.capacityAdmissionFor(
                    helper.getLevel().dimension(), region, beneficiary.getUUID());
            helper.assertTrue(boundBeforeTamper.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING).isPresent(),
                    "test setup: the admission must already be a real, production-bound BUILDING record");
            int stoneBeforeTamper = store.balance(TownResourceCategory.STONE);
            int woodBeforeTamper = store.balance(TownResourceCategory.WOOD);

            CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
            ListTag admissions = saved.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
            helper.assertTrue(admissions.size() == 1, "test setup: exactly one persisted admission expected");
            String realPlanSignature = admissions.getCompound(0).getString("BoundPlanSignature");
            helper.assertTrue(!realPlanSignature.isEmpty(), "test setup: the real production plan signature must have been persisted");
            // Simulate a plan that changed at this exact coordinate since binding (e.g. a mod-version
            // zoning change) - the live world/seed is UNCHANGED, so buildAt's own freshly recomputed
            // plan signature on the next tick will still be realPlanSignature; only the PERSISTED one is
            // tampered, to desynchronize them exactly the way a genuine drift would.
            admissions.getCompound(0).putString("BoundPlanSignature", realPlanSignature + "-DIFFERENT");
            TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
            TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));

            for (int i = 0; i < 5; i++) {
                TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
            }

            helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                    "a desynchronized plan signature must stop ALL further construction, through the REAL production authority gate");
            helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == stoneBeforeTamper
                            && reloadedStore.balance(TownResourceCategory.WOOD) == woodBeforeTamper,
                    "a desynchronized plan signature must cause no further debit at all");
            helper.assertTrue(reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID())
                            .filter(value -> (realPlanSignature + "-DIFFERENT").equals(value.boundPlanSignature())
                                    && value.state() == ResidentCapacityAdmission.State.BUILDING).isPresent(),
                    "the admission's own record must stay exactly as tampered/reloaded - never silently repaired or advanced");
        }).thenSucceed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51268 P2]: "needs tests for missing
     * Builder, missing plan signature, conflicting beneficiaries/site, second save/load
     * persistence." Each malformed/conflicting variant below is built by tampering a real, validly
     * saved BUILDING admission's own NBT - never hand-invented - and must quarantine the EXACT site
     * it names rather than silently dropping just the record. The conflicting-beneficiaries variant
     * additionally proves the quarantine itself survives a SECOND save/load round trip, not only the
     * first.
     */
    @GameTest(template = "settlement_test")
    public static void malformedOrConflictingPersistedBindingsQuarantineTheirSiteNotJustDropTheRecord(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(new BlockPos(5, 2, 5));
        // [coordinator review 51373] Both distinct-UUID beneficiaries are spawned at the SAME
        // template-relative anchor (5,2,5) - previously at (3,2,3)/(7,2,7), which a random
        // structure placement could straddle across a region boundary (region derived only from
        // beneficiaryA, with beneficiaryB silently landing in a DIFFERENT region and correctly
        // rejected by requestResidentCapacity's own region check - a coordinate-precondition
        // defect, not a persistence assertion failure). Region is derived from absoluteCampfire,
        // the same convention every other test in this file already uses, and independently
        // asserted equal for both beneficiaries before any admission request is attempted.
        Villager beneficiaryA = resident(helper, new BlockPos(5, 2, 5), VillagerProfession.ARMORER);
        Villager beneficiaryB = resident(helper, new BlockPos(5, 2, 5), VillagerProfession.ARMORER);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        helper.assertTrue(TownRegion.fromBlockCoords(beneficiaryA.blockPosition().getX(), beneficiaryA.blockPosition().getZ()).equals(region)
                        && TownRegion.fromBlockCoords(beneficiaryB.blockPosition().getX(), beneficiaryB.blockPosition().getZ()).equals(region),
                "test setup: both beneficiaries must resolve to the exact same region as the campfire");
        TownStorage storage = foundedStorage(helper, region);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of(VillagerProfession.ARMORER, 1));
        Optional<ResidentCapacityAdmission> admissionA = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiaryA.getUUID(), demand, storage);
        Optional<ResidentCapacityAdmission> admissionB = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiaryB.getUUID(), demand, storage);
        helper.assertTrue(admissionA.isPresent() && admissionB.isPresent(), "test setup: both admissions must be created");
        TownConstructionService.CapacityBindResolution boundAResolution = TownConstructionService.resolveCapacityAdmissionForSite(
                helper.getLevel(), storage, absoluteCampfire, region, "site:persistence-test", TownBuildingTemplates.Kind.COMMERCIAL,
                Optional.of(VillagerProfession.ARMORER), "plan-x", UUID.randomUUID());
        helper.assertTrue(boundAResolution instanceof TownConstructionService.CapacityBindResolution.Bound,
                "test setup: admission A must bind, found " + boundAResolution);
        Optional<ResidentCapacityAdmission> boundA = Optional.of(
                ((TownConstructionService.CapacityBindResolution.Bound) boundAResolution).admission());
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag admissions = saved.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        helper.assertTrue(admissions.size() == 2, "test setup: exactly two persisted admissions expected (A bound, B unbound)");

        // Variant 1: missing Builder on the BUILDING entry.
        CompoundTag missingBuilder = saved.copy();
        ListTag missingBuilderList = missingBuilder.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < missingBuilderList.size(); i++) {
            if ("BUILDING".equals(missingBuilderList.getCompound(i).getString("State"))) {
                missingBuilderList.getCompound(i).remove("Builder");
            }
        }
        TownStorage reloadedMissingBuilder = TownStorage.load(missingBuilder, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedMissingBuilder.isAdmissionSiteQuarantined(helper.getLevel().dimension(), "site:persistence-test"),
                "a BUILDING record missing Builder must quarantine its named site");
        // [coordinator review 51270 P2] Which of beneficiaryA/B actually got bound depends on
        // HashMap iteration order inside resolveCapacityAdmissionForSite, never assumed - use the
        // ACTUAL beneficiary the bind call itself returned.
        helper.assertTrue(reloadedMissingBuilder.capacityAdmissionFor(helper.getLevel().dimension(), region, boundA.get().beneficiaryId())
                        .isEmpty(),
                "the malformed admission record itself must be dropped, never half-loaded");

        // Variant 2: missing BoundPlanSignature on the BUILDING entry.
        CompoundTag missingPlan = saved.copy();
        ListTag missingPlanList = missingPlan.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < missingPlanList.size(); i++) {
            if ("BUILDING".equals(missingPlanList.getCompound(i).getString("State"))) {
                missingPlanList.getCompound(i).remove("BoundPlanSignature");
            }
        }
        TownStorage reloadedMissingPlan = TownStorage.load(missingPlan, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedMissingPlan.isAdmissionSiteQuarantined(helper.getLevel().dimension(), "site:persistence-test"),
                "a BUILDING record missing BoundPlanSignature must quarantine its named site");

        // Variant 2b [coordinator review 51271/51274]: an otherwise-COMPLETE legacy record saved
        // BEFORE the generation-token field existed at all (Builder/BoundPlanSignature both
        // present and valid - only AnchorGenerationToken missing). Must quarantine (fail closed,
        // never trusted without a verifiable live anchor) while preserving BOTH the record itself
        // (history) and the reverse-site fence - never dropped outright like a genuinely malformed
        // entry.
        CompoundTag missingToken = saved.copy();
        ListTag missingTokenList = missingToken.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < missingTokenList.size(); i++) {
            if ("BUILDING".equals(missingTokenList.getCompound(i).getString("State"))) {
                missingTokenList.getCompound(i).remove("AnchorGenerationToken");
            }
        }
        TownStorage reloadedMissingToken = TownStorage.load(missingToken, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedMissingToken.isAdmissionSiteQuarantined(helper.getLevel().dimension(), "site:persistence-test"),
                "a legacy BUILDING record with no generation token at all must quarantine its own site");
        helper.assertTrue(reloadedMissingToken.capacityAdmissionFor(helper.getLevel().dimension(), region, boundA.get().beneficiaryId())
                        .filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING && value.anchorGenerationToken() == null)
                        .isPresent(),
                "the legacy record itself (history) must be PRESERVED, not dropped, unlike a genuinely malformed entry");
        helper.assertTrue(reloadedMissingToken.hasSiteEverBeenBoundToAnAdmission(helper.getLevel().dimension(), "site:persistence-test"),
                "the reverse-site fence must also be preserved for a legacy no-token record, not just the record itself");

        // Variant 3: conflicting beneficiaries both claiming the SAME site.
        CompoundTag conflicting = saved.copy();
        ListTag conflictingList = conflicting.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < conflictingList.size(); i++) {
            CompoundTag entry = conflictingList.getCompound(i);
            if (!"BUILDING".equals(entry.getString("State"))) {
                entry.putString("State", "BUILDING");
                entry.putString("BoundSiteOwner", "site:persistence-test");
                entry.putUUID("Builder", UUID.randomUUID());
                entry.putString("BoundPlanSignature", "plan-x");
            }
        }
        TownStorage reloadedConflicting = TownStorage.load(conflicting, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedConflicting.isAdmissionSiteQuarantined(helper.getLevel().dimension(), "site:persistence-test"),
                "two different admissions both claiming the same bound site must quarantine that site");
        helper.assertTrue(reloadedConflicting.capacityAdmissionCount() == 2,
                "both conflicting admission RECORDS stay loaded (ambiguous ownership, not data loss) - only the SITE is quarantined");

        // "Second save/load persistence": the quarantine itself must survive ANOTHER round trip.
        CompoundTag savedAgain = reloadedConflicting.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloadedTwice = TownStorage.load(savedAgain, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedTwice.isAdmissionSiteQuarantined(helper.getLevel().dimension(), "site:persistence-test"),
                "a quarantined site must remain quarantined across a SECOND save/load round trip, not only the first");
        helper.succeed();
    }

    /**
     * MINECRAFT-454 acceptance [coordinator review, comment 51268 P2]: "Assert no mutation/debit/
     * rebinding/POI changes" for a quarantined site, through the REAL {@code buildAt} path (not only
     * the storage-level mechanics above) at a REAL campfire coordinate.
     */
    @GameTest(template = "settlement_test")
    public static void buildAtNeverMutatesDebitsOrRebindsAQuarantinedSite(GameTestHelper helper) {
        BlockPos campfireCell = new BlockPos(5, 2, 5);
        helper.setBlock(campfireCell.below(), Blocks.DIRT);
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        String realSiteOwner = TownStorage.siteOwner(absoluteCampfire);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(VillagerProfession.FARMER));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(helper.getLevel().dimension(),
                TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ())));
        store.credit(TownResourceCategory.STONE, 100000);
        store.credit(TownResourceCategory.WOOD, 100000);
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag quarantineList = saved.getList("QuarantinedAdmissionSites", Tag.TAG_COMPOUND);
        CompoundTag quarantineEntry = new CompoundTag();
        quarantineEntry.putString("Key", helper.getLevel().dimension().location() + "@" + realSiteOwner);
        quarantineList.add(quarantineEntry);
        saved.put("QuarantinedAdmissionSites", quarantineList);
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.isAdmissionSiteQuarantined(helper.getLevel().dimension(), realSiteOwner),
                "test setup: this exact real campfire's own site must actually be quarantined");
        TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(),
                TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ())));
        int stoneBefore = reloadedStore.balance(TownResourceCategory.STONE);
        int woodBefore = reloadedStore.balance(TownResourceCategory.WOOD);

        for (int i = 0; i < 5; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        }

        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE),
                "a quarantined site must never be mutated at all");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == stoneBefore
                        && reloadedStore.balance(TownResourceCategory.WOOD) == woodBefore,
                "a quarantined site must never be debited at all");
        helper.assertTrue(reloaded.capacityAdmissionBoundToSite(helper.getLevel().dimension(), realSiteOwner).isEmpty(),
                "a quarantined site must never report (or accept) a binding");
        helper.succeed();
    }

    private static TownStorage foundedStorage(GameTestHelper helper, RegionCoord region) {
        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        return storage;
    }

    /** {@code anchor} offset by ({@code dx}, {@code dz}), with either axis's sign flipped if the
     *  plain offset would cross a {@link TownConstants#REGION_SIZE_BLOCKS} region boundary - so the
     *  result is always in the SAME region as {@code anchor}, independent of where the GameTest
     *  batch runner's own floating structure origin happens to land relative to that fixed grid.
     *  [coordinator review 51284/51282] A plain structure-relative offset computed through {@code
     *  helper.absolutePos} has no such guarantee: it is only as safe as wherever this run's
     *  structure happens to be placed, which is exactly what let two residents from the same
     *  fixture land in adjacent regions in a real run. Only safe for {@code |dx|, |dz| <
     *  REGION_SIZE_BLOCKS / 2}, true for every small fixture offset this file actually uses. */
    private static BlockPos nearbyWithinSameRegion(BlockPos anchor, int dx, int dz) {
        int regionRemX = Math.floorMod(anchor.getX(), TownConstants.REGION_SIZE_BLOCKS);
        int regionRemZ = Math.floorMod(anchor.getZ(), TownConstants.REGION_SIZE_BLOCKS);
        int safeDx = regionRemX + dx >= 0 && regionRemX + dx < TownConstants.REGION_SIZE_BLOCKS ? dx : -dx;
        int safeDz = regionRemZ + dz >= 0 && regionRemZ + dz < TownConstants.REGION_SIZE_BLOCKS ? dz : -dz;
        return new BlockPos(anchor.getX() + safeDx, anchor.getY(), anchor.getZ() + safeDz);
    }

    private static Villager resident(GameTestHelper helper, BlockPos relativePos, VillagerProfession profession) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(relativePos),
                MobSpawnType.EVENT, false, false);
        helper.assertTrue(villager != null, "test setup: resident villager must be constructible");
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(profession));
        return villager;
    }

    private static void takeHome(GameTestHelper helper, BlockPos pos) {
        helper.getLevel().getPoiManager().add(pos, poiType(helper, PoiTypes.HOME));
        helper.getLevel().getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, candidate) -> candidate.equals(pos), pos, 1);
    }

    private static void takeArmorerWorkplace(GameTestHelper helper, BlockPos pos) {
        takeWorkplace(helper, pos, PoiTypes.ARMORER);
    }

    /** [coordinator review 51268 P2] Generalized so a test can set up a pre-existing workplace
     *  matching whichever profession it actually needs, rather than always ARMORER regardless of
     *  what the villager's own profession is set to. */
    private static void takeWorkplace(GameTestHelper helper, BlockPos pos, net.minecraft.resources.ResourceKey<PoiType> poiKey) {
        helper.getLevel().getPoiManager().add(pos, poiType(helper, poiKey));
        helper.getLevel().getPoiManager().take(type -> type.is(poiKey), (type, candidate) -> candidate.equals(pos), pos, 1);
    }

    private static net.minecraft.resources.ResourceKey<PoiType> poiKeyFor(VillagerProfession profession) {
        return profession == VillagerProfession.FARMER ? PoiTypes.FARMER : PoiTypes.ARMORER;
    }

    private static Holder<PoiType> poiType(GameTestHelper helper, net.minecraft.resources.ResourceKey<PoiType> key) {
        HolderLookup.RegistryLookup<PoiType> registry = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE);
        return registry.getOrThrow(key);
    }

    /**
     * MINECRAFT-454 [coordinator review 51356/51358]: a beneficiary that requested a durable,
     * unbound, home-only admission while genuinely unemployed must never bind (or advance) that
     * SAME admission once it later becomes the claimant/Builder of a site - there is no such thing
     * as a villager building its own home. requestResidentCapacity only rejects a CURRENT Builder
     * as beneficiary; this proves the later-identity-collision path is independently closed at
     * bind time too: zero mutation, debit, anchor-token write, POI/handoff effect, or exception for
     * the self-claim tick, the durable admission left completely untouched, and a DIFFERENT,
     * genuinely eligible Builder still able to satisfy the exact same admission afterward.
     */
    @GameTest(template = "settlement_test")
    public static void aBeneficiaryThatLaterBecomesTheClaimantNeverBindsOrAdvancesItsOwnAdmission(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));

        // Requested while genuinely unemployed - requestResidentCapacity's own Builder-beneficiary
        // guard does not (and cannot) see the FUTURE profession change below.
        BlockPos selfClaimSpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager selfClaimingBeneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, selfClaimSpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(selfClaimingBeneficiary);
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, selfClaimingBeneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent() && admission.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                "test setup: the home-only admission must be created while genuinely unemployed");

        // The SAME villager now becomes the Builder claimant of this exact site.
        selfClaimingBeneficiary.setVillagerData(selfClaimingBeneficiary.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        selfClaimingBeneficiary.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        selfClaimingBeneficiary.teleportTo(absoluteCampfire.getX() + 0.5, absoluteCampfire.getY(), absoluteCampfire.getZ() + 0.5);

        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);
        for (int i = 0; i < 10; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a self-claiming Builder must cause ZERO placement, including the closing cell, at its own site");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore && store.balance(TownResourceCategory.WOOD) == woodBefore,
                "a self-claiming Builder must cause ZERO debit");
        Optional<ResidentCapacityAdmission> afterSelfClaim = storage.capacityAdmissionFor(
                helper.getLevel().dimension(), region, selfClaimingBeneficiary.getUUID());
        helper.assertTrue(afterSelfClaim.filter(value -> value.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && value.boundSiteOwner() == null && value.builderId() == null).isPresent(),
                "the durable admission must remain completely unbound and untouched - found " + afterSelfClaim);
        helper.assertTrue(selfClaimingBeneficiary.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "a self-claiming Builder must never receive its own handoff");
        helper.assertTrue(!storage.hasSiteEverBeenBoundToAnAdmission(helper.getLevel().dimension(), TownStorage.siteOwner(absoluteCampfire)),
                "a correctly-refused self-claim must never record this site as ever bound");

        // A DIFFERENT, genuinely eligible Builder must still be able to satisfy the SAME admission.
        selfClaimingBeneficiary.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        selfClaimingBeneficiary.setVillagerData(selfClaimingBeneficiary.getVillagerData().setProfession(VillagerProfession.NONE));
        selfClaimingBeneficiary.teleportTo(selfClaimSpot.getX() + 0.5, selfClaimSpot.getY(), selfClaimSpot.getZ() + 0.5);

        Villager distinctBuilder = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(distinctBuilder);
        distinctBuilder.setVillagerData(distinctBuilder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        distinctBuilder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        Optional<ResidentCapacityAdmission> boundToDistinctBuilder = Optional.empty();
        for (int i = 0; i < 5 && boundToDistinctBuilder.isEmpty(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
            boundToDistinctBuilder = storage.capacityAdmissionFor(helper.getLevel().dimension(), region, selfClaimingBeneficiary.getUUID())
                    .filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING);
        }
        helper.assertTrue(boundToDistinctBuilder.filter(value -> value.builderId().equals(distinctBuilder.getUUID())).isPresent(),
                "a DIFFERENT, eligible Builder must still be able to bind and advance the SAME admission the self-claim correctly refused - found "
                        + boundToDistinctBuilder);
        helper.succeed();
    }

    /**
     * MINECRAFT-454 [coordinator review 51356/51358]: defensive coverage for an admission ALREADY
     * bound (e.g. persisted by a build predating this fix) whose own recorded beneficiary happens
     * to equal the current claimant - buildAt must refuse to advance it any further the moment this
     * exact tick is driven, with zero further placement, debit, or silent completion.
     */
    @GameTest(template = "settlement_test")
    public static void anAlreadyBoundAdmissionWhoseBeneficiaryBecomesTheClaimantIsNeverAdvanced(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        assertNonStorehouseFixture(helper, site);

        Villager originalBuilder = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(originalBuilder);
        originalBuilder.setVillagerData(originalBuilder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        originalBuilder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        BlockPos beneficiarySpot = helper.absolutePos(new BlockPos(9, 2, 1));
        Villager beneficiary = EntityType.VILLAGER.create(helper.getLevel(), null, beneficiarySpot, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(beneficiary);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        TownResidentDemand demand = new TownResidentDemand(1, Map.of());
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, beneficiary.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent(), "test setup: the home-only admission must be created");

        // Drive ONE tick to bind legitimately to the ORIGINAL (distinct) builder - never finish.
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        Optional<ResidentCapacityAdmission> bound = storage.capacityAdmissionFor(helper.getLevel().dimension(), region, beneficiary.getUUID());
        helper.assertTrue(bound.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING
                        && value.builderId().equals(originalBuilder.getUUID())).isPresent(),
                "test setup: the admission must already be BUILDING, bound to the original, distinct builder - found " + bound);
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "test setup: the site must not have finished yet");

        // Simulate a persisted record from a build predating this fix, where this exact identity
        // collision was never guarded against: tamper the SAVED admission's own Beneficiary field
        // to equal the current builder's own UUID, reload, and let that SAME villager keep
        // claiming this site.
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag admissions = saved.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        helper.assertTrue(admissions.size() == 1, "test setup: exactly one persisted admission expected");
        admissions.getCompound(0).putUUID("Beneficiary", originalBuilder.getUUID());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
        int stoneBeforeTamperedTicks = reloadedStore.balance(TownResourceCategory.STONE);
        int woodBeforeTamperedTicks = reloadedStore.balance(TownResourceCategory.WOOD);

        for (int i = 0; i < 10; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        }

        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "an already-bound admission whose own beneficiary now equals the current claimant must cause ZERO further placement");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == stoneBeforeTamperedTicks
                        && reloadedStore.balance(TownResourceCategory.WOOD) == woodBeforeTamperedTicks,
                "ZERO further debit must happen once the tampered self-identity is detected");
        Optional<ResidentCapacityAdmission> afterTamperedTicks = reloaded.capacityAdmissionFor(
                helper.getLevel().dimension(), region, originalBuilder.getUUID());
        helper.assertTrue(afterTamperedTicks.filter(value -> value.state() == ResidentCapacityAdmission.State.BUILDING).isPresent(),
                "the tampered admission must remain exactly BUILDING, never silently advanced to COMPLETED");
        helper.succeed();
    }
}
