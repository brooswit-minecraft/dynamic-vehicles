package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuilderSettlementPlanTest {
    @Test
    void settlementChanceMapsEveryBuilderLevelToTheRequiredFraction() {
        assertEquals(0.0D, BuilderSettlementPlan.settlementChance(1));
        assertEquals(0.025D, BuilderSettlementPlan.settlementChance(2));
        assertEquals(0.05D, BuilderSettlementPlan.settlementChance(3));
        assertEquals(0.075D, BuilderSettlementPlan.settlementChance(4));
        assertEquals(0.10D, BuilderSettlementPlan.settlementChance(5));
    }

    @Test
    void noonSchedulingAndPersistenceGateAreDeterministic() {
        assertTrue(BuilderSettlementPlan.isNoon(6000));
        assertTrue(BuilderSettlementPlan.isNoon(30000));
        assertFalse(BuilderSettlementPlan.isNoon(5999));
        assertFalse(BuilderSettlementPlan.isNoon(6001));
        assertTrue(BuilderSettlementPlan.shouldRunForDay(4, 5));
        assertFalse(BuilderSettlementPlan.shouldRunForDay(5, 5));
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

    @Test
    void buildingSelectionIsDeterministicAndLimitedToProjectOwnedVariants() {
        assertEquals(BuilderSettlementPlan.BuildingVariant.OAK_HUT, BuilderSettlementPlan.selectBuildingVariant(0));
        assertEquals(BuilderSettlementPlan.BuildingVariant.SPRUCE_HUT, BuilderSettlementPlan.selectBuildingVariant(1));
        assertEquals(BuilderSettlementPlan.BuildingVariant.OAK_HUT, BuilderSettlementPlan.selectBuildingVariant(2));
        assertEquals(BuilderSettlementPlan.BuildingVariant.SPRUCE_HUT, BuilderSettlementPlan.selectBuildingVariant(-1));
    }
}
