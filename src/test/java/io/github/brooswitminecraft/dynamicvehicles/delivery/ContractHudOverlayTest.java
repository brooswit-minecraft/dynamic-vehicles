package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractHudOverlayTest {
    @Test
    void legacyOverlayIsHiddenByDefaultForAnActiveContract() {
        assertFalse(ContractHudOverlay.shouldRender(false, true));
    }

    @Test
    void legacyOverlayReturnsWhenEnabledForAnActiveContract() {
        assertTrue(ContractHudOverlay.shouldRender(true, true));
    }

    @Test
    void legacyOverlayNeverRendersWithoutAnActiveContract() {
        assertFalse(ContractHudOverlay.shouldRender(true, false));
    }
}
