package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * Formats a server-authoritative "reached/total" checkpoint count. Minecraft-free so it is
 * directly unit-testable, and shared between {@code pathfinder.PathfinderHudOverlay} (legacy) and
 * {@code pathfinder.PathfinderWaypointOverlay} so a checkpoint-advance transition reads the same
 * way in both.
 */
public final class ProgressFormat {
    private ProgressFormat() {
    }

    public static String format(int reachedCount, int totalCheckpoints) {
        return reachedCount + "/" + totalCheckpoints;
    }
}
