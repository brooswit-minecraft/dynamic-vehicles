package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MINECRAFT-411: covers the checkpoint-advance transitions (reached count increasing while total
 * stays fixed, and the final reached==total state) the shared Pathfinder progress display must
 * read correctly, server-authoritative values only - this never computes or increments anything.
 */
class ProgressFormatTest {

    @Test
    void formatsReachedOverTotal() {
        assertEquals("0/3", ProgressFormat.format(0, 3));
        assertEquals("1/3", ProgressFormat.format(1, 3));
    }

    @Test
    void reachingTheFinalCheckpointStillReadsAsReachedOverTotal() {
        assertEquals("3/3", ProgressFormat.format(3, 3));
    }

    @Test
    void noCheckpointsYetIsZeroOverZero() {
        // Start-of-attempt edge case before the server has ever reported a total.
        assertEquals("0/0", ProgressFormat.format(0, 0));
    }
}
