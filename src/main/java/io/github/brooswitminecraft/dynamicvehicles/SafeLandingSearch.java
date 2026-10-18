package io.github.brooswitminecraft.dynamicvehicles;

import java.util.OptionalLong;

/**
 * Pure landing-safety search (MINECRAFT-397 review fixes): given a starting Y,
 * the world's Y bounds, and a caller-supplied column query, finds the nearest
 * Y (searching outward, alternating up/down) at which a player can actually
 * stand safely - feet (Y) and head (Y+1) both unobstructed and not
 * hazardous, AND a solid, non-hazardous floor immediately below (Y-1), so a
 * landing is never an unsupported fall nor a step into something like lava -
 * or reports none found within range or world bounds.
 *
 * <p>Kept free of every Minecraft type - no {@code BlockState}, no
 * {@code ServerLevel} - so the search order, bounds enforcement and
 * termination are directly unit-testable. This repo's test sourceSet has no
 * path to vanilla/NeoForge classes outside a running mod loader (confirmed
 * by {@code DispatcherProfessionTest}'s own javadoc), so a landing search
 * built directly against {@code BlockState} could not be exercised here at
 * all. {@link FlatGarageCommand} supplies the real obstruction/hazard query
 * and mirrors this exact algorithm, the same tested-core/thin-glue split
 * {@code ReturnLocationCodec}/{@code ReturnLocationStorage} already use.
 */
final class SafeLandingSearch {
    private SafeLandingSearch() {
    }

    static final int MAX_SEARCH_DISTANCE = 64;

    /** Everything the search needs to know about one Y level in a fixed X/Z column. */
    interface ColumnQuery {
        /** True if this Y cannot share space with a player (a solid-ish block). */
        boolean isObstructed(int y);

        /** True if occupying this Y is dangerous even though it isn't obstructed (e.g. lava). */
        boolean isHazardous(int y);
    }

    /**
     * @return the found feet Y (head is this value + 1, required floor is this value - 1), or
     *         empty if no safe Y was found within {@link #MAX_SEARCH_DISTANCE} or the given
     *         world bounds. Every Y ever passed to {@code query} lies within {@code [minY, maxY]}.
     */
    static OptionalLong findSafeY(int startY, int minY, int maxY, ColumnQuery query) {
        if (isSafe(startY, minY, maxY, query)) {
            return OptionalLong.of(startY);
        }
        for (int distance = 1; distance <= MAX_SEARCH_DISTANCE; distance++) {
            int up = startY + distance;
            if (isSafe(up, minY, maxY, query)) {
                return OptionalLong.of(up);
            }
            int down = startY - distance;
            if (isSafe(down, minY, maxY, query)) {
                return OptionalLong.of(down);
            }
        }
        return OptionalLong.empty();
    }

    private static boolean isSafe(int y, int minY, int maxY, ColumnQuery query) {
        // Floor (y-1), feet (y) and head (y+1) must all lie within the world's actual bounds
        // before the floor/feet/head checks below ever query any of them.
        if (y - 1 < minY || y + 1 > maxY) {
            return false;
        }
        return query.isObstructed(y - 1) // solid floor - no unsupported fall
                && !query.isObstructed(y) && !query.isHazardous(y) // feet clear and safe
                && !query.isObstructed(y + 1) && !query.isHazardous(y + 1); // head clear and safe
    }
}
