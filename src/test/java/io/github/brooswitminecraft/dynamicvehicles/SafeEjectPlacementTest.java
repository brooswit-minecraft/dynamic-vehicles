package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-function checks for {@link SafeEjectPlacement} (MINECRAFT-249): never a point the caller's {@code
 * isFree} predicate rejects, never farther than {@link SafeEjectPlacement#SEARCH_RADIUS_BLOCKS}, and the
 * origin itself as a last resort when nothing else is free.
 */
class SafeEjectPlacementTest {

    @Test
    void returnsTheOriginWhenItIsAlreadyFree() {
        double[] result = SafeEjectPlacement.find(5.0, 10.0, 5.0, candidate -> true);
        assertArrayEquals(new double[] {5.0, 10.0, 5.0}, result);
    }

    @Test
    void returnsTheNearestFreeCandidateWhenTheOriginIsBlocked() {
        double[] result = SafeEjectPlacement.find(0.0, 0.0, 0.0, candidate -> {
            double dx = candidate[0];
            double dz = candidate[2];
            return Math.max(Math.abs(dx), Math.abs(dz)) == 1;
        });
        // Nearest ring (radius 1) must win over any farther candidate, even though several are free.
        assertEquals(1.0, Math.max(Math.abs(result[0]), Math.abs(result[2])));
    }

    @Test
    void neverReturnsAPointThePredicateRejects() {
        double[] result = SafeEjectPlacement.find(0.0, 0.0, 0.0, candidate -> candidate[0] == 2.0 && candidate[2] == 0.0);
        assertArrayEquals(new double[] {2.0, 0.0, 0.0}, result);
    }

    @Test
    void fallsBackToTheOriginWhenNothingInRangeIsFree() {
        double[] result = SafeEjectPlacement.find(3.0, 7.0, -2.0, candidate -> false);
        assertArrayEquals(new double[] {3.0, 7.0, -2.0}, result);
    }

    @Test
    void neverSearchesBeyondTheBoundedRadius() {
        boolean[] sawOutOfRange = {false};
        SafeEjectPlacement.find(0.0, 0.0, 0.0, candidate -> {
            double dx = candidate[0];
            double dz = candidate[2];
            if (Math.max(Math.abs(dx), Math.abs(dz)) > SafeEjectPlacement.SEARCH_RADIUS_BLOCKS) {
                sawOutOfRange[0] = true;
            }
            return false; // force the search to exhaust every candidate it tries
        });
        assertTrue(!sawOutOfRange[0], "must never probe a candidate past SEARCH_RADIUS_BLOCKS");
    }

    @Test
    void staysAtTheSameHeightAsTheOrigin() {
        SafeEjectPlacement.find(0.0, 64.0, 0.0, candidate -> {
            assertEquals(64.0, candidate[1], "must never search a different height than the origin");
            return false;
        });
    }
}
