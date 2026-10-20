package io.github.brooswitminecraft.dynamicvehicles.town;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-388 review round 2, finding #6: the pure success/cleanup decision
 * {@link TownBootstrapOutcome#evaluatePlacement} must mark the region founded ONLY when both the
 * campfire and its villager are confirmed placed, and must call for cleaning up an orphan campfire
 * in exactly the one partial-failure shape that would otherwise strand it (campfire placed,
 * villager spawn failed) - never in any other combination.
 */
class TownFounderPlacementTest {
    @Test
    void bothPlacementsSucceedingMarksFoundedAndNeedsNoCleanup() {
        TownBootstrapOutcome.PlacementOutcome outcome = TownBootstrapOutcome.evaluatePlacement(true, true);

        assertTrue(outcome.markFounded(), "both placements confirmed should commit the founded claim");
        assertFalse(outcome.cleanupCampfire(), "no cleanup is needed when both placements succeeded");
    }

    @Test
    void campfireFailingNeverMarksFoundedAndNeedsNoCleanup() {
        // Nothing was placed at all - there is no orphan campfire to clean up, and the region must
        // remain retryable.
        TownBootstrapOutcome.PlacementOutcome outcome = TownBootstrapOutcome.evaluatePlacement(false, false);

        assertFalse(outcome.markFounded(), "a failed campfire placement must never mark the region founded");
        assertFalse(outcome.cleanupCampfire(), "there is no campfire to clean up when its own placement failed");
    }

    @Test
    void campfireSucceedingButVillagerFailingNeitherMarksFoundedNorLeavesAnOrphan() {
        TownBootstrapOutcome.PlacementOutcome outcome = TownBootstrapOutcome.evaluatePlacement(true, false);

        assertFalse(outcome.markFounded(), "a half-succeeded bootstrap must never mark the region founded");
        assertTrue(outcome.cleanupCampfire(), "the orphan campfire (placed with no claiming villager) must be cleaned up");
    }

    @Test
    void retryingAfterAPartialFailureCanStillSucceed() {
        TownBootstrapOutcome.PlacementOutcome firstAttempt = TownBootstrapOutcome.evaluatePlacement(true, false);
        assertTrue(firstAttempt.cleanupCampfire());
        assertFalse(firstAttempt.markFounded());

        // After cleanup, a later retry (campfire placed again, villager spawning this time) must
        // be free to succeed and commit the founded claim - the earlier failure must not have
        // poisoned anything.
        TownBootstrapOutcome.PlacementOutcome retry = TownBootstrapOutcome.evaluatePlacement(true, true);
        assertTrue(retry.markFounded());
        assertFalse(retry.cleanupCampfire());
    }
}
