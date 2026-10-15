package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * Small, Minecraft-free decision boundary for settlement arrival handling.
 * Keeping the decision here makes the important retry and safety choices
 * testable without bootstrapping a world.
 */
public final class SettlementFoundationPlan {
    private SettlementFoundationPlan() {
    }

    public enum Decision {
        FOUND,
        EXISTING_VILLAGE,
        ALREADY_FOUNDED
    }

    public static Decision decide(boolean existingVillage, boolean alreadyFounded) {
        if (existingVillage) {
            return Decision.EXISTING_VILLAGE;
        }
        if (alreadyFounded) {
            return Decision.ALREADY_FOUNDED;
        }
        return Decision.FOUND;
    }

    /**
     * A Trading Post begins with eight residents: seven general villagers and one
     * Dispatcher.  The count is deliberately explicit rather than random so a
     * destination has the same population after every retry or restart.
     */
    public static int villagerCount(String settlementKey) {
        return 8;
    }

    /** The bounded pavilion footprint is seven blocks square and five blocks high. */
    public static final int TRADING_POST_RADIUS = 3;
    public static final int TRADING_POST_HEIGHT = 5;

    /** How far below the floor a column is filled while looking for solid ground. */
    public static final int MAX_FOUNDATION_DEPTH = 48;
}
