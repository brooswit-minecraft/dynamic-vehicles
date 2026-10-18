package io.github.brooswitminecraft.dynamicvehicles;

import java.util.OptionalLong;

/**
 * Pure landing-safety search (MINECRAFT-397 review fix): given a starting Y,
 * the world's Y bounds, and a caller-supplied "is this Y obstructed" query,
 * finds the nearest Y (searching outward, alternating up/down) at which both
 * that Y and the one above it ("feet" and "head") are unobstructed - or
 * reports none found within range or world bounds.
 *
 * <p>Kept free of every Minecraft type - no {@code BlockState}, no
 * {@code ServerLevel} - so the search order and termination are directly
 * unit-testable. This repo's test sourceSet has no path to vanilla/NeoForge
 * classes outside a running mod loader (confirmed by {@code
 * DispatcherProfessionTest}'s own javadoc), so a landing search built
 * directly against {@code BlockState} could not be exercised here at all.
 * {@link FlatGarageCommand} supplies the real {@code BlockState}-backed
 * obstruction query and mirrors this exact algorithm, the same
 * tested-core/thin-glue split {@code ReturnLocationCodec}/{@code
 * ReturnLocationStorage} already use.
 */
final class SafeLandingSearch {
    private SafeLandingSearch() {
    }

    static final int MAX_SEARCH_DISTANCE = 64;

    @FunctionalInterface
    interface Obstruction {
        boolean isObstructed(int y);
    }

    /**
     * @return the found feet Y (head is this value + 1), or empty if no safe Y was found
     *         within {@link #MAX_SEARCH_DISTANCE} or the given world bounds.
     */
    static OptionalLong findSafeY(int startY, int minY, int maxY, Obstruction obstruction) {
        if (isSafe(startY, maxY, obstruction)) {
            return OptionalLong.of(startY);
        }
        for (int distance = 1; distance <= MAX_SEARCH_DISTANCE; distance++) {
            int up = startY + distance;
            if (isSafe(up, maxY, obstruction)) {
                return OptionalLong.of(up);
            }
            int down = startY - distance;
            if (down >= minY && isSafe(down, maxY, obstruction)) {
                return OptionalLong.of(down);
            }
        }
        return OptionalLong.empty();
    }

    private static boolean isSafe(int y, int maxY, Obstruction obstruction) {
        return y + 1 <= maxY && !obstruction.isObstructed(y) && !obstruction.isObstructed(y + 1);
    }
}
