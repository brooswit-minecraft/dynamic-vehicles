package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingMathTest {

    private static final RegionCoord ORIGIN = new RegionCoord(10, -3);

    @Test
    void ringEnumerationOrder_everyRegionIsAtTheRequestedChebyshevDistance() {
        for (int ring = 0; ring <= 4; ring++) {
            List<RegionCoord> regions = RingMath.regionsInRing(ORIGIN, ring);
            for (RegionCoord region : regions) {
                assertEquals(ring, RingMath.ringOf(ORIGIN, region),
                        "region " + region + " should be at ring " + ring);
            }
        }
    }

    @Test
    void ringEnumerationOrder_ringSizeMatchesSquarePerimeterFormula() {
        assertEquals(1, RingMath.regionsInRing(ORIGIN, 0).size());
        for (int ring = 1; ring <= 5; ring++) {
            int expected = 8 * ring;
            assertEquals(expected, RingMath.regionsInRing(ORIGIN, ring).size(), "ring " + ring);
        }
    }

    @Test
    void ringEnumerationOrder_perRingRegionsAreUnique() {
        for (int ring = 0; ring <= 4; ring++) {
            List<RegionCoord> regions = RingMath.regionsInRing(ORIGIN, ring);
            assertEquals(regions.size(), new HashSet<>(regions).size());
        }
    }

    @Test
    void shuffleDeterminism_sameSeedAndRingAlwaysProducesTheSameOrder() {
        List<RegionCoord> ring = RingMath.regionsInRing(ORIGIN, 3);
        List<RegionCoord> first = RingMath.shuffleRing(ring, 42L, 3);
        List<RegionCoord> second = RingMath.shuffleRing(ring, 42L, 3);
        assertEquals(first, second);
    }

    @Test
    void shuffleDeterminism_shuffleIsAPermutationOfTheInput() {
        List<RegionCoord> ring = RingMath.regionsInRing(ORIGIN, 3);
        List<RegionCoord> shuffled = RingMath.shuffleRing(ring, 42L, 3);
        assertEquals(new HashSet<>(ring), new HashSet<>(shuffled));
        assertEquals(ring.size(), shuffled.size());
    }

    @Test
    void shuffleDeterminism_differentRingsOfTheSameSeedDecorrelate() {
        List<RegionCoord> ring = RingMath.regionsInRing(ORIGIN, 3);
        List<RegionCoord> shuffledAsRing3 = RingMath.shuffleRing(ring, 42L, 3);
        List<RegionCoord> shuffledAsRing7 = RingMath.shuffleRing(ring, 42L, 7);
        assertFalse(shuffledAsRing3.equals(shuffledAsRing7),
                "different ring indices should decorrelate the shuffle order");
    }

    @Test
    void originRegionExclusion_destinationRingsNeverContainTheOriginRegion() {
        List<RegionCoord> destinations = RingMath.destinationRingsUpTo(ORIGIN, 5, 123L);
        assertFalse(destinations.contains(ORIGIN));
    }

    @Test
    void originRegionExclusion_destinationRingsHaveNoDuplicates() {
        List<RegionCoord> destinations = RingMath.destinationRingsUpTo(ORIGIN, 5, 123L);
        assertEquals(destinations.size(), new HashSet<>(destinations).size());
    }

    @Test
    void originRegionExclusion_destinationRingsAreOrderedNearestRingFirst() {
        List<RegionCoord> destinations = RingMath.destinationRingsUpTo(ORIGIN, 4, 123L);
        int lastRing = 0;
        for (RegionCoord region : destinations) {
            int ring = RingMath.ringOf(ORIGIN, region);
            assertTrue(ring >= lastRing, "ring " + ring + " appeared after ring " + lastRing);
            lastRing = ring;
        }
    }

    @Test
    void gracefulExhaustion_maxRingZeroYieldsNoDestinations() {
        assertTrue(RingMath.destinationRingsUpTo(ORIGIN, 0, 123L).isEmpty());
    }

    @Test
    void gracefulExhaustion_destinationCountMatchesSumOfRingSizes() {
        int maxRing = 3;
        int expected = 0;
        for (int ring = 1; ring <= maxRing; ring++) {
            expected += 8 * ring;
        }
        assertEquals(expected, RingMath.destinationRingsUpTo(ORIGIN, maxRing, 123L).size());
    }
}
