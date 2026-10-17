package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuilderSettlementPlanTest {
    @Test
    void donationAndPassiveRatesAreIndependentlyLevelScaled() {
        assertEquals(0.0D, BuilderSettlementPlan.donationSettlementChance(1, 5));
        assertEquals(0.0625D, BuilderSettlementPlan.donationSettlementChance(2, 2));
        assertEquals(0.25D, BuilderSettlementPlan.donationSettlementChance(3, 3));
        assertEquals(0.5625D, BuilderSettlementPlan.donationSettlementChance(4, 4));
        assertEquals(1.0D, BuilderSettlementPlan.donationSettlementChance(5, 5));
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

    @Test
    void settlementFootprintMustStayInTheCampfireChunk() {
        // Campfire at x=8,z=8 (chunk 0,0); house centred 4 east spans x 6..10 plus entrance at x=11.
        assertTrue(BuilderSettlementPlan.isWithinCampfireChunk(8, 8, 6, 6, 11, 10));
        // Campfire at x=13: house centred 4 east spans 15..19 and crosses into chunk 1.
        assertFalse(BuilderSettlementPlan.isWithinCampfireChunk(13, 8, 15, 6, 20, 10));
        // Negative coordinates floor correctly: -1 is chunk -1, 0 is chunk 0.
        assertFalse(BuilderSettlementPlan.isWithinCampfireChunk(-3, 8, -5, 6, 0, 10));
        assertTrue(BuilderSettlementPlan.isWithinCampfireChunk(-8, -8, -10, -10, -5, -6));
        // A rejected chunk placement can never complete the settlement, so the campfire is kept.
        assertFalse(BuilderSettlementPlan.shouldCompleteSettlement(true, true,
                BuilderSettlementPlan.isWithinCampfireChunk(13, 8, 15, 6, 20, 10)));
    }

    @Test
    void houseIsCentredOnTheCampfiresChunkRatherThanOffsetFromTheCampfire() {
        // A campfire anywhere in chunk 0 (0..15) centres on local 8.
        assertEquals(8, BuilderSettlementPlan.chunkCenterCoordinate(0));
        assertEquals(8, BuilderSettlementPlan.chunkCenterCoordinate(8));
        assertEquals(8, BuilderSettlementPlan.chunkCenterCoordinate(15));
        // Chunk 1 (16..31) centres on 24; a campfire near the edge still centres on its own chunk.
        assertEquals(24, BuilderSettlementPlan.chunkCenterCoordinate(16));
        assertEquals(24, BuilderSettlementPlan.chunkCenterCoordinate(29));
        // Negative coordinates floor to the containing chunk: chunk -1 is -16..-1, centred on -8.
        assertEquals(-8, BuilderSettlementPlan.chunkCenterCoordinate(-1));
        assertEquals(-8, BuilderSettlementPlan.chunkCenterCoordinate(-16));
        assertEquals(-24, BuilderSettlementPlan.chunkCenterCoordinate(-17));
        // The centred point is always inside the campfire's own chunk.
        assertTrue(BuilderSettlementPlan.isWithinCampfireChunk(13, -3,
                BuilderSettlementPlan.chunkCenterCoordinate(13), BuilderSettlementPlan.chunkCenterCoordinate(-3),
                BuilderSettlementPlan.chunkCenterCoordinate(13), BuilderSettlementPlan.chunkCenterCoordinate(-3)));
    }
}
