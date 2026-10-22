package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    /** Terrain prep must reject a real block entity before any write, preserving its inventory/NBT
     * instead of trying a state-only rollback after a later injected failure.  The production
     * guard is state-agnostic, so it applies equally to a modded waterlogged block entity. */
    @GameTest(template = "settlement_test")
    public static void blockEntityPreflightPreservesInventoryAndStopsBuild(GameTestHelper helper) {
        prepareFlatGround(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        BlockPos containerCell = ANCHOR.offset(1, 0, 1);
        BlockPos absoluteContainerCell = helper.absolutePos(containerCell);
        helper.setBlock(containerCell, Blocks.CHEST);
        ChestBlockEntity inventory = (ChestBlockEntity) helper.getLevel().getBlockEntity(absoluteContainerCell);
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 7));
        spawnClaimant(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);
        int woodBefore = store.balance(TownResourceCategory.WOOD);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        TownConstructionService.testForceTerrainMutationFailureAt = helper.absolutePos(ANCHOR.offset(1, 1, 1));
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        TownConstructionService.testForceTerrainMutationFailureAt = null;

        helper.assertTrue(helper.getBlockState(containerCell).is(Blocks.CHEST),
                "a block-entity cell must not be cleared by terrain preparation");
        helper.assertTrue(helper.getLevel().getBlockEntity(absoluteContainerCell) instanceof ChestBlockEntity chest
                        && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7,
                "the rejected cell's identifiable block-entity inventory/NBT must remain intact");
        helper.assertTrue(store.balance(TownResourceCategory.WOOD) == woodBefore
                        && store.balance(TownResourceCategory.STONE) == stoneBefore
                        && helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "rejecting a block entity must leave resources and building progress untouched");
        helper.succeed();
    }
}
