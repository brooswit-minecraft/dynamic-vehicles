package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review follow-up (PR #163, review 5563448396): "infinity currently accepts any same-dimension
 * position" and non-finite stored coordinates were not rejected. The reach-radius checks live on
 * {@link PathfinderCheckpoint#isReachedBy} now, not the constructor — a later review follow-up
 * moved the radius from a field stored on the checkpoint to a parameter callers pass at check
 * time (see that class's javadoc), so validating it had to move with it.
 */
class PathfinderValidationTest {

    private static final PathfinderCheckpoint CHECKPOINT =
            new PathfinderCheckpoint(new PathfinderRegion(0, 0), new PathfinderPosition("overworld", 0, 0, 0));
    private static final PathfinderPosition SAME_SPOT = new PathfinderPosition("overworld", 0, 0, 0);

    @Test
    void infiniteReachRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CHECKPOINT.isReachedBy(SAME_SPOT, Double.POSITIVE_INFINITY));
    }

    @Test
    void nanReachRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CHECKPOINT.isReachedBy(SAME_SPOT, Double.NaN));
    }

    @Test
    void zeroOrNegativeReachRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CHECKPOINT.isReachedBy(SAME_SPOT, 0));
        assertThrows(IllegalArgumentException.class, () -> CHECKPOINT.isReachedBy(SAME_SPOT, -1));
    }

    @Test
    void nonFinitePositionCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PathfinderPosition("overworld", Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PathfinderPosition("overworld", 0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new PathfinderPosition("overworld", 0, 0, Double.NEGATIVE_INFINITY));
    }
}
