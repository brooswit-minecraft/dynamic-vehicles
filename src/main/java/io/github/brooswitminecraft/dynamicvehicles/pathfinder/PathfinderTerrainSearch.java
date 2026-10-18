package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Minecraft-free half of {@link PathfinderTerrainPlacer}'s bounded safe-placement search:
 * just the deterministic column offsets to try, in order. Kept Minecraft-free (unlike {@link
 * PathfinderTerrainPlacer} itself, which needs a real {@code ServerLevel} for the actual safety
 * check) so the search ORDER is directly unit-testable without pulling Minecraft classes into
 * the plain test sourceSet — the same reason {@code delivery.RingMath} is split out from the
 * Minecraft-facing services that use it.
 */
final class PathfinderTerrainSearch {
    private static final int SEARCH_STEP_BLOCKS = 4;
    private static final int SEARCH_RINGS = 4;
    private static final int[][] COMPASS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private PathfinderTerrainSearch() {
    }

    /**
     * The bounded, deterministic column offsets (relative to the candidate X/Z) to try in order:
     * the candidate itself, then 8 compass directions at each of {@link #SEARCH_RINGS} rings
     * {@link #SEARCH_STEP_BLOCKS} blocks apart.
     */
    static List<int[]> offsets() {
        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[] {0, 0});
        for (int ring = 1; ring <= SEARCH_RINGS; ring++) {
            int distance = ring * SEARCH_STEP_BLOCKS;
            for (int[] direction : COMPASS) {
                offsets.add(new int[] {direction[0] * distance, direction[1] * distance});
            }
        }
        return offsets;
    }
}
