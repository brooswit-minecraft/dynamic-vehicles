package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.minecraft.world.entity.npc.Villager;

/** Registers the documented, level-scaled Builder material donations. */
public final class BuilderTradeHandler {
    private BuilderTradeHandler() {}

    /** The completed-trade event also handles offers restored from NBT after a reload. */
    public static void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager() instanceof Villager villager)
                || villager.getVillagerData().getProfession() != BuilderProfession.BUILDER.get()) return;
        var cost = event.getMerchantOffer().getBaseCostA();
        int tier = cost.is(Items.OAK_LOG) ? 1 : cost.is(Items.COBBLESTONE) ? 2
                : cost.is(Items.SANDSTONE) ? 3 : cost.is(Items.BRICK) ? 4
                : cost.is(Items.QUARTZ_BLOCK) ? 5 : 0;
        if (tier > 0) BuilderSettlementService.queueDonationSettlement(villager, tier);
    }

    @SubscribeEvent
    public static void registerTrades(VillagerTradesEvent event) {
        if (event.getType() != BuilderProfession.BUILDER.get()) return;
        add(event, 1, Items.OAK_LOG, 8, Items.STICK, 8, 1);
        add(event, 2, Items.COBBLESTONE, 16, Items.DIRT, 6, 5);
        add(event, 3, Items.SANDSTONE, 20, Items.OAK_LEAVES, 4, 10);
        add(event, 4, Items.BRICK, 24, Items.STICK, 3, 15);
        add(event, 5, Items.QUARTZ_BLOCK, 28, Items.DIRT, 2, 30);
    }

    private static void add(VillagerTradesEvent event, int level, net.minecraft.world.item.Item cost,
            int costCount, net.minecraft.world.item.Item output, int maxUses, int xp) {
        event.getTrades().get(level).add((entity, random) -> new MerchantOffer(
                new ItemCost(cost, costCount), new ItemStack(output), maxUses, xp, 0.05F));
    }
}
