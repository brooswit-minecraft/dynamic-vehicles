package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MINECRAFT-455 self-admission liveness [coordinator design 51579-51586, resumed 51681]: focused,
 * assertion-backed lifecycle coverage for {@code ResidentCapacityAdmission.State#SELF_CLAIM_LINKED}
 * - a resident's own real HOME/workplace-capacity admission that is later matched, at bind time,
 * against that SAME resident's own self-built site (beneficiary == builder == live claimant).
 * Dedicated namespace, never the shared default suite - mirrors the established pattern already
 * used by {@link TownResidentCapacityAdmissionGameTests}' own fixtures.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownSelfClaimLinkedAdmissionGameTests {
    private TownSelfClaimLinkedAdmissionGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    /** Four interior corners of the settlement_test template, same deterministic candidates
     *  {@code TownResidentCapacityAdmissionGameTests#HANDOFF_CANDIDATE_ANCHORS} already uses -
     *  duplicated here (not shared across files, consistent with this codebase's existing
     *  per-file fixture convention) so a test that specifically needs a profession-bearing
     *  (COMMERCIAL/INDUSTRIAL) site can deterministically find one rather than depending on
     *  whichever kind the single fixed ANCHOR happens to resolve to for this run's placement. */
    private static final BlockPos[] CANDIDATE_ANCHORS = {
            new BlockPos(3, 2, 3), new BlockPos(20, 2, 3), new BlockPos(3, 2, 20), new BlockPos(20, 2, 20)
    };

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 0; y <= 7; y++) {
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

    /** Spawns a real adult villager at {@code absoluteCampfire}, requests HOME (+ the given
     *  workplace demand, if any) capacity for it while it is still genuinely {@code NONE} (the
     *  only eligible beneficiary state), THEN - simulating that this exact resident later,
     *  separately became the Builder claimant of this exact site - flips it to Builder with
     *  {@code JOB_SITE} pointing at the campfire. Matches the established direct-call convention
     *  {@code TownResidentCapacityAdmissionGameTests} already uses for a "real claimant" without
     *  needing full vanilla AI. */
    private static Villager spawnSelfClaimingResident(GameTestHelper helper, TownStorage storage, RegionCoord region,
            BlockPos absoluteCampfire, boolean needsWorkplace, Optional<VillagerProfession> finalProfession) {
        Villager resident = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(resident);
        helper.assertTrue(resident.getVillagerData().getProfession() == VillagerProfession.NONE,
                "test setup: resident must start NONE - the only eligible beneficiary state");
        Map<VillagerProfession, Integer> requestedWorkplaces = needsWorkplace
                ? finalProfession.map(profession -> Map.of(profession, 1)).orElse(Map.of()) : Map.of();
        if (needsWorkplace && finalProfession.isPresent()) {
            // [established pattern] unmetDemand's own aggregate only counts a workplace as missing
            // for a villager that ALREADY holds that profession (NONE never counts) - this resident
            // must already hold its own retained profession before the demand is requested, exactly
            // mirroring TownResidentCapacityAdmissionGameTests' own identical setup.
            resident.setVillagerData(resident.getVillagerData().setProfession(finalProfession.get()));
        }
        TownResidentDemand demand = new TownResidentDemand(1, requestedWorkplaces, false);
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                helper.getLevel(), region, resident.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent() && admission.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                "test setup: the resident's own admission must be created AWAITING_CLAIM before it becomes a Builder - admission="
                        + admission);
        resident.setVillagerData(resident.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        resident.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        return resident;
    }

    /** Drives {@code buildAt} directly (the same pure, explicit-storage call every other admission
     *  test in this codebase uses - no real tick pacing needed) until either the site completes
     *  (the resident is released from Builder) or {@code maxAttempts} is exhausted. */
    private static void driveBuildAt(ServerLevel level, BlockPos absoluteCampfire, TownStorage storage, Villager resident,
            int maxAttempts) {
        for (int i = 0; i < maxAttempts && resident.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(level, absoluteCampfire, storage);
        }
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void selfClaimedAdmissionCompletesThroughTheOrdinaryClaimantOnlyPathAndMatchesItsOwnRequestedCapacity(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE,
                "test setup: this fixture must resolve to a non-STOREHOUSE kind to exercise admission binding at all; got "
                        + site.kind());
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, true, site.finalProfession());

        TownConstructionService.buildAt(level, absoluteCampfire, storage);
        Optional<ResidentCapacityAdmission> afterFirstTick = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(afterFirstTick.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED
                        && a.builderId().equals(resident.getUUID()) && a.beneficiaryId().equals(resident.getUUID())
                        && a.boundSiteOwner().equals(siteOwner)).isPresent(),
                "binding must happen at the first construction effect, as SELF_CLAIM_LINKED, against the exact resident - found "
                        + afterFirstTick);
        helper.assertTrue(storage.hasSiteEverBeenBoundToAnAdmission(level.dimension(), siteOwner),
                "a SELF_CLAIM_LINKED bind must set the SAME permanent reverse-site fence a BUILDING bind sets");

        driveBuildAt(level, absoluteCampfire, storage, resident, 500);

        Optional<ResidentCapacityAdmission> afterBuild = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(resident.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "the site must actually finish - the resident must no longer be Builder");
        helper.assertTrue(afterBuild.filter(a -> a.state() == ResidentCapacityAdmission.State.COMPLETED).isPresent(),
                "the SELF_CLAIM_LINKED admission must reach COMPLETED once the resident's own handoff actually happened, found "
                        + afterBuild.map(ResidentCapacityAdmission::state).orElse(null));

        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(expectedBed)).orElse(false),
                "the resident must receive its own real HOME this build placed");
        helper.assertTrue(level.getPoiManager().getFreeTickets(expectedBed) == 0,
                "the resident's new bed ticket must actually be held, not merely referenced by memory");
        if (site.finalProfession().isPresent()) {
            helper.assertTrue(resident.getVillagerData().getProfession() == site.finalProfession().get(),
                    "the resident must receive its own requested final profession");
            BlockPos expectedWorkstation = TownBuildingTemplates.workstationPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
            helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            .map(gp -> gp.pos().equals(expectedWorkstation)).orElse(false),
                    "the resident's JOB_SITE must point at the real workstation this build placed");
        }
        helper.assertTrue(store.balance(TownResourceCategory.STONE) < stoneBefore || store.balance(TownResourceCategory.WOOD) < woodBefore,
                "a genuinely completed build must have actually debited resources");
        helper.succeed();
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void selfClaimedHomeOnlyAdmissionStillReceivesTheSitesOwnOfferedWorkstation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);

        BlockPos foundAnchor = null;
        ResolvedSite foundSite = null;
        for (BlockPos candidate : CANDIDATE_ANCHORS) {
            BlockPos candidateAbsolute = helper.absolutePos(candidate);
            ResolvedSite candidateSite = resolveSite(helper, candidateAbsolute);
            if (candidateSite.kind() != TownBuildingTemplates.Kind.STOREHOUSE && candidateSite.finalProfession().isPresent()) {
                foundAnchor = candidate;
                foundSite = candidateSite;
                break;
            }
        }
        helper.assertTrue(foundAnchor != null,
                "test setup: at least one of the 4 candidate anchors must resolve to a profession-bearing, non-STOREHOUSE site");
        BlockPos absoluteCampfire = helper.absolutePos(foundAnchor);
        helper.setBlock(foundAnchor, Blocks.CAMPFIRE);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        // needsWorkplace=false: a HOME-ONLY admission, even though this site's own kind/zone also
        // offers a workstation - the regression this test targets.
        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, false, foundSite.finalProfession());

        driveBuildAt(level, absoluteCampfire, storage, resident, 500);

        helper.assertTrue(resident.getVillagerData().getProfession() == foundSite.finalProfession().get(),
                "a home-only self-claim on a profession-bearing site must still receive that site's own workstation/profession - "
                        + "actualProfession=" + resident.getVillagerData().getProfession()
                        + " expectedProfession=" + foundSite.finalProfession()
                        + " stillBuilder=" + (resident.getVillagerData().getProfession() == BuilderProfession.BUILDER.get())
                        + " campfireStillPresent=" + level.getBlockState(absoluteCampfire).is(Blocks.CAMPFIRE)
                        + " admission=" + storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID()));
        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        BlockPos expectedWorkstation = TownBuildingTemplates.workstationPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(expectedWorkstation)).orElse(false),
                "the resident's JOB_SITE must point at the real workstation this build placed, not be left null/stale");
        Optional<ResidentCapacityAdmission> afterBuild = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(afterBuild.filter(a -> a.state() == ResidentCapacityAdmission.State.COMPLETED).isPresent(),
                "the home-only admission must still reach COMPLETED once its own requested home was actually delivered");
        helper.succeed();
    }

    /** [MINECRAFT-455 unassigned-workplace demand] A self-claimed admission whose ONLY request is
     *  {@code needsAnyWorkplace} - a genuinely unemployed resident, never a retained specific
     *  profession - must complete through the exact same SELF_CLAIM_LINKED lifecycle, matching
     *  whichever real profession this site's own workstation happens to resolve to. Mirrors {@link
     *  #selfClaimedAdmissionCompletesThroughTheOrdinaryClaimantOnlyPathAndMatchesItsOwnRequestedCapacity}
     *  but for the new profession-independent demand shape instead of a retained profession. */
    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void selfClaimedNeedsAnyWorkplaceOnlyAdmissionCompletesAndDeliversWhicheverRealProfessionTheSiteOffers(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);

        BlockPos foundAnchor = null;
        ResolvedSite foundSite = null;
        for (BlockPos candidate : CANDIDATE_ANCHORS) {
            BlockPos candidateAbsolute = helper.absolutePos(candidate);
            ResolvedSite candidateSite = resolveSite(helper, candidateAbsolute);
            if (candidateSite.kind() != TownBuildingTemplates.Kind.STOREHOUSE && candidateSite.finalProfession().isPresent()) {
                foundAnchor = candidate;
                foundSite = candidateSite;
                break;
            }
        }
        helper.assertTrue(foundAnchor != null,
                "test setup: at least one of the 4 candidate anchors must resolve to a profession-bearing, non-STOREHOUSE site");
        BlockPos absoluteCampfire = helper.absolutePos(foundAnchor);
        helper.setBlock(foundAnchor, Blocks.CAMPFIRE);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        Villager resident = EntityType.VILLAGER.create(level, null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        level.addFreshEntity(resident);
        helper.assertTrue(resident.getVillagerData().getProfession() == VillagerProfession.NONE,
                "test setup: resident must start genuinely unemployed - needsAnyWorkplace's only eligible beneficiary state");
        TownResidentDemand demand = new TownResidentDemand(0, Map.of(), true);
        Optional<ResidentCapacityAdmission> admission = TownConstructionService.requestResidentCapacity(
                level, region, resident.getUUID(), demand, storage);
        helper.assertTrue(admission.isPresent() && admission.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && admission.get().needsAnyWorkplace() && admission.get().requestedWorkplaces().isEmpty()
                        && admission.get().requestedHomes() == 0,
                "test setup: the resident's own needsAnyWorkplace-only admission must be created AWAITING_CLAIM before it "
                        + "becomes a Builder - admission=" + admission);
        resident.setVillagerData(resident.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        resident.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), absoluteCampfire));

        driveBuildAt(level, absoluteCampfire, storage, resident, 500);

        helper.assertTrue(resident.getVillagerData().getProfession() == foundSite.finalProfession().get(),
                "a needsAnyWorkplace-only self-claim must receive whichever real profession this site's own workstation "
                        + "resolves to - actualProfession=" + resident.getVillagerData().getProfession()
                        + " expectedProfession=" + foundSite.finalProfession());
        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        BlockPos expectedWorkstation = TownBuildingTemplates.workstationPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(expectedWorkstation)).orElse(false),
                "the resident's JOB_SITE must point at the real workstation this build placed, not be left null/stale");
        Optional<ResidentCapacityAdmission> afterBuild = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(afterBuild.filter(a -> a.state() == ResidentCapacityAdmission.State.COMPLETED
                        && a.boundSiteOwner().equals(siteOwner)).isPresent(),
                "the needsAnyWorkplace-only admission must reach COMPLETED once its workplace was actually delivered; found "
                        + afterBuild);
        helper.succeed();
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void aDistinctCompetingAdmissionCanNeverBindToAnAlreadySelfClaimedSite(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE, "test setup: must be non-STOREHOUSE, got " + site.kind());
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, false, site.finalProfession());
        TownConstructionService.buildAt(level, absoluteCampfire, storage);
        helper.assertTrue(storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID())
                        .filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "test setup: the resident's own admission must be SELF_CLAIM_LINKED before introducing a competing demand");

        // A genuinely DIFFERENT villager, far enough away to never collide physically, requests
        // its own home capacity in the SAME region/town - introduced AFTER the self-claim already
        // bound this exact site. [51383-style correction] The template's own absolute placement is
        // random per run, so a fixed relative offset from ANCHOR is not guaranteed to land in the
        // SAME region - deterministically searched here instead, never assumed.
        BlockPos competitorRelative = null;
        for (int x = 1; x <= 22 && competitorRelative == null; x++) {
            for (int z = 1; z <= 22 && competitorRelative == null; z++) {
                if (Math.abs(x - ANCHOR.getX()) < 4 && Math.abs(z - ANCHOR.getZ()) < 4) {
                    continue;
                }
                BlockPos candidate = new BlockPos(x, ANCHOR.getY(), z);
                if (TownRegion.fromBlockCoords(helper.absolutePos(candidate).getX(), helper.absolutePos(candidate).getZ()).equals(region)) {
                    competitorRelative = candidate;
                }
            }
        }
        helper.assertTrue(competitorRelative != null, "test setup: a same-region, non-blocking companion position for the competitor must exist");
        BlockPos competitorPos = helper.absolutePos(competitorRelative);
        Villager competitor = EntityType.VILLAGER.create(level, null, competitorPos, MobSpawnType.EVENT, false, false);
        level.addFreshEntity(competitor);
        TownResidentDemand competitorDemand = new TownResidentDemand(1, Map.of(), false);
        Optional<ResidentCapacityAdmission> competitorAdmission = TownConstructionService.requestResidentCapacity(
                level, region, competitor.getUUID(), competitorDemand, storage);
        helper.assertTrue(competitorAdmission.isPresent(), "test setup: the competitor's own admission must be created");

        driveBuildAt(level, absoluteCampfire, storage, resident, 500);

        helper.assertTrue(storage.capacityAdmissionFor(level.dimension(), region, competitor.getUUID())
                        .filter(a -> a.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM).isPresent(),
                "the competitor's own admission must never bind to the already self-claimed, permanently-fenced site");
        helper.assertTrue(storage.capacityAdmissionBoundToSite(level.dimension(), siteOwner)
                        .filter(a -> a.beneficiaryId().equals(resident.getUUID())).isPresent(),
                "the site must stay bound to the ORIGINAL self-claim throughout, never rebound to the competitor");
        helper.succeed();
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void anUnloadedClaimantIsDeferredNeverTreatedAsLoss(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE, "test setup: must be non-STOREHOUSE, got " + site.kind());
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, false, site.finalProfession());
        TownConstructionService.buildAt(level, absoluteCampfire, storage);
        Optional<ResidentCapacityAdmission> linked = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(linked.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "test setup: must be SELF_CLAIM_LINKED before simulating unload");

        // Simulate "unloaded" the same way other tests in this codebase do: discard the entity so
        // it is no longer visible to level.getEntities().getAll() - the anchor itself (CAMPFIRE,
        // matching generation token) stays completely untouched.
        resident.discard();

        TownConstructionService.reconcileSelfClaimLinkedAdmissions(level, storage);
        Optional<ResidentCapacityAdmission> afterReconcile = storage.capacityAdmissionFor(level.dimension(), region, linked.get().beneficiaryId());
        helper.assertTrue(afterReconcile.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "an unloaded (not positively proven lost) claimant must never be reverted - found "
                        + afterReconcile.map(ResidentCapacityAdmission::state).orElse(null));
        helper.succeed();
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void aPositivelyRelinquishedClaimRevertsToAwaitingClaimWithTheOldSitePermanentlyFenced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE, "test setup: must be non-STOREHOUSE, got " + site.kind());
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, false, site.finalProfession());
        TownConstructionService.buildAt(level, absoluteCampfire, storage);
        helper.assertTrue(storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID())
                        .filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "test setup: must be SELF_CLAIM_LINKED before relinquishing");

        // The anchor itself stays untouched (still CAMPFIRE, same generation token) - the resident
        // stays LOADED but genuinely, positively relinquishes its own claim (e.g. reassigned
        // elsewhere), the only safe trigger this reconciliation pass honors.
        resident.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        resident.setVillagerData(resident.getVillagerData().setProfession(VillagerProfession.NONE));

        TownConstructionService.reconcileSelfClaimLinkedAdmissions(level, storage);

        Optional<ResidentCapacityAdmission> afterReconcile = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(afterReconcile.filter(a -> a.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                        && a.boundSiteOwner() == null && a.builderId() == null).isPresent(),
                "a positively relinquished claim (loaded, anchor intact, JOB_SITE genuinely moved) must revert to a clean AWAITING_CLAIM - found "
                        + afterReconcile);
        helper.assertTrue(storage.hasSiteEverBeenBoundToAnAdmission(level.dimension(), siteOwner),
                "the OLD physical site's permanent reverse-site fence must survive the revert - it is never made bindable again");
        helper.assertTrue(storage.capacityAdmissionBoundToSite(level.dimension(), siteOwner).isEmpty(),
                "the old site must show no CURRENT binding after the revert, even though it stays permanently fenced");
        helper.succeed();
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void anAmbiguousReplacedAnchorStaysLinkedAndFencedAcrossLaterRecoveryTicksNeverReassigned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE, "test setup: must be non-STOREHOUSE, got " + site.kind());
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, false, site.finalProfession());
        TownConstructionService.buildAt(level, absoluteCampfire, storage);
        Optional<ResidentCapacityAdmission> linked = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(linked.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "test setup: must be SELF_CLAIM_LINKED before the ambiguous replacement");

        // Simulates the EXACT fingerprint an already-successful (or still-interrupted) closing
        // leaves behind: the anchor cell is no longer CAMPFIRE, resident still loaded. This must
        // NEVER be read as positive loss evidence - it is indistinguishable from this mod's own
        // in-progress closing.
        helper.setBlock(ANCHOR, Blocks.AIR);

        for (int i = 0; i < 5; i++) {
            TownConstructionService.reconcileSelfClaimLinkedAdmissions(level, storage);
        }

        Optional<ResidentCapacityAdmission> afterRecoveryTicks = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(afterRecoveryTicks.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "an ambiguous replaced anchor must stay SELF_CLAIM_LINKED across repeated later recovery ticks, never reverted - found "
                        + afterRecoveryTicks.map(ResidentCapacityAdmission::state).orElse(null));
        helper.assertTrue(storage.hasSiteEverBeenBoundToAnAdmission(level.dimension(), siteOwner),
                "the site's own permanent fence must remain untouched throughout");

        // Round-trip through persistence too - the ambiguity must survive reload unresolved.
        net.minecraft.nbt.CompoundTag saved = storage.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        TownStorage reloaded = TownStorage.load(saved, level.registryAccess());
        Optional<ResidentCapacityAdmission> afterReload = reloaded.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(afterReload.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "the unresolved SELF_CLAIM_LINKED admission must survive a save/load round-trip exactly as-is - found "
                        + afterReload.map(ResidentCapacityAdmission::state).orElse(null));
        helper.succeed();
    }

    @GameTest(template = "settlement_test", templateNamespace = "dynamicvehicles_m455_self_claim_linked",
            batch = "m455SelfClaimLinked", skyAccess = true)
    public static void aMalformedSelfClaimedRecordWithMismatchedIdentityIsQuarantinedNotAdvanced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        helper.assertTrue(site.kind() != TownBuildingTemplates.Kind.STOREHOUSE, "test setup: must be non-STOREHOUSE, got " + site.kind());
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String siteOwner = TownStorage.siteOwner(absoluteCampfire);

        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        fundStore(helper, storage, absoluteCampfire, 100000);

        // A real claimant builds and links normally first, establishing a genuine anchor/token.
        Villager resident = spawnSelfClaimingResident(helper, storage, region, absoluteCampfire, false, site.finalProfession());
        TownConstructionService.buildAt(level, absoluteCampfire, storage);
        Optional<ResidentCapacityAdmission> linked = storage.capacityAdmissionFor(level.dimension(), region, resident.getUUID());
        helper.assertTrue(linked.filter(a -> a.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED).isPresent(),
                "test setup: must be SELF_CLAIM_LINKED before corrupting it");

        // Corrupt ONLY the admission's own recorded builderId to a different, unrelated UUID -
        // beneficiary/state/site/token all stay exactly as a genuine self-claim would record them,
        // isolating this test to the identity invariant alone.
        UUID unrelatedBuilderId = UUID.randomUUID();
        ResidentCapacityAdmission corrupted = new ResidentCapacityAdmission(linked.get().dimension(), linked.get().townKey(),
                linked.get().region(), linked.get().beneficiaryId(), linked.get().requestedHomes(), linked.get().requestedWorkplaces(),
                linked.get().needsAnyWorkplace(), linked.get().siteOwner(), linked.get().planSignature(),
                ResidentCapacityAdmission.State.SELF_CLAIM_LINKED, linked.get().boundSiteOwner(), unrelatedBuilderId,
                linked.get().boundPlanSignature(), linked.get().anchorGenerationToken());
        storage.testOnlyReplaceCapacityAdmission(corrupted);

        TownConstructionService.buildAt(level, absoluteCampfire, storage);

        helper.assertTrue(storage.isAdmissionSiteQuarantined(level.dimension(), siteOwner),
                "a SELF_CLAIM_LINKED record whose identity invariant fails must be quarantined, never silently trusted");
        helper.assertTrue(resident.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "a quarantined site must make no construction effect at all - the resident must remain Builder, un-advanced");
        helper.succeed();
    }
}
