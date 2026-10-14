package io.github.brooswitminecraft.dynamicvehicles.delivery;

/** Minecraft-free Builder donation and placement decisions. */
public final class BuilderSettlementPlan {
    public static final int HOUSE_FOOTPRINT_WIDTH = 5;
    public static final int HOUSE_HEIGHT = 4;
    /** Ten seconds at Minecraft's normal 20 ticks per second. */
    static final long PASSIVE_CHECK_INTERVAL_TICKS = 200L;
    public enum BuildingVariant { OAK_HUT, SPRUCE_HUT }
    private BuilderSettlementPlan() {}

    /** Builder donations retain their original 0%, 2.5%, 5%, 7.5%, and 10% settlement roll. */
    public static double donationSettlementChance(int villagerLevel) {
        return Math.max(0, Math.min(4, villagerLevel - 1)) / 40.0D;
    }

    /**
     * Passive checks run every ten seconds, so their rates are deliberately much lower than donation rates:
     * 0%, .02%, .04%, .06%, and .08% for levels one through five.  At the maximum level, 120 checks in
     * a Minecraft day yield roughly a 9.2% chance of at least one settlement attempt, rather than 10% per check.
     */
    public static double passiveSettlementChance(int villagerLevel) {
        return Math.max(0, Math.min(4, villagerLevel - 1)) / 5000.0D;
    }

    /** The passive clock is server game time: one eligible check per ten seconds, never a catch-up loop. */
    public static boolean shouldRunPassiveCheck(boolean hasLastCheck, long lastCheckGameTime, long gameTime) {
        return !hasLastCheck || (gameTime >= lastCheckGameTime
                && gameTime - lastCheckGameTime >= PASSIVE_CHECK_INTERVAL_TICKS);
    }

    /** Both triggers share this final commit gate; after the first success the consumed anchor rejects a duplicate. */
    public static boolean shouldCompleteSettlement(boolean anchorPresent, boolean rollSucceeded, boolean placementIsSafe) {
        return anchorPresent && rollSucceeded && placementIsSafe;
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
