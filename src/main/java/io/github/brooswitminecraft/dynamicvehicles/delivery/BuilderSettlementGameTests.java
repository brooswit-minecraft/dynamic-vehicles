package io.github.brooswitminecraft.dynamicvehicles.delivery;

import io.github.brooswitminecraft.dynamicvehicles.town.TownCampfireClaims;
import io.github.brooswitminecraft.dynamicvehicles.town.TownConstructionService;
import io.github.brooswitminecraft.dynamicvehicles.town.TownStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-388 review round 3, finding #1: the legacy chance-based "complete a free house"
 * mechanic has been retired entirely (see {@code BuilderSettlementService#attemptSettlement}).
 * These regressions replace the old house-building assertions: every trigger into this class must
 * now leave the campfire, the world, the Builder's employment, and the town resource store
 * completely unchanged, regardless of claim status or a guaranteed chance roll - no building ever
 * happens here any more.
 */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class BuilderSettlementGameTests {
    private static final BlockPos ANCHOR = new BlockPos(10, 2, 10);

    private static void prepareFixture(GameTestHelper helper) {
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++)
            for (int y = 0; y <= 12; y++) helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
    }

    /** Spawns a Builder with a recorded campfire anchor at an arbitrary (already-prepared) position. */
    private static Villager builderAt(GameTestHelper helper, BlockPos anchor) {
        helper.setBlock(anchor, Blocks.CAMPFIRE);
        Villager villager = helper.spawn(EntityType.VILLAGER, anchor.offset(0, 0, 1));
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(BuilderProfession.BUILDER.get()).setLevel(5));
        villager.setVillagerXp(250);
        villager.getPersistentData().putLong(BuilderSettlementService.CAMPFIRE_POS_TAG, helper.absolutePos(anchor).asLong());
        return villager;
    }

    /**
     * The donation trigger (guaranteed chance) must never place anything and never retire the
     * Builder for an UNCLAIMED campfire - the exact scenario the old mechanic used to complete a
     * free house for.
     */
    @GameTest(template = "settlement_test")
    public static void guaranteedDonationRollNeverSettlesAnUnclaimedCampfire(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos campfireAnchor = ANCHOR;
        Villager villager = builderAt(helper, campfireAnchor);
        var player = GameTestMockPlayers.create(helper);
        villager.setTradingPlayer(player);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.QUARTZ_BLOCK, 28), new ItemStack(Items.DIRT), 2, 30, 0.05F);
        villager.notifyTrade(offer);
        helper.assertTrue(villager.getPersistentData().getIntArray("dynamicvehicles.pendingBuilderDonations").length == 2,
                "completed-trade listener did not queue the donation");
        villager.setVillagerXp(villager.getVillagerXp() + offer.getXp());
        BlockPos absoluteCampfire = helper.absolutePos(campfireAnchor);
        helper.assertTrue(!TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "test setup sanity check: this campfire must start unclaimed");

        BuilderSettlementService.processPendingDonations(villager);

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE),
                "an unclaimed campfire must never be replaced by the retired legacy mechanic, even on a guaranteed roll");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the Builder must never retire via the retired legacy mechanic");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y < 4; y++) {
            BlockPos cell = campfireAnchor.offset(x, y, z);
            if (cell.equals(campfireAnchor)) continue;
            helper.assertTrue(helper.getBlockState(cell).isAir(),
                    "no block should have been placed anywhere near the campfire: " + cell);
        }
        helper.succeed();
    }

    /** Same guaranteed roll, but on a campfire already claimed as a town construction site - must
     *  still be a complete no-op (this is the case the round-2 fix already covered; retiring the
     *  mechanic entirely must not have regressed it). */
    @GameTest(template = "settlement_test")
    public static void guaranteedDonationRollNeverSettlesAClaimedCampfire(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos campfireAnchor = ANCHOR;
        Villager villager = builderAt(helper, campfireAnchor);
        BlockPos absoluteCampfire = helper.absolutePos(campfireAnchor);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        helper.assertTrue(TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "test setup sanity check: the campfire should read as town-claimed before the roll");

        BuilderSettlementService.attemptDonationSettlement(villager, 5);

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE),
                "a town-claimed campfire must never be replaced");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "a deferred settlement must not retire the Builder");
        helper.succeed();
    }

    /** The passive trigger (guaranteed chance) must be an equally complete no-op. */
    @GameTest(template = "settlement_test")
    public static void guaranteedPassiveRollNeverSettlesAnyCampfire(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos campfireAnchor = ANCHOR;
        Villager villager = builderAt(helper, campfireAnchor);
        villager.setVillagerData(villager.getVillagerData().setLevel(5));

        BuilderSettlementService.attemptPassiveSettlement(villager);

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE),
                "the passive trigger must never replace a campfire via the retired legacy mechanic");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the passive trigger must never retire the Builder");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 3, finding #1 (combined-listener regression, as the review asked
     * for): an unclaimed campfire with a completely empty town resource store must see NO change
     * to the campfire, the world, or the balance - even when the legacy donation chance roll is
     * guaranteed to succeed. Both mechanics act on the same campfire and both must do nothing.
     */
    @GameTest(template = "settlement_test")
    public static void unclaimedCampfireAndEmptyTownResourcesProduceNoChangeEvenWhenTheLegacyChanceSucceeds(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos campfireAnchor = ANCHOR;
        Villager villager = builderAt(helper, campfireAnchor);
        BlockPos absoluteCampfire = helper.absolutePos(campfireAnchor);
        helper.assertTrue(!TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "test setup sanity check: this campfire must start unclaimed");

        TownStorage storage = new TownStorage(); // zero resources in every category by construction
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE),
                "an unclaimed campfire with no resources must never trigger town construction");

        BuilderSettlementService.attemptDonationSettlement(villager, 5); // guaranteed chance

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE),
                "the campfire must remain completely unchanged - the retired legacy mechanic must never settle it");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "the Builder must remain employed - the retired legacy mechanic must never retire it");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y < 4; y++) {
            BlockPos cell = campfireAnchor.offset(x, y, z);
            if (cell.equals(campfireAnchor)) continue;
            helper.assertTrue(helper.getBlockState(cell).isAir(), "the world must remain unchanged: " + cell);
        }
        helper.succeed();
    }
}
