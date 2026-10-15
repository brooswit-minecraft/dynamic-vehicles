package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BedBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Integration tests use real blocks, plain saved-offer equivalents, and the registered completed-trade event. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class BuilderSettlementGameTests {
    private static final BlockPos ANCHOR = new BlockPos(10, 2, 10);

    private static void prepareFixture(GameTestHelper helper) {
        // Establish an explicit surface and clear the GameTest barrier roof before heightmap checks.
        for (int x = 0; x < 20; x++) for (int z = 0; z < 20; z++)
            for (int y = 0; y <= 12; y++) helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
    }

    private static Villager builder(GameTestHelper helper) {
        prepareFixture(helper);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        Villager villager = helper.spawn(EntityType.VILLAGER, ANCHOR.offset(0, 0, 1));
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(BuilderProfession.BUILDER.get()).setLevel(5));
        villager.setVillagerXp(250);
        villager.getPersistentData().putLong(BuilderSettlementService.CAMPFIRE_POS_TAG, helper.absolutePos(ANCHOR).asLong());
        return villager;
    }

    @GameTest(template = "settlement_test")
    public static void completedDonationBuildsEnclosedHouseAndRetiresTradedBuilder(GameTestHelper helper) {
        Villager villager = builder(helper);
        var player = GameTestMockPlayers.create(helper);
        villager.setTradingPlayer(player);
        // A plain offer has no custom take() subclass, just like one restored from a saved world.
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.QUARTZ_BLOCK, 28), new ItemStack(Items.DIRT), 2, 30, 0.05F);
        villager.notifyTrade(offer);
        helper.assertTrue(villager.getPersistentData().getIntArray("dynamicvehicles.pendingBuilderDonations").length == 2,
                "completed-trade listener did not queue the donation");
        villager.setVillagerXp(villager.getVillagerXp() + offer.getXp());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos c = helper.absolutePos(ANCHOR.relative(d, 4));
            for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) for(int y=0;y<4;y++)
                helper.assertTrue(helper.getLevel().isEmptyBlock(c.offset(x,y,z)), "fixture blocks house: " + c.offset(x,y,z) + " " + helper.getLevel().getBlockState(c.offset(x,y,z)));
        }
        BuilderSettlementService.processPendingDonations(villager);
        helper.assertTrue(!helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "guaranteed donation did not settle: anchor=" + helper.absolutePos(ANCHOR) + " support=" + helper.getBlockState(ANCHOR.offset(4, -1, 0)) + " data=" + villager.getPersistentData());
        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE, "Builder did not retire");
        helper.assertTrue(villager.getVillagerData().getLevel() == 1 && villager.getVillagerXp() == 0, "traded Builder kept progression");
        helper.assertTrue(villager.getOffers().isEmpty(), "retired Builder kept donation offers");
        BlockPos center = null;
        Direction facing = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = ANCHOR.relative(direction, 4);
            if (helper.getBlockState(candidate.above()).getBlock() instanceof BedBlock) {
                center = candidate;
                facing = direction;
            }
        }
        helper.assertTrue(center != null, "house has no bed head");
        int doors = 0;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            helper.assertTrue(!helper.getBlockState(center.offset(x, 0, z)).isAir(), "missing floor");
            helper.assertTrue(!helper.getBlockState(center.offset(x, 3, z)).isAir(), "missing roof");
            if (Math.abs(x) == 2 || Math.abs(z) == 2) for (int y = 1; y <= 2; y++) {
                var state = helper.getBlockState(center.offset(x, y, z));
                helper.assertTrue(!state.isAir(), "wall gap");
                if (state.getBlock() instanceof DoorBlock) doors++;
            }
        }
        helper.assertTrue(doors == 2, "door needs both halves");
        helper.assertTrue(helper.getBlockState(center.relative(facing.getOpposite()).above()).getBlock() instanceof BedBlock, "bed foot missing");
        helper.assertTrue(helper.getBlockState(center.relative(facing.getClockWise()).above()).is(Blocks.BARREL), "barrel missing");
        BuilderSettlementService.attemptDonationSettlement(villager, 5);
        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE, "duplicate trigger changed retirement");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollStillPreservesBlockedSite(GameTestHelper helper) {
        Villager villager = builder(helper);
        for (Direction direction : Direction.Plane.HORIZONTAL) helper.setBlock(ANCHOR.relative(direction, 4), Blocks.CHEST);
        BuilderSettlementService.attemptDonationSettlement(villager, 5);
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE), "failed placement consumed anchor");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(), "failed placement retired Builder");
        for (Direction direction : Direction.Plane.HORIZONTAL) helper.assertTrue(helper.getBlockState(ANCHOR.relative(direction, 4)).is(Blocks.CHEST), "overwrote existing build");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void terrainPreparationLevelsNaturalGroundAndPreservesOutsideFootprint(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos center = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR.offset(-2, 0, -2), Blocks.DIRT);
        helper.setBlock(ANCHOR.offset(-2, 1, -2), Blocks.SHORT_GRASS);
        helper.setBlock(ANCHOR.offset(2, -1, 2), Blocks.AIR);
        helper.setBlock(ANCHOR.offset(4, 0, 0), Blocks.CHEST);
        SettlementFounder.placeTradingPost(helper.getLevel(), center);
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(2, -1, 2)).is(Blocks.DIRT), "depression was not filled: " + helper.getBlockState(ANCHOR.offset(2,-1,2)));
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(-2, 1, -2)).isAir(), "grass was not cleared");
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(4, 0, 0)).is(Blocks.CHEST), "changed outside footprint");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void tradingPostForcesItsWayOverBuildsAndOpenAir(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos center = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR.offset(2, 0, 2), Blocks.CHEST);
        helper.setBlock(ANCHOR.offset(-2, -1, -2), Blocks.AIR);
        helper.setBlock(ANCHOR.offset(-2, -2, -2), Blocks.AIR);
        SettlementFounder.placeTradingPost(helper.getLevel(), center);
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(2, 0, 2)).is(Blocks.SPRUCE_PLANKS), "build was not replaced");
        helper.assertTrue(!helper.getBlockState(ANCHOR.offset(-2, -1, -2)).isAir(), "floor left unsupported");
        helper.succeed();
    }
}
