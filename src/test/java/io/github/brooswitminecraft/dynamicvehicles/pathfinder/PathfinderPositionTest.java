package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import org.junit.jupiter.api.Test;

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
}
