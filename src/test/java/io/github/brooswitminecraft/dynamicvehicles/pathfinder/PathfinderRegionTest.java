package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathfinderRegionTest {

    @Test
    void positiveBlockCoordsFloorIntoTheExpectedRegion() {
        assertEquals(new PathfinderRegion(0, 0), PathfinderRegion.fromBlockCoords(0, 0));
        assertEquals(new PathfinderRegion(0, 0), PathfinderRegion.fromBlockCoords(511, 511));
        assertEquals(new PathfinderRegion(1, 0), PathfinderRegion.fromBlockCoords(512, 0));
    }

    @Test
    void negativeBlockCoordsFloorTowardNegativeInfinityNotTowardZero() {
        // Naive truncating division (-1 / 512 == 0) would wrongly put block -1 in region 0.
        assertEquals(new PathfinderRegion(-1, -1), PathfinderRegion.fromBlockCoords(-1, -1));
        assertEquals(new PathfinderRegion(-1, -1), PathfinderRegion.fromBlockCoords(-512, -512));
        assertEquals(new PathfinderRegion(-2, -1), PathfinderRegion.fromBlockCoords(-513, -1));
    }

    @Test
    void regionBoundaryIsExclusiveOnTheHighSide() {
        assertEquals(new PathfinderRegion(0, 0), PathfinderRegion.fromBlockCoords(511, 0));
        assertEquals(new PathfinderRegion(1, 0), PathfinderRegion.fromBlockCoords(512, 0));
    }
}
