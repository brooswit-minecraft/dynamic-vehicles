package io.github.brooswitminecraft.dynamicvehicles.delivery;

/** Keeps the legacy overlay opt-in while the waypoint marker remains the default presentation. */
public final class ContractHudVisibility {
    private ContractHudVisibility() {}

    public static boolean shouldRender(boolean legacyOverlayEnabled, boolean contractActive) {
        return legacyOverlayEnabled && contractActive;
    }
}
