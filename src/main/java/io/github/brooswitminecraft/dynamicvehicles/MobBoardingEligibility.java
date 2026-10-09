package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The actual mob-auto-boarding eligibility filter (MINECRAFT-211), pure and free of every Minecraft type
 * -- including {@code EntityType}, whose own constants (e.g. {@code EntityType.COW}) need the game's
 * registry bootstrap just to load the class that references them, which this test environment does not
 * have. Kept in its own class, separate from {@link MobBoardingRules} (which holds those registry-backed
 * constants), purely so a test can call this logic without loading anything that touches the registry.
 */
public final class MobBoardingEligibility {

    private MobBoardingEligibility() {}

    /**
     * Whether a mob may automatically board a vehicle right now: both its type and the vehicle must be on
     * the auto-boarding allow-list, and the mob must be an adult, not leashed, and not hostile.
     */
    public static boolean isEligible(boolean isAutoBoardingMobType, boolean isAutoBoardingVehicle,
            boolean isBaby, boolean isLeashed, boolean isHostile) {
        return isAutoBoardingMobType && isAutoBoardingVehicle && !isBaby && !isLeashed && !isHostile;
    }
}
