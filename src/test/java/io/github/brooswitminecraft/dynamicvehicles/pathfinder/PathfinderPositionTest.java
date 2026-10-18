package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicvehicles.delivery.ArrivalPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathfinderPosition#sameChunkAs}: MINECRAFT-403's "enters the current target chunk" rule,
 * including correctness across negative chunk boundaries (chunk (-1, -1) covers blocks -16..-1,
 * not 0..15 - a plain {@code / 16} would get this wrong for negative coordinates).
 */
class PathfinderPositionTest {

    @Test
    void sameChunkAnyYIsATrueMatch() {
        PathfinderPosition a = new PathfinderPosition("overworld", 5, 70, 5);
        PathfinderPosition b = new PathfinderPosition("overworld", 10, -40, 12);
        assertTrue(a.sameChunkAs(b), "both corners sit in chunk (0,0) regardless of Y");
    }

    @Test
    void adjacentChunkIsNotAMatch() {
        PathfinderPosition a = new PathfinderPosition("overworld", 15, 70, 15);
        PathfinderPosition b = new PathfinderPosition("overworld", 16, 70, 15);
        assertFalse(a.sameChunkAs(b), "block 16 is the first block of the NEXT chunk");
    }

    @Test
    void negativeChunkBoundaryIsCorrect() {
        PathfinderPosition withinNegativeChunk = new PathfinderPosition("overworld", -1, 70, -16);
        PathfinderPosition sameNegativeChunk = new PathfinderPosition("overworld", -16, 70, -1);
        assertTrue(withinNegativeChunk.sameChunkAs(sameNegativeChunk), "-16..-1 is one chunk, (-1,-1)");

        PathfinderPosition justAcrossZero = new PathfinderPosition("overworld", 0, 70, -1);
        assertFalse(withinNegativeChunk.sameChunkAs(justAcrossZero), "block 0 is chunk (0,0), not (-1,-1)");
    }

    /**
     * MINECRAFT-409 follow-up: John asked for Pathfinder arrival to get "the same type of check
     * that dispatcher uses" - verified compatible (same 16x16 floorDiv chunk rule, no Y/radius/
     * accessibility), but kept as its own implementation rather than sharing code with {@link
     * ArrivalPredicate}, since {@link PathfinderPosition} is deliberately Minecraft-free for a
     * different reason (unit-testable checkpoint-reach math, see its own javadoc) that a shared
     * helper would not change. These parity tests are the "explicit parity tests" alternative John
     * asked for instead: same X/Z inputs (including negative/fractional-after-floor coordinates,
     * correct vs. just-adjacent chunks) must agree between the two independent implementations,
     * so a future edit to either one that silently diverges them is caught here.
     */
    @Test
    void sameChunkAsAgreesWithDispatchersArrivalPredicate_exactMatch() {
        assertParity(100, 200, 100, 200);
        assertParity(100, 200, 96, 192);
        assertParity(100, 200, 111, 207);
    }

    @Test
    void sameChunkAsAgreesWithDispatchersArrivalPredicate_justAcrossTheBoundary() {
        assertParity(100, 200, 112, 200);
        assertParity(100, 200, 100, 191);
        assertParity(0, 0, 10_000, 10_000);
    }

    @Test
    void sameChunkAsAgreesWithDispatchersArrivalPredicate_negativeCoordinates() {
        assertParity(-5, -5, -16, -1);
        assertParity(-5, -5, 0, -1);
    }

    @Test
    void sameChunkAsAgreesWithDispatchersArrivalPredicate_fractionalPlayerPositionAfterFloor() {
        // A continuous player position (e.g. mid-block from player.position()) floors to the same
        // block coordinate ArrivalPredicate already takes as an integer - same outcome either way.
        assertParity(100, 200, 111.75, 192.5);
        assertParity(100, 200, 112.0, 192.5);
        assertParity(-5, -5, -0.5, -1.5);
    }

    private static void assertParity(double targetX, double targetZ, double candidateX, double candidateZ) {
        PathfinderPosition target = new PathfinderPosition("overworld", targetX, 70, targetZ);
        PathfinderPosition candidate = new PathfinderPosition("overworld", candidateX, 70, candidateZ);
        boolean pathfinderResult = target.sameChunkAs(candidate);
        boolean dispatcherResult = ArrivalPredicate.hasArrived(
                (long) Math.floor(targetX), (long) Math.floor(targetZ),
                (long) Math.floor(candidateX), (long) Math.floor(candidateZ));
        assertEquals(dispatcherResult, pathfinderResult, "Pathfinder and Dispatcher arrival checks must agree for ("
                + targetX + "," + targetZ + ") vs (" + candidateX + "," + candidateZ + ")");
    }
}
