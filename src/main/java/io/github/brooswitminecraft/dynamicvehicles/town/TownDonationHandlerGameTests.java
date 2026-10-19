package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-388 review finding #5: credit must scale with the quantity actually consumed by the
 * REGISTERED trade event, not a flat per-trade amount - exercising {@link TownDonationHandler#onTrade}
 * against a real {@link TradeWithVillagerEvent}, not just {@code TownDonationConversion#creditFor}
 * in isolation (which the review explicitly called out as insufficient coverage).
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownDonationHandlerGameTests {
    private TownDonationHandlerGameTests() {}

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

    private static Villager employedVillagerAt(GameTestHelper helper, BlockPos absolutePos) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absolutePos, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        return villager;
    }

    /**
     * Mirrors {@code BuilderTradeHandler}'s own registered 16-cobblestone offer: the documented
     * rate (2 stone per single item) must be multiplied by the 16 actually consumed, crediting 32,
     * never a flat 2.
     */
    @GameTest(template = "settlement_test")
    public static void creditScalesWithTheActualQuantityConsumedByTheTrade(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = employedVillagerAt(helper, absoluteAnchor);
        ServerPlayer player = GameTestMockPlayers.create(helper);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        int before = storage.storeFor(key).balance(TownResourceCategory.STONE);

        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.COBBLESTONE, 16), new ItemStack(Items.STICK), 6, 5, 0.05F);
        TradeWithVillagerEvent event = new TradeWithVillagerEvent(player, offer, villager);

        TownDonationHandler.onTrade(event);

        int after = storage.storeFor(key).balance(TownResourceCategory.STONE);
        int expectedCredit = TownConstants.DONATION_COBBLESTONE_STONE_CREDIT * 16;
        helper.assertTrue(after - before == expectedCredit,
                "expected a credit of " + expectedCredit + " (rate-per-item x quantity consumed), but balance changed by "
                        + (after - before));
        helper.succeed();
    }

    /** A different consumed quantity for the SAME item must scale the credit proportionally -
     *  proves this is genuinely reading the consumed stack size, not a coincidental match to one
     *  hard-coded trade's own cost count. */
    @GameTest(template = "settlement_test")
    public static void creditScalesDifferentlyForADifferentConsumedQuantity(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = employedVillagerAt(helper, absoluteAnchor);
        ServerPlayer player = GameTestMockPlayers.create(helper);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        int before = storage.storeFor(key).balance(TownResourceCategory.WOOD);

        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.OAK_LOG, 8), new ItemStack(Items.STICK), 8, 1, 0.05F);
        TradeWithVillagerEvent event = new TradeWithVillagerEvent(player, offer, villager);

        TownDonationHandler.onTrade(event);

        int after = storage.storeFor(key).balance(TownResourceCategory.WOOD);
        int expectedCredit = TownConstants.DONATION_OAK_LOG_WOOD_CREDIT * 8;
        helper.assertTrue(after - before == expectedCredit,
                "expected a credit of " + expectedCredit + " for 8 consumed oak logs, but balance changed by " + (after - before));
        helper.succeed();
    }
}
