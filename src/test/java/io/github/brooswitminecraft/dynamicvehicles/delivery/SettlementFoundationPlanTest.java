package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementFoundationPlanTest {
    @Test
    void existingVillageAlwaysWinsOverEveryFoundingPath() {
        assertEquals(SettlementFoundationPlan.Decision.EXISTING_VILLAGE,
                SettlementFoundationPlan.decide(true, false, true));
        assertEquals(SettlementFoundationPlan.Decision.EXISTING_VILLAGE,
                SettlementFoundationPlan.decide(true, true, false));
    }

    @Test
    void priorFoundationAndUnsafeSitesNeverFoundAgain() {
        assertEquals(SettlementFoundationPlan.Decision.ALREADY_FOUNDED,
                SettlementFoundationPlan.decide(false, true, true));
        assertEquals(SettlementFoundationPlan.Decision.UNSAFE_SITE,
                SettlementFoundationPlan.decide(false, false, false));
        assertEquals(SettlementFoundationPlan.Decision.FOUND,
                SettlementFoundationPlan.decide(false, false, true));
    }

    @Test
    void tradingPostPopulationIsStableAndMany() {
        String key = "minecraft:12:-48";
        int count = SettlementFoundationPlan.villagerCount(key);
        assertEquals(count, SettlementFoundationPlan.villagerCount(key));
        assertEquals(8, count);
        assertTrue(count >= 6);
    }

    @Test
    void tradingPostLayoutIsExplicitlyBounded() {
        assertEquals(3, SettlementFoundationPlan.TRADING_POST_RADIUS);
        assertEquals(5, SettlementFoundationPlan.TRADING_POST_HEIGHT);
    }

    @Test
    void unevenTerrainWithinThePreparationEnvelopeCanBeLevelled() {
        assertTrue(SettlementFoundationPlan.canLevelColumn(80, 78));
        assertTrue(SettlementFoundationPlan.canLevelColumn(80, 82));
        assertEquals(2, SettlementFoundationPlan.fillBlocks(80, 78));
        assertEquals(2, SettlementFoundationPlan.clearBlocks(80, 82));
    }

    @Test
    void steepOrProtectedPreparationCandidatesAreRejectedBeforeMutation() {
        assertTrue(!SettlementFoundationPlan.canLevelColumn(80, 77));
        assertTrue(!SettlementFoundationPlan.canLevelColumn(80, 83));
    }
}
