package io.github.brooswitminecraft.dynamicvehicles.town;

/**
 * Minecraft-free bootstrap decision for a town region's designated centre, mirroring
 * {@code delivery.SettlementFoundationPlan}'s decision shape exactly (reused rather than
 * reinvented, per MINECRAFT-388): when the chunk containing the town centre generates, a village
 * already present there wins unconditionally; otherwise the one-way "already founded" claim
 * guarantees the bootstrap campfire+villager are placed at most once, surviving reload/retry.
 */
public final class TownFoundationPlan {
    private TownFoundationPlan() {}

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
