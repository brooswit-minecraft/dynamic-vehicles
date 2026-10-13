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
    void villagerCountIsStableAndBounded() {
        String key = "minecraft:12:-48";
        int count = SettlementFoundationPlan.villagerCount(key);
        assertEquals(count, SettlementFoundationPlan.villagerCount(key));
        assertTrue(count >= 1 && count <= 12);
    }
}
