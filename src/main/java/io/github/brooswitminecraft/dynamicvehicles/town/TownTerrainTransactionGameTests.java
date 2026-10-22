package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
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
}
