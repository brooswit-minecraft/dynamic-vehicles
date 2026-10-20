package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownFoundationPlanTest {
    @Test
    void existingVillageAlwaysWinsRegardlessOfFoundedState() {
        assertEquals(TownFoundationPlan.Decision.EXISTING_VILLAGE, TownFoundationPlan.decide(true, false));
        assertEquals(TownFoundationPlan.Decision.EXISTING_VILLAGE, TownFoundationPlan.decide(true, true));
    }

    @Test
    void alreadyFoundedBlocksASecondBootstrapWhenNoVillageExists() {
        assertEquals(TownFoundationPlan.Decision.ALREADY_FOUNDED, TownFoundationPlan.decide(false, true));
    }

    @Test
    void foundOnlyWhenNeitherAnExistingVillageNorAPriorBootstrapIsPresent() {
        assertEquals(TownFoundationPlan.Decision.FOUND, TownFoundationPlan.decide(false, false));
    }
}
