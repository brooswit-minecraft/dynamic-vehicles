package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownRegionTest {
    @Test
    void positiveBlockCoordinatesMapToTheExpectedRegion() {
        assertEquals(new RegionCoord(0, 0), TownRegion.fromBlockCoords(0, 0));
        assertEquals(new RegionCoord(1, 0), TownRegion.fromBlockCoords(TownConstants.REGION_SIZE_BLOCKS, 0));
    }

    @Test
    void negativeBlockCoordinatesFloorCorrectlyRatherThanTruncatingTowardZero() {
        assertEquals(new RegionCoord(-1, -1), TownRegion.fromBlockCoords(-1, -1));
        assertEquals(new RegionCoord(-1, 0), TownRegion.fromBlockCoords(-TownConstants.REGION_SIZE_BLOCKS, 0));
    }

    @Test
    void neighborsAreAdjacentOnTheExpectedAxis() {
        RegionCoord region = new RegionCoord(2, 3);
        assertEquals(new RegionCoord(3, 3), TownRegion.neighbor(region, TownEdge.EAST));
        assertEquals(new RegionCoord(1, 3), TownRegion.neighbor(region, TownEdge.WEST));
        assertEquals(new RegionCoord(2, 2), TownRegion.neighbor(region, TownEdge.NORTH));
        assertEquals(new RegionCoord(2, 4), TownRegion.neighbor(region, TownEdge.SOUTH));
    }

    @Test
    void neighborOfNeighborInTheOppositeDirectionReturnsToTheOriginal() {
        RegionCoord region = new RegionCoord(-4, 6);
        for (TownEdge edge : TownEdge.values()) {
            RegionCoord neighbor = TownRegion.neighbor(region, edge);
            assertEquals(region, TownRegion.neighbor(neighbor, edge.opposite()));
        }
    }
}
