package io.github.brooswitminecraft.dynamicvehicles;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-397 review fix: regression coverage for the actual landing-selection
 * algorithm {@link FlatGarageCommand} uses for both the return teleport and the
 * fixed garage-spawn teleport, exercised here without any Minecraft type (see
 * {@link SafeLandingSearch}'s javadoc for why that split exists).
 */
class SafeLandingSearchTest {

    /** @param obstructedYs every Y this fake world considers solid/obstructed. */
    private static SafeLandingSearch.Obstruction worldOf(Set<Integer> obstructedYs) {
        return obstructedYs::contains;
    }

    @Test
    void startingPositionIsReturnedWhenAlreadyClear() {
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(Set.of()));

        assertEquals(OptionalLong.of(64), found);
    }

    @Test
    void obstructedFeetSearchesUpwardToTheNearestClearSpot() {
        // Someone built a block at the departure feet position (MINECRAFT-397 review repro).
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(Set.of(64)));

        assertEquals(OptionalLong.of(65), found);
    }

    @Test
    void obstructedHeadAlsoCountsAsUnsafe() {
        // Y=64 itself is clear, but its head (65) is obstructed, so the start position must be
        // rejected - not just checked at the feet.
        Set<Integer> obstructed = Set.of(65);
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(obstructed));

        assertTrue(found.isPresent());
        assertTrue(found.getAsLong() != 64, "must not land at the start Y once its head is obstructed");
        int y = (int) found.getAsLong();
        assertTrue(!obstructed.contains(y) && !obstructed.contains(y + 1));
    }

    @Test
    void searchesDownwardWhenUpwardIsBlocked() {
        // Everything from Y=65 upward (feet or head) is obstructed; the first fully clear
        // two-block gap is just below the start.
        Set<Integer> obstructed = Set.of(65, 66, 67, 68, 69, 70);
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(obstructed));

        assertTrue(found.isPresent());
        int y = (int) found.getAsLong();
        assertTrue(!obstructed.contains(y) && !obstructed.contains(y + 1));
        assertTrue(y < 64);
    }

    @Test
    void returnsEmptyWhenNoSafeYExistsWithinWorldBounds() {
        // A world only one block tall at the search column: no feet+head (2-tall) pair can ever fit,
        // regardless of obstruction, because maxY (0) leaves no room above minY (0) for a head block.
        OptionalLong found = SafeLandingSearch.findSafeY(0, 0, 0, worldOf(Set.of()));

        assertTrue(found.isEmpty());
    }

    @Test
    void returnsEmptyWhenEveryReachableYIsObstructed() {
        Set<Integer> everything = new java.util.HashSet<>();
        for (int y = -64; y <= 320; y++) {
            everything.add(y);
        }
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(everything));

        assertTrue(found.isEmpty());
    }
}
