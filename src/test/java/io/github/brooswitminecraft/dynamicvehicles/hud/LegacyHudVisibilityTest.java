package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-411: the {@code ContractHudVisibility} rule previously covered only by
 * {@code delivery.ContractHudOverlayTest} moved here (unchanged) alongside the shared class it
 * tests, now also used by Pathfinder's own legacy corner HUD toggle.
 */
class LegacyHudVisibilityTest {
    @Test
    void legacyOverlayIsHiddenByDefaultForAnActiveMission() {
        assertFalse(LegacyHudVisibility.shouldRender(false, true));
    }

    @Test
    void legacyOverlayRendersWhenEnabledForAnActiveMission() {
        assertTrue(LegacyHudVisibility.shouldRender(true, true));
    }

    @Test
    void legacyOverlayNeverRendersWithoutAnActiveMission() {
        assertFalse(LegacyHudVisibility.shouldRender(true, false));
    }
}
