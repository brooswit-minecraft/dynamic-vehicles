package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MINECRAFT-454 [structural-cell adapter]: focused coverage for the new construction-operation
 * ledger adapter wired into {@code TownConstructionService#buildAt} for ordinary floor/wall/roof
 * cells placed with one of the four allowlisted structural materials. Every test here recreates
 * the actual failure/success condition through real world/ledger state (via the test-only fault
 * injection seam {@code TownStorage#testForceChunkAckFailureOnce}/{@code
 * testForceLedgerAckFailureOnce}, or by driving the real ledger/anchor APIs directly), never a
 * mocked stand-in.
 */
@GameTestHolder("dynamicvehicles_m454_structural_cell_adapter")
@PrefixGameTestTemplate(false)
public final class TownStructuralCellAdapterGameTests {
    private TownStructuralCellAdapterGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    /** [established convention, see TownResidentCapacityAdmissionGameTests#HANDOFF_CANDIDATE_ANCHORS]
     *  Four interior corners of the settlement_test template, each with footprint radius 2 - tried
     *  in this fixed order until one resolves to a NON-STOREHOUSE kind (STOREHOUSE's own, seed/
     *  position-dependent, larger footprint/height is not guaranteed to fit {@link
     *  #prepareFlatGround}'s bounded area, and is irrelevant to what this file actually tests). */
    private static final BlockPos[] CANDIDATE_ANCHORS = {
            new BlockPos(5, 2, 5), new BlockPos(18, 2, 5), new BlockPos(5, 2, 18), new BlockPos(18, 2, 18)
    };

    /** The first candidate anchor (relative to the test structure) that resolves to a non-
     *  STOREHOUSE kind for this exact world placement/seed - see {@link #CANDIDATE_ANCHORS}. */
    private static BlockPos pickNonStorehouseAnchor(GameTestHelper helper) {
        for (BlockPos candidate : CANDIDATE_ANCHORS) {
            if (resolveSite(helper, helper.absolutePos(candidate)).kind() != TownBuildingTemplates.Kind.STOREHOUSE) {
                return candidate;
            }
        }
        throw new IllegalStateException("test setup: every candidate anchor resolved to STOREHOUSE for this world placement");
    }

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

    private static Villager createClaimant(GameTestHelper helper, BlockPos absoluteCampfire) {
        ServerLevel level = helper.getLevel();
        Villager claimant = EntityType.VILLAGER.create(level, null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        level.addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                net.minecraft.core.GlobalPos.of(level.dimension(), absoluteCampfire));
        return claimant;
    }

    /** The exact kind/final-profession a site at {@code absoluteCampfire} resolves to, computed
     *  the same way {@code TownConstructionService#buildAt} itself does. */
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

    private static File tempLedgerFile(String prefix) throws IOException {
        return Files.createTempFile(prefix, ".dat").toFile();
    }

    // ------------------------------------------------------------------------------------------
    // (1) establishOrReadAnchorAssociation: fault injection -> fail closed -> pending retry -> same token.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test")
    public static void establishOrReadAnchorAssociationFailsClosedThenRetriesPendingAckToTheSameToken(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(ANCHOR);
        String siteOwner = TownStorage.siteOwner(campfire);
        UUID claimantId = UUID.randomUUID();
        String planSignature = "test-plan-sig-1";
        TownStorage storage = new TownStorage();
        File ledgerFile = tempLedgerFile("structural-adapter-anchor-fault");
        HolderLookup.Provider registries = level.registryAccess();
        ResourceKey<Level> dimension = level.dimension();

        storage.testForceChunkAckFailureOnce = true;
        Optional<UUID> firstAttempt = storage.establishOrReadAnchorAssociation(
                level, ledgerFile, registries, campfire, dimension, siteOwner, planSignature, claimantId);
        helper.assertTrue(firstAttempt.isEmpty(), "an injected chunk-ack failure must fail closed - found " + firstAttempt);
        helper.assertTrue(storage.anchorAssociationFor(siteOwner) != null,
                "the association map must retain the entry even though acknowledgment failed");
        helper.assertTrue(storage.isPendingAnchorAcknowledgment(siteOwner),
                "pendingAnchorAcknowledgment must still contain this siteOwner after a failed ack");
        helper.assertTrue(storage.pendingConstructionOperations(dimension).isEmpty(),
                "a failed anchor acknowledgment must never reserve any construction operation");
        helper.assertTrue(storage.storeFor(TownKeys.keyFor(dimension,
                        TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()))).balance(TownResourceCategory.STONE) == 0,
                "a failed anchor acknowledgment must never debit any escrow");
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "a failed anchor acknowledgment must never mutate any world block");
        UUID firstToken = storage.anchorAssociationFor(siteOwner).anchorGenerationToken();

        // Lift the injected failure - the retry must take the pending-retry branch (never mint a
        // fresh live marker again), both acknowledgments must now succeed, the pending flag must
        // clear, and the returned token must equal the FIRST call's token.
        Optional<UUID> secondAttempt = storage.establishOrReadAnchorAssociation(
                level, ledgerFile, registries, campfire, dimension, siteOwner, planSignature, claimantId);
        helper.assertTrue(secondAttempt.isPresent(), "the retry, with the fault lifted, must succeed - found " + secondAttempt);
        helper.assertTrue(secondAttempt.get().equals(firstToken),
                "the retry must return the SAME token as the first call - never a freshly minted one");
        helper.assertTrue(!storage.isPendingAnchorAcknowledgment(siteOwner),
                "a successful retry must clear the pending-acknowledgment mark");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (2) A reload with an association present but its acknowledgment incomplete must not assume readiness.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test")
    public static void reloadedStorageWithAnIncompleteAssociationNeverAssumesReadinessAndSafelyRetries(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(ANCHOR);
        String siteOwner = TownStorage.siteOwner(campfire);
        UUID claimantId = UUID.randomUUID();
        String planSignature = "test-plan-sig-2";
        TownStorage storage = new TownStorage();
        File ledgerFile = tempLedgerFile("structural-adapter-anchor-reload");
        HolderLookup.Provider registries = level.registryAccess();
        ResourceKey<Level> dimension = level.dimension();

        storage.testForceChunkAckFailureOnce = true;
        Optional<UUID> firstAttempt = storage.establishOrReadAnchorAssociation(
                level, ledgerFile, registries, campfire, dimension, siteOwner, planSignature, claimantId);
        helper.assertTrue(firstAttempt.isEmpty(), "test setup: the ack must have failed, leaving the association pending");

        CompoundTag saved = storage.save(new CompoundTag(), registries);
        TownStorage reloaded = TownStorage.load(saved, registries);

        helper.assertTrue(reloaded.anchorAssociationFor(siteOwner) != null,
                "the reloaded storage must still have the association recorded");
        helper.assertTrue(reloaded.isPendingAnchorAcknowledgment(siteOwner),
                "the reloaded storage must still treat this association as not-ready (pending)");

        Optional<UUID> retried = reloaded.establishOrReadAnchorAssociation(
                level, ledgerFile, registries, campfire, dimension, siteOwner, planSignature, claimantId);
        helper.assertTrue(retried.isPresent() && retried.get().equals(reloaded.anchorAssociationFor(siteOwner).anchorGenerationToken()),
                "the reload must safely retry - never assume readiness - and eventually succeed with the persisted token, found " + retried);
        helper.assertTrue(!reloaded.isPendingAnchorAcknowledgment(siteOwner),
                "a successful retry on the reloaded storage must clear the pending flag");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // Shared synthetic-ledger-record helpers for tests (3)-(5).
    // ------------------------------------------------------------------------------------------
    private static TownConstructionOperation.CellEffect syntheticEffect(BlockPos pos, String signature) {
        return new TownConstructionOperation.CellEffect(pos, Blocks.AIR.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.None(), new TownConstructionOperation.BlockEntitySnapshot.None(), signature);
    }

    private static UUID reserveWorldApplyAndSettle(GameTestHelper helper, TownStorage storage, ResourceKey<Level> dimension,
            String townKey, String siteOwner, UUID anchorToken, UUID builderId, TownConstructionOperation.CellEffect effect) {
        UUID operationId = UUID.randomUUID();
        Optional<TownConstructionOperation> reserved = storage.reserveConstructionOperation(operationId, dimension, townKey, siteOwner,
                anchorToken, builderId, Optional.empty(), List.of(effect), Map.of());
        helper.assertTrue(reserved.isPresent(), "test setup: the synthetic operation must reserve cleanly");
        storage.markWorldApplied(operationId);
        Optional<TownConstructionOperation> settled = storage.settleConstructionOperation(operationId);
        helper.assertTrue(settled.isPresent() && settled.get().phase() == TownConstructionOperation.Phase.SETTLED,
                "test setup: the synthetic operation must settle cleanly");
        return operationId;
    }

    /** Establishes a REAL, fully acknowledged anchor association for {@code siteOwner}/{@code
     *  claimantId}, so the backfill's own identity checks have something genuine to match against. */
    private static UUID establishRealAssociation(GameTestHelper helper, ServerLevel level, TownStorage storage,
            BlockPos campfire, String siteOwner, String planSignature, UUID claimantId) throws IOException {
        File ledgerFile = tempLedgerFile("structural-adapter-backfill-anchor");
        Optional<UUID> token = storage.establishOrReadAnchorAssociation(
                level, ledgerFile, level.registryAccess(), campfire, level.dimension(), siteOwner, planSignature, claimantId);
        helper.assertTrue(token.isPresent(), "test setup: the real anchor association must establish cleanly - found " + token);
        return token.get();
    }

    // ------------------------------------------------------------------------------------------
    // (3) [superseded by the latest round's claimant-independent pending-operation recovery pass -
    // Fix 2] A SETTLED operation whose own terminal acknowledgment was left pending is no longer
    // stuck: buildAt's OWN new recovery pass (storage.pendingTerminalAcknowledgments, run BEFORE
    // 2b's backfill loop) retries and durably resolves it first - a real, unmocked ledger
    // acknowledgment in this fixture genuinely succeeds, so by the time 2b's backfill loop checks
    // isPendingTerminalAcknowledgment for this exact operation, it is already false, and backfill
    // correctly proceeds in the SAME tick. This replaces the PRIOR round's assertion (which
    // documented the ack staying stuck forever, before Fix 2 existed) now that the recovery pass
    // actually closes that gap - the never-overwrite/never-re-reserve safety property this test
    // originally guarded is now proven the OTHER way: the SAME tick's backfill, once genuinely
    // unblocked, must still reach the correct, fully-authorized result.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void settledOperationWithPendingTerminalAckIsResolvedByRecoveryThenBackfilled(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        String townKey = TownKeys.keyFor(level.dimension(), TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()));
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, campfire, 1_000_000);

        UUID anchorToken = establishRealAssociation(helper, level, storage, campfire, siteOwner, "test-plan-sig-3", claimant.getUUID());

        BlockPos syntheticPos = helper.absolutePos(new BlockPos(16, 2, 16));
        String signature = "synthetic-sig-3";
        helper.setBlock(new BlockPos(16, 2, 16), Blocks.STONE_BRICKS); // matches effect.after() -> classifies AFTER
        TownConstructionOperation.CellEffect effect = syntheticEffect(syntheticPos, signature);
        UUID operationId = reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, anchorToken,
                claimant.getUUID(), effect);
        storage.markPendingTerminalAcknowledgment(operationId);
        helper.assertTrue(storage.isPendingTerminalAcknowledgment(operationId), "test setup: the ack must start out pending");
        helper.assertTrue(!storage.isCellPlaced(level.dimension(), syntheticPos, siteOwner, signature),
                "test setup: markCellPlaced must never have run yet - only backfill may create this record");

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(!storage.isPendingTerminalAcknowledgment(operationId),
                "the claimant-independent recovery pass must durably resolve this site's own pending terminal "
                        + "acknowledgment BEFORE backfill ever runs, in the SAME tick");
        helper.assertTrue(storage.isCellPlaced(level.dimension(), syntheticPos, siteOwner, signature),
                "once the pending acknowledgment is durably resolved, the SAME tick's own backfill must then correctly "
                        + "mark this fully-authorized, already-settled cell as placed");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (4) A mismatched builder/token, and a conflicting pre-existing placed record, must NOT be
    //     backfilled/overwritten - the pre-existing record stays exactly as it was.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void mismatchedAuthorizationAndConflictingPlacedRecordAreNeverBackfilledOrOverwritten(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        String townKey = TownKeys.keyFor(level.dimension(), TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()));
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, campfire, 1_000_000);

        UUID anchorToken = establishRealAssociation(helper, level, storage, campfire, siteOwner, "test-plan-sig-4", claimant.getUUID());

        // Sub-case A: the recorded operation's own builderId does not match the currently
        // authorized association's claimant - must never backfill.
        BlockPos mismatchedPos = helper.absolutePos(new BlockPos(16, 2, 16));
        String mismatchedSignature = "synthetic-sig-4a";
        helper.setBlock(new BlockPos(16, 2, 16), Blocks.STONE_BRICKS);
        TownConstructionOperation.CellEffect mismatchedEffect = syntheticEffect(mismatchedPos, mismatchedSignature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, anchorToken,
                UUID.randomUUID() /* a DIFFERENT builder than the authorized claimant */, mismatchedEffect);

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), mismatchedPos, siteOwner, mismatchedSignature),
                "an operation whose recorded builder no longer matches the authorized association must NOT be backfilled");

        // Sub-case B: a DIFFERENT plan's own paid record already occupies the position a SETTLED,
        // otherwise-valid operation targets - the pre-existing record must survive unchanged.
        BlockPos conflictPos = helper.absolutePos(new BlockPos(17, 2, 17));
        helper.setBlock(new BlockPos(17, 2, 17), Blocks.OAK_PLANKS);
        storage.markCellPlaced(level.dimension(), conflictPos, "other-owner", "other-signature", Blocks.OAK_PLANKS.defaultBlockState());
        String ourSignature = "synthetic-sig-4b";
        helper.setBlock(new BlockPos(17, 2, 17), Blocks.STONE_BRICKS); // our own effect's "after" state
        TownConstructionOperation.CellEffect conflictEffect = syntheticEffect(conflictPos, ourSignature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, anchorToken, claimant.getUUID(), conflictEffect);

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), conflictPos, siteOwner, ourSignature),
                "a position already holding a DIFFERENT plan's own paid record must never be backfilled for our own plan");
        helper.assertTrue(storage.isCellPlaced(level.dimension(), conflictPos, "other-owner", "other-signature"),
                "the pre-existing conflicting record must survive completely unchanged - never overwritten");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (5) A fully acknowledged, identity-matching, live-After record DOES backfill before preflight.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void fullyAcknowledgedMatchingRecordBackfillsCorrectlyEvenAfterAReload(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        String townKey = TownKeys.keyFor(level.dimension(), TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()));
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, campfire, 1_000_000);

        UUID anchorToken = establishRealAssociation(helper, level, storage, campfire, siteOwner, "test-plan-sig-5", claimant.getUUID());

        BlockPos pos = helper.absolutePos(new BlockPos(16, 2, 16));
        String signature = "synthetic-sig-5";
        helper.setBlock(new BlockPos(16, 2, 16), Blocks.STONE_BRICKS);
        TownConstructionOperation.CellEffect effect = syntheticEffect(pos, signature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, anchorToken, claimant.getUUID(), effect);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), pos, siteOwner, signature),
                "test setup: markCellPlaced must never have run yet - only backfill may create this record");

        // [the "markCellPlaced ran in-memory but a reload happened before any save" scenario]: the
        // settled operation itself is durable and survives a reload exactly like this; the
        // backfill restores placedCells afresh from it regardless of whether a prior in-memory
        // markCellPlaced call ever happened to run before that reload.
        CompoundTag saved = storage.save(new CompoundTag(), level.registryAccess());
        TownStorage reloaded = TownStorage.load(saved, level.registryAccess());
        helper.assertTrue(!reloaded.isCellPlaced(level.dimension(), pos, siteOwner, signature),
                "test setup: the reloaded storage must also lack the placedCells record before backfill runs");

        TownConstructionService.buildAt(level, campfire, reloaded);

        helper.assertTrue(reloaded.isCellPlaced(level.dimension(), pos, siteOwner, signature),
                "a fully acknowledged, identity-matching, live-After settled record must backfill correctly before preflight");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (6) Reactive-neighbor rejection (interior + perimeter) and unloaded-chunk deferral - direct,
    //     deterministic unit-level exercise of the pre-mutation neighbor-safety gate itself.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test")
    public static void reactiveNeighborRejectionForInteriorAndPerimeterCellsAndUnloadedChunkDeferral(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        Set<BlockPos> noExemptPositions = Set.of();

        // (a) Interior allowlisted cell - the neighbor BELOW it (outside the building footprint,
        // which starts at the floor) is checked. Safe baseline first (never merely an empty fixture).
        BlockPos interior = helper.absolutePos(new BlockPos(10, 2, 10));
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.DIRT);
        TownConstructionService.NeighborSafety safeInterior =
                TownConstructionService.checkNeighborSafetyForStructuralCell(level, interior, noExemptPositions);
        helper.assertTrue(safeInterior == TownConstructionService.NeighborSafety.SAFE,
                "an interior cell with only inert neighbors must be SAFE - found " + safeInterior);

        helper.setBlock(new BlockPos(10, 1, 10), Blocks.SAND);
        TownConstructionService.NeighborSafety rejectedInterior =
                TownConstructionService.checkNeighborSafetyForStructuralCell(level, interior, noExemptPositions);
        helper.assertTrue(rejectedInterior == TownConstructionService.NeighborSafety.REJECT,
                "an interior cell with a genuinely reactive (gravity-affected) neighbor below must be REJECTed, "
                        + "specifically because of that neighbor - found " + rejectedInterior);

        // (b) Floor/roof/boundary-perimeter cell - a HORIZONTAL neighbor just outside the
        // footprint. Safe baseline first, then a genuinely reactive neighbor.
        BlockPos perimeter = helper.absolutePos(new BlockPos(10, 3, 10));
        helper.setBlock(new BlockPos(11, 3, 10), Blocks.DIRT);
        TownConstructionService.NeighborSafety safePerimeter =
                TownConstructionService.checkNeighborSafetyForStructuralCell(level, perimeter, noExemptPositions);
        helper.assertTrue(safePerimeter == TownConstructionService.NeighborSafety.SAFE,
                "a perimeter cell with only inert neighbors must be SAFE - found " + safePerimeter);

        helper.setBlock(new BlockPos(11, 3, 10), Blocks.SAND);
        TownConstructionService.NeighborSafety rejectedPerimeter =
                TownConstructionService.checkNeighborSafetyForStructuralCell(level, perimeter, noExemptPositions);
        helper.assertTrue(rejectedPerimeter == TownConstructionService.NeighborSafety.REJECT,
                "a perimeter cell with a genuinely reactive neighbor just outside the footprint must be REJECTed - found "
                        + rejectedPerimeter);

        // Unloaded neighbor chunk - defer (no mutation), never a crash or a false rejection.
        BlockPos farAway = new BlockPos(1_000_000, 64, 1_000_000); // never loaded by this test
        TownConstructionService.NeighborSafety deferred =
                TownConstructionService.checkNeighborSafetyForStructuralCell(level, farAway, noExemptPositions);
        helper.assertTrue(deferred == TownConstructionService.NeighborSafety.DEFER,
                "an unloaded neighbor chunk must DEFER, never crash or falsely reject - found " + deferred);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (7) Positive case: an ordinary allowlisted cell with only safe/inert neighbors successfully
    //     reserves, mutates, settles, and results in exactly one markCellPlaced with no double-debit.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void ordinaryAllowlistedCellReservesMutatesSettlesWithNoDoubleDebit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        int funded = 1_000_000;
        TownResourceStore store = fundStore(helper, storage, campfire, funded);

        ResolvedSite site = resolveSite(helper, campfire);
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(site.kind());
        int balanceBeforeFirstTick = store.balance(costCategory);

        TownConstructionService.buildAt(level, campfire, storage);

        List<TownConstructionOperation> settledAfterFirstTick = storage.settledOperationsForSite(level.dimension(), siteOwner);
        helper.assertTrue(!settledAfterFirstTick.isEmpty(),
                "test setup: the first tick, generously funded with only inert surrounding terrain, must settle at least one structural cell");
        for (TownConstructionOperation operation : settledAfterFirstTick) {
            helper.assertTrue(operation.effects().size() == 1,
                    "each structural-cell-adapter operation must record exactly one cell effect");
            TownConstructionOperation.CellEffect effect = operation.effects().get(0);
            helper.assertTrue(storage.isCellPlaced(level.dimension(), effect.pos(), siteOwner, effect.signature()),
                    "every settled structural-cell operation must result in exactly one markCellPlaced record");
        }
        int expectedDebit = settledAfterFirstTick.size() * TownConstants.CONSTRUCTION_COST_PER_BLOCK;
        int balanceAfterFirstTick = store.balance(costCategory);
        helper.assertTrue(balanceBeforeFirstTick - balanceAfterFirstTick == expectedDebit,
                "the total debit after the first tick must equal exactly (settled cell count * cost per block) - no double charge, no missed charge; "
                        + "before=" + balanceBeforeFirstTick + " after=" + balanceAfterFirstTick + " settledCount=" + settledAfterFirstTick.size());

        // A SECOND tick must never re-debit or re-settle any of the SAME already-placed cells.
        TownConstructionService.buildAt(level, campfire, storage);
        for (TownConstructionOperation operation : settledAfterFirstTick) {
            TownConstructionOperation.CellEffect effect = operation.effects().get(0);
            helper.assertTrue(storage.isCellPlaced(level.dimension(), effect.pos(), siteOwner, effect.signature()),
                    "a previously settled cell must remain recorded as placed after a later tick");
            Optional<TownConstructionOperation> stillSettled = storage.constructionOperation(operation.operationId());
            helper.assertTrue(stillSettled.isPresent() && stillSettled.get().phase() == TownConstructionOperation.Phase.SETTLED,
                    "a previously settled operation must remain SETTLED, never reopened or double-processed");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (8) Ordinary end-to-end regression: a normal small build with no reactive neighbors at all
    //     still completes normally - the new guards never silently stall ordinary construction.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void ordinaryConstructionWithNoReactiveNeighborsStillCompletesNormally(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, campfire, 1_000_000);
        // No beneficiary admission is ever requested here - this is a pure self-build. Production
        // (commitClaimantLifecycle, non-beneficiary branch) assigns the claimant itself the site's
        // own resolved final profession when it has one (COMMERCIAL/INDUSTRIAL), or releases it to
        // NONE when it does not (RESIDENTIAL) - both are "released normally," never a stall.
        Optional<VillagerProfession> finalProfession = resolveSite(helper, campfire).finalProfession();

        for (int i = 0; i < 500 && helper.getBlockState(relativeAnchor).is(Blocks.CAMPFIRE); i++) {
            TownConstructionService.buildAt(level, campfire, storage);
        }

        helper.assertTrue(!helper.getBlockState(relativeAnchor).is(Blocks.CAMPFIRE),
                "an ordinary build with no reactive neighbors anywhere must still complete (the campfire must close) within a generous tick budget - "
                        + "the new structural-cell adapter guards must never silently stall it");
        VillagerProfession expectedProfession = finalProfession.orElse(VillagerProfession.NONE);
        helper.assertTrue(claimant.getVillagerData().getProfession() == expectedProfession,
                "the claimant must still be released/assigned normally once the site completes - expected "
                        + expectedProfession + " but found " + claimant.getVillagerData().getProfession());
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // [coordinator review 51468] Post-acknowledgment revalidation: shared setup for all three
    // "something changed during the acknowledgment window" cases below. Drives the REAL buildAt
    // adapter, never only the safety helper: a first real buildAt tick establishes a genuine
    // anchor association and settles a few ordinary cells; a NEXT, still-unplaced allowlisted
    // placement is identified from the real template; an operation for that EXACT cell is reserved
    // directly via the real, deterministic structuralCellOperationId - simulating "this cell was
    // already reserved and acknowledged on a prior tick" - before the adversarial change is
    // injected and a real buildAt call is made to exercise the revalidation boundary itself.
    // ------------------------------------------------------------------------------------------
    // [coordinator review 51468] Deliberately uses the ABSOLUTE position throughout, never
    // helper.relativePos - this codebase's own DiffuserAssistGameTests documents in detail that
    // relativePos is NOT a reliable inverse of absolutePos under structure rotation. Every world
    // read/write below uses the real ServerLevel (level.getBlockState/level.setBlock) directly on
    // this absolute position instead.
    private record PendingTarget(TownBuildingTemplates.Placement placement, String signature, UUID operationId,
            TownConstructionOperation.CellEffect effect, BlockPos absolutePos, TownResourceCategory costCategory) {}

    private static String planSignatureFor(GameTestHelper helper, BlockPos absoluteCampfire, ResolvedSite site) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        return site.kind().name() + ":" + plan.storehouseVariant() + ":" + site.finalProfession()
                .map(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION::getKey).map(Object::toString).orElse("none");
    }

    /** Identifies (never reserves - no side effect) the exact next not-yet-placed allowlisted,
     *  non-closing placement the real adapter will itself attempt next, from the real template -
     *  so a test can arm {@link TownConstructionService#testInjectDuringAcknowledgmentAt} for
     *  EXACTLY that position before letting a real buildAt tick reach it fresh. */
    private static PendingTarget findNextPendingStructuralCell(GameTestHelper helper, TownStorage storage,
            BlockPos absoluteCampfire, String siteOwner) {
        ServerLevel level = helper.getLevel();
        ResolvedSite site = resolveSite(helper, absoluteCampfire);
        String planSignature = planSignatureFor(helper, absoluteCampfire, site);
        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        List<TownBuildingTemplates.Placement> template = TownBuildingTemplates.generate(site.kind(),
                absoluteCampfire.getX(), floorY, absoluteCampfire.getZ(),
                TownPlanner.generate(TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ()), level.getSeed())
                        .storehouseVariant(),
                site.finalProfession().orElse(null));
        // Must match buildAt's OWN exact per-tick traversal order (bottomUpOrder), never the raw
        // template order - otherwise this test's "next" cell can differ from the one buildAt
        // actually reaches first/within its own CONSTRUCTION_BLOCKS_PER_TICK budget this tick,
        // and the hook below would then be armed for a position buildAt never even visits.
        java.util.Map<String, TownBuildingTemplates.Placement> byPosition = new java.util.HashMap<>();
        for (TownBuildingTemplates.Placement placement : template) {
            byPosition.put(placement.x() + "," + placement.y() + "," + placement.z(), placement);
        }
        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(
                template.stream().map(TownBuildingTemplates.Placement::toPlan).toList(),
                absoluteCampfire.getX(), absoluteCampfire.getY(), absoluteCampfire.getZ());
        for (ConstructionSitePlan.PlannedBlock block : ordered) {
            TownBuildingTemplates.Placement placement = byPosition.get(block.x() + "," + block.y() + "," + block.z());
            BlockPos pos = new BlockPos(placement.x(), placement.y(), placement.z());
            if (pos.equals(absoluteCampfire) || !TownConstructionService.isAllowlistedStructuralBlock(placement.block())) {
                continue;
            }
            String signature = TownConstructionService.cellSignature(planSignature, placement);
            if (storage.isCellPlaced(level.dimension(), pos, siteOwner, signature)) {
                continue; // already settled by the earlier real buildAt tick
            }
            TownStorage.AnchorAssociation authorized = storage.anchorAssociationFor(siteOwner);
            helper.assertTrue(authorized != null, "test setup: a real anchor association must already be established");
            UUID anchorGenerationToken = authorized.anchorGenerationToken();
            UUID operationId = TownConstructionService.structuralCellOperationId(siteOwner, planSignature, anchorGenerationToken, pos, signature);
            TownConstructionOperation.CellEffect effect = new TownConstructionOperation.CellEffect(pos, level.getBlockState(pos),
                    placement.state(), new TownConstructionOperation.BlockEntitySnapshot.None(),
                    new TownConstructionOperation.BlockEntitySnapshot.None(), signature);
            return new PendingTarget(placement, signature, operationId, effect, pos, placement.cost());
        }
        throw new IllegalStateException("test setup: no eligible not-yet-placed allowlisted cell remained to target");
    }

    private static void assertOtherCellsStillProgressNormally(GameTestHelper helper, TownStorage storage,
            ServerLevel level, BlockPos absoluteCampfire, String siteOwner, int settledBeforeAdversarialTick) {
        List<TownConstructionOperation> settledAfter = storage.settledOperationsForSite(level.dimension(), siteOwner);
        helper.assertTrue(settledAfter.size() > settledBeforeAdversarialTick,
                "a still-valid cell elsewhere at this same site must still place and settle normally in the same tick - "
                        + "the revalidation guard must be narrowly scoped to the invalidated cell only, never stall the whole site; "
                        + "settled before=" + settledBeforeAdversarialTick + " after=" + settledAfter.size());
    }


    // ------------------------------------------------------------------------------------------
    // (9) [coordinator review 51468, case 1] The target cell's own recorded Before state changes
    // EXACTLY during the acknowledgment window - injected via testInjectDuringAcknowledgmentAt
    // immediately after this tick's own reservation/acknowledgment succeeds, before the mutation -
    // never overwritten; left fenced (RESERVED), not cancelled, since the live world is now
    // neither the recorded Before nor After (ambiguous -> quarantined by cancelWithVerifiedRollback's
    // own refusal), and no duplicate debit.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void targetBeforeStateChangeDuringAcknowledgmentWindowIsNeverOverwrittenAndStaysFenced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, campfire, 1_000_000);

        TownConstructionService.buildAt(level, campfire, storage); // establishes the real anchor association + settles a few cells
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(resolveSite(helper, campfire).kind());
        // Drain to EXACTLY one block's cost, leaving nothing for any OTHER cell this same tick -
        // isolates the balance check below to this one target's own reservation/refund, regardless
        // of how many other cells this site's own template happens to still have left.
        store.tryDebit(costCategory, store.balance(costCategory) - TownConstants.CONSTRUCTION_COST_PER_BLOCK);

        PendingTarget target = findNextPendingStructuralCell(helper, storage, campfire, siteOwner);
        BlockPos absoluteTarget = target.absolutePos();
        TownConstructionService.testInjectDuringAcknowledgmentAt = absoluteTarget;
        TownConstructionService.testInjectDuringAcknowledgmentAction =
                () -> level.setBlock(absoluteTarget, Blocks.DIRT.defaultBlockState(), 3); // neither the recorded Before (air) nor After

        TownConstructionService.buildAt(level, campfire, storage); // the SAME tick reserves this cell, then the hook fires, then revalidation must catch it

        Optional<TownConstructionOperation> afterInjection = storage.constructionOperation(target.operationId());
        helper.assertTrue(afterInjection.isPresent() && afterInjection.get().phase() == TownConstructionOperation.Phase.RESERVED,
                "an operation whose target position changed to neither its recorded Before nor After must stay fenced (RESERVED), "
                        + "never settled and never (wrongly) cancelled - found " + afterInjection);
        helper.assertTrue(level.getBlockState(absoluteTarget).is(Blocks.DIRT),
                "the adapter must never overwrite the changed target position with the planned material");
        helper.assertTrue(!storage.isCellPlaced(level.dimension(), absoluteTarget, siteOwner, target.signature()),
                "an invalidated operation must never be recorded as placed progress");
        int balanceAfter = store.balance(costCategory);
        helper.assertTrue(balanceAfter == 0,
                "exactly the one real reservation's escrow (drained to exactly its own cost beforehand) is spent, never refunded "
                        + "(ambiguous classification correctly refuses cancellation) - found " + balanceAfter);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (10) [coordinator review 51468, case 2] A vertical neighbor becomes reactive EXACTLY during
    // the acknowledgment window - injected the same way as case 1. The target's OWN position is
    // untouched, so classification genuinely still shows Before, and cancelWithVerifiedRollback's
    // own verified rollback legitimately refunds it exactly once.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void reactiveVerticalNeighborDuringAcknowledgmentWindowIsNeverOverwrittenAndCleanlyCancelled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, campfire, 1_000_000);

        TownConstructionService.buildAt(level, campfire, storage);
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(resolveSite(helper, campfire).kind());
        store.tryDebit(costCategory, store.balance(costCategory) - TownConstants.CONSTRUCTION_COST_PER_BLOCK);

        PendingTarget target = findNextPendingStructuralCell(helper, storage, campfire, siteOwner);
        BlockPos absoluteTarget = target.absolutePos();
        BlockPos absoluteBelowTarget = absoluteTarget.below();
        TownConstructionService.testInjectDuringAcknowledgmentAt = absoluteTarget;
        TownConstructionService.testInjectDuringAcknowledgmentAction =
                () -> level.setBlock(absoluteBelowTarget, Blocks.SAND.defaultBlockState(), 3); // genuinely reactive, outside this site's own planned footprint

        TownConstructionService.buildAt(level, campfire, storage);

        Optional<TownConstructionOperation> afterInjection = storage.constructionOperation(target.operationId());
        helper.assertTrue(afterInjection.isPresent() && afterInjection.get().phase() == TownConstructionOperation.Phase.CANCELLED,
                "an operation whose own target position never changed, but a vertical neighbor became reactive during the "
                        + "acknowledgment window, must be CLEANLY cancelled via a genuinely verified rollback - found " + afterInjection);
        helper.assertTrue(!level.getBlockState(absoluteTarget).is(target.placement().block()),
                "the target position must never have been mutated to the planned material");
        helper.assertTrue(!storage.isCellPlaced(level.dimension(), absoluteTarget, siteOwner, target.signature()),
                "a cancelled operation must never be recorded as placed progress");
        helper.assertTrue(level.getBlockState(absoluteBelowTarget).is(Blocks.SAND),
                "the reactive neighbor itself must never be touched by the adapter either");
        // cancelConstructionOperation's own code (TownStorage) has exactly one path to CANCELLED,
        // and that path unconditionally credits reservedCost first - so phase==CANCELLED above is
        // already conclusive proof the refund happened. The aggregate store balance is NOT
        // asserted further: the refund can legitimately free up headroom a LATER cell in this same
        // tick then consumes (e.g. a non-ledger bed/workstation placement), which is correct
        // behavior, not a double-debit of THIS operation.
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (11) [coordinator review 51468, case 3] The physical anchor's own generation changes EXACTLY
    // during the acknowledgment window - injected the same way as cases 1-2 (destroy+recreate the
    // live campfire, yielding a brand-new, empty-persistent-data BlockEntity). The target's own
    // position is untouched, so a genuinely verified rollback cleanly cancels and refunds exactly
    // once - this operation's OWN revalidation catches the stale generation directly, distinct
    // from (and not to be confused with) the SEPARATE, already-documented limitation that a
    // recreated anchor also prevents any NEW reservation at this site afterward (51452/51457).
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void anchorAuthorityChangeDuringAcknowledgmentWindowIsNeverOverwrittenAndCleanlyCancelled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        // Deliberately FULL funding (not drained) - this is what makes the "whole tick halts"
        // assertion below meaningful: if authority loss only `continue`d instead of `break`ing,
        // several OTHER cells would legitimately have enough escrow to also settle this same tick.
        TownResourceStore store = fundStore(helper, storage, campfire, 1_000_000);

        TownConstructionService.buildAt(level, campfire, storage);
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(resolveSite(helper, campfire).kind());
        int settledBeforeHookTick = storage.settledOperationsForSite(level.dimension(), siteOwner).size();
        int balanceBeforeHookTick = store.balance(costCategory);

        PendingTarget target = findNextPendingStructuralCell(helper, storage, campfire, siteOwner);
        BlockPos absoluteTarget = target.absolutePos();
        TownConstructionService.testInjectDuringAcknowledgmentAt = absoluteTarget;
        TownConstructionService.testInjectDuringAcknowledgmentAction = () -> {
            // Destroy and recreate the live anchor - a brand-new CampfireBlockEntity has empty
            // persistent data, so the operation's own recorded anchorGenerationToken can never
            // match the live marker again (the exact RECREATED-anchor mechanism this whole ticket
            // already relies on elsewhere).
            helper.setBlock(relativeAnchor, Blocks.AIR);
            helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        };

        TownConstructionService.buildAt(level, campfire, storage);

        Optional<TownConstructionOperation> afterInjection = storage.constructionOperation(target.operationId());
        helper.assertTrue(afterInjection.isPresent() && afterInjection.get().phase() == TownConstructionOperation.Phase.CANCELLED,
                "an operation whose physical anchor generation no longer matches the live marker (changed during the acknowledgment "
                        + "window) must be cleanly cancelled via a genuinely verified rollback - found " + afterInjection);
        helper.assertTrue(!level.getBlockState(absoluteTarget).is(target.placement().block()),
                "the target position must never have been mutated to the planned material once the anchor's own authority changed");
        helper.assertTrue(!storage.isCellPlaced(level.dimension(), absoluteTarget, siteOwner, target.signature()),
                "a cancelled operation must never be recorded as placed progress");
        // [coordinator review 51477] Authority loss must stop the ENTIRE remaining tick for this
        // site, not just this one cell - confirmed here with full funding available: nothing else
        // settles and the balance is back to exactly its pre-reservation level (cancellation's own
        // refund, nothing more spent and nothing further gained).
        int settledAfterHookTick = storage.settledOperationsForSite(level.dimension(), siteOwner).size();
        helper.assertTrue(settledAfterHookTick == settledBeforeHookTick,
                "authority loss must halt the WHOLE remaining tick (ledger path included) - no other cell may settle in the same "
                        + "tick this operation was cancelled in - settledBefore=" + settledBeforeHookTick + " settledAfter=" + settledAfterHookTick);
        int balanceAfterHookTick = store.balance(costCategory);
        helper.assertTrue(balanceAfterHookTick == balanceBeforeHookTick,
                "authority loss must halt the WHOLE remaining tick's spending too (legacy non-ledger path included) - "
                        + "balanceBefore=" + balanceBeforeHookTick + " balanceAfter=" + balanceAfterHookTick);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (12) [superseded by the latest round's claimant-independent pending-operation recovery pass -
    // Fix 2] The PRIOR round's "DISCOVERED GAP" (a pre-existing operation whose recorded builderId
    // no longer matches any live claimant stayed RESERVED, with its escrow permanently retained,
    // forever - because reserveConstructionOperation's own sameContentAs guard rejects every future
    // attempt at this exact id before Reserved is ever produced, so the per-cell loop's own retry
    // never even reached a cancellation path) is now CLOSED: buildAt's new recovery pass scans
    // every non-terminal operation for this siteOwner BEFORE the per-cell loop even starts: this
    // orphaned, still-RESERVED, genuinely-never-mutated (Before) operation is cancelled and its
    // escrow refunded immediately. The per-cell loop's own EXISTING chain-forward-on-CANCELLED logic
    // (pre-dating this delta) then lets the REAL claimant's own retry advance to a fresh chained id
    // and legitimately re-reserve/settle the SAME target cell, in the SAME tick - exactly the
    // recovery this fix is for; a stale claimant can no longer orphan an operation (and its escrow)
    // forever merely by dying/losing its claim before this exact cell was reached.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void claimantAuthorityMismatchWithUnchangedPhysicalTokenIsReconciledByRecoveryThenRetried(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        Villager realClaimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, campfire, 1_000_000);

        TownConstructionService.buildAt(level, campfire, storage); // establishes the real anchor association + settles a few cells
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(resolveSite(helper, campfire).kind());
        int settledBefore = storage.settledOperationsForSite(level.dimension(), siteOwner).size();
        int balanceBefore = store.balance(costCategory);

        PendingTarget target = findNextPendingStructuralCell(helper, storage, campfire, siteOwner);
        UUID realAnchorToken = storage.anchorAssociationFor(siteOwner).anchorGenerationToken();
        UUID wrongBuilderId = UUID.randomUUID();
        helper.assertTrue(!wrongBuilderId.equals(realClaimant.getUUID()), "test setup: the wrong builder id must genuinely differ");
        RegionCoord region = TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ());
        String townKey = TownKeys.keyFor(level.dimension(), region);
        Optional<TownConstructionOperation> preReserved = storage.reserveConstructionOperation(target.operationId(), level.dimension(),
                townKey, siteOwner, realAnchorToken, wrongBuilderId, Optional.empty(), List.of(target.effect()), Map.of(target.costCategory(), TownConstants.CONSTRUCTION_COST_PER_BLOCK));
        helper.assertTrue(preReserved.isPresent(), "test setup: the wrong-builder reservation must succeed - found " + preReserved);
        helper.assertTrue(store.balance(costCategory) == balanceBefore - TownConstants.CONSTRUCTION_COST_PER_BLOCK,
                "test setup: the wrong-builder reservation's own escrow debit must have actually landed");

        TownConstructionService.buildAt(level, campfire, storage); // the real claimant's own attempt at this exact id

        // [Fix 2] The recovery pass (run BEFORE the per-cell loop) finds this orphaned RESERVED
        // operation still genuinely verifies as Before (nothing was ever mutated for it) and
        // cancels it with a verified rollback - refunding its escrow exactly once - rather than
        // leaving it stuck forever under the stale wrong builderId.
        Optional<TownConstructionOperation> afterRecovery = storage.constructionOperation(target.operationId());
        helper.assertTrue(afterRecovery.isPresent() && afterRecovery.get().phase() == TownConstructionOperation.Phase.CANCELLED
                        && afterRecovery.get().builderId().equals(wrongBuilderId),
                "the claimant-independent recovery pass must cancel (never silently adopt under a new claimant) the orphaned "
                        + "wrong-builder operation, refunding its escrow, BEFORE the per-cell loop runs - found " + afterRecovery);
        helper.assertTrue(TownConstructionService.liveAnchorGenerationTokenMatches(level, campfire, realAnchorToken),
                "the physical anchor token itself must remain completely valid and unchanged throughout this scenario");

        // The SAME tick's per-cell loop then reaches this exact target cell again: finding it
        // CANCELLED (not RESERVED), its own pre-existing chain-forward logic advances to a fresh
        // chained id and lets the REAL claimant legitimately re-reserve and settle it - a stale
        // claimant's own abandoned operation no longer permanently blocks this cell.
        int settledAfter = storage.settledOperationsForSite(level.dimension(), siteOwner).size();
        helper.assertTrue(settledAfter > settledBefore,
                "once reconciled, the real claimant's own retry at this exact cell must be able to legitimately settle in the "
                        + "SAME tick - settledBefore=" + settledBefore + " settledAfter=" + settledAfter);
        helper.assertTrue(storage.isCellPlaced(level.dimension(), target.absolutePos(), siteOwner, target.signature()),
                "the target cell must end up genuinely placed under the REAL claimant, never left stuck under the stale wrong builder");
        helper.assertTrue(level.getBlockState(target.absolutePos()).is(target.placement().block()),
                "the target position must have been mutated to the planned material by the real claimant's own legitimate retry");
        // The orphaned reservation's escrow is refunded exactly once by the recovery pass, and the
        // per-cell loop then continues normally for the REST of this tick's own budget (up to
        // CONSTRUCTION_BLOCKS_PER_TICK cells, including the real retry at the target itself) -
        // each legitimately debiting exactly once. Net spend must equal exactly (cells actually
        // settled this call) * cost - never more (no double charge) and never less (no stranded,
        // unrefunded escrow left over from the orphaned operation).
        int cellsSettledThisCall = settledAfter - settledBefore;
        helper.assertTrue(cellsSettledThisCall > 0 && cellsSettledThisCall <= TownConstants.CONSTRUCTION_BLOCKS_PER_TICK,
                "test invariant: this call can settle at most CONSTRUCTION_BLOCKS_PER_TICK cells - found " + cellsSettledThisCall);
        int balanceAfter = store.balance(costCategory);
        helper.assertTrue(balanceAfter == balanceBefore - cellsSettledThisCall * TownConstants.CONSTRUCTION_COST_PER_BLOCK,
                "the orphaned reservation's escrow is refunded exactly once, and every cell actually settled this call "
                        + "(including the real claimant's own fresh retry at the target) debits exactly once each - no double "
                        + "charge and no stranded escrow - balanceBefore=" + balanceBefore + " balanceAfter=" + balanceAfter
                        + " cellsSettledThisCall=" + cellsSettledThisCall);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // [latest round, Fix 3] Dual-authority backfill: an admission-BOUND site's settled structural-
    // cell operations must backfill using the admission's OWN builderId()/anchorGenerationToken()
    // as the authority source, since establishOrReadAnchorAssociation is never called for a bound
    // site (anchorAssociationFor(siteOwner) stays null forever for it). Shared setup below binds a
    // REAL admission through the actual production path (requestResidentCapacity + a real buildAt
    // tick's own resolveCapacityAdmissionForSite), never a direct storage.bindCapacityAdmissionToSite
    // call - the established convention this package already uses (see
    // TownResidentCapacityAdmissionGameTests). A home-only demand is used throughout so the exact
    // resolved kind/final-profession at the chosen anchor never matters - every non-STOREHOUSE kind
    // offers a bed.
    // ------------------------------------------------------------------------------------------

    /** A same-region, non-blocking companion position for the beneficiary villager - reuses {@link
     *  #CANDIDATE_ANCHORS} (already spaced far enough apart that none can collide with another's
     *  radius-2 building footprint) as companion candidates too, excluding whichever one is already
     *  the real anchor. */
    private static BlockPos pickSameRegionCompanion(GameTestHelper helper, BlockPos anchorRelative, RegionCoord anchorRegion) {
        for (BlockPos candidate : CANDIDATE_ANCHORS) {
            if (candidate.equals(anchorRelative)) {
                continue;
            }
            BlockPos candidateAbsolute = helper.absolutePos(candidate);
            if (TownRegion.fromBlockCoords(candidateAbsolute.getX(), candidateAbsolute.getZ()).equals(anchorRegion)) {
                return candidate;
            }
        }
        throw new IllegalStateException("test setup: no same-region companion candidate found among CANDIDATE_ANCHORS");
    }

    /** Establishes a REAL, {@code BUILDING} admission bound to {@code siteOwner} via the actual
     *  production path - a distinct, non-Builder beneficiary villager requests HOME-ONLY capacity
     *  (satisfied by every non-STOREHOUSE kind, so the anchor's own resolved kind never matters
     *  here), and one real {@code buildAt} tick binds it exactly the way production itself does. */
    private static ResidentCapacityAdmission establishBoundAdmissionSite(GameTestHelper helper, TownStorage storage,
            BlockPos relativeAnchor, BlockPos campfire, String siteOwner, Villager claimant) {
        ServerLevel level = helper.getLevel();
        RegionCoord region = TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ());
        storage.markTownFounded(TownKeys.keyFor(level.dimension(), region));
        BlockPos companionRelative = pickSameRegionCompanion(helper, relativeAnchor, region);
        BlockPos absoluteCompanion = helper.absolutePos(companionRelative);
        Villager beneficiary = EntityType.VILLAGER.create(level, null, absoluteCompanion, MobSpawnType.EVENT, false, false);
        helper.assertTrue(beneficiary != null && level.addFreshEntity(beneficiary),
                "test setup: the beneficiary villager must be constructible and accepted by the level");

        Optional<ResidentCapacityAdmission> awaiting = TownConstructionService.requestResidentCapacity(
                level, region, beneficiary.getUUID(), new TownResidentDemand(1, Map.of()), storage);
        helper.assertTrue(awaiting.isPresent() && awaiting.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                "test setup: the beneficiary's home-only admission must be created and unbound before construction begins - found "
                        + awaiting);

        TownConstructionService.buildAt(level, campfire, storage);

        Optional<ResidentCapacityAdmission> bound = storage.capacityAdmissionBoundToSite(level.dimension(), siteOwner);
        helper.assertTrue(bound.isPresent() && bound.get().state() == ResidentCapacityAdmission.State.BUILDING
                        && bound.get().builderId().equals(claimant.getUUID()),
                "test setup: the real first buildAt tick must bind this admission to the site under the real claimant - found " + bound);
        helper.assertTrue(storage.anchorAssociationFor(siteOwner) == null,
                "test setup: a bound-admission site must never populate anchorAssociations at all - "
                        + "establishOrReadAnchorAssociation is only ever called on the non-bound path");
        return bound.get();
    }

    // ------------------------------------------------------------------------------------------
    // (13) [Fix 3, positive case] A bound-admission site's settled structural-cell operation whose
    // own markCellPlaced never ran correctly backfills before preflight, using the admission's own
    // builderId()/anchorGenerationToken() - never anchorAssociationFor, which is null here.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void boundAdmissionSiteBackfillsASettledOperationBeforePreflight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, campfire, 1_000_000);

        ResidentCapacityAdmission bound = establishBoundAdmissionSite(helper, storage, relativeAnchor, campfire, siteOwner, claimant);
        String townKey = TownKeys.keyFor(level.dimension(), TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()));

        BlockPos pos = helper.absolutePos(new BlockPos(16, 2, 16));
        String signature = "bound-backfill-sig-1";
        helper.setBlock(new BlockPos(16, 2, 16), Blocks.STONE_BRICKS);
        TownConstructionOperation.CellEffect effect = syntheticEffect(pos, signature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, bound.anchorGenerationToken(),
                bound.builderId(), effect);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), pos, siteOwner, signature),
                "test setup: markCellPlaced must never have run yet - only backfill may create this record");

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(storage.isCellPlaced(level.dimension(), pos, siteOwner, signature),
                "a bound-admission site's settled structural-cell operation whose own markCellPlaced never ran must backfill "
                        + "correctly before preflight, deriving authority from the admission's own builderId()/"
                        + "anchorGenerationToken() rather than the (null, for a bound site) anchorAssociationFor");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (14) [Fix 3, negative cases] A bound-admission site's settled operation under a DIFFERENT
    // builder, or a stale/mismatched anchor token, is correctly NOT backfilled; and (the realistic
    // "wrong plan" mechanism) a position already holding a DIFFERENT plan's own paid record is never
    // overwritten even by an otherwise-correctly-authorized operation.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void boundAdmissionSiteMismatchedAuthorizationAndConflictingPlacedRecordAreNeverBackfilled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, campfire, 1_000_000);

        ResidentCapacityAdmission bound = establishBoundAdmissionSite(helper, storage, relativeAnchor, campfire, siteOwner, claimant);
        String townKey = TownKeys.keyFor(level.dimension(), TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ()));

        // Sub-case A: a DIFFERENT builder than the admission's own builderId() - never backfilled.
        BlockPos wrongBuilderPos = helper.absolutePos(new BlockPos(16, 2, 16));
        String wrongBuilderSignature = "bound-backfill-sig-2a";
        helper.setBlock(new BlockPos(16, 2, 16), Blocks.STONE_BRICKS);
        TownConstructionOperation.CellEffect wrongBuilderEffect = syntheticEffect(wrongBuilderPos, wrongBuilderSignature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, bound.anchorGenerationToken(),
                UUID.randomUUID() /* a DIFFERENT builder than the admission's own builderId */, wrongBuilderEffect);

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), wrongBuilderPos, siteOwner, wrongBuilderSignature),
                "an operation whose recorded builder does not match the admission's own builderId() must NOT be backfilled");

        // Sub-case B: a stale/mismatched anchorGenerationToken (correct builder) - never backfilled.
        BlockPos wrongTokenPos = helper.absolutePos(new BlockPos(17, 2, 16));
        String wrongTokenSignature = "bound-backfill-sig-2b";
        helper.setBlock(new BlockPos(17, 2, 16), Blocks.STONE_BRICKS);
        TownConstructionOperation.CellEffect wrongTokenEffect = syntheticEffect(wrongTokenPos, wrongTokenSignature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner,
                UUID.randomUUID() /* a stale/mismatched token - never the admission's own */, bound.builderId(), wrongTokenEffect);

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), wrongTokenPos, siteOwner, wrongTokenSignature),
                "an operation whose recorded anchorGenerationToken no longer matches the admission's own token must NOT be backfilled");

        // Sub-case C ["wrong plan", via the realistic mechanism]: a DIFFERENT plan's own paid record
        // already occupies the position a SETTLED, otherwise-correctly-authorized operation targets -
        // the pre-existing record must survive unchanged, never silently overwritten for our own plan.
        BlockPos conflictPos = helper.absolutePos(new BlockPos(16, 2, 17));
        helper.setBlock(new BlockPos(16, 2, 17), Blocks.OAK_PLANKS);
        storage.markCellPlaced(level.dimension(), conflictPos, "other-owner", "other-signature", Blocks.OAK_PLANKS.defaultBlockState());
        String ourSignature = "bound-backfill-sig-2c";
        helper.setBlock(new BlockPos(16, 2, 17), Blocks.STONE_BRICKS); // our own effect's "after" state
        TownConstructionOperation.CellEffect conflictEffect = syntheticEffect(conflictPos, ourSignature);
        reserveWorldApplyAndSettle(helper, storage, level.dimension(), townKey, siteOwner, bound.anchorGenerationToken(),
                bound.builderId(), conflictEffect);

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(!storage.isCellPlaced(level.dimension(), conflictPos, siteOwner, ourSignature),
                "a position already holding a DIFFERENT plan's own paid record must never be backfilled for our own plan, "
                        + "even under an otherwise-correctly-authorized bound-admission operation");
        helper.assertTrue(storage.isCellPlaced(level.dimension(), conflictPos, "other-owner", "other-signature"),
                "the pre-existing conflicting record must survive completely unchanged - never overwritten");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // (15) [latest round, Fix 4] Post-ack authority must be a genuine LIVE re-read, not a reuse of
    // in-call locals: the live CLAIM is lost EXACTLY during the acknowledgment window (the original
    // claimant's own JOB_SITE memory is erased by the injected hook) while the PHYSICAL anchor token
    // is never touched - distinct from (and never to be confused with) case 3/test 11's
    // anchor-recreation mechanism, and distinct from test 12's pre-existing sameContentAs rejection
    // path: this must be caught by the NEW fresh findClaimant() re-read itself, driven through the
    // real buildAt, not by pre-seeding a wrong-builder operation.
    // ------------------------------------------------------------------------------------------
    @GameTest(template = "settlement_test", skyAccess = true)
    public static void liveClaimLossDuringAcknowledgmentWindowIsCaughtByTheLiveRereadAuthorityCheck(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos relativeAnchor = pickNonStorehouseAnchor(helper);
        helper.setBlock(relativeAnchor, Blocks.CAMPFIRE);
        BlockPos campfire = helper.absolutePos(relativeAnchor);
        String siteOwner = TownStorage.siteOwner(campfire);
        Villager claimant = createClaimant(helper, campfire);
        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, campfire, 1_000_000);

        TownConstructionService.buildAt(level, campfire, storage); // establishes the real anchor association + settles a few cells
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(resolveSite(helper, campfire).kind());
        int settledBeforeHookTick = storage.settledOperationsForSite(level.dimension(), siteOwner).size();
        int balanceBeforeHookTick = store.balance(costCategory);

        PendingTarget target = findNextPendingStructuralCell(helper, storage, campfire, siteOwner);
        BlockPos absoluteTarget = target.absolutePos();
        UUID realAnchorToken = storage.anchorAssociationFor(siteOwner).anchorGenerationToken();
        TownConstructionService.testInjectDuringAcknowledgmentAt = absoluteTarget;
        TownConstructionService.testInjectDuringAcknowledgmentAction =
                () -> claimant.getBrain().eraseMemory(MemoryModuleType.JOB_SITE); // the live claim itself disappears

        TownConstructionService.buildAt(level, campfire, storage);

        helper.assertTrue(TownCampfireClaims.findClaimant(level, campfire).isEmpty(),
                "test setup: erasing the claimant's own JOB_SITE memory must genuinely make the live claim absent");
        helper.assertTrue(TownConstructionService.liveAnchorGenerationTokenMatches(level, campfire, realAnchorToken),
                "the physical anchor token itself must remain completely valid and unchanged throughout this scenario - "
                        + "only the live CLAIM changed, proving this is caught by the NEW live-reread authority check");

        Optional<TownConstructionOperation> afterInjection = storage.constructionOperation(target.operationId());
        helper.assertTrue(afterInjection.isPresent() && afterInjection.get().phase() == TownConstructionOperation.Phase.CANCELLED,
                "an operation whose live claimant disappeared EXACTLY during the acknowledgment window (JOB_SITE erased, "
                        + "physical anchor untouched) must be cleanly cancelled via the NEW live-reread authority check - found "
                        + afterInjection);
        helper.assertTrue(!level.getBlockState(absoluteTarget).is(target.placement().block()),
                "the target position must never have been mutated to the planned material once the live claim was lost");
        helper.assertTrue(!storage.isCellPlaced(level.dimension(), absoluteTarget, siteOwner, target.signature()),
                "a cancelled operation must never be recorded as placed progress");
        int settledAfterHookTick = storage.settledOperationsForSite(level.dimension(), siteOwner).size();
        helper.assertTrue(settledAfterHookTick == settledBeforeHookTick,
                "authority loss must halt the WHOLE remaining tick - no other cell may settle in the same tick this "
                        + "operation was cancelled in - settledBefore=" + settledBeforeHookTick + " settledAfter=" + settledAfterHookTick);
        int balanceAfterHookTick = store.balance(costCategory);
        helper.assertTrue(balanceAfterHookTick == balanceBeforeHookTick,
                "authority loss must halt the WHOLE remaining tick's spending too - balanceBefore=" + balanceBeforeHookTick
                        + " balanceAfter=" + balanceAfterHookTick);
        helper.succeed();
    }
}
