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
    void hostileMobsAreKnockedToTheSideTheyAreOn() {
        // Car faces +Z (forward 0,1); perpendicular is (-1,0).
        assertEquals(-1, ImpactRules.lateralSide(0, 1, 1.0, 2.0));
        assertEquals(1, ImpactRules.lateralSide(0, 1, -1.0, 2.0));
        // Same sides when the car reverses toward the mob's other end: depends only on lateral offset.
        assertEquals(-1, ImpactRules.lateralSide(0, 1, 0.2, -2.0));
        // Dead ahead goes to the positive side, never straight back.
        assertEquals(1, ImpactRules.lateralSide(0, 1, 0.0, 2.0));
    }

    @Test
    void hostileMobHitNeedsSpeedAndScalesDamage() {
        assertFalse(ImpactRules.hostileMobHitEligible(2.9));
        assertTrue(ImpactRules.hostileMobHitEligible(-3.0));
        assertTrue(ImpactRules.hostileMobHitDamage(10.0) > ImpactRules.hostileMobHitDamage(4.0));
        assertEquals(20.0f, ImpactRules.hostileMobHitDamage(500.0));
        assertTrue(ImpactRules.hostileMobKnockback(20.0) > ImpactRules.hostileMobKnockback(4.0));
    }

    @Test
    void truckRoutesDirtAndSandToTerrainBeforeItsNarrowBambooRule() {
        assertEquals(ImpactRules.BlockImpactRoute.TERRAIN_EROSION,
                ImpactRules.blockImpactRoute(true, true, false, true),
                "a truck impact on dirt or sand must reach Dynamic Terrain before the truck return");
        assertEquals(ImpactRules.BlockImpactRoute.TERRAIN_EROSION,
                ImpactRules.blockImpactRoute(true, true, true, true),
                "terrain remains authoritative even if a block is otherwise bamboo-eligible");
    }

    @Test
    void onlyBreakableBambooInFrontOfTheTruckIsEligible() {
        assertTrue(ImpactRules.truckMayBreakBamboo(true, true, true));
        assertFalse(ImpactRules.truckMayBreakBamboo(false, true, true));
        assertFalse(ImpactRules.truckMayBreakBamboo(true, false, true));
        assertFalse(ImpactRules.truckMayBreakBamboo(true, true, false));
        assertEquals(ImpactRules.BlockImpactRoute.TRUCK_BAMBOO,
                ImpactRules.blockImpactRoute(true, false, true, true));
        assertEquals(ImpactRules.BlockImpactRoute.DEFAULT,
                ImpactRules.blockImpactRoute(true, false, true, false));
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
