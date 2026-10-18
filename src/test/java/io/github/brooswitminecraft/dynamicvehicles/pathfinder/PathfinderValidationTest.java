package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review follow-up (PR #163, review 5563448396): "infinity currently accepts any same-dimension
 * position" and non-finite stored coordinates were not rejected.
 */
class PathfinderValidationTest {

    @Test
    void nonFinitePositionCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PathfinderPosition("overworld", Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PathfinderPosition("overworld", 0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new PathfinderPosition("overworld", 0, 0, Double.NEGATIVE_INFINITY));
    }
}
