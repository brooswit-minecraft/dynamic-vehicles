package io.github.brooswitminecraft.dynamicvehicles.hud;

import java.util.Locale;

/**
 * Formats a server-tick countdown as {@code m:ss}, clamped at zero. Minecraft-free so it is
 * directly unit-testable.
 *
 * <p>MINECRAFT-411: extracted (unchanged) from three identical private copies in
 * {@code delivery.ContractHudOverlay}, {@code pathfinder.PathfinderHudOverlay} and the new
 * {@code pathfinder.PathfinderWaypointOverlay}, so a mission's remaining-time display is computed
 * one way rather than three independently-maintained ones.
 */
public final class CountdownFormat {
    private CountdownFormat() {
    }

    public static String format(long remainingTicks) {
        if (remainingTicks <= 0) {
            return "0:00";
        }
        long totalSeconds = remainingTicks / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
