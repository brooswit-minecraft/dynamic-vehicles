package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderProgressTest {

    private static final double RADIUS = 3.0;
    private static final PathfinderRegion ORIGIN = new PathfinderRegion(3, 3);
    private static final PathfinderRoute ROUTE = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_2);

    private static PathfinderPosition at(PathfinderCheckpoint checkpoint) {
        return checkpoint.position();
    }

    // PR #163 review: a level-1 attempt must not be completable by standing in the origin region once -
    // it now needs all 8 of its distinct local-loop checkpoints, exactly like any other level.
    @Test
    void level1NeedsAllEightCheckpointsNotJustOneRegionEntry() {
        PathfinderRoute level1 = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_1);
        assertEquals(8, level1.checkpoints().size());
        PathfinderProgress progress = new PathfinderProgress(level1, 0, 1000);
        // A position far from the first checkpoint's exact spot (but still the right dimension) must be
        // rejected: before the fix, the old region-only check accepted ANY position "in the origin region"
        // and a single-checkpoint placeholder route finished on the spot.
        PathfinderPosition wrongSpot = new PathfinderPosition(at(level1.checkpoints().get(0)).dimensionId(), 0, 0, 0);
        assertFalse(progress.onReach(wrongSpot, 1, RADIUS));
        assertFalse(progress.isFinished());
        // Running the real 8 checkpoints in order DOES finish it - level 1 is reachable, just not trivially.
        long tick = 1;
        for (PathfinderCheckpoint checkpoint : level1.checkpoints()) {
            assertTrue(progress.onReach(at(checkpoint), tick++, RADIUS));
        }
        assertTrue(progress.onReach(at(level1.checkpoints().get(0)), tick, RADIUS));
        assertTrue(progress.isFinished());
    }

    @Test
    void wrongPositionInTheCorrectRegionIsRejected() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 1000);
        PathfinderPosition correctCheckpoint = at(ROUTE.checkpoints().get(0));
        PathfinderPosition wrongSpotSameRegion = new PathfinderPosition(
                correctCheckpoint.dimensionId(), correctCheckpoint.x() + 200, correctCheckpoint.y(), correctCheckpoint.z() + 200);
        assertFalse(progress.onReach(wrongSpotSameRegion, 1, RADIUS));
        assertEquals(ROUTE.checkpoints().get(0), progress.nextExpected());
    }

    @Test
    void wrongDimensionAtTheCorrectCoordinatesIsRejected() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 1000);
        PathfinderPosition correctCheckpoint = at(ROUTE.checkpoints().get(0));
        PathfinderPosition sameCoordsOtherDimension = new PathfinderPosition(
                "the_nether", correctCheckpoint.x(), correctCheckpoint.y(), correctCheckpoint.z());
        assertFalse(progress.onReach(sameCoordsOtherDimension, 1, RADIUS));
    }

    @Test
    void sameRegionDistinctCheckpointsAreTellApart() {
        // Two checkpoints manually placed in the SAME region but at different positions: reaching the
        // wrong one must not advance past it, even though both are "in region".
        PathfinderRegion region = new PathfinderRegion(9, 9);
        PathfinderCheckpoint first = new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 0, 0, 0));
        PathfinderCheckpoint second = new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 100, 0, 100));
        PathfinderRoute route = new PathfinderRoute(region, PathfinderLevel.LEVEL_1,
                java.util.List.of(first, second, new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 200, 0, 200)),
                        new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 300, 0, 300)),
                        new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 400, 0, 400)),
                        new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 500, 0, 500)),
                        new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 600, 0, 600)),
                        new PathfinderCheckpoint(region, new PathfinderPosition("overworld", 700, 0, 700))));
        PathfinderProgress progress = new PathfinderProgress(route, 0, 1000);

        assertFalse(progress.onReach(second.position(), 1, RADIUS), "reporting the second checkpoint before the first must be rejected");
        assertTrue(progress.onReach(first.position(), 2, RADIUS), "the first checkpoint, reported correctly, must be accepted");
        assertEquals(second, progress.nextExpected());
    }

    @Test
    void checkpointsMustBeReachedInSequenceThenCloseBackToTheFirst() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 1000);
        long tick = 1;
        for (PathfinderCheckpoint checkpoint : ROUTE.checkpoints()) {
            assertTrue(progress.onReach(at(checkpoint), tick++, RADIUS), "checkpoint " + checkpoint);
            assertFalse(progress.isFinished());
        }
        // Closing leg: return to the first checkpoint, not to the villager.
        assertEquals(ROUTE.checkpoints().get(0), progress.nextExpected());
        assertTrue(progress.onReach(at(ROUTE.checkpoints().get(0)), tick, RADIUS));
        assertTrue(progress.isFinished());
    }

    @Test
    void expiryFailsTheAttemptAndBlocksFurtherProgress() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 100);
        assertFalse(progress.onReach(at(ROUTE.checkpoints().get(0)), 150, RADIUS));
        assertTrue(progress.isExpired());
        assertFalse(progress.onReach(at(ROUTE.checkpoints().get(0)), 151, RADIUS));
    }

    @Test
    void expiryIsEvaluatedAtomicallyWithReach_exactDeadlineTickIsAlreadyExpired() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 100);
        // currentTick - startTick == timeLimitTicks exactly: expired, even though the position is correct.
        assertFalse(progress.onReach(at(ROUTE.checkpoints().get(0)), 100, RADIUS));
        assertTrue(progress.isExpired());
    }

    @Test
    void oneTickBeforeTheDeadlineStillAccepts() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 100);
        assertTrue(progress.onReach(at(ROUTE.checkpoints().get(0)), 99, RADIUS));
        assertFalse(progress.isExpired());
    }

    @Test
    void fullStateConstructorReconstructsAnEquivalentInProgressAttempt() {
        // Simulates what a reload reconstructs from persisted storage: a route re-resolved through
        // the route cache (not recomputed), plus the player's persisted reachedCount.
        PathfinderProgress original = new PathfinderProgress(ROUTE, 100, 1000);
        original.onReach(at(ROUTE.checkpoints().get(0)), 101, RADIUS);
        original.onReach(at(ROUTE.checkpoints().get(1)), 102, RADIUS);

        PathfinderProgress restored = new PathfinderProgress(
                original.route(), original.startTick(), original.timeLimitTicks(), original.reachedCount(), original.isFinished(), original.isExpired());

        assertEquals(original.nextExpected(), restored.nextExpected());
        assertEquals(original.reachedCount(), restored.reachedCount());
        // The restored attempt keeps advancing exactly like the original would have.
        assertTrue(restored.onReach(at(ROUTE.checkpoints().get(2)), 103, RADIUS));
    }

    @Test
    void fullStateConstructorRejectsAnOutOfRangeReachedCount() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PathfinderProgress(ROUTE, 0, 1000, ROUTE.checkpoints().size() + 1, false, false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PathfinderProgress(ROUTE, 0, 1000, -1, false, false));
    }

    @Test
    void concurrentAttemptsOnTheSameRouteDoNotShareOrCorruptEachOthersProgress() {
        PathfinderProgress playerA = new PathfinderProgress(ROUTE, 0, 1000);
        PathfinderProgress playerB = new PathfinderProgress(ROUTE, 0, 1000);

        assertTrue(playerA.onReach(at(ROUTE.checkpoints().get(0)), 1, RADIUS));
        assertTrue(playerA.onReach(at(ROUTE.checkpoints().get(1)), 2, RADIUS));

        // Player B has made no progress at all; A's advancement must not leak into B's state.
        assertEquals(ROUTE.checkpoints().get(0), playerB.nextExpected());
        assertFalse(playerB.onReach(at(ROUTE.checkpoints().get(1)), 2, RADIUS));
    }

    @Test
    void reachRadiusIsReadFreshNotFrozenAtRouteGeneration() {
        // Review follow-up: reach radius must not be baked into the cached checkpoint/route -
        // the SAME route accepts a reach at a smaller radius and rejects it at a larger one,
        // entirely controlled by what the caller passes to onReach, each call.
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 1000);
        PathfinderPosition correctCheckpoint = at(ROUTE.checkpoints().get(0));
        PathfinderPosition nearby = new PathfinderPosition(correctCheckpoint.dimensionId(),
                correctCheckpoint.x() + 10, correctCheckpoint.y(), correctCheckpoint.z());

        assertFalse(progress.onReach(nearby, 1, 3.0), "10 blocks away must miss a 3-block radius");
        assertTrue(progress.onReach(nearby, 2, 20.0), "the same route/position must accept a larger radius");
    }
}
