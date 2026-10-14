package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementFoundationPlanTest {
    @Test
    void existingVillageAlwaysWinsOverEveryFoundingPath() {
        assertEquals(SettlementFoundationPlan.Decision.EXISTING_VILLAGE,
                SettlementFoundationPlan.decide(true, false));
        assertEquals(SettlementFoundationPlan.Decision.EXISTING_VILLAGE,
                SettlementFoundationPlan.decide(true, true));
    }

    @Test
    void missingVillageIsAlwaysFoundedUnlessAlreadyFounded() {
        assertEquals(SettlementFoundationPlan.Decision.ALREADY_FOUNDED,
                SettlementFoundationPlan.decide(false, true));
        assertEquals(SettlementFoundationPlan.Decision.FOUND,
                SettlementFoundationPlan.decide(false, false));
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
}
