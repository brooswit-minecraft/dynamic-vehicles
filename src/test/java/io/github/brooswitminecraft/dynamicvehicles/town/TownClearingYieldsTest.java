package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownClearingYieldsTest {
    @Test
    void stoneReturnsOneForOneToTheStoneCategory() {
        assertEquals(TownResourceCategory.STONE, TownClearingYields.yieldFor(TownClearingYields.ClearedMaterial.STONE).category());
        assertEquals(5, TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.STONE, 5));
    }

    @Test
    void treesReturnOneForOneToTheWoodCategory() {
        assertEquals(TownResourceCategory.WOOD, TownClearingYields.yieldFor(TownClearingYields.ClearedMaterial.LOG).category());
        assertEquals(4, TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.LOG, 4));
    }

    @Test
    void dirtReturnsOnlyASmallAmountOfStone() {
        int blocksPerCredit = TownConstants.CLEARING_DIRT_BLOCKS_PER_STONE_CREDIT;
        assertEquals(TownResourceCategory.STONE, TownClearingYields.yieldFor(TownClearingYields.ClearedMaterial.DIRT).category());
        assertEquals(0, TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.DIRT, blocksPerCredit - 1),
                "fewer than one credit's worth of dirt should earn nothing");
        assertEquals(1, TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.DIRT, blocksPerCredit));
    }

    @Test
    void leavesReturnOnlyASmallAmountOfWood() {
        int blocksPerCredit = TownConstants.CLEARING_LEAVES_BLOCKS_PER_WOOD_CREDIT;
        assertEquals(TownResourceCategory.WOOD, TownClearingYields.yieldFor(TownClearingYields.ClearedMaterial.LEAVES).category());
        assertEquals(1, TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.LEAVES, blocksPerCredit));
    }

    @Test
    void negativeBlockCountsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.STONE, -1));
    }
}
