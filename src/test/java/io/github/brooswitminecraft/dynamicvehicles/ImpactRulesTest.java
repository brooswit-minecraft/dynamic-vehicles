package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImpactRulesTest {
    @Test
    void creeperExplosionNeedsThresholdAndIsDeduplicatedByCooldown() {
        assertFalse(ImpactRules.creeperExplosionEligible(7.99, 8.0, false));
        assertTrue(ImpactRules.creeperExplosionEligible(8.0, 8.0, false));
        assertFalse(ImpactRules.creeperExplosionEligible(30.0, 8.0, true));
    }

    @Test
    void onlyBreakableBambooInFrontOfTheTruckIsEligible() {
        assertTrue(ImpactRules.truckMayBreakBamboo(true, true, true));
        assertFalse(ImpactRules.truckMayBreakBamboo(false, true, true));
        assertFalse(ImpactRules.truckMayBreakBamboo(true, false, true));
        assertFalse(ImpactRules.truckMayBreakBamboo(true, true, false));
    }

    @Test
    void glassNeedsASolidImpactButOtherMaterialsKeepTheirExistingPath() {
        assertTrue(ImpactRules.glassMayBreak(true, true));
        assertFalse(ImpactRules.glassMayBreak(true, false));
        assertTrue(ImpactRules.glassMayBreak(false, false));
    }

    @Test
    void dirtAndSandAreRoutedToTerrainInsteadOfDirectDestruction() {
        assertTrue(ImpactRules.usesTerrainErosion(true));
        assertFalse(ImpactRules.usesTerrainErosion(false));
    }

    @Test
    void repeatedSolidImpactsProgressivelyRemoveMomentum() {
        double first = ImpactRules.afterSolidImpact(10.0);
        double second = ImpactRules.afterSolidImpact(first);
        assertEquals(3.0, first);
        assertEquals(0.9, second, 1.0e-9);
        assertTrue(second < first && first < 10.0);
    }
}
