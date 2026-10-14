package io.github.brooswitminecraft.dynamicvehicles.delivery;

/** Minecraft-free Builder donation and placement decisions. */
public final class BuilderSettlementPlan {
    public static final int HOUSE_FOOTPRINT_WIDTH = 5;
    public static final int HOUSE_HEIGHT = 4;
    public enum BuildingVariant { OAK_HUT, SPRUCE_HUT }
    private BuilderSettlementPlan() {}

    /** The specified fractional settlement chance; level one can never settle. */
    public static double settlementChance(int villagerLevel, int tradeLevel) {
        return ((villagerLevel - 1) / 4.0D) * ((tradeLevel - 1) / 4.0D);
    }

    /** A campfire is removed only after the replacement structure is known safe. */
    public static boolean shouldReplaceCampfire(boolean placementIsSafe, boolean rollSucceeded) {
        return placementIsSafe && rollSucceeded;
    }

    /** A complete settlement also retires the Builder; failures leave it unchanged. */
    public static boolean shouldResetBuilder(boolean placementIsSafe, boolean rollSucceeded) {
        return shouldReplaceCampfire(placementIsSafe, rollSucceeded);
    }

    /** Maps a supplied bounded random index to a project-owned building variant. */
    public static BuildingVariant selectBuildingVariant(int randomIndex) {
        return BuildingVariant.values()[Math.floorMod(randomIndex, BuildingVariant.values().length)];
    }

    /** The bounded marker prevents a villager from repeatedly founding at one campfire. */
    public static boolean shouldCreateCampfire(boolean unemployed, boolean bedless, boolean darkNight,
            boolean skyVisible, boolean campfireAlreadyRecorded) {
        return unemployed && bedless && darkNight && skyVisible && !campfireAlreadyRecorded;
    }
}
