package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-110 AC6/AC9: the tight arrival criterion - same chunk column as
 * the destination start, never the whole placement region - including the
 * near-miss case AC9 explicitly requires.
 */
class ArrivalPredicateTest {

    @Test
    void hasArrived_trueAtExactStartPosition() {
        assertTrue(ArrivalPredicate.hasArrived(100, 200, 100, 200));
    }

    @Test
    void hasArrived_trueAnywhereInsideTheSameChunk() {
        // start at (100, 200) -> chunk (6, 12), spanning blocks [96,112) x [192,208)
        assertTrue(ArrivalPredicate.hasArrived(100, 200, 96, 192));
        assertTrue(ArrivalPredicate.hasArrived(100, 200, 111, 207));
    }

    @Test
    void hasArrived_falseJustOutsideTheChunkBoundary_nearMiss() {
        // one block across the chunk's X edge, despite being very close in raw distance
        assertFalse(ArrivalPredicate.hasArrived(100, 200, 112, 200));
        // one block across the chunk's Z edge
        assertFalse(ArrivalPredicate.hasArrived(100, 200, 100, 191));
    }

    @Test
    void hasArrived_falseFarAway() {
        assertFalse(ArrivalPredicate.hasArrived(0, 0, 10_000, 10_000));
    }

    @Test
    void hasArrived_handlesNegativeCoordinatesAcrossTheOriginBoundary() {
        // start chunk (-1, -1) spans [-16, 0) x [-16, 0)
        assertTrue(ArrivalPredicate.hasArrived(-5, -5, -16, -1));
        assertFalse(ArrivalPredicate.hasArrived(-5, -5, 0, -1));
    }
}
