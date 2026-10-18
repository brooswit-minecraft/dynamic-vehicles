package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathfinderTerrainSearch#offsets()} is the pure, Minecraft-free half of the bounded-
 * safe-placement search {@link PathfinderTerrainPlacer} uses (review follow-up); the actual
 * per-column safety check needs a real level and is covered by GameTests instead.
 */
class PathfinderTerrainPlacerOffsetsTest {

    @Test
    void firstOffsetIsTheCandidateItself() {
        assertEquals(List.of(0, 0), toList(PathfinderTerrainSearch.offsets().get(0)));
    }

    @Test
    void everyOffsetIsDistinct() {
        List<int[]> offsets = PathfinderTerrainSearch.offsets();
        Set<List<Integer>> distinct = new HashSet<>();
        for (int[] offset : offsets) {
            distinct.add(toList(offset));
        }
        assertEquals(offsets.size(), distinct.size(), "the search must never check the same column twice");
    }

    @Test
    void searchIsBounded() {
        List<int[]> offsets = PathfinderTerrainSearch.offsets();
        assertTrue(offsets.size() < 100, "the search must stay small/bounded, not scan an unbounded area");
        for (int[] offset : offsets) {
            assertTrue(Math.abs(offset[0]) <= 16 && Math.abs(offset[1]) <= 16, "every offset must stay within a small bounded radius");
        }
    }

    private static List<Integer> toList(int[] offset) {
        return List.of(offset[0], offset[1]);
    }
}
