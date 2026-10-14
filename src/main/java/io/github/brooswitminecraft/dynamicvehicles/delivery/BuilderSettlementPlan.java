package io.github.brooswitminecraft.dynamicvehicles.delivery;

/** Minecraft-free Builder donation and placement decisions. */
public final class BuilderSettlementPlan {
    public static final int HOUSE_FOOTPRINT_WIDTH = 5;
    public static final int HOUSE_HEIGHT = 4;
    public enum BuildingVariant { OAK_HUT, SPRUCE_HUT }
    private BuilderSettlementPlan() {}

    /**
     * Builder's once-per-noon settlement roll: 0%, 2.5%, 5%, 7.5%, and 10%
     * for levels one through five.  The calculation deliberately stays fractional.
     */
    public static double settlementChance(int villagerLevel) {
        return Math.max(0, Math.min(4, villagerLevel - 1)) / 40.0D;
    }

    /** Vanilla days are 24000 ticks and noon is tick 6000. */
    public static boolean isNoon(long dayTime) {
        return Math.floorMod(dayTime, 24000L) == 6000L;
    }

    /** A persisted day marker makes a noon pass retry-safe across tick and save boundaries. */
    public static boolean shouldRunForDay(long lastProcessedDay, long currentDay) {
        return lastProcessedDay != currentDay;
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
