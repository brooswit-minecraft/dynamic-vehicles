package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathfinderRingMathTest {

    private static final PathfinderRegion ORIGIN = new PathfinderRegion(-7, 2);

    @Test
    void ringSizesMatchTheDesign_8_16_24_32() {
        for (PathfinderLevel level : List.of(PathfinderLevel.LEVEL_2, PathfinderLevel.LEVEL_3,
                PathfinderLevel.LEVEL_4, PathfinderLevel.LEVEL_5)) {
            assertEquals(level.checkpointCount(),
                    PathfinderRingMath.regionsInRing(ORIGIN, level.ring()).size(),
                    level + " ring size");
        }
    }

    @Test
    void everyRegionInARingIsActuallyAtThatChebyshevDistance() {
        for (int ring = 1; ring <= 4; ring++) {
            for (PathfinderRegion region : PathfinderRingMath.regionsInRing(ORIGIN, ring)) {
                assertEquals(ring, PathfinderRingMath.ringOf(ORIGIN, region));
            }
        }
    }

    @Test
    void circuitOrderIsAPermutationOfTheRing() {
        List<PathfinderRegion> ring = PathfinderRingMath.regionsInRing(ORIGIN, 2);
        List<PathfinderRegion> circuit = PathfinderRingMath.circuitOrder(ORIGIN, 2);
        assertEquals(new HashSet<>(ring), new HashSet<>(circuit));
        assertEquals(ring.size(), circuit.size());
    }

    @Test
    void circuitOrderSweepsClockwiseStartingDueEast() {
        // Ring 1's eight neighbors, expected clockwise from due east (+x, dz=0)
        // in a +x-east/+z-south frame: E, SE, S, SW, W, NW, N, NE.
        List<PathfinderRegion> circuit = PathfinderRingMath.circuitOrder(ORIGIN, 1);
        List<PathfinderRegion> expected = List.of(
                new PathfinderRegion(ORIGIN.x() + 1, ORIGIN.z()),     // E
                new PathfinderRegion(ORIGIN.x() + 1, ORIGIN.z() + 1), // SE
                new PathfinderRegion(ORIGIN.x(), ORIGIN.z() + 1),     // S
                new PathfinderRegion(ORIGIN.x() - 1, ORIGIN.z() + 1), // SW
                new PathfinderRegion(ORIGIN.x() - 1, ORIGIN.z()),     // W
                new PathfinderRegion(ORIGIN.x() - 1, ORIGIN.z() - 1), // NW
                new PathfinderRegion(ORIGIN.x(), ORIGIN.z() - 1),     // N
                new PathfinderRegion(ORIGIN.x() + 1, ORIGIN.z() - 1)  // NE
        );
        assertEquals(expected, circuit);
    }

    @Test
    void circuitOrderIsDeterministicAcrossRepeatedCalls() {
        assertEquals(PathfinderRingMath.circuitOrder(ORIGIN, 3), PathfinderRingMath.circuitOrder(ORIGIN, 3));
    }

    @Test
    void negativeOriginRegionsAreHandledLikeAnyOther() {
        PathfinderRegion negativeOrigin = new PathfinderRegion(-100, -100);
        List<PathfinderRegion> circuit = PathfinderRingMath.circuitOrder(negativeOrigin, 2);
        assertEquals(16, circuit.size());
        for (PathfinderRegion region : circuit) {
            assertEquals(2, PathfinderRingMath.ringOf(negativeOrigin, region));
        }
    }
}
