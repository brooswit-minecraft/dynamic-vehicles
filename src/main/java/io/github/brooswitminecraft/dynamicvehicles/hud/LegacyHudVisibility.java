package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * Keeps a legacy corner HUD opt-in while a shared mission waypoint marker remains the default
 * presentation. MINECRAFT-411: moved here (unchanged) from {@code delivery.ContractHudVisibility}
 * so Pathfinder's own legacy corner HUD can be gated the same way, by the same rule, instead of a
 * duplicated one-off check.
 */
public final class LegacyHudVisibility {
    private LegacyHudVisibility() {
    }

    public static boolean shouldRender(boolean legacyOverlayEnabled, boolean missionActive) {
        return legacyOverlayEnabled && missionActive;
    }
}
