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
        ALREADY_FOUNDED,
        UNSAFE_SITE
    }

    public static Decision decide(boolean existingVillage, boolean alreadyFounded, boolean boardAndBellSpaceClear) {
        if (existingVillage) {
            return Decision.EXISTING_VILLAGE;
        }
        if (alreadyFounded) {
            return Decision.ALREADY_FOUNDED;
        }
        return boardAndBellSpaceClear ? Decision.FOUND : Decision.UNSAFE_SITE;
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

    /** No column is adjusted by more than two blocks during automatic preparation. */
    public static final int MAX_TERRAIN_ADJUSTMENT = 2;

    public static boolean canLevelColumn(int floorY, int terrainY) {
        return Math.abs(terrainY - floorY) <= MAX_TERRAIN_ADJUSTMENT;
    }

    public static int fillBlocks(int floorY, int terrainY) {
        return Math.max(0, floorY - terrainY);
    }

    public static int clearBlocks(int floorY, int terrainY) {
        return Math.max(0, terrainY - floorY);
    }
}
