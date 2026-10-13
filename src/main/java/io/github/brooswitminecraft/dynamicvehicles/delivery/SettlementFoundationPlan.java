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

    /** A stable 1-12 count derived only from the persisted destination identity. */
    public static int villagerCount(String settlementKey) {
        return Math.floorMod(settlementKey.hashCode(), 12) + 1;
    }
}
