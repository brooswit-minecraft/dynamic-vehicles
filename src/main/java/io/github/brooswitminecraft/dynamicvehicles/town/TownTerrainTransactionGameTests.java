package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Focused production-path regressions for building terrain-preparation transactions. */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownTerrainTransactionGameTests {
    private TownTerrainTransactionGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    private static void spawnClaimant(GameTestHelper helper, BlockPos absoluteCampfire) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
    }

    private static TownResourceStore fundStore(GameTestHelper helper, TownStorage storage, BlockPos absoluteCampfire, int amount) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
        store.credit(TownResourceCategory.STONE, amount);
        store.credit(TownResourceCategory.WOOD, amount);
        return store;
    }

    /**
     * A failed intermediate clear must roll back every earlier terrain write, award no salvage,
     * and leave the build unadvanced.  The next clean production invocation then proves the same
     * site can commit normally rather than being poisoned by the interrupted attempt.
     */
    @GameTest(template = "settlement_test")
    public static void interruptedBuildingTerrainPreparationRollsBackWithoutCreditAndRetriesCleanly(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        BlockPos trunkBase = ANCHOR.offset(1, 0, 1);
        helper.setBlock(trunkBase, Blocks.OAK_LOG);
        helper.setBlock(trunkBase.above(), Blocks.OAK_LOG);
        spawnClaimant(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);
        int woodBefore = store.balance(TownResourceCategory.WOOD);
        int stoneBefore = store.balance(TownResourceCategory.STONE);

        // The second log is reached only after the preparation has already removed earlier natural
        // terrain, so this proves the full transaction rolls those earlier writes back too.
        TownConstructionService.testForceTerrainMutationFailureAt = helper.absolutePos(trunkBase.above());
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(trunkBase).is(Blocks.OAK_LOG)
                        && helper.getBlockState(trunkBase.above()).is(Blocks.OAK_LOG),
                "a rejected intermediate clear must restore both the failed log and earlier cleared terrain");
        helper.assertTrue(store.balance(TownResourceCategory.WOOD) == woodBefore
                        && store.balance(TownResourceCategory.STONE) == stoneBefore,
                "an interrupted terrain transaction must not credit salvage or debit construction resources");
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE)
                        && helper.getBlockState(ANCHOR.offset(-2, 0, -2)).isAir(),
                "an interrupted terrain transaction must not advance any building placement");

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(!helper.getBlockState(trunkBase).is(Blocks.OAK_LOG)
                        && !helper.getBlockState(trunkBase.above()).is(Blocks.OAK_LOG),
                "a clean retry after rollback must commit the normal terrain clearing");
        helper.succeed();
    }

    /** A rollback failure in a below-floor fill must survive reload and stop the whole site, not
     * let a later attempt skip that untrusted support cell and advance the building above it. */
    @GameTest(template = "settlement_test")
    public static void rejectedFillRollbackFailureBlocksRetryAfterReload(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos elevatedAnchor = ANCHOR.above(2);
        helper.setBlock(elevatedAnchor, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(elevatedAnchor);
        spawnClaimant(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        // Persist the elevated floor so adjacent footprint columns must perform below-floor fills.
        storage.setFloor(TownStorage.siteOwner(absoluteCampfire), absoluteCampfire.getY());
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);
        int woodBefore = store.balance(TownResourceCategory.WOOD);
        int stoneBefore = store.balance(TownResourceCategory.STONE);

        BlockPos firstFill = elevatedAnchor.offset(-2, -2, -2);
        BlockPos rejectedFill = firstFill.above();
        BlockPos absoluteFirstFill = helper.absolutePos(firstFill);
        BlockPos absoluteRejectedFill = helper.absolutePos(rejectedFill);
        TownConstructionService.testForceTerrainMutationFailureAt = absoluteRejectedFill;
        TownConstructionService.testForceTerrainRollbackFailureAt = absoluteFirstFill;
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        TownConstructionService.testForceTerrainRollbackFailureAt = null;

        helper.assertTrue(storage.isCellBlocked(helper.getLevel().dimension(), absoluteFirstFill)
                        && storage.isCellBlocked(helper.getLevel().dimension(), absoluteRejectedFill),
                "an unconfirmed fill rollback must durably block every cell in its terrain transaction");
        helper.assertTrue(helper.getBlockState(firstFill).is(Blocks.DIRT)
                        && helper.getBlockState(rejectedFill).isAir(),
                "the test must leave the failed rollback's first fill visibly untrusted");
        helper.assertTrue(store.balance(TownResourceCategory.WOOD) == woodBefore
                        && store.balance(TownResourceCategory.STONE) == stoneBefore
                        && helper.getBlockState(elevatedAnchor).is(Blocks.CAMPFIRE),
                "a rejected fill and failed rollback must not credit, debit, or advance the building");

        // Subsequent natural coverage changes the heightmap so the original blocked upper fill
        // would be outside both preparation loops.  Only the site-level quarantine may now stop
        // a second attempt.
        helper.setBlock(rejectedFill, Blocks.DIRT);
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(),
                TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ())));
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);

        helper.assertTrue(reloaded.isCellBlocked(helper.getLevel().dimension(), absoluteFirstFill)
                        && reloaded.isCellBlocked(helper.getLevel().dimension(), absoluteRejectedFill),
                "the terrain transaction hold must survive reload");
        helper.assertTrue(helper.getBlockState(firstFill).is(Blocks.DIRT)
                        && helper.getBlockState(rejectedFill).is(Blocks.DIRT)
                        && helper.getBlockState(elevatedAnchor).is(Blocks.CAMPFIRE),
                "a later attempt must stop before adopting or building over blocked fill cells");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.WOOD) == woodBefore
                        && reloadedStore.balance(TownResourceCategory.STONE) == stoneBefore,
                "a blocked retry must leave resources unchanged");
        helper.succeed();
    }

    /** CAVE_AIR is an air state but not the same state as ordinary AIR.  A rejected fill must
     * restore that exact pre-state rather than normalising it to plain air. */
    @GameTest(template = "settlement_test")
    public static void rejectedFillRestoresExactCaveAirState(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos elevatedAnchor = ANCHOR.above(2);
        helper.setBlock(elevatedAnchor, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(elevatedAnchor);
        spawnClaimant(helper, absoluteCampfire);
        TownStorage storage = new TownStorage();
        storage.setFloor(TownStorage.siteOwner(absoluteCampfire), absoluteCampfire.getY());
        fundStore(helper, storage, absoluteCampfire, 500);

        BlockPos firstFill = elevatedAnchor.offset(-2, -2, -2);
        BlockPos rejectedFill = firstFill.above();
        helper.setBlock(firstFill, Blocks.CAVE_AIR);
        TownConstructionService.testForceTerrainMutationFailureAt = helper.absolutePos(rejectedFill);
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(firstFill).equals(Blocks.CAVE_AIR.defaultBlockState()),
                "rollback must restore CAVE_AIR exactly, not replace it with ordinary AIR");
        helper.assertTrue(helper.getBlockState(elevatedAnchor).is(Blocks.CAMPFIRE),
                "a rejected fill must not advance the building");
        helper.succeed();
    }

    /** Terrain prep must reject a populated waterlogged chest before any write.  Without the
     * block-entity guard, its fluid state makes it pass natural-terrain admission and a later
     * terrain mutation would clear it without a way to restore its inventory/NBT. */
    @GameTest(template = "settlement_test")
    public static void waterloggedChestPreflightPreservesInventoryAndStopsBuild(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        BlockPos containerCell = ANCHOR.offset(1, 0, 1);
        BlockPos absoluteContainerCell = helper.absolutePos(containerCell);
        BlockState waterloggedChest = Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED, true);
        helper.getLevel().setBlock(absoluteContainerCell, waterloggedChest, 3);
        ChestBlockEntity inventory = (ChestBlockEntity) helper.getLevel().getBlockEntity(absoluteContainerCell);
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 7));
        spawnClaimant(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);
        int woodBefore = store.balance(TownResourceCategory.WOOD);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        helper.assertTrue(helper.getLevel().getFluidState(absoluteContainerCell).isSource(),
                "fixture must be a genuinely waterlogged chest");
        helper.assertTrue(TownTerrain.isNaturalTerrain(helper.getBlockState(containerCell)),
                "fixture must satisfy the pre-guard natural-terrain admission predicate");
        TownConstructionService.testForceTerrainMutationFailureAt = helper.absolutePos(ANCHOR.offset(1, 1, 1));
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        TownConstructionService.testForceTerrainMutationFailureAt = null;

        helper.assertTrue(helper.getBlockState(containerCell).is(Blocks.CHEST),
                "a waterlogged block-entity cell must not be cleared by terrain preparation");
        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteContainerCell) instanceof ChestBlockEntity chest
                        && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7,
                "the rejected cell's identifiable block-entity inventory/NBT must remain intact");
        helper.assertTrue(store.balance(TownResourceCategory.WOOD) == woodBefore
                        && store.balance(TownResourceCategory.STONE) == stoneBefore
                        && helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "rejecting a block entity must leave resources and building progress untouched");
        helper.succeed();
    }

    /**
     * Persisted paid progress is permission to retain a matching live cell, not permission to
     * bypass whole-site safety after that cell has changed.  This deliberately exercises the real
     * claimed construction consumer once to create paid progress, then reloads it before retrying.
     */
    @GameTest(template = "settlement_test")
    public static void paidProgressWithAirAndProtectedChestRejectsWholeBuildingSite(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        spawnClaimant(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        int floorY = storage.floorFor(TownStorage.siteOwner(absoluteCampfire)).orElseThrow();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean storehouse = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = storehouse ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        var profession = TownProfessionWorkstations.finalProfessionFor(kind, helper.getLevel().getSeed(),
                absoluteCampfire.getX(), absoluteCampfire.getZ());
        List<TownBuildingTemplates.Placement> template = TownBuildingTemplates.generate(kind, absoluteCampfire.getX(),
                floorY, absoluteCampfire.getZ(), plan.storehouseVariant(), profession.orElse(null));
        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(
                template.stream()
                        .map(TownBuildingTemplates.Placement::toPlan).toList(),
                absoluteCampfire.getX(), absoluteCampfire.getY(), absoluteCampfire.getZ());
        ConstructionSitePlan.PlannedBlock earlier = ordered.get(0);
        ConstructionSitePlan.PlannedBlock later = ordered.get(1);
        BlockPos earlierAbsolute = new BlockPos(earlier.x(), earlier.y(), earlier.z());
        BlockPos laterAbsolute = new BlockPos(later.x(), later.y(), later.z());

        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        ListTag paidBefore = saved.getList("PlacedCells", Tag.TAG_COMPOUND).copy();
        String earlierKey = helper.getLevel().dimension().location() + "@" + earlierAbsolute.getX() + "," + earlierAbsolute.getY() + "," + earlierAbsolute.getZ();
        String laterKey = helper.getLevel().dimension().location() + "@" + laterAbsolute.getX() + "," + laterAbsolute.getY() + "," + laterAbsolute.getZ();
        CompoundTag earlierRecord = paidBefore.stream().map(CompoundTag.class::cast)
                .filter(cell -> cell.getString("Key").equals(earlierKey)).findFirst().orElseThrow().copy();
        CompoundTag laterRecord = paidBefore.stream().map(CompoundTag.class::cast)
                .filter(cell -> cell.getString("Key").equals(laterKey)).findFirst().orElseThrow().copy();
        helper.assertTrue(earlierRecord.getString("Owner").equals(TownStorage.siteOwner(absoluteCampfire))
                        && laterRecord.getString("Owner").equals(TownStorage.siteOwner(absoluteCampfire))
                        && !earlierRecord.getString("Signature").isEmpty() && !laterRecord.getString("Signature").isEmpty(),
                "the actual claimed consumer must persist exact owner/signature records before reload");

        helper.getLevel().setBlock(earlierAbsolute, Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(laterAbsolute, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chestBeforeRetry = (ChestBlockEntity) helper.getLevel().getBlockEntity(laterAbsolute);
        chestBeforeRetry.setItem(0, new ItemStack(Items.DIAMOND, 7));
        int woodAfterPaidProgress = store.balance(TownResourceCategory.WOOD);
        int stoneAfterPaidProgress = store.balance(TownResourceCategory.STONE);
        Map<BlockPos, BlockState> siteBeforeRetry = new HashMap<>();
        for (TownBuildingTemplates.Placement placement : template) {
            BlockPos pos = new BlockPos(placement.x(), placement.y(), placement.z());
            siteBeforeRetry.put(pos, helper.getLevel().getBlockState(pos));
        }

        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        CompoundTag afterRetry = reloaded.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloadedAgain = TownStorage.load(afterRetry, helper.getLevel().registryAccess());
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloadedAgain);
        CompoundTag afterSecondRetry = reloadedAgain.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloadedAgain.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));

        helper.assertTrue(helper.getLevel().getBlockState(earlierAbsolute).isAir(),
                "a protected later paid cell must reject the whole site before an earlier missing paid cell is restored");
        helper.assertTrue(helper.getLevel().getBlockState(laterAbsolute).is(Blocks.CHEST)
                        && helper.getLevel().getBlockEntity(laterAbsolute) instanceof ChestBlockEntity chest
                        && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7,
                "whole-site rejection must preserve the protected chest and its inventory");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.WOOD) == woodAfterPaidProgress
                        && reloadedStore.balance(TownResourceCategory.STONE) == stoneAfterPaidProgress,
                "a rejected retry must not change either resource balance");
        helper.assertTrue(helper.getLevel().getBlockState(absoluteCampfire).is(Blocks.CAMPFIRE)
                        && siteBeforeRetry.entrySet().stream().allMatch(entry -> helper.getLevel().getBlockState(entry.getKey()).equals(entry.getValue())),
                "repeated rejected retries must preserve the full planned site and its anchor");
        ListTag paidAfter = afterSecondRetry.getList("PlacedCells", Tag.TAG_COMPOUND);
        helper.assertTrue(paidAfter.size() == paidBefore.size()
                        && paidAfter.stream().map(CompoundTag.class::cast).filter(cell -> cell.getString("Key").equals(earlierKey))
                                .findFirst().map(earlierRecord::equals).orElse(false)
                        && paidAfter.stream().map(CompoundTag.class::cast).filter(cell -> cell.getString("Key").equals(laterKey))
                                .findFirst().map(laterRecord::equals).orElse(false),
                "whole-site rejection must preserve exact owner/signature/fixture paid-progress records across repeated reload and retry");

        // With the chest restored to its own paid planned state, the earlier destroyed paid cell
        // is now the only mismatch. It must still hold the whole site without a free rebuild.
        BlockState laterExpectedState = template.stream()
                .filter(placement -> placement.x() == laterAbsolute.getX() && placement.y() == laterAbsolute.getY()
                        && placement.z() == laterAbsolute.getZ())
                .findFirst().orElseThrow().state();
        helper.getLevel().setBlock(laterAbsolute, laterExpectedState, 3);
        helper.assertTrue(template.stream().filter(placement -> {
                    BlockPos pos = new BlockPos(placement.x(), placement.y(), placement.z());
                    String recordKey = helper.getLevel().dimension().location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
                    return !pos.equals(earlierAbsolute)
                            && paidBefore.stream().map(CompoundTag.class::cast).anyMatch(cell -> cell.getString("Key").equals(recordKey));
                }).allMatch(placement -> helper.getLevel().getBlockState(new BlockPos(placement.x(), placement.y(), placement.z()))
                        .equals(placement.state())),
                "the AIR-only retry fixture must restore every other recorded paid cell to its expected planned state");
        Map<BlockPos, BlockState> airOnlySite = new HashMap<>();
        for (TownBuildingTemplates.Placement placement : template) {
            BlockPos pos = new BlockPos(placement.x(), placement.y(), placement.z());
            airOnlySite.put(pos, helper.getLevel().getBlockState(pos));
        }
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloadedAgain);
        CompoundTag afterAirOnlyRetry = reloadedAgain.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(helper.getLevel().getBlockState(earlierAbsolute).isAir()
                        && airOnlySite.entrySet().stream().allMatch(entry -> helper.getLevel().getBlockState(entry.getKey()).equals(entry.getValue()))
                        && reloadedStore.balance(TownResourceCategory.WOOD) == woodAfterPaidProgress
                        && reloadedStore.balance(TownResourceCategory.STONE) == stoneAfterPaidProgress
                        && afterAirOnlyRetry.getList("PlacedCells", Tag.TAG_COMPOUND).equals(paidAfter),
                "an AIR-only paid-progress mismatch must hold the entire site without mutation, charges, or record changes");
        helper.succeed();
    }

    /** Explicitly requested post-write quarantine survives reload without creating a debit or
     * paid record. This is not a process-crash recovery test. */
    @GameTest(template = "settlement_test")
    public static void postPlacementInterruptionBlocksRetryWithoutDebitOrPaidRecord(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        spawnClaimant(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        boolean storehouse = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = storehouse ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        var profession = TownProfessionWorkstations.finalProfessionFor(kind, helper.getLevel().getSeed(),
                absoluteCampfire.getX(), absoluteCampfire.getZ());
        List<TownBuildingTemplates.Placement> template = TownBuildingTemplates.generate(kind, absoluteCampfire.getX(),
                floorY, absoluteCampfire.getZ(), plan.storehouseVariant(), profession.orElse(null));
        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(
                template.stream()
                        .map(TownBuildingTemplates.Placement::toPlan).toList(),
                absoluteCampfire.getX(), absoluteCampfire.getY(), absoluteCampfire.getZ());
        ConstructionSitePlan.PlannedBlock first = ordered.get(0);
        BlockPos interrupted = new BlockPos(first.x(), first.y(), first.z());
        BlockState expectedState = template.stream().filter(placement -> placement.x() == first.x()
                && placement.y() == first.y() && placement.z() == first.z()).findFirst().orElseThrow().state();
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        try {
            TownConstructionService.testForcePlacementInterruptionAt = interrupted;
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

            helper.assertTrue(helper.getLevel().getBlockState(interrupted).equals(expectedState)
                            && storage.isCellBlocked(helper.getLevel().dimension(), interrupted)
                            && store.balance(TownResourceCategory.STONE) == stoneBefore
                            && store.balance(TownResourceCategory.WOOD) == woodBefore,
                    "an explicit post-write quarantine must leave the landed block durably blocked with no debit");
            CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
            String interruptedKey = helper.getLevel().dimension().location() + "@" + interrupted.getX() + ","
                    + interrupted.getY() + "," + interrupted.getZ();
            helper.assertTrue(saved.getList("PlacedCells", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                            .noneMatch(cell -> cell.getString("Key").equals(interruptedKey)),
                    "the quarantined write must not fabricate a paid-progress record");

            TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
            TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
            CompoundTag afterRetry = reloaded.save(new CompoundTag(), helper.getLevel().registryAccess());
            helper.assertTrue(reloaded.isCellBlocked(helper.getLevel().dimension(), interrupted)
                            && helper.getLevel().getBlockState(interrupted).equals(expectedState)
                            && reloadedStore.balance(TownResourceCategory.STONE) == stoneBefore
                            && reloadedStore.balance(TownResourceCategory.WOOD) == woodBefore
                            && afterRetry.getList("PlacedCells", Tag.TAG_COMPOUND).stream().map(CompoundTag.class::cast)
                                    .noneMatch(cell -> cell.getString("Key").equals(interruptedKey)),
                    "a reload retry must preserve the explicit hold without charging or fabricating paid progress");
        } finally {
            TownConstructionService.testForcePlacementInterruptionAt = null;
        }
        helper.succeed();
    }
}
