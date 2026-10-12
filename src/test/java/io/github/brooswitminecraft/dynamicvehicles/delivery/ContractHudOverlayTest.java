package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractHudOverlayTest {
    @Test
    void legacyOverlayIsHiddenByDefaultForAnActiveContract() {
        assertFalse(ContractHudVisibility.shouldRender(false, true));
    }

    @Test
    void legacyOverlayReturnsWhenEnabledForAnActiveContract() {
        assertTrue(ContractHudVisibility.shouldRender(true, true));
    }

    @Test
    void legacyOverlayNeverRendersWithoutAnActiveContract() {
        assertFalse(ContractHudVisibility.shouldRender(true, false));
    }
}
