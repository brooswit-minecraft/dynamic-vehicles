package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;

/**
 * Generalizes {@code delivery.BuilderTradeHandler}'s "a completed Builder donation trade" hook from
 * "roll a settlement chance" to "credit the town's own abstract resource store" — the same event,
 * the same donation items, but landing on {@link TownResourceStore} instead of
 * {@code BuilderSettlementService}'s narrower chance mechanic, per MINECRAFT-388's "generalized to a
 * town resource store rather than the existing narrower chance-of-settlement mechanic".
 *
 * <p>Donations still flow through trading with an employed villager (there is no separate
 * donation-box block in this codebase to reuse — {@code BuilderDonationTrades} is itself a trade
 * table), which is the explicit, documented reuse decision for "donation boxes" in the PR
 * description rather than a new block+menu built from scratch.
 */
public final class TownDonationHandler {
    private TownDonationHandler() {}

    public static void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getAbstractVillager() instanceof Villager villager) || !(villager.level() instanceof ServerLevel level)) {
            return;
        }
        if (villager.getVillagerData().getProfession() == net.minecraft.world.entity.npc.VillagerProfession.NONE) {
            return;
        }
        MerchantOffer offer = event.getMerchantOffer();
        String costItemId = idFor(offer.getBaseCostA());
        TownDonationConversion.Credit credit = costItemId == null ? null : TownDonationConversion.creditFor(costItemId);
        if (credit == null) {
            return;
        }

        // TownConstants documents these rates as credit PER SINGLE donated item; getCostA() (as
        // opposed to getBaseCostA()) returns the stack actually consumed by this completed trade,
        // including any demand/price-multiplier adjustment, so a 16-cobblestone offer must credit
        // 16x the per-item rate, not a flat per-trade amount.
        int consumedCount = offer.getCostA().getCount();
        if (consumedCount <= 0) {
            return;
        }

        RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
        TownStorage storage = TownStorage.of(level.getServer());
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), region));
        store.credit(credit.category(), credit.amount() * consumedCount);
        storage.setDirty();
    }

    /**
     * Resolves the offer's cost {@link ItemStack} to the same registry-id strings
     * {@link TownDonationConversion} is keyed by, via {@code ItemStack#is(Item)} checks against
     * every item that table recognizes.
     */
    private static String idFor(ItemStack cost) {
        if (cost.is(Items.DIRT)) return "minecraft:dirt";
        if (cost.is(Items.COBBLESTONE)) return "minecraft:cobblestone";
        if (cost.is(Items.STONE_BRICKS)) return "minecraft:stone_bricks";
        if (cost.is(Items.SMOOTH_STONE)) return "minecraft:smooth_stone";
        if (cost.is(Items.CHISELED_STONE_BRICKS)) return "minecraft:chiseled_stone_bricks";
        if (cost.is(Items.OAK_LOG)) return "minecraft:oak_log";
        if (cost.is(Items.OAK_PLANKS)) return "minecraft:oak_planks";
        if (cost.is(Items.WHEAT)) return "minecraft:wheat";
        if (cost.is(Items.BREAD)) return "minecraft:bread";
        if (cost.is(Items.CARROT)) return "minecraft:carrot";
        return null;
    }
}
