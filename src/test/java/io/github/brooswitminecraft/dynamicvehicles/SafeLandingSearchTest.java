package io.github.brooswitminecraft.dynamicvehicles;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-397 review fixes: regression coverage for the actual landing-selection
 * algorithm {@link FlatGarageCommand} uses for both the return teleport and the
 * fixed garage-spawn teleport, exercised here without any Minecraft type (see
 * {@link SafeLandingSearch}'s javadoc for why that split exists).
 */
class SafeLandingSearchTest {

    /** A column where every Y in {@code solidYs} is solid (obstructed) and every Y in {@code lavaYs} is hazardous. */
    private static SafeLandingSearch.ColumnQuery worldOf(Set<Integer> solidYs, Set<Integer> lavaYs) {
        return new SafeLandingSearch.ColumnQuery() {
            @Override
            public boolean isObstructed(int y) {
                return solidYs.contains(y);
            }

            @Override
            public boolean isHazardous(int y) {
                return lavaYs.contains(y);
            }
        };
    }

    private static SafeLandingSearch.ColumnQuery worldOf(Set<Integer> solidYs) {
        return worldOf(solidYs, Set.of());
    }

    @Test
    void startingPositionIsReturnedWhenAlreadySafelySupported() {
        // Floor at 63, clear feet/head at 64/65 - a normal standing spot.
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(Set.of(63)));

        assertEquals(OptionalLong.of(64), found);
    }

    @Test
    void obstructedFeetSearchesUpwardToTheNearestClearSupportedSpot() {
        // Someone built a block at the departure feet position (original review repro); 65 has
        // floor (64), and clear feet/head (65/66).
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(Set.of(63, 64)));

        assertEquals(OptionalLong.of(65), found);
    }

    @Test
    void unsupportedAirIsRejectedEvenWhenFeetAndHeadAreClear() {
        // Feet/head at 64/65 are clear, but nothing solid is anywhere below (a removed
        // bridge/platform): landing there would just be a fall, so it must not be accepted.
        // The only real floor in this column is far below, at 10 (so 11 is the real landing).
        Set<Integer> solid = Set.of(10);
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(solid));

        assertEquals(OptionalLong.of(11), found);
    }

    @Test
    void lavaAtFeetIsRejectedEvenThoughItDoesNotBlockMotion() {
        // Lava does not block motion (not "obstructed"), but standing in it is still unsafe.
        Set<Integer> solid = Set.of(63, 66);
        Set<Integer> lava = Set.of(64);
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(solid, lava));

        assertEquals(OptionalLong.of(67), found);
    }

    @Test
    void lavaAtHeadIsAlsoRejected() {
        Set<Integer> solid = Set.of(63, 66);
        Set<Integer> lava = Set.of(65);
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(solid, lava));

        assertEquals(OptionalLong.of(67), found);
    }

    @Test
    void searchesDownwardWhenUpwardIsBlocked() {
        // Everything from Y=64 upward is obstructed; the first fully safe spot below has
        // floor at 50 and clear feet/head at 51/52.
        Set<Integer> solid = new HashSet<>();
        for (int y = 64; y <= 80; y++) {
            solid.add(y);
        }
        solid.add(50);
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(solid));

        assertEquals(OptionalLong.of(51), found);
    }

    @Test
    void enforcesTheWorldFloorForTheStartingAndUpwardCandidates() {
        // MINECRAFT-397 review repro: a start Y below minY, with nothing ever obstructed, must
        // not be accepted outright just because it was never checked against minY.
        OptionalLong found = SafeLandingSearch.findSafeY(-65, -64, 319, worldOf(Set.of()));

        assertTrue(found.isEmpty());
    }

    @Test
    void returnsEmptyWhenNoSafeYExistsWithinWorldBounds() {
        // A world only two blocks tall at the search column: no floor+feet+head (3-tall) stack
        // can ever fit, regardless of obstruction.
        OptionalLong found = SafeLandingSearch.findSafeY(0, 0, 1, worldOf(Set.of()));

        assertTrue(found.isEmpty());
    }

    @Test
    void returnsEmptyWhenEverySupportedSpotIsAlsoHazardous() {
        Set<Integer> solid = new HashSet<>();
        Set<Integer> lava = new HashSet<>();
        for (int y = -64; y <= 320; y++) {
            solid.add(y);
            lava.add(y);
        }
        OptionalLong found = SafeLandingSearch.findSafeY(64, -64, 320, worldOf(solid, lava));

        assertTrue(found.isEmpty());
    }
}
