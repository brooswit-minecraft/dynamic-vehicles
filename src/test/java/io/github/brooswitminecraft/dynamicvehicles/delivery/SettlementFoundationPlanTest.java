package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
