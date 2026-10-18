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
}
