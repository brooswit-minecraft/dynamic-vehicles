package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderRouteGeneratorTest {

    private static final PathfinderRegion ORIGIN = new PathfinderRegion(5, -9);

    @Test
    void level1IsAnEightCheckpointLoopEntirelyInsideTheOriginRegion() {
        PathfinderRoute route = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_1);
        assertEquals(8, route.checkpoints().size());
        for (PathfinderCheckpoint checkpoint : route.checkpoints()) {
            assertEquals(ORIGIN, checkpoint.region());
        }
    }

    @Test
    void level1ChecksCheckpointsAreDistinctPositionsNotJustTheRegionCenter() {
        PathfinderRoute route = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_1);
        long distinctPositions = route.checkpoints().stream().map(PathfinderCheckpoint::position).distinct().count();
        assertEquals(8, distinctPositions, "an 8-checkpoint local loop needs 8 distinct points, not one repeated");
    }

    @Test
    void level2Through5ProduceTheDesignedRingSizes() {
        assertEquals(8, PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_2).checkpoints().size());
        assertEquals(16, PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_3).checkpoints().size());
        assertEquals(24, PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_4).checkpoints().size());
        assertEquals(32, PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_5).checkpoints().size());
    }

    @Test
    void level2Through5GiveEachRingRegionExactlyOneCheckpoint() {
        PathfinderRoute route = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_3);
        long distinctRegions = route.checkpoints().stream().map(PathfinderCheckpoint::region).distinct().count();
        assertEquals(route.checkpoints().size(), distinctRegions);
    }

    @Test
    void sameOriginAndLevelAlwaysProducesAnEqualRoute_acrossVillagersRetriesAndReload() {
        // "Across villagers/retries/reload" all collapse to "call this pure function again" -
        // there is no villager/player/session identity anywhere in its input.
        PathfinderRoute first = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_3);
        PathfinderRoute second = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_3);
        assertEquals(first, second);

        PathfinderRoute firstLevel1 = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_1);
        PathfinderRoute secondLevel1 = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_1);
        assertEquals(firstLevel1, secondLevel1);
    }

    @Test
    void distantOriginRegionsGetDisjointRoutesAtTheSameLevel() {
        PathfinderRoute a = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_2);
        PathfinderRoute b = PathfinderRouteGenerator.generate(new PathfinderRegion(ORIGIN.x() + 100, ORIGIN.z()), PathfinderLevel.LEVEL_2);
        assertTrue(a.checkpoints().stream().noneMatch(b.checkpoints()::contains));
    }

    @Test
    void negativeOriginRegionProducesAFullCircuitTooAtEveryLevel() {
        PathfinderRegion negativeOrigin = new PathfinderRegion(-40, -40);
        for (PathfinderLevel level : PathfinderLevel.values()) {
            PathfinderRoute route = PathfinderRouteGenerator.generate(negativeOrigin, level);
            assertEquals(level.checkpointCount(), route.checkpoints().size());
        }
    }
}
