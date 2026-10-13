package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.List;

/** Concrete Builder donations, kept independently inspectable from registry callbacks. */
public final class BuilderDonationTrades {
    private BuilderDonationTrades() {}

    public record Donation(String material, int cost, String output, int maxUses, int villagerXp) {}

    public static final List<Donation> LEVEL_ONE = List.of(new Donation("oak_log", 8, "stick", 8, 1));
    public static final List<Donation> LEVEL_TWO = List.of(new Donation("cobblestone", 16, "dirt", 6, 5));
    public static final List<Donation> LEVEL_THREE = List.of(new Donation("sandstone", 20, "oak_leaves", 4, 10));
    public static final List<Donation> LEVEL_FOUR = List.of(new Donation("bricks", 24, "stick", 3, 15));
    public static final List<Donation> LEVEL_FIVE = List.of(new Donation("quartz_block", 28, "dirt", 2, 30));
}
