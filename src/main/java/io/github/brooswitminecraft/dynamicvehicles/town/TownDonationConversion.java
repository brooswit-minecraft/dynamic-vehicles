package io.github.brooswitminecraft.dynamicvehicles.town;

import java.util.Map;

/**
 * Generalizes {@code delivery.BuilderDonationTrades}' "donation converts item -> settlement roll"
 * pattern into "donation converts item -> town resource category credit". Kept Minecraft-free
 * (keyed by registry id string, not {@code Item}) so the conversion table itself is unit-testable
 * without a server; the donation-box block entity looks up the actual {@code Item}'s id string
 * against this table.
 */
public final class TownDonationConversion {
    private TownDonationConversion() {}

    public record Credit(TownResourceCategory category, int amount) {}

    private static final Map<String, Credit> TABLE = Map.ofEntries(
            Map.entry("minecraft:dirt", new Credit(TownResourceCategory.STONE, TownConstants.DONATION_DIRT_STONE_CREDIT)),
            Map.entry("minecraft:cobblestone", new Credit(TownResourceCategory.STONE, TownConstants.DONATION_COBBLESTONE_STONE_CREDIT)),
            Map.entry("minecraft:stone_bricks", new Credit(TownResourceCategory.STONE, TownConstants.DONATION_STONE_BRICKS_STONE_CREDIT)),
            Map.entry("minecraft:smooth_stone", new Credit(TownResourceCategory.STONE, TownConstants.DONATION_SMOOTH_STONE_STONE_CREDIT)),
            Map.entry("minecraft:chiseled_stone_bricks", new Credit(TownResourceCategory.STONE, TownConstants.DONATION_CHISELED_STONE_BRICKS_STONE_CREDIT)),
            Map.entry("minecraft:oak_log", new Credit(TownResourceCategory.WOOD, TownConstants.DONATION_OAK_LOG_WOOD_CREDIT)),
            Map.entry("minecraft:oak_planks", new Credit(TownResourceCategory.WOOD, TownConstants.DONATION_OAK_PLANKS_WOOD_CREDIT)),
            Map.entry("minecraft:wheat", new Credit(TownResourceCategory.FOOD, TownConstants.DONATION_WHEAT_FOOD_CREDIT)),
            Map.entry("minecraft:bread", new Credit(TownResourceCategory.FOOD, TownConstants.DONATION_BREAD_FOOD_CREDIT)),
            Map.entry("minecraft:carrot", new Credit(TownResourceCategory.FOOD, TownConstants.DONATION_CARROT_FOOD_CREDIT))
    );

    /** {@code null} when {@code itemId} is not a recognized donation (the donation box should reject it). */
    public static Credit creditFor(String itemId) {
        return TABLE.get(itemId);
    }

    public static boolean isDonatable(String itemId) {
        return TABLE.containsKey(itemId);
    }
}
