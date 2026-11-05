package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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
 * MINECRAFT-454 [coordinator review 51341] Focused coverage for the bounded enforcement-boundary
 * correction: ONE authoritative ledger ownership decision shared by reservation and checkpointing
 * (not merely reservation-time fencing), load-time quarantine that actually excludes ambiguous
 * records from recovery (not merely future reservations), and the remaining strict decoder gaps
 * (ReservedCost compound presence, canonical block-state round-trip, Beneficiary malformed-vs-
 * absent).
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownConstructionOperationEnforcementGameTests {
    private TownConstructionOperationEnforcementGameTests() {}

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

    private static CompoundTag firstEffectTag(CompoundTag savedStorage) {
        return savedStorage.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0)
                .getList("Effects", Tag.TAG_COMPOUND).getCompound(0);
    }

    /** [coordinator review 51341] "The same helper must protect operation-linked and standalone
     *  witness overwrite; tests must prove direct overwrite rejection, not merely second
     *  reservation rejection." Exercises the actual guarded checkpoint entry points - {@link
     *  TownStorage#checkpointChunkForOperation} and {@link TownStorage#checkpointChunkIfUnclaimed}
     *  - never merely the reservation-time fence. */
    @GameTest(template = "settlement_test")
    public static void guardedCheckpointRejectsOverwritingADifferentUnsettledOperationsWitness(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        RegionCoord region = new RegionCoord(30, 30);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);

        BlockPos cell = helper.absolutePos(new BlockPos(3, 2, 3));
        ChunkPos chunkPos = new ChunkPos(cell);
        UUID ownerOperationId = UUID.randomUUID();
        Optional<TownConstructionOperation> reserved = storage.reserveConstructionOperation(ownerOperationId, dimension, townKey,
                "site:guard-owner", UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), effects(cell),
                Map.of(TownResourceCategory.STONE, 10));
        helper.assertTrue(reserved.isPresent(), "test setup: the owning operation's reservation must succeed");

        Optional<TownStorageChunkCheckpoint.CheckpointResult> ownResult = storage.checkpointChunkForOperation(level, chunkPos, ownerOperationId);
        helper.assertTrue(ownResult.isPresent() && ownResult.get() instanceof TownStorageChunkCheckpoint.CheckpointResult.Checkpointed,
                "the owning operation must be allowed to checkpoint its own chunk - found " + ownResult);

        UUID intruderOperationId = UUID.randomUUID();
        Optional<TownStorageChunkCheckpoint.CheckpointResult> intruderResult = storage.checkpointChunkForOperation(level, chunkPos, intruderOperationId);
        helper.assertTrue(intruderResult.isEmpty(),
                "a DIFFERENT operation must be rejected by the guard BEFORE ever touching the chunk's witness - found " + intruderResult);

        Optional<TownStorageChunkCheckpoint.CheckpointResult> standaloneResult = storage.checkpointChunkIfUnclaimed(level, chunkPos);
        helper.assertTrue(standaloneResult.isEmpty(),
                "a standalone checkpoint must also be rejected by the SAME guard while an unsettled operation owns this chunk - found " + standaloneResult);

        storage.markWorldApplied(ownerOperationId);
        storage.settleConstructionOperation(ownerOperationId);
        Optional<TownStorageChunkCheckpoint.CheckpointResult> standaloneAfterSettlement = storage.checkpointChunkIfUnclaimed(level, chunkPos);
        helper.assertTrue(standaloneAfterSettlement.isPresent(),
                "once the owning operation reaches the TERMINAL SETTLED phase, a standalone checkpoint must be allowed again - found "
                        + standaloneAfterSettlement);
        helper.succeed();
    }

    /** [coordinator review 51341] Duplicate persisted ids must quarantine BOTH the already-retained
     *  first occurrence's own site AND the second entry's own site - never leaving one "winner"
     *  trusted - and a quarantined operation must be excluded from {@code
     *  pendingConstructionOperations} (recovery), not merely blocked from a brand-new reservation. */
    @GameTest(template = "settlement_test")
    public static void duplicateOperationIdsOnLoadQuarantineBothSitesAndExcludeFromRecovery(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord regionA = new RegionCoord(31, 31);
        RegionCoord regionB = new RegionCoord(32, 32);
        TownStorage firstStorage = new TownStorage();
        String townKeyA = TownKeys.keyFor(dimension, regionA);
        firstStorage.storeFor(townKeyA).credit(TownResourceCategory.STONE, 100);
        UUID sharedId = UUID.randomUUID();
        firstStorage.reserveConstructionOperation(sharedId, dimension, townKeyA, "site:dup-A", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(new BlockPos(31, 0, 31)), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag firstSave = firstStorage.save(new CompoundTag(), helper.getLevel().registryAccess());

        TownStorage secondStorage = new TownStorage();
        String townKeyB = TownKeys.keyFor(dimension, regionB);
        secondStorage.storeFor(townKeyB).credit(TownResourceCategory.STONE, 100);
        secondStorage.reserveConstructionOperation(sharedId, dimension, townKeyB, "site:dup-B", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(new BlockPos(32, 0, 32)), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag secondSave = secondStorage.save(new CompoundTag(), helper.getLevel().registryAccess());

        // Simulate a corrupted/hand-merged file carrying the SAME operation id twice, under two
        // different sites - this can never happen through the ledger's own normal operation (each
        // storage above is independent), only through external corruption.
        ListTag mergedOps = new ListTag();
        mergedOps.add(firstSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).copy());
        mergedOps.add(secondSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).copy());
        CompoundTag merged = firstSave.copy();
        merged.put("ConstructionOperations", mergedOps);

        TownStorage reloaded = TownStorage.load(merged, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.isSiteFencedByUnsettledOperation(dimension, "site:dup-A"),
                "the FIRST occurrence's own site must be quarantined too, not left trusted as the 'winner'");
        helper.assertTrue(reloaded.isSiteFencedByUnsettledOperation(dimension, "site:dup-B"),
                "the SECOND occurrence's own site must be quarantined");
        helper.assertTrue(reloaded.pendingConstructionOperations(dimension).stream()
                        .noneMatch(op -> "site:dup-A".equals(op.siteOwner()) || "site:dup-B".equals(op.siteOwner())),
                "neither quarantined site's operation must ever be handed back by pendingConstructionOperations for recovery");
        helper.succeed();
    }

    /** [coordinator review 51341] Two DIFFERENT siteOwners' unsettled operations overlapping the
     *  SAME chunk - a conflict the ledger's own reservation-time fence now prevents going forward,
     *  but a hand-edited/legacy file could still contain - must quarantine EVERY involved
     *  operation's own site on load, independent of the same-site conflict check. */
    @GameTest(template = "settlement_test")
    public static void distinctSitesSharingAChunkOnLoadQuarantineAllInvolvedSites(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(33, 33);
        String townKey = TownKeys.keyFor(dimension, region);
        BlockPos sharedCellA = new BlockPos(1, 0, 1);
        BlockPos sharedCellB = new BlockPos(2, 0, 2);
        helper.assertTrue(new ChunkPos(sharedCellA).equals(new ChunkPos(sharedCellB)), "test setup: both cells must share one chunk");

        TownStorage firstStorage = new TownStorage();
        firstStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        firstStorage.reserveConstructionOperation(UUID.randomUUID(), dimension, townKey, "site:chunk-conflict-A", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effects(sharedCellA), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag firstSave = firstStorage.save(new CompoundTag(), helper.getLevel().registryAccess());

        TownStorage secondStorage = new TownStorage();
        secondStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        secondStorage.reserveConstructionOperation(UUID.randomUUID(), dimension, townKey, "site:chunk-conflict-B", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effects(sharedCellB), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag secondSave = secondStorage.save(new CompoundTag(), helper.getLevel().registryAccess());

        ListTag mergedOps = new ListTag();
        mergedOps.add(firstSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).copy());
        mergedOps.add(secondSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).copy());
        CompoundTag merged = firstSave.copy();
        merged.put("ConstructionOperations", mergedOps);

        TownStorage reloaded = TownStorage.load(merged, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.isSiteFencedByUnsettledOperation(dimension, "site:chunk-conflict-A"),
                "a site whose operation shares a chunk with a DIFFERENT unsettled site must be quarantined");
        helper.assertTrue(reloaded.isSiteFencedByUnsettledOperation(dimension, "site:chunk-conflict-B"),
                "the OTHER conflicting site must be quarantined too");
        helper.succeed();
    }

    /** [coordinator review 51341] A missing/wrong-typed ReservedCost compound must never silently
     *  default to an empty (free) reservation. */
    @GameTest(template = "settlement_test")
    public static void loadRejectsAMissingReservedCostCompound(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(34, 34);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID operationId = UUID.randomUUID();
        storage.reserveConstructionOperation(operationId, dimension, townKey, "site:missing-cost", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effects(new BlockPos(34, 0, 34)), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        saved.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).remove("ReservedCost");

        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.constructionOperation(operationId).isEmpty(),
                "an operation with a stripped ReservedCost compound must never load as a free reservation");
        helper.succeed();
    }

    /** [coordinator review 51341] A present-but-malformed Beneficiary field must be rejected, never
     *  silently read as "no beneficiary" - distinct from a genuinely, legitimately ABSENT
     *  Beneficiary (a home-only operation), which must still load correctly as {@code
     *  Optional.empty()}. */
    @GameTest(template = "settlement_test")
    public static void loadDistinguishesAMalformedBeneficiaryFromAGenuinelyAbsentOne(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(35, 35);
        String townKey = TownKeys.keyFor(dimension, region);

        TownStorage malformedStorage = new TownStorage();
        malformedStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID malformedId = UUID.randomUUID();
        malformedStorage.reserveConstructionOperation(malformedId, dimension, townKey, "site:malformed-beneficiary", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effects(new BlockPos(35, 0, 35)), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag malformedSave = malformedStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        malformedSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).putString("Beneficiary", "not-a-uuid");
        TownStorage malformedReload = TownStorage.load(malformedSave, helper.getLevel().registryAccess());
        helper.assertTrue(malformedReload.constructionOperation(malformedId).isEmpty(),
                "a present-but-malformed Beneficiary field must never silently decode as absent");

        TownStorage absentStorage = new TownStorage();
        absentStorage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID absentId = UUID.randomUUID();
        absentStorage.reserveConstructionOperation(absentId, dimension, townKey, "site:absent-beneficiary", UUID.randomUUID(),
                UUID.randomUUID(), Optional.empty(), effects(new BlockPos(36, 0, 36)), Map.of(TownResourceCategory.STONE, 10));
        CompoundTag absentSave = absentStorage.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(!absentSave.getList("ConstructionOperations", Tag.TAG_COMPOUND).getCompound(0).contains("Beneficiary"),
                "test setup: a genuinely beneficiary-less operation must never even persist the key");
        TownStorage absentReload = TownStorage.load(absentSave, helper.getLevel().registryAccess());
        helper.assertTrue(absentReload.constructionOperation(absentId).filter(op -> op.beneficiaryId().isEmpty()).isPresent(),
                "a genuinely absent Beneficiary must still load correctly as Optional.empty()");
        helper.succeed();
    }

    /** [coordinator review 51341] Validate the FULL canonical state serialization, not merely the
     *  block name: an unknown/invalid property VALUE (which {@code NbtUtils#readBlockState} silently
     *  ignores rather than rejecting) must still be caught and rejected via the round-trip check. */
    @GameTest(template = "settlement_test")
    public static void loadRejectsAnEffectWithAnInvalidBlockStatePropertyValue(GameTestHelper helper) {
        ResourceKey<Level> dimension = helper.getLevel().dimension();
        RegionCoord region = new RegionCoord(37, 37);
        String townKey = TownKeys.keyFor(dimension, region);
        TownStorage storage = new TownStorage();
        storage.storeFor(townKey).credit(TownResourceCategory.STONE, 100);
        UUID operationId = UUID.randomUUID();
        List<TownConstructionOperation.CellEffect> effectsWithProperty = List.of(new TownConstructionOperation.CellEffect(
                new BlockPos(37, 0, 37), Blocks.AIR.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                new TownConstructionOperation.BlockEntitySnapshot.None(), new TownConstructionOperation.BlockEntitySnapshot.None(), "sig"));
        storage.reserveConstructionOperation(operationId, dimension, townKey, "site:bad-property", UUID.randomUUID(), UUID.randomUUID(),
                Optional.empty(), effectsWithProperty, Map.of(TownResourceCategory.STONE, 10));
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        CompoundTag afterState = firstEffectTag(saved).getCompound("After");
        helper.assertTrue(afterState.contains("Properties", Tag.TAG_COMPOUND) && afterState.getCompound("Properties").contains("axis"),
                "test setup: oak log must persist its own axis property");
        afterState.getCompound("Properties").putString("axis", "not-a-real-axis-value");

        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.constructionOperation(operationId).isEmpty(),
                "an effect whose recorded block state does not canonically round-trip (invalid property value) must never load");
        helper.succeed();
    }
}
