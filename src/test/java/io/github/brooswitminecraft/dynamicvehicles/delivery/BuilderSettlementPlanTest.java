package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuilderSettlementPlanTest {
    @Test
    void settlementChanceUsesTheRequiredFractionalFormula() {
        assertEquals(1.0D, BuilderSettlementPlan.settlementChance(5, 5));
        assertEquals(0.5D, BuilderSettlementPlan.settlementChance(5, 3));
        assertEquals(0.0625D, BuilderSettlementPlan.settlementChance(2, 2));
        assertEquals(0.0D, BuilderSettlementPlan.settlementChance(1, 5));
    }

    @Test
    void campfireRequiresEveryCreationGuardAndSingleClaim() {
        assertTrue(BuilderSettlementPlan.shouldCreateCampfire(true, true, true, true, false));
        assertFalse(BuilderSettlementPlan.shouldCreateCampfire(false, true, true, true, false));
        assertFalse(BuilderSettlementPlan.shouldCreateCampfire(true, false, true, true, false));
        assertFalse(BuilderSettlementPlan.shouldCreateCampfire(true, true, false, true, false));
        assertFalse(BuilderSettlementPlan.shouldCreateCampfire(true, true, true, false, false));
        assertFalse(BuilderSettlementPlan.shouldCreateCampfire(true, true, true, true, true));
    }

    @Test
    void unsafeOrFailedSettlementKeepsTheCampfire() {
        assertTrue(BuilderSettlementPlan.shouldReplaceCampfire(true, true));
        assertFalse(BuilderSettlementPlan.shouldReplaceCampfire(false, true));
        assertFalse(BuilderSettlementPlan.shouldReplaceCampfire(true, false));
    }
}
