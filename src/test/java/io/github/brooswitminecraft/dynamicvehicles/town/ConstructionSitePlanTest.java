package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstructionSitePlanTest {
    @Test
    void blocksAreOrderedBottomToTop() {
        List<ConstructionSitePlan.PlannedBlock> blocks = List.of(
                new ConstructionSitePlan.PlannedBlock(0, 3, 0, TownResourceCategory.STONE),
                new ConstructionSitePlan.PlannedBlock(0, 1, 0, TownResourceCategory.STONE),
                new ConstructionSitePlan.PlannedBlock(0, 2, 0, TownResourceCategory.STONE));

        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(blocks, 99, 99, 99);

        assertEquals(1, ordered.get(0).y());
        assertEquals(2, ordered.get(1).y());
        assertEquals(3, ordered.get(2).y());
    }

    @Test
    void theCampfireCellIsAlwaysPlacedLastEvenWhenItIsTheLowestBlock() {
        List<ConstructionSitePlan.PlannedBlock> blocks = List.of(
                new ConstructionSitePlan.PlannedBlock(5, 0, 5, TownResourceCategory.WOOD),   // the campfire's own cell
                new ConstructionSitePlan.PlannedBlock(5, 1, 5, TownResourceCategory.WOOD),
                new ConstructionSitePlan.PlannedBlock(5, 2, 5, TownResourceCategory.WOOD));

        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(blocks, 5, 0, 5);

        ConstructionSitePlan.PlannedBlock last = ordered.get(ordered.size() - 1);
        assertEquals(0, last.y());
        assertEquals(5, last.x());
        assertEquals(5, last.z());
    }

    @Test
    void unclaimedCampfireMustNotTriggerConstruction() {
        List<ConstructionSitePlan.PlannedBlock> remaining =
                List.of(new ConstructionSitePlan.PlannedBlock(0, 0, 0, TownResourceCategory.STONE));

        Optional<ConstructionSitePlan.PlannedBlock> next = ConstructionSitePlan.nextBlockToPlace(remaining, false);

        assertTrue(next.isEmpty());
    }

    @Test
    void claimedCampfireWithRemainingBlocksYieldsTheNextBlock() {
        List<ConstructionSitePlan.PlannedBlock> remaining =
                List.of(new ConstructionSitePlan.PlannedBlock(1, 2, 3, TownResourceCategory.FOOD));

        Optional<ConstructionSitePlan.PlannedBlock> next = ConstructionSitePlan.nextBlockToPlace(remaining, true);

        assertTrue(next.isPresent());
        assertEquals(remaining.get(0), next.get());
    }

    @Test
    void anEmptySiteNeverYieldsABlockRegardlessOfClaim() {
        assertTrue(ConstructionSitePlan.nextBlockToPlace(List.of(), true).isEmpty());
        assertTrue(ConstructionSitePlan.nextBlockToPlace(List.of(), false).isEmpty());
    }

    @Test
    void payingForABlockDebitsItsCategoryWhenAffordable() {
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.STONE, 10);
        ConstructionSitePlan.PlannedBlock block = new ConstructionSitePlan.PlannedBlock(0, 0, 0, TownResourceCategory.STONE);

        boolean paid = ConstructionSitePlan.tryPayForBlock(store, block, 4);

        assertTrue(paid);
        assertEquals(6, store.balance(TownResourceCategory.STONE));
    }

    @Test
    void insufficientResourcesStopConstructionWithoutPartialCharge() {
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.STONE, 2);
        ConstructionSitePlan.PlannedBlock block = new ConstructionSitePlan.PlannedBlock(0, 0, 0, TownResourceCategory.STONE);

        boolean paid = ConstructionSitePlan.tryPayForBlock(store, block, 4);

        assertFalse(paid);
        assertEquals(2, store.balance(TownResourceCategory.STONE));
    }
}
