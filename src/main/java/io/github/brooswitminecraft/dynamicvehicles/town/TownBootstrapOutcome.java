package io.github.brooswitminecraft.dynamicvehicles.town;

/**
 * MINECRAFT-388 review round 2, finding #6: the pure success/cleanup decision for
 * {@code TownFounder#tryFoundAt}'s campfire+villager pair, deliberately kept Minecraft-free (same
 * shape as {@link TownFoundationPlan}) so it is directly unit-testable without a real level.
 * {@code markFounded} is {@code true} only when BOTH placements are confirmed; {@code
 * cleanupCampfire} is {@code true} exactly in the one partial-failure shape that would otherwise
 * strand an unclaimed, un-retryable orphan campfire (campfire placed, villager spawn failed).
 */
public final class TownBootstrapOutcome {
    private TownBootstrapOutcome() {}

    public record PlacementOutcome(boolean markFounded, boolean cleanupCampfire) {}

    public static PlacementOutcome evaluatePlacement(boolean campfirePlaced, boolean villagerSpawned) {
        if (!campfirePlaced) {
            return new PlacementOutcome(false, false);
        }
        if (!villagerSpawned) {
            return new PlacementOutcome(false, true);
        }
        return new PlacementOutcome(true, false);
    }
}
