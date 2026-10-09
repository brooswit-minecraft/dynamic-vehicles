package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MINECRAFT-130 review: the join-level backstop for a tagged mob with no matching {@link EncounterRecord}. */
class OrphanAmbushCleanupTest {

    @Test
    void taggedWithNoRecord_andNoOneNearby_isDiscarded() {
        assertTrue(OrphanAmbushCleanup.shouldDiscardOrphan(false, false));
    }

    @Test
    void taggedWithNoRecord_butSomeoneNearby_isKept() {
        assertFalse(OrphanAmbushCleanup.shouldDiscardOrphan(false, true));
    }

    @Test
    void taggedWithALiveRecord_isKept_regardlessOfNearby_leftToTheNormalPredicateInstead() {
        assertFalse(OrphanAmbushCleanup.shouldDiscardOrphan(true, false));
        assertFalse(OrphanAmbushCleanup.shouldDiscardOrphan(true, true));
    }
}
