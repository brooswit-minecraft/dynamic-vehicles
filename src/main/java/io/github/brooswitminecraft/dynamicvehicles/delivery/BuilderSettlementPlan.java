package io.github.brooswitminecraft.dynamicvehicles.delivery;

/** Minecraft-free Builder donation and placement decisions. */
public final class BuilderSettlementPlan {
    private BuilderSettlementPlan() {}

    /** The specified fractional settlement chance; level one can never settle. */
    public static double settlementChance(int villagerLevel, int tradeLevel) {
        return ((villagerLevel - 1) / 4.0D) * ((tradeLevel - 1) / 4.0D);
    }

    /** A campfire is removed only after the replacement structure is known safe. */
    public static boolean shouldReplaceCampfire(boolean placementIsSafe, boolean rollSucceeded) {
        return placementIsSafe && rollSucceeded;
    }

    /** The bounded marker prevents a villager from repeatedly founding at one campfire. */
    public static boolean shouldCreateCampfire(boolean unemployed, boolean bedless, boolean darkNight,
            boolean skyVisible, boolean campfireAlreadyRecorded) {
        return unemployed && bedless && darkNight && skyVisible && !campfireAlreadyRecorded;
    }
}
