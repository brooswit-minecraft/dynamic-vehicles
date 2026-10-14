package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuilderSettlementPlanTest {
    @Test
    void donationAndPassiveRatesAreIndependentlyLevelScaled() {
        assertEquals(0.0D, BuilderSettlementPlan.donationSettlementChance(1));
        assertEquals(0.025D, BuilderSettlementPlan.donationSettlementChance(2));
        assertEquals(0.05D, BuilderSettlementPlan.donationSettlementChance(3));
        assertEquals(0.075D, BuilderSettlementPlan.donationSettlementChance(4));
        assertEquals(0.10D, BuilderSettlementPlan.donationSettlementChance(5));
        assertEquals(0.0D, BuilderSettlementPlan.passiveSettlementChance(1));
        assertEquals(0.0002D, BuilderSettlementPlan.passiveSettlementChance(2));
        assertEquals(0.0004D, BuilderSettlementPlan.passiveSettlementChance(3));
        assertEquals(0.0006D, BuilderSettlementPlan.passiveSettlementChance(4));
        assertEquals(0.0008D, BuilderSettlementPlan.passiveSettlementChance(5));
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
    void passiveChecksUseElapsedGameTimeAndBoundTimeJumpCatchUp() {
        assertTrue(BuilderSettlementPlan.shouldRunPassiveCheck(false, 0, 80));
        assertFalse(BuilderSettlementPlan.shouldRunPassiveCheck(true, 100, 299));
        assertTrue(BuilderSettlementPlan.shouldRunPassiveCheck(true, 100, 300));
        assertTrue(BuilderSettlementPlan.shouldRunPassiveCheck(true, 100, 1_000_000));
        assertFalse(BuilderSettlementPlan.shouldRunPassiveCheck(true, 1_000_000, 1_000_000));
        assertFalse(BuilderSettlementPlan.shouldRunPassiveCheck(true, 1_000_000, 999_999));
    }

    @Test
    void donationAndPassiveTriggersShareOneSettlementCommitGate() {
        assertTrue(BuilderSettlementPlan.shouldCompleteSettlement(true, true, true));
        assertFalse(BuilderSettlementPlan.shouldCompleteSettlement(false, true, true),
                "the first successful path consumes the shared campfire anchor");
        assertFalse(BuilderSettlementPlan.shouldCompleteSettlement(true, false, true));
        assertFalse(BuilderSettlementPlan.shouldCompleteSettlement(true, true, false));
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
        assertTrue(BuilderSettlementPlan.shouldResetBuilder(true, true));
        assertFalse(BuilderSettlementPlan.shouldResetBuilder(false, true));
        assertFalse(BuilderSettlementPlan.shouldResetBuilder(true, false));
    }

    @Test
    void replacementHouseUsesTheRequiredEnclosedFootprint() {
        assertEquals(5, BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH);
        assertEquals(4, BuilderSettlementPlan.HOUSE_HEIGHT);
    }

    @Test
    void buildingSelectionIsDeterministicAndLimitedToProjectOwnedVariants() {
        assertEquals(BuilderSettlementPlan.BuildingVariant.OAK_HUT, BuilderSettlementPlan.selectBuildingVariant(0));
        assertEquals(BuilderSettlementPlan.BuildingVariant.SPRUCE_HUT, BuilderSettlementPlan.selectBuildingVariant(1));
        assertEquals(BuilderSettlementPlan.BuildingVariant.OAK_HUT, BuilderSettlementPlan.selectBuildingVariant(2));
        assertEquals(BuilderSettlementPlan.BuildingVariant.SPRUCE_HUT, BuilderSettlementPlan.selectBuildingVariant(-1));
    }
}
