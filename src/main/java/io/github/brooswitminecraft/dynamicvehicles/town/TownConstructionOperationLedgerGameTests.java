package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MINECRAFT-454 [coordinator design 51327, stage 2] Focused, real (never mocked) coverage of
 * {@code TownStorage}'s own construction operation ledger: durable reservation/escrow, world-
 * applied/settlement phase transitions, cancellation/rollback, site fencing, and anchor-independent
 * recovery enumeration. Deliberately bounded to the LEDGER itself - never wires into {@code
 * TownConstructionService#buildAt}'s own per-cell loop, and makes no claim about entity/POI
 * durability (see {@code TownConstructionOperation}'s own class doc).
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownConstructionOperationLedgerGameTests {
    private TownConstructionOperationLedgerGameTests() {}

    private static TownConstructionOperation.CellEffect effect(BlockPos pos, String signature) {
        return new TownConstructionOperation.CellEffect(pos, Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.None(), new TownConstructionOperation.BlockEntitySnapshot.None(),
                signature);
    }

    private static List<TownConstructionOperation.CellEffect> effects(BlockPos... positions) {
        List<TownConstructionOperation.CellEffect> result = new ArrayList<>();
        for (BlockPos pos : positions) {
            result.add(effect(pos, "sig:" + pos));
        }
        return result;
    }

    private static Optional<TownConstructionOperation> reserve(TownStorage storage, ResourceKey<Level> dimension, String townKey,
            String siteOwner, UUID operationId, Map<TownResourceCategory, Integer> cost, BlockPos... positions) {
        return storage.reserveConstructionOperation(operationId, dimension, townKey, siteOwner, UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(positions), cost);
    }

    /** "No effects before failed intent acknowledgment": a reservation that fails - here, for
     *  insufficient funds - must debit nothing and create no operation record at all. */
    @GameTest(template = "settlement_test")
    public static void failedReservationDebitsNothingAndCreatesNoOperation(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(1, 1);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 5);

        UUID operationId = UUID.randomUUID();
        Optional<TownConstructionOperation> result = reserve(storage, dimension, townKey, "site:A", operationId,
                Map.of(TownResourceCategory.STONE, 10), new BlockPos(0, 0, 0));

        helper.assertTrue(result.isEmpty(), "a reservation that cannot afford its full cost must fail");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 5,
                "a failed reservation must debit nothing at all");
        helper.assertTrue(storage.constructionOperation(operationId).isEmpty(), "a failed reservation must create no operation record");
        helper.succeed();
    }

    /** "Insufficient/competing funding": every requested category must independently afford the
     *  full cost BEFORE any category is debited - a request that can afford STONE but not WOOD must
     *  leave the STONE balance untouched too (no partial charge across categories). */
    @GameTest(template = "settlement_test")
    public static void reservationIsAllOrNothingAcrossEveryRequestedCategory(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(2, 2);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        storage.storeFor(townKey).credit(TownResourceCategory.WOOD, 1);

        Optional<TownConstructionOperation> result = reserve(storage, dimension, townKey, "site:B", UUID.randomUUID(),
                Map.of(TownResourceCategory.STONE, 50, TownResourceCategory.WOOD, 10), new BlockPos(1, 0, 0));

        helper.assertTrue(result.isEmpty(), "a reservation must fail closed when ANY requested category cannot afford its share");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 100,
                "STONE must be untouched even though it alone could afford its own share");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.WOOD) == 1, "WOOD must be untouched");
        helper.succeed();
    }

    /** "Insufficient/competing funding" (site overlap) + "witness overwrite prevention": a second,
     *  fully-funded operation must still be refused against a site a first non-terminal operation
     *  already claims - the second operation can never overwrite the first's unsettled witness
     *  evidence. The fence clears only once the first operation reaches a TERMINAL phase. */
    @GameTest(template = "settlement_test")
    public static void aSecondOperationCannotReserveAgainstAnAlreadyFencedSiteUntilTheFirstIsTerminal(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(3, 3);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 1000);
        String siteOwner = "site:C";

        UUID firstId = UUID.randomUUID();
        Optional<TownConstructionOperation> first = reserve(storage, dimension, townKey, siteOwner, firstId,
                Map.of(TownResourceCategory.STONE, 10), new BlockPos(2, 0, 0));
        helper.assertTrue(first.isPresent(), "test setup: the first reservation must succeed");

        UUID secondId = UUID.randomUUID();
        Optional<TownConstructionOperation> second = reserve(storage, dimension, townKey, siteOwner, secondId,
                Map.of(TownResourceCategory.STONE, 10), new BlockPos(3, 0, 0));
        helper.assertTrue(second.isEmpty(), "a competing operation must be refused while the first still fences this exact site");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 990,
                "the refused competing reservation must debit nothing - only the first operation's cost was ever spent");

        storage.markWorldApplied(firstId);
        Optional<TownConstructionOperation> secondWhileWorldApplied = reserve(storage, dimension, townKey, siteOwner, secondId,
                Map.of(TownResourceCategory.STONE, 10), new BlockPos(3, 0, 0));
        helper.assertTrue(secondWhileWorldApplied.isEmpty(), "WORLD_APPLIED is still non-terminal - the fence must still hold");

        helper.assertTrue(storage.settleConstructionOperation(firstId).isPresent(), "test setup: the first operation must settle");
        Optional<TownConstructionOperation> secondAfterSettlement = reserve(storage, dimension, townKey, siteOwner, secondId,
                Map.of(TownResourceCategory.STONE, 10), new BlockPos(3, 0, 0));
        helper.assertTrue(secondAfterSettlement.isPresent(),
                "once the first operation reaches the TERMINAL SETTLED phase, the fence must clear and a new reservation must succeed");
        helper.succeed();
    }

    /** "Successful settlement and duplicate retry/reload": the full reserve -> world-applied ->
     *  settle sequence, a duplicate settle call afterward is a safe no-op, and the SETTLED record
     *  survives a real save/load round trip exactly. Also covers "both persistence orderings": the
     *  operation is independently saved/loaded once while still RESERVED and again once
     *  WORLD_APPLIED, proving both non-terminal phases persist correctly (the two orderings a crash
     *  could land on before settlement). */
    @GameTest(template = "settlement_test")
    public static void successfulSettlementSurvivesDuplicateRetryAndBothNonTerminalPersistenceOrderings(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(4, 4);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 50);
        UUID operationId = UUID.randomUUID();
        BlockPos cell = new BlockPos(4, 0, 0);

        TownConstructionOperation reserved = reserve(storage, dimension, townKey, "site:D", operationId,
                Map.of(TownResourceCategory.STONE, 50), cell).orElseThrow();
        helper.assertTrue(reserved.phase() == TownConstructionOperation.Phase.RESERVED, "test setup: must start RESERVED");

        // Persistence ordering #1: a crash right after the RESERVED intent is acknowledged, before
        // any world effect - reload must show the exact same RESERVED operation, escrow already spent.
        CompoundTag savedWhileReserved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloadedWhileReserved = TownStorage.load(savedWhileReserved, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedWhileReserved.constructionOperation(operationId).filter(reserved::equals).isPresent(),
                "the RESERVED operation must survive save/load exactly");
        helper.assertTrue(reloadedWhileReserved.storeFor(townKey).balance(TownResourceCategory.STONE) == 0,
                "the escrow debit must also survive save/load");

        TownConstructionOperation applied = storage.markWorldApplied(operationId).orElseThrow();
        helper.assertTrue(applied.phase() == TownConstructionOperation.Phase.WORLD_APPLIED, "test setup: must advance to WORLD_APPLIED");

        // Persistence ordering #2: a crash right after world effects were applied, before settlement.
        CompoundTag savedWhileApplied = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloadedWhileApplied = TownStorage.load(savedWhileApplied, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedWhileApplied.constructionOperation(operationId).filter(applied::equals).isPresent(),
                "the WORLD_APPLIED operation must survive save/load exactly");

        TownConstructionOperation settled = storage.settleConstructionOperation(operationId).orElseThrow();
        helper.assertTrue(settled.phase() == TownConstructionOperation.Phase.SETTLED, "test setup: must reach SETTLED");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 0,
                "settlement must never debit a second time - escrow was already spent at reservation");

        // Duplicate retry: a caller that re-observes/replays settlement after it already happened.
        Optional<TownConstructionOperation> duplicateSettle = storage.settleConstructionOperation(operationId);
        helper.assertTrue(duplicateSettle.filter(settled::equals).isPresent(), "a duplicate settle call must be a safe idempotent no-op");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 0, "a duplicate settle must still never debit again");

        CompoundTag savedWhileSettled = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloadedWhileSettled = TownStorage.load(savedWhileSettled, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedWhileSettled.constructionOperation(operationId).filter(settled::equals).isPresent(),
                "the SETTLED operation must survive save/load exactly, including its full effect records");
        helper.succeed();
    }

    /** "Unrelated balance changes": an operation's reservation/settlement must never disturb a
     *  DIFFERENT town's balance, or a different resource category within the SAME town that this
     *  operation never requested. */
    @GameTest(template = "settlement_test")
    public static void reservationAndSettlementNeverTouchUnrelatedTownsOrCategories(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(5, 5);
        RegionCoord otherRegion = new RegionCoord(6, 6);
        String townKey = TownKeys.keyFor(dimension, region);
        String otherTownKey = TownKeys.keyFor(dimension, otherRegion);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        storage.storeFor(townKey).credit(TownResourceCategory.WOOD, 77);
        storage.storeFor(otherTownKey).credit(TownResourceCategory.STONE, 42);

        UUID operationId = UUID.randomUUID();
        TownConstructionOperation reserved = reserve(storage, dimension, townKey, "site:E", operationId,
                Map.of(TownResourceCategory.STONE, 30), new BlockPos(5, 0, 0)).orElseThrow();
        storage.markWorldApplied(operationId);
        storage.settleConstructionOperation(operationId);

        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.WOOD) == 77,
                "a category this operation never requested must be completely untouched");
        helper.assertTrue(storage.storeFor(otherTownKey).balance(TownResourceCategory.STONE) == 42,
                "a different town's balance, even in the same category, must be completely untouched");
        helper.succeed();
    }

    /** "Rollback failure containment": cancelling a not-yet-settled operation refunds its FULL
     *  escrow exactly once; a repeat cancellation is a safe no-op that refunds nothing further; and
     *  cancellation after settlement is rejected outright - escrow was already spent, so there is
     *  nothing to refund and doing so would create resources out of nowhere. */
    @GameTest(template = "settlement_test")
    public static void cancellationRefundsExactlyOnceAndIsRejectedAfterSettlement(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(7, 7);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);

        UUID cancelledId = UUID.randomUUID();
        reserve(storage, dimension, townKey, "site:F", cancelledId, Map.of(TownResourceCategory.STONE, 40), new BlockPos(7, 0, 0));
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 60, "test setup: escrow must be spent");

        TownConstructionOperation cancelled = storage.cancelConstructionOperation(cancelledId).orElseThrow();
        helper.assertTrue(cancelled.phase() == TownConstructionOperation.Phase.CANCELLED, "cancellation must transition to CANCELLED");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 100, "cancellation must refund the full escrow exactly once");

        Optional<TownConstructionOperation> repeatCancel = storage.cancelConstructionOperation(cancelledId);
        helper.assertTrue(repeatCancel.filter(cancelled::equals).isPresent(), "a repeated cancellation must be a safe no-op");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 100, "a repeated cancellation must never refund twice");

        UUID settledId = UUID.randomUUID();
        reserve(storage, dimension, townKey, "site:G", settledId, Map.of(TownResourceCategory.STONE, 30), new BlockPos(8, 0, 0));
        storage.markWorldApplied(settledId);
        storage.settleConstructionOperation(settledId);
        int balanceAfterSettlement = storage.storeFor(townKey).balance(TownResourceCategory.STONE);

        Optional<TownConstructionOperation> cancelAfterSettle = storage.cancelConstructionOperation(settledId);
        helper.assertTrue(cancelAfterSettle.isEmpty(), "cancelling an already-SETTLED operation must be rejected outright");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == balanceAfterSettlement,
                "a rejected cancel-after-settle must never refund anything - that would create resources out of nowhere");
        helper.succeed();
    }

    /** "Anchor-absent enumeration": {@code pendingConstructionOperations} is the SOLE recovery
     *  entry point and must enumerate every non-terminal operation purely from the durable ledger -
     *  this test never places a single block or touches the live world at all, proving enumeration
     *  cannot depend on a surviving campfire/anchor. */
    @GameTest(template = "settlement_test")
    public static void pendingConstructionOperationsEnumeratesPurelyFromTheLedgerWithNoLiveAnchor(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(9, 9);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 1000);

        UUID reservedId = UUID.randomUUID();
        UUID appliedId = UUID.randomUUID();
        UUID settledId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        reserve(storage, dimension, townKey, "site:H1", reservedId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(9, 0, 0));
        reserve(storage, dimension, townKey, "site:H2", appliedId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(9, 0, 1));
        storage.markWorldApplied(appliedId);
        reserve(storage, dimension, townKey, "site:H3", settledId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(9, 0, 2));
        storage.markWorldApplied(settledId);
        storage.settleConstructionOperation(settledId);
        reserve(storage, dimension, townKey, "site:H4", cancelledId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(9, 0, 3));
        storage.cancelConstructionOperation(cancelledId);

        List<TownConstructionOperation> pending = storage.pendingConstructionOperations(dimension);
        helper.assertTrue(pending.size() == 2, "exactly the RESERVED and WORLD_APPLIED operations must be enumerated as pending - found " + pending.size());
        helper.assertTrue(pending.stream().anyMatch(op -> op.operationId().equals(reservedId)), "the RESERVED operation must be enumerated");
        helper.assertTrue(pending.stream().anyMatch(op -> op.operationId().equals(appliedId)), "the WORLD_APPLIED operation must be enumerated");
        helper.assertTrue(pending.stream().noneMatch(op -> op.operationId().equals(settledId)), "a SETTLED (terminal) operation must never be enumerated as pending");
        helper.assertTrue(pending.stream().noneMatch(op -> op.operationId().equals(cancelledId)), "a CANCELLED (terminal) operation must never be enumerated as pending");
        helper.succeed();
    }

    /** [coordinator review 51336] "Witness overwrite prevention" (chunk-overlap variant):
     *  {@code TownStorageChunkCheckpoint} stores exactly ONE witness per chunk, so two DIFFERENT
     *  sites that happen to share a chunk must be refused a second unsettled reservation there -
     *  independent of {@code siteOwner} - until the first reaches a TERMINAL phase. A genuinely
     *  independent (non-overlapping) chunk must remain freely reservable the whole time. */
    @GameTest(template = "settlement_test")
    public static void distinctSitesSharingAChunkAreRejectedUntilTheFirstReachesATerminalPhaseButIndependentChunksAreAllowed(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(10, 10);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 1000);

        BlockPos sharedChunkFirstCell = new BlockPos(1, 0, 1);
        BlockPos sharedChunkSecondSiteCell = new BlockPos(2, 0, 2);
        BlockPos independentChunkCell = new BlockPos(2000, 0, 2000);
        helper.assertTrue(new ChunkPos(sharedChunkFirstCell).equals(new ChunkPos(sharedChunkSecondSiteCell)),
                "test setup: both site cells must genuinely share one chunk");
        helper.assertTrue(!new ChunkPos(sharedChunkFirstCell).equals(new ChunkPos(independentChunkCell)),
                "test setup: the independent cell must genuinely be a different chunk");

        UUID firstSiteId = UUID.randomUUID();
        Optional<TownConstructionOperation> firstSite = reserve(storage, dimension, townKey, "site:overlap-A", firstSiteId,
                Map.of(TownResourceCategory.STONE, 10), sharedChunkFirstCell);
        helper.assertTrue(firstSite.isPresent(), "test setup: the first site's reservation must succeed");

        UUID secondSiteId = UUID.randomUUID();
        Optional<TownConstructionOperation> secondSiteWhileFirstUnsettled = reserve(storage, dimension, townKey, "site:overlap-B",
                secondSiteId, Map.of(TownResourceCategory.STONE, 10), sharedChunkSecondSiteCell);
        helper.assertTrue(secondSiteWhileFirstUnsettled.isEmpty(),
                "a DIFFERENT site reserving a cell in the SAME chunk as an unsettled operation must be refused, even with a different siteOwner");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 990,
                "the refused second site must debit nothing");

        UUID independentSiteId = UUID.randomUUID();
        Optional<TownConstructionOperation> independentSite = reserve(storage, dimension, townKey, "site:overlap-independent",
                independentSiteId, Map.of(TownResourceCategory.STONE, 10), independentChunkCell);
        helper.assertTrue(independentSite.isPresent(),
                "a site in a genuinely independent, non-overlapping chunk must remain freely reservable while the first is still unsettled");

        storage.markWorldApplied(firstSiteId);
        helper.assertTrue(storage.settleConstructionOperation(firstSiteId).isPresent(), "test setup: the first site must settle");

        Optional<TownConstructionOperation> secondSiteAfterFirstSettled = reserve(storage, dimension, townKey, "site:overlap-B",
                secondSiteId, Map.of(TownResourceCategory.STONE, 10), sharedChunkSecondSiteCell);
        helper.assertTrue(secondSiteAfterFirstSettled.isPresent(),
                "once the first site reaches the TERMINAL SETTLED phase, the shared chunk's fence must clear and the second site must succeed");
        helper.succeed();
    }

    /** [coordinator review 51336] "Test stripped fields and wrong types": a persisted construction
     *  operation whose effect position is missing, or whose position field is present but the
     *  wrong NBT type, must never silently decode to position (0,0,0) via a defaulting getter - it
     *  must be dropped and its site quarantined, exactly like any other malformed record. */
    @GameTest(template = "settlement_test")
    public static void loadQuarantinesAnOperationWithAStrippedOrWrongTypedEffectPosition(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(12, 12);
        String townKey = TownKeys.keyFor(dimension, region);

        TownStorage strippedStorage = new TownStorage();
        strippedStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID strippedId = UUID.randomUUID();
        reserve(strippedStorage, dimension, townKey, "site:stripped-pos", strippedId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(12, 0, 12));
        CompoundTag strippedSave = strippedStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        firstEffectTag(strippedSave).remove("X");
        TownStorage strippedReload = TownStorage.load(strippedSave, helper.getLevel().registryAccess());
        helper.assertTrue(strippedReload.constructionOperation(strippedId).isEmpty(),
                "an operation with a stripped effect position must never load as valid recovery evidence");
        helper.assertTrue(strippedReload.isSiteFencedByUnsettledOperation(dimension, "site:stripped-pos"),
                "a site whose operation record was dropped for a stripped position must be quarantined, not silently unfenced");

        TownStorage wrongTypeStorage = new TownStorage();
        wrongTypeStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID wrongTypeId = UUID.randomUUID();
        reserve(wrongTypeStorage, dimension, townKey, "site:wrongtype-pos", wrongTypeId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(13, 0, 13));
        CompoundTag wrongTypeSave = wrongTypeStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        firstEffectTag(wrongTypeSave).putString("X", "not-an-int");
        TownStorage wrongTypeReload = TownStorage.load(wrongTypeSave, helper.getLevel().registryAccess());
        helper.assertTrue(wrongTypeReload.constructionOperation(wrongTypeId).isEmpty(),
                "an operation with a wrong-typed effect position field must never load as valid recovery evidence");
        helper.succeed();
    }

    /** [coordinator review 51336] A corrupted/unknown block id in a recorded before/after state
     *  must never silently decode as {@code minecraft:air} - that would masquerade as legitimate
     *  "this cell was air" evidence. Likewise a stripped {@code Captured} NBT payload must never
     *  silently decode to an empty-but-valid-looking payload. */
    @GameTest(template = "settlement_test")
    public static void loadQuarantinesAnOperationWithACorruptedBlockIdOrAStrippedCapturedNbtPayload(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(14, 14);
        String townKey = TownKeys.keyFor(dimension, region);

        TownStorage corruptBlockStorage = new TownStorage();
        corruptBlockStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID corruptBlockId = UUID.randomUUID();
        reserve(corruptBlockStorage, dimension, townKey, "site:corrupt-block", corruptBlockId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(14, 0, 14));
        CompoundTag corruptBlockSave = corruptBlockStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        firstEffectTag(corruptBlockSave).getCompound("Before").putString("Name", "dynamicvehicles:no_such_block_ever");
        TownStorage corruptBlockReload = TownStorage.load(corruptBlockSave, helper.getLevel().registryAccess());
        helper.assertTrue(corruptBlockReload.constructionOperation(corruptBlockId).isEmpty(),
                "an operation whose recorded block id does not exist must never silently decode to AIR and load as valid evidence");

        TownStorage strippedNbtStorage = new TownStorage();
        strippedNbtStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID strippedNbtId = UUID.randomUUID();
        List<TownConstructionOperation.CellEffect> effectsWithCapturedNbt = List.of(new TownConstructionOperation.CellEffect(
                new BlockPos(15, 0, 15), Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.None(),
                new TownConstructionOperation.BlockEntitySnapshot.Captured(makeTagWithMarker()), "sig"));
        strippedNbtStorage.reserveConstructionOperation(strippedNbtId, dimension, townKey, "site:stripped-nbt", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effectsWithCapturedNbt, Map.of(TownResourceCategory.STONE, 10));
        CompoundTag strippedNbtSave = strippedNbtStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        firstEffectTag(strippedNbtSave).getCompound("AfterBE").remove("Nbt");
        TownStorage strippedNbtReload = TownStorage.load(strippedNbtSave, helper.getLevel().registryAccess());
        helper.assertTrue(strippedNbtReload.constructionOperation(strippedNbtId).isEmpty(),
                "an operation with a stripped Captured.Nbt payload must never silently decode to an empty-but-valid payload");
        helper.succeed();
    }

    /** [coordinator review 51336] A wrong-typed reserved-cost entry must be rejected rather than
     *  silently defaulting to zero; a genuinely, explicitly recorded zero-cost category must still
     *  be preserved exactly as zero, never confused with "absent" or dropped. */
    @GameTest(template = "settlement_test")
    public static void loadRejectsAWrongTypedReservedCostButPreservesAnExplicitZeroCost(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(16, 16);
        String townKey = TownKeys.keyFor(dimension, region);

        TownStorage wrongTypeCostStorage = new TownStorage();
        wrongTypeCostStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID wrongTypeCostId = UUID.randomUUID();
        reserve(wrongTypeCostStorage, dimension, townKey, "site:wrongtype-cost", wrongTypeCostId, Map.of(TownResourceCategory.STONE, 10), new BlockPos(16, 0, 16));
        CompoundTag wrongTypeCostSave = wrongTypeCostStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        wrongTypeCostSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("ReservedCost").putString(TownResourceCategory.STONE.name(), "not-an-int");
        TownStorage wrongTypeCostReload = TownStorage.load(wrongTypeCostSave, helper.getLevel().registryAccess());
        helper.assertTrue(wrongTypeCostReload.constructionOperation(wrongTypeCostId).isEmpty(),
                "an operation with a wrong-typed reserved cost entry must never load, defaulting it to zero");

        TownStorage explicitZeroStorage = new TownStorage();
        explicitZeroStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        explicitZeroStorage.storeFor(townKey).credit(TownResourceCategory.WOOD, 100);
        UUID explicitZeroId = UUID.randomUUID();
        explicitZeroStorage.reserveConstructionOperation(explicitZeroId, dimension, townKey, "site:explicit-zero", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effects(new BlockPos(17, 0, 17)),
                Map.of(TownResourceCategory.STONE, 10, TownResourceCategory.WOOD, 0));
        CompoundTag explicitZeroSave = explicitZeroStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage explicitZeroReload = TownStorage.load(explicitZeroSave, helper.getLevel().registryAccess());
        TownConstructionOperation reloadedExplicitZero = explicitZeroReload.constructionOperation(explicitZeroId).orElseThrow();
        helper.assertTrue(Integer.valueOf(0).equals(reloadedExplicitZero.reservedCost().get(TownResourceCategory.WOOD)),
                "an explicitly recorded zero cost must be preserved exactly - found " + reloadedExplicitZero.reservedCost());
        helper.succeed();
    }

    private static CompoundTag firstEffectTag(CompoundTag savedStorage) {
        return savedStorage.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0)
                .getList("Effects", Tag.TAG_COMPOUND).getCompound(0);
    }

    private static CompoundTag makeTagWithMarker() {
        CompoundTag tag = new CompoundTag();
        tag.putString("marker", "present");
        return tag;
    }
}
