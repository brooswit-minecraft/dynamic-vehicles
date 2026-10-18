package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderProgressTest {

    private static final PathfinderRegion ORIGIN = new PathfinderRegion(3, 3);
    private static final PathfinderRoute ROUTE = PathfinderRouteGenerator.generate(ORIGIN, PathfinderLevel.LEVEL_2);

    @Test
    void outOfOrderCheckpointIsRejectedAndDoesNotAdvance() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 1000);
        PathfinderRegion wrong = ROUTE.checkpoints().get(1); // second checkpoint, reported first
        assertFalse(progress.onReachRegion(wrong));
        assertEquals(ROUTE.checkpoints().get(0), progress.nextExpected());
    }

    @Test
    void checkpointsMustBeReachedInSequenceThenCloseBackToTheFirst() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 1000);
        for (PathfinderRegion checkpoint : ROUTE.checkpoints()) {
            assertTrue(progress.onReachRegion(checkpoint), "checkpoint " + checkpoint);
            assertFalse(progress.isFinished());
        }
        // Closing leg: return to the first checkpoint, not to the villager.
        assertEquals(ROUTE.checkpoints().get(0), progress.nextExpected());
        assertTrue(progress.onReachRegion(ROUTE.checkpoints().get(0)));
        assertTrue(progress.isFinished());
    }

    @Test
    void expiryFailsTheAttemptAndBlocksFurtherProgress() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 100);
        assertTrue(progress.tick(150));
        assertTrue(progress.isExpired());
        assertFalse(progress.onReachRegion(ROUTE.checkpoints().get(0)));
    }

    @Test
    void stillWithinTheTimeLimitDoesNotExpire() {
        PathfinderProgress progress = new PathfinderProgress(ROUTE, 0, 100);
        assertFalse(progress.tick(99));
        assertFalse(progress.isExpired());
    }

    @Test
    void concurrentAttemptsOnTheSameRouteDoNotShareOrCorruptEachOthersProgress() {
        PathfinderProgress playerA = new PathfinderProgress(ROUTE, 0, 1000);
        PathfinderProgress playerB = new PathfinderProgress(ROUTE, 0, 1000);

        assertTrue(playerA.onReachRegion(ROUTE.checkpoints().get(0)));
        assertTrue(playerA.onReachRegion(ROUTE.checkpoints().get(1)));

        // Player B has made no progress at all; A's advancement must not leak into B's state.
        assertEquals(ROUTE.checkpoints().get(0), playerB.nextExpected());
        assertFalse(playerB.onReachRegion(ROUTE.checkpoints().get(1)));
    }
}
