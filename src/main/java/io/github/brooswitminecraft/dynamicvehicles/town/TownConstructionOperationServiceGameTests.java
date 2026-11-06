package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MINECRAFT-454 [coordinator design 51327, stage 2 orchestration - review 51348/51351 repeated-P1
 * correction] Focused, real (never mocked) coverage of {@link TownConstructionOperationService}'s
 * phase-INDEPENDENT effect classification, terminal-acknowledgment retry reachability, exact
 * block-entity rollback verification, and beneficiary-owned (not merely occupied) handoff proof.
 * Every test here recreates the ACTUAL failure condition through real world/ledger state, never
 * asserting an implementation's own phase label as if it were proof.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownConstructionOperationServiceGameTests {
    private TownConstructionOperationServiceGameTests() {}

    private static TownConstructionOperation.CellEffect effect(BlockPos pos) {
        return new TownConstructionOperation.CellEffect(pos, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.None(), new TownConstructionOperation.BlockEntitySnapshot.None(),
                "sig:" + pos);
    }

    private static List<TownConstructionOperation.CellEffect> effects(BlockPos... positions) {
        List<TownConstructionOperation.CellEffect> result = new ArrayList<>();
        for (BlockPos pos : positions) {
            result.add(effect(pos));
        }
        return result;
    }

    /** "No effects before failed intent acknowledgment": a reservation whose ledger ack fails must
     *  report {@code AcknowledgmentPending}, never {@code Reserved}. */
    @GameTest(template = "settlement_test")
    public static void failedLedgerAcknowledgmentNeverReportsReservedEvenThoughEscrowWasDebited(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(40, 40);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage(new TownStorageDurableWriter(Runnable::run,
                (content, path) -> { throw new IOException("forced failure for test"); }));
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        File scratchFile = new File("ignored-stage2-ack-failure-test.dat");
        UUID operationId = UUID.randomUUID();

        TownConstructionOperationService.ReservationOutcome outcome = TownConstructionOperationService.reserveAndAwaitAcknowledgment(
                storage, scratchFile, helper.getLevel().registryAccess(), operationId, dimension, townKey, "site:ack-fail",
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), effects(new BlockPos(40, 0, 40)),
                Map.of(TownResourceCategory.STONE, 10));

        helper.assertTrue(outcome instanceof TownConstructionOperationService.ReservationOutcome.AcknowledgmentPending,
                "a failed ledger acknowledgment must never report Reserved - found " + outcome);
        helper.assertTrue(storage.constructionOperation(operationId).filter(op -> op.phase() == TownConstructionOperation.Phase.RESERVED).isPresent(),
                "the operation must remain RESERVED in memory, still fencing its own site, pending retry");
        helper.succeed();
    }

    /** [coordinator review 51348/51351, THE MANDATORY REGRESSION] "A fully changed world with
     *  RESERVED ledger and no witness" must NEVER be treated as unapplied: {@code classifyEffects}
     *  must report {@code After}, and cancellation must be REFUSED - escrow must never be refunded
     *  for an operation whose world effects actually, verifiably happened, no matter what the
     *  ledger's own phase label says. */
    @GameTest(template = "settlement_test")
    public static void reservedOperationWithActuallyChangedWorldAndNoWitnessIsNeverTrustedAsUnappliedOrRefunded(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(50, 50);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        File scratchFile = java.nio.file.Files.createTempFile("stage2-crash-counterexample", ".dat").toFile();
        HolderLookup.Provider registries = level.registryAccess();
        UUID operationId = UUID.randomUUID();
        BlockPos cellRelative = new BlockPos(3, 2, 3);
        BlockPos cell = helper.absolutePos(cellRelative);

        TownConstructionOperationService.ReservationOutcome reservation = TownConstructionOperationService.reserveAndAwaitAcknowledgment(
                storage, scratchFile, registries, operationId, dimension, townKey, "site:crash-counterexample", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effects(cell), Map.of(TownResourceCategory.STONE, 10));
        helper.assertTrue(reservation instanceof TownConstructionOperationService.ReservationOutcome.Reserved, "test setup: reservation must succeed");

        // Simulate the exact counterexample: the world effect is applied, but the process dies
        // BEFORE checkpointAndSettle is ever called - no markWorldApplied, no witness, no checkpoint.
        helper.setBlock(cellRelative, Blocks.STONE);
        helper.assertTrue(storage.constructionOperation(operationId).filter(op -> op.phase() == TownConstructionOperation.Phase.RESERVED).isPresent(),
                "test setup: the ledger must still show RESERVED - checkpointAndSettle was never called");

        TownConstructionOperationService.EffectClassification classification = TownConstructionOperationService.classifyEffects(
                level, storage.constructionOperation(operationId).orElseThrow());
        helper.assertTrue(classification instanceof TownConstructionOperationService.EffectClassification.After,
                "the phase-independent classifier must see the ACTUAL changed world and report After, regardless of the RESERVED ledger label - found "
                        + classification);

        TownConstructionOperationService.CancellationOutcome cancelAttempt = TownConstructionOperationService.cancelWithVerifiedRollback(
                storage, level, scratchFile, registries, operationId);
        helper.assertTrue(cancelAttempt instanceof TownConstructionOperationService.CancellationOutcome.EffectsNotVerifiedUnapplied,
                "cancellation must be REFUSED - the world was not actually unapplied - found " + cancelAttempt);
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 90,
                "escrow must NEVER be refunded for an operation whose world effects genuinely, verifiably happened");
        helper.succeed();
    }

    /** [coordinator review 51348/51351] An effect in an unloaded chunk classifies as {@code
     *  Deferred} regardless of the ledger's phase - neither settlement nor cancellation may proceed. */
    @GameTest(template = "settlement_test")
    public static void unloadedChunkClassifiesAsDeferredAndBlocksBothSettlementAndCancellation(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(51, 51);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        File scratchFile = java.nio.file.Files.createTempFile("stage2-deferred", ".dat").toFile();
        HolderLookup.Provider registries = level.registryAccess();
        UUID operationId = UUID.randomUUID();
        // Far outside the loaded GameTest structure's own chunk - never loaded by this test.
        BlockPos unloadedCell = new BlockPos(1_000_000, 64, 1_000_000);

        TownConstructionOperation operation = storage.reserveConstructionOperation(operationId, dimension, townKey, "site:deferred",
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), effects(unloadedCell),
                Map.of(TownResourceCategory.STONE, 10)).orElseThrow();
        helper.assertTrue(!level.hasChunkAt(unloadedCell), "test setup: the target chunk must genuinely be unloaded");

        helper.assertTrue(TownConstructionOperationService.classifyEffects(level, operation) instanceof TownConstructionOperationService.EffectClassification.Deferred,
                "an unloaded chunk must classify as Deferred");
        helper.assertTrue(TownConstructionOperationService.checkpointAndSettle(storage, level, scratchFile, registries, operationId)
                        instanceof TownConstructionOperationService.SettlementOutcome.EffectsNotFullyApplied,
                "settlement must be refused while Deferred");
        helper.assertTrue(TownConstructionOperationService.cancelWithVerifiedRollback(storage, level, scratchFile, registries, operationId)
                        instanceof TownConstructionOperationService.CancellationOutcome.EffectsNotVerifiedUnapplied,
                "cancellation must also be refused while Deferred");
        helper.succeed();
    }

    /** [coordinator review 51348/51351] "Use exact supported payload comparison," not mere BE
     *  existence: a block entity that exists but carries DIFFERENT data than the recorded snapshot
     *  must classify as {@code Quarantined}, never silently accepted as a match. */
    @GameTest(template = "settlement_test")
    public static void aBlockEntityWithMismatchedDataClassifiesAsQuarantinedNeverAccepted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(52, 52);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID operationId = UUID.randomUUID();
        BlockPos chestRelative = new BlockPos(6, 2, 6);
        BlockPos chest = helper.absolutePos(chestRelative);

        // Capture the REAL, empty chest's own saveWithoutMetadata as the "recorded" snapshot -
        // never a fabricated tag - then mutate the LIVE chest's actual contents afterward.
        helper.setBlock(chestRelative, Blocks.CHEST);
        BlockEntity liveChest = level.getBlockEntity(chest);
        helper.assertTrue(liveChest instanceof net.minecraft.world.Container,
                "test setup: the chest must have a real container block entity");
        CompoundTag recordedEmptyNbt = liveChest.saveWithoutMetadata(level.registryAccess());
        TownConstructionOperation.CellEffect chestEffect = new TownConstructionOperation.CellEffect(chest,
                Blocks.CHEST.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.Captured(recordedEmptyNbt),
                new TownConstructionOperation.BlockEntitySnapshot.Captured(recordedEmptyNbt), "sig:chest");
        TownConstructionOperation operation = storage.reserveConstructionOperation(operationId, dimension, townKey, "site:be-mismatch",
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), List.of(chestEffect),
                Map.of(TownResourceCategory.STONE, 10)).orElseThrow();

        ((net.minecraft.world.Container) liveChest).setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));

        TownConstructionOperationService.EffectClassification classification = TownConstructionOperationService.classifyEffects(level, operation);
        helper.assertTrue(classification instanceof TownConstructionOperationService.EffectClassification.Quarantined,
                "a live block entity whose data does not match the recorded snapshot must classify as Quarantined - found " + classification);
        helper.succeed();
    }

    /** A real end-to-end reserve -> apply -> checkpointAndSettle, gated by the real {@code After}
     *  classification, not a phase shortcut. */
    @GameTest(template = "settlement_test")
    public static void checkpointAndSettleSucceedsOnlyOnceTheLiveWorldGenuinelyClassifiesAsAfter(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(53, 53);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        File scratchFile = java.nio.file.Files.createTempFile("stage2-settle-ack", ".dat").toFile();
        HolderLookup.Provider registries = level.registryAccess();
        UUID operationId = UUID.randomUUID();
        BlockPos cellRelative = new BlockPos(2, 2, 2);
        BlockPos cell = helper.absolutePos(cellRelative);

        TownConstructionOperationService.ReservationOutcome reservation = TownConstructionOperationService.reserveAndAwaitAcknowledgment(
                storage, scratchFile, registries, operationId, dimension, townKey, "site:settle", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(cell), Map.of(TownResourceCategory.STONE, 10));
        helper.assertTrue(reservation instanceof TownConstructionOperationService.ReservationOutcome.Reserved, "test setup: reservation must succeed");

        TownConstructionOperationService.SettlementOutcome tooEarly = TownConstructionOperationService.checkpointAndSettle(
                storage, level, scratchFile, registries, operationId);
        helper.assertTrue(tooEarly instanceof TownConstructionOperationService.SettlementOutcome.EffectsNotFullyApplied,
                "settlement before the world actually shows the effect must be refused - found " + tooEarly);

        helper.setBlock(cellRelative, Blocks.STONE);
        TownConstructionOperationService.SettlementOutcome settlement = TownConstructionOperationService.checkpointAndSettle(
                storage, level, scratchFile, registries, operationId);
        helper.assertTrue(settlement instanceof TownConstructionOperationService.SettlementOutcome.Settled,
                "once the world genuinely shows the effect, settlement must succeed - found " + settlement);
        helper.assertTrue(storage.constructionOperation(operationId).filter(op -> op.phase() == TownConstructionOperation.Phase.SETTLED).isPresent(),
                "the ledger must show SETTLED after a successful checkpointAndSettle");
        helper.succeed();
    }

    /** A genuinely unapplied (still showing {@code before}) operation cancels and refunds, once
     *  verified through the real classifier and a fresh checkpoint. */
    @GameTest(template = "settlement_test")
    public static void cancellationSucceedsOnlyOnceTheLiveWorldGenuinelyClassifiesAsBefore(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(54, 54);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        File scratchFile = java.nio.file.Files.createTempFile("stage2-cancel-ack", ".dat").toFile();
        HolderLookup.Provider registries = level.registryAccess();
        UUID operationId = UUID.randomUUID();

        storage.reserveConstructionOperation(operationId, dimension, townKey, "site:cancel-before", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(new BlockPos(54, 0, 54)), Map.of(TownResourceCategory.STONE, 10));
        TownConstructionOperationService.CancellationOutcome cancelled = TownConstructionOperationService.cancelWithVerifiedRollback(
                storage, level, scratchFile, registries, operationId);
        helper.assertTrue(cancelled instanceof TownConstructionOperationService.CancellationOutcome.Cancelled,
                "a genuinely unapplied operation must cancel - found " + cancelled);
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 100, "its escrow must be fully refunded");
        helper.succeed();
    }

    /** [coordinator review 51348/51351] "Terminal acknowledgments that fail must remain
     *  discoverable for retry... retry only the pending ledger acknowledgment." Also covers an
     *  intervening UNRELATED balance change surviving the retry untouched. */
    @GameTest(template = "settlement_test")
    public static void failedTerminalAcknowledgmentIsDiscoverableAndRetrySucceedsWithoutReapplyingOrDoubleCharging(GameTestHelper helper)
            throws IOException {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(55, 55);
        RegionCoord otherRegion = new RegionCoord(56, 56);
        String townKey = TownKeys.keyFor(dimension, region);
        String otherTownKey = TownKeys.keyFor(dimension, otherRegion);
        AtomicInteger writeAttempt = new AtomicInteger();
        TownStorage storage = new TownStorage(new TownStorageDurableWriter(Runnable::run, (content, path) -> {
            if (writeAttempt.getAndIncrement() == 0) {
                throw new IOException("forced first-attempt failure");
            }
        }));
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        storage.storeFor(otherTownKey).credit(TownResourceCategory.WOOD, 50);
        File scratchFile = new File("ignored-terminal-retry.dat");
        HolderLookup.Provider registries = level.registryAccess();
        UUID operationId = UUID.randomUUID();
        BlockPos cellRelative = new BlockPos(7, 2, 7);
        BlockPos cell = helper.absolutePos(cellRelative);

        storage.reserveConstructionOperation(operationId, dimension, townKey, "site:terminal-retry", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(cell), Map.of(TownResourceCategory.STONE, 10));
        helper.setBlock(cellRelative, Blocks.STONE);

        TownConstructionOperationService.SettlementOutcome firstAttempt = TownConstructionOperationService.checkpointAndSettle(
                storage, level, scratchFile, registries, operationId);
        helper.assertTrue(firstAttempt instanceof TownConstructionOperationService.SettlementOutcome.LedgerAcknowledgmentPending,
                "test setup: the FIRST ledger acknowledgment attempt must fail - found " + firstAttempt);
        helper.assertTrue(storage.isPendingTerminalAcknowledgment(operationId), "the failed terminal transition must be discoverable as pending");
        helper.assertTrue(storage.pendingTerminalAcknowledgments(dimension).stream().anyMatch(op -> op.operationId().equals(operationId)),
                "the pending-terminal-acknowledgment enumeration must include this operation");

        // Intervening UNRELATED balance change - a different town/category entirely.
        storage.storeFor(otherTownKey).credit(TownResourceCategory.WOOD, 25);

        TownConstructionOperationService.TerminalAcknowledgmentOutcome retry = TownConstructionOperationService.retryTerminalAcknowledgment(
                storage, scratchFile, registries, operationId);
        helper.assertTrue(retry instanceof TownConstructionOperationService.TerminalAcknowledgmentOutcome.Acknowledged,
                "the retry must succeed once the writer stops failing - found " + retry);
        helper.assertTrue(!storage.isPendingTerminalAcknowledgment(operationId), "a successful retry must clear the pending-acknowledgment mark");
        helper.assertTrue(storage.constructionOperation(operationId).filter(op -> op.phase() == TownConstructionOperation.Phase.SETTLED).isPresent(),
                "the operation must still show SETTLED - the retry must never reapply effects or change phase itself");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 90,
                "the retry must never debit or refund again - balance must be exactly what settlement itself already produced");
        helper.assertTrue(storage.storeFor(otherTownKey).balance(TownResourceCategory.WOOD) == 75,
                "the intervening unrelated balance change must survive the retry completely untouched");
        helper.succeed();
    }

    /** [coordinator review 51348/51351] "Beneficiary-specific owned POI proof": occupancy
     *  (freeTickets==0) alone is insufficient - a FOREIGN villager's occupied ticket at the right
     *  position must NOT satisfy handoff readiness for a DIFFERENT beneficiary. Reuses the exact
     *  existing ownership-safe {@code hasValidBeneficiaryHome} check. */
    @GameTest(template = "settlement_test")
    public static void reconcileHandoffRejectsAForeignVillagersOccupiedTicketForADifferentBeneficiary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        BlockPos homeRelative = new BlockPos(1, 2, 1);
        BlockPos home = helper.absolutePos(homeRelative);
        RegionCoord region = TownRegion.fromBlockCoords(home.getX(), home.getZ());
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();

        // A FOREIGN villager genuinely, legitimately occupies this exact HOME ticket.
        Villager foreignOccupant = EntityType.VILLAGER.create(level, null, home, MobSpawnType.EVENT, false, false);
        level.addFreshEntity(foreignOccupant);
        net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.entity.ai.village.poi.PoiType> poiRegistry =
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE);
        level.getPoiManager().add(home, poiRegistry.getOrThrow(PoiTypes.HOME));
        level.getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, candidate) -> candidate.equals(home), home, 1);
        foreignOccupant.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(dimension, home));

        // The OPERATION's own beneficiary is a DIFFERENT villager, whose admission also claims the
        // SAME home memory position by coincidence/bug - this must never be read as satisfied.
        UUID realBeneficiaryId = UUID.randomUUID();
        Villager realBeneficiary = EntityType.VILLAGER.create(level, null, helper.absolutePos(new BlockPos(8, 2, 8)),
                MobSpawnType.EVENT, false, false);
        realBeneficiary.setUUID(realBeneficiaryId);
        level.addFreshEntity(realBeneficiary);
        realBeneficiary.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(dimension, home));

        ResidentCapacityAdmission awaiting = new ResidentCapacityAdmission(dimension, townKey, region, realBeneficiaryId, 1, Map.of(),
                false, "", "demand-sig", ResidentCapacityAdmission.State.AWAITING_CLAIM, null, null, null, null);
        storage.createOrReuseCapacityAdmission(awaiting);
        UUID builderId = UUID.randomUUID();
        UUID anchorToken = UUID.randomUUID();
        String siteOwner = "site:foreign-ticket";
        ResidentCapacityAdmission bound = storage.bindCapacityAdmissionToSite(awaiting, siteOwner, builderId, "concrete-plan",
                () -> Optional.of(anchorToken)).orElseThrow();
        ResidentCapacityAdmission completed = storage.completeCapacityAdmission(bound).orElseThrow();
        TownConstructionOperation operation = new TownConstructionOperation(UUID.randomUUID(), dimension, townKey, siteOwner, anchorToken,
                builderId, Optional.of(realBeneficiaryId), effects(new BlockPos(60, 0, 60)), Map.of(TownResourceCategory.STONE, 0),
                TownConstructionOperation.Phase.RESERVED);

        TownConstructionOperationService.HandoffOutcome outcome = TownConstructionOperationService.reconcileHandoff(storage, level, region, operation);
        helper.assertTrue(outcome instanceof TownConstructionOperationService.HandoffOutcome.HandoffPending,
                "a HOME memory pointing at a position whose ticket is held by a DIFFERENT (foreign) villager must never report HandoffReady - found "
                        + outcome);
        helper.succeed();
    }

    /** Full positive path: COMPLETED admission, admission/generation/Builder contract matching the
     *  operation, live beneficiary, and a genuinely own-occupied, town-built HOME fixture together
     *  report {@code HandoffReady}; a DIFFERENT beneficiary's binding at the same site is left
     *  completely untouched; no binding at all reports {@code HandoffPending}. */
    @GameTest(template = "settlement_test")
    public static void reconcileHandoffReportsReadyOnlyWithFullOwnershipProofAndLeavesForeignBindingsUntouched(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        BlockPos homeRelative = new BlockPos(1, 2, 1);
        BlockPos home = helper.absolutePos(homeRelative);
        RegionCoord region = TownRegion.fromBlockCoords(home.getX(), home.getZ());
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();

        UUID ownBeneficiaryId = UUID.randomUUID();
        Villager beneficiary = EntityType.VILLAGER.create(level, null, home, MobSpawnType.EVENT, false, false);
        beneficiary.setUUID(ownBeneficiaryId);
        level.addFreshEntity(beneficiary);

        ResidentCapacityAdmission ownAwaiting = new ResidentCapacityAdmission(dimension, townKey, region, ownBeneficiaryId, 1, Map.of(),
                false, "", "demand-sig", ResidentCapacityAdmission.State.AWAITING_CLAIM, null, null, null, null);
        storage.createOrReuseCapacityAdmission(ownAwaiting);
        String ownSiteOwner = "site:handoff-own";
        UUID builderId = UUID.randomUUID();
        UUID anchorToken = UUID.randomUUID();
        ResidentCapacityAdmission ownBound = storage.bindCapacityAdmissionToSite(ownAwaiting, ownSiteOwner, builderId, "concrete-plan",
                () -> Optional.of(anchorToken)).orElseThrow();
        TownConstructionOperation ownOperation = new TownConstructionOperation(UUID.randomUUID(), dimension, townKey, ownSiteOwner,
                anchorToken, builderId, Optional.of(ownBeneficiaryId), effects(new BlockPos(45, 0, 45)),
                Map.of(TownResourceCategory.STONE, 0), TownConstructionOperation.Phase.RESERVED);

        TownConstructionOperationService.HandoffOutcome beforeCompletion = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, ownOperation);
        helper.assertTrue(beforeCompletion instanceof TownConstructionOperationService.HandoffOutcome.HandoffPending,
                "a bound-but-not-yet-COMPLETED admission must never report HandoffReady - found " + beforeCompletion);

        ResidentCapacityAdmission completed = storage.completeCapacityAdmission(ownBound).orElseThrow();
        TownConstructionOperationService.HandoffOutcome completedWithNoLivePoi = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, ownOperation);
        helper.assertTrue(completedWithNoLivePoi instanceof TownConstructionOperationService.HandoffOutcome.HandoffPending,
                "a COMPLETED admission with NO live occupied/paid HOME fixture must still report HandoffPending - found " + completedWithNoLivePoi);

        // Establish a real, town-built, occupied HOME fixture for the beneficiary - but WITHOUT
        // any tracked reservation-owner record yet.
        helper.setBlock(homeRelative, Blocks.WHITE_BED);
        storage.markCellPlaced(dimension, home, ownSiteOwner, "home-fixture-signature", level.getBlockState(home));
        net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.entity.ai.village.poi.PoiType> poiRegistry =
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE);
        level.getPoiManager().add(home, poiRegistry.getOrThrow(PoiTypes.HOME));
        level.getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, candidate) -> candidate.equals(home), home, 1);
        beneficiary.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(dimension, home));

        // [coordinator review 51352] "An occupied ticket with beneficiary memory but NO owner
        // record must remain Pending" - the mandatory regression for this finding.
        TownConstructionOperationService.HandoffOutcome occupiedButUntracked = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, ownOperation);
        helper.assertTrue(occupiedButUntracked instanceof TownConstructionOperationService.HandoffOutcome.HandoffPending,
                "an occupied ticket with correct beneficiary memory but NO tracked isReservedBySelf record must remain HandoffPending, never Ready - found "
                        + occupiedButUntracked);

        // Now record the positive, tracked ownership proof - ONLY then may this report Ready.
        storage.setReservationOwner(dimension, home, ownBeneficiaryId);
        TownConstructionOperationService.HandoffOutcome readyOutcome = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, ownOperation);
        helper.assertTrue(readyOutcome instanceof TownConstructionOperationService.HandoffOutcome.HandoffReady,
                "COMPLETED + matching admission/generation/Builder contract + live beneficiary + a genuinely occupied, town-built, POSITIVELY OWNED HOME fixture must report HandoffReady - found "
                        + readyOutcome);

        UUID foreignBeneficiaryId = UUID.randomUUID();
        ResidentCapacityAdmission foreignAwaiting = new ResidentCapacityAdmission(dimension, townKey, region, foreignBeneficiaryId, 1,
                Map.of(), false, "", "demand-sig", ResidentCapacityAdmission.State.AWAITING_CLAIM, null, null, null, null);
        storage.createOrReuseCapacityAdmission(foreignAwaiting);
        String foreignSiteOwner = "site:handoff-foreign";
        ResidentCapacityAdmission foreignBound = storage.bindCapacityAdmissionToSite(foreignAwaiting, foreignSiteOwner, UUID.randomUUID(),
                "concrete-plan", () -> Optional.of(UUID.randomUUID())).orElseThrow();
        TownConstructionOperation ambiguousOperation = new TownConstructionOperation(UUID.randomUUID(), dimension, townKey,
                foreignSiteOwner, UUID.randomUUID(), UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                effects(new BlockPos(46, 0, 46)), Map.of(TownResourceCategory.STONE, 0), TownConstructionOperation.Phase.RESERVED);
        TownConstructionOperationService.HandoffOutcome foreignOutcome = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, ambiguousOperation);
        helper.assertTrue(foreignOutcome instanceof TownConstructionOperationService.HandoffOutcome.HandoffAlreadyAssignedElsewhere,
                "a site already bound to a DIFFERENT beneficiary must report HandoffAlreadyAssignedElsewhere - found " + foreignOutcome);
        helper.assertTrue(storage.capacityAdmissionBoundToSite(dimension, foreignSiteOwner).filter(foreignBound::equals).isPresent(),
                "the foreign admission must be left completely untouched - never substituted or overwritten");

        TownConstructionOperation unboundOperation = new TownConstructionOperation(UUID.randomUUID(), dimension, townKey,
                "site:handoff-unbound", UUID.randomUUID(), UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                effects(new BlockPos(47, 0, 47)), Map.of(TownResourceCategory.STONE, 0), TownConstructionOperation.Phase.RESERVED);
        TownConstructionOperationService.HandoffOutcome unboundOutcome = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, unboundOperation);
        helper.assertTrue(unboundOutcome instanceof TownConstructionOperationService.HandoffOutcome.HandoffPending,
                "a site with no binding at all must report HandoffPending, never fabricate a new one - found " + unboundOutcome);
        helper.succeed();
    }

    /** [coordinator review 51348/51351] The admission/generation/Builder contract check: a bound,
     *  COMPLETED admission whose {@code builderId} does not match this operation's own recorded
     *  {@code builderId} must never report HandoffReady, even with an otherwise-valid live fixture. */
    @GameTest(template = "settlement_test")
    public static void reconcileHandoffRejectsAMismatchedBuilderOrGenerationContract(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        BlockPos homeRelative = new BlockPos(1, 2, 1);
        BlockPos home = helper.absolutePos(homeRelative);
        RegionCoord region = TownRegion.fromBlockCoords(home.getX(), home.getZ());
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();

        UUID beneficiaryId = UUID.randomUUID();
        Villager beneficiary = EntityType.VILLAGER.create(level, null, home, MobSpawnType.EVENT, false, false);
        beneficiary.setUUID(beneficiaryId);
        level.addFreshEntity(beneficiary);
        helper.setBlock(homeRelative, Blocks.WHITE_BED);
        String siteOwner = "site:mismatched-contract";
        storage.markCellPlaced(dimension, home, siteOwner, "home-fixture-signature", level.getBlockState(home));
        net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.entity.ai.village.poi.PoiType> poiRegistry =
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.POINT_OF_INTEREST_TYPE);
        level.getPoiManager().add(home, poiRegistry.getOrThrow(PoiTypes.HOME));
        level.getPoiManager().take(type -> type.is(PoiTypes.HOME), (type, candidate) -> candidate.equals(home), home, 1);
        beneficiary.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(dimension, home));
        storage.setReservationOwner(dimension, home, beneficiaryId);

        ResidentCapacityAdmission awaiting = new ResidentCapacityAdmission(dimension, townKey, region, beneficiaryId, 1, Map.of(),
                false, "", "demand-sig", ResidentCapacityAdmission.State.AWAITING_CLAIM, null, null, null, null);
        storage.createOrReuseCapacityAdmission(awaiting);
        UUID realBuilderId = UUID.randomUUID();
        UUID anchorToken = UUID.randomUUID();
        ResidentCapacityAdmission bound = storage.bindCapacityAdmissionToSite(awaiting, siteOwner, realBuilderId, "concrete-plan",
                () -> Optional.of(anchorToken)).orElseThrow();
        storage.completeCapacityAdmission(bound);

        UUID differentBuilderId = UUID.randomUUID();
        TownConstructionOperation operationWithWrongBuilder = new TownConstructionOperation(UUID.randomUUID(), dimension, townKey,
                siteOwner, anchorToken, differentBuilderId, Optional.of(beneficiaryId), effects(new BlockPos(61, 0, 61)),
                Map.of(TownResourceCategory.STONE, 0), TownConstructionOperation.Phase.RESERVED);
        TownConstructionOperationService.HandoffOutcome outcome = TownConstructionOperationService.reconcileHandoff(
                storage, level, region, operationWithWrongBuilder);
        helper.assertTrue(outcome instanceof TownConstructionOperationService.HandoffOutcome.HandoffPending,
                "a mismatched builderId between the operation and the bound admission must never report HandoffReady - found " + outcome);
        helper.succeed();
    }
}
