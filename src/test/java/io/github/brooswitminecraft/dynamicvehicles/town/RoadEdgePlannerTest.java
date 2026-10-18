package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two regions sharing an EAST/WEST (or NORTH/SOUTH) boundary never occupy the same block column —
 * region A's EAST edge sits at its own max-X column, its east neighbor's WEST edge sits one column
 * further out, at ITS own min-X column. What must agree between them is the perpendicular "offset
 * along the edge" coordinate (Z for an EAST/WEST pair, X for a NORTH/SOUTH pair) — that is the
 * actual shared-edge-point agreement MINECRAFT-388 asks for, not literal identical block
 * coordinates on an axis that can never be shared between two disjoint regions in the first place.
 */
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
        assertEquals(absoluteFromA[1], absoluteFromB[1], "the shared Z offset along an EAST/WEST boundary must agree");
        assertEquals(absoluteFromA[0] + 1, absoluteFromB[0], "b's west edge column should sit exactly one block east of a's east edge column");
    }

    @Test
    void northNeighborAgreesWithSouthNeighborOnTheSharedEdgePoint() {
        RegionCoord a = new RegionCoord(-2, -3);
        RegionCoord b = new RegionCoord(-2, -4); // a's north neighbor

        RoadEdgePlanner.EdgeConnection fromA = RoadEdgePlanner.computeEdgeConnection(a, TownEdge.NORTH, SEED);
        RoadEdgePlanner.EdgeConnection fromB = RoadEdgePlanner.computeEdgeConnection(b, TownEdge.SOUTH, SEED);

        int[] absoluteFromA = RoadEdgePlanner.absolutePosition(a, TownEdge.NORTH, fromA);
        int[] absoluteFromB = RoadEdgePlanner.absolutePosition(b, TownEdge.SOUTH, fromB);
        assertEquals(absoluteFromA[0], absoluteFromB[0], "the shared X offset along a NORTH/SOUTH boundary must agree");
        assertEquals(absoluteFromA[1] - 1, absoluteFromB[1], "b's south edge row should sit exactly one block north of a's north edge row");
    }

    @Test
    void agreementHoldsAcrossNegativeRegionCoordinates() {
        RegionCoord a = new RegionCoord(-10, -10);
        RegionCoord b = new RegionCoord(-9, -10);

        RoadEdgePlanner.EdgeConnection fromA = RoadEdgePlanner.computeEdgeConnection(a, TownEdge.EAST, SEED);
        RoadEdgePlanner.EdgeConnection fromB = RoadEdgePlanner.computeEdgeConnection(b, TownEdge.WEST, SEED);

        int[] absoluteFromA = RoadEdgePlanner.absolutePosition(a, TownEdge.EAST, fromA);
        int[] absoluteFromB = RoadEdgePlanner.absolutePosition(b, TownEdge.WEST, fromB);
        assertEquals(absoluteFromA[1], absoluteFromB[1], "the shared Z offset must agree even for negative region coordinates");
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
