package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoadEdgePlannerTest {
    private static final long SEED = 987654321L;

    @Test
    void eastNeighborAgreesWithWestNeighborOnTheSharedEdgePoint() {
        RegionCoord a = new RegionCoord(4, 9);
        RegionCoord b = new RegionCoord(5, 9); // a's east neighbor

        RoadEdgePlanner.EdgeConnection fromA = RoadEdgePlanner.computeEdgeConnection(a, TownEdge.EAST, SEED);
        RoadEdgePlanner.EdgeConnection fromB = RoadEdgePlanner.computeEdgeConnection(b, TownEdge.WEST, SEED);

        int[] absoluteFromA = RoadEdgePlanner.absolutePosition(a, TownEdge.EAST, fromA);
        int[] absoluteFromB = RoadEdgePlanner.absolutePosition(b, TownEdge.WEST, fromB);
        assertArrayEquals(absoluteFromA, absoluteFromB);
    }

    @Test
    void northNeighborAgreesWithSouthNeighborOnTheSharedEdgePoint() {
        RegionCoord a = new RegionCoord(-2, -3);
        RegionCoord b = new RegionCoord(-2, -4); // a's north neighbor

        RoadEdgePlanner.EdgeConnection fromA = RoadEdgePlanner.computeEdgeConnection(a, TownEdge.NORTH, SEED);
        RoadEdgePlanner.EdgeConnection fromB = RoadEdgePlanner.computeEdgeConnection(b, TownEdge.SOUTH, SEED);

        int[] absoluteFromA = RoadEdgePlanner.absolutePosition(a, TownEdge.NORTH, fromA);
        int[] absoluteFromB = RoadEdgePlanner.absolutePosition(b, TownEdge.SOUTH, fromB);
        assertArrayEquals(absoluteFromA, absoluteFromB);
    }

    @Test
    void agreementHoldsAcrossNegativeRegionCoordinates() {
        RegionCoord a = new RegionCoord(-10, -10);
        RegionCoord b = new RegionCoord(-9, -10);

        RoadEdgePlanner.EdgeConnection fromA = RoadEdgePlanner.computeEdgeConnection(a, TownEdge.EAST, SEED);
        RoadEdgePlanner.EdgeConnection fromB = RoadEdgePlanner.computeEdgeConnection(b, TownEdge.WEST, SEED);

        int[] absoluteFromA = RoadEdgePlanner.absolutePosition(a, TownEdge.EAST, fromA);
        int[] absoluteFromB = RoadEdgePlanner.absolutePosition(b, TownEdge.WEST, fromB);
        assertArrayEquals(absoluteFromA, absoluteFromB);
    }

    @Test
    void connectionOffsetIsAlwaysWithinTheRegionSpan() {
        RoadEdgePlanner.EdgeConnection connection =
                RoadEdgePlanner.computeEdgeConnection(new RegionCoord(0, 0), TownEdge.NORTH, SEED);
        org.junit.jupiter.api.Assertions.assertTrue(connection.offsetAlongEdge() >= 0);
        org.junit.jupiter.api.Assertions.assertTrue(connection.offsetAlongEdge() < TownConstants.REGION_SIZE_BLOCKS);
    }

    @Test
    void rejectsAnOutOfRangeOffset() {
        assertThrows(IllegalArgumentException.class, () -> new RoadEdgePlanner.EdgeConnection(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new RoadEdgePlanner.EdgeConnection(TownConstants.REGION_SIZE_BLOCKS));
    }
}
