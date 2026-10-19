package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * Keeps each active mission's current marker panel bounds for the frame, so a second mission's
 * marker - rendered after the first - can detect and resolve an overlap instead of painting over
 * it. Minecraft-free so the overlap resolution itself is directly unit-testable.
 *
 * <p>PR #177 review: Dispatcher's and Pathfinder's overlays each projected and drew independently,
 * with no shared notion of the other's occupied screen space; two destinations along the same
 * camera ray (or both clamped to the same edge point) landed at the same screen position, and
 * whichever overlay rendered second (Pathfinder, registered after Dispatcher) painted its own
 * panel directly over the other's - a colour difference alone cannot keep an obscured marker
 * visible.
 *
 * <p>Each mission overlay calls {@link #clear} when its own mission isn't currently showing a
 * marker, and {@link #reserve} with its own just-computed bounds otherwise, every frame. Dispatcher
 * is registered (and so rendered) before Pathfinder, so by the time Pathfinder calls {@code
 * reserve}, Dispatcher's slot already holds this exact frame's value, not a stale one; the reverse
 * direction (Dispatcher checking Pathfinder's slot) would be at most one frame stale, which is not
 * visually significant for a screen position that only changes with camera/player movement.
 *
 * <p>Re-review follow-up: resolving an overlap by unconditionally shifting the candidate straight
 * down could itself push an already bottom-edge-clamped candidate below the viewport (two targets
 * both clamped near the bottom edge is exactly the case both panels are near the bottom in the
 * first place). {@link #reserve} now also tries shifting up, and keeps whichever direction stays
 * fully on screen.
 */
public final class MissionWaypointLayout {

    public enum Mission {
        DISPATCHER, PATHFINDER
    }

    /** Minimum gap left between two resolved panels. */
    private static final int GAP = 4;

    private static final Rect[] occupied = new Rect[Mission.values().length];

    private MissionWaypointLayout() {
    }

    /** Call once per frame when {@code mission} has no marker to show this frame. */
    public static void clear(Mission mission) {
        occupied[mission.ordinal()] = null;
    }

    /**
     * Reserves {@code candidate} for {@code mission} within a {@code viewportHeight}-tall screen,
     * returning it moved just far enough, in whichever vertical direction keeps it fully on
     * screen, to clear every other mission's currently reserved bounds it would otherwise overlap.
     */
    public static Rect reserve(Mission mission, Rect candidate, int viewportHeight) {
        Rect resolved = candidate;
        for (Mission other : Mission.values()) {
            if (other == mission) {
                continue;
            }
            Rect otherBounds = occupied[other.ordinal()];
            if (otherBounds != null) {
                resolved = resolveOverlap(otherBounds, resolved, viewportHeight);
            }
        }
        occupied[mission.ordinal()] = resolved;
        return resolved;
    }

    static Rect resolveOverlap(Rect occupiedBounds, Rect candidate, int viewportHeight) {
        if (!occupiedBounds.overlaps(candidate)) {
            return candidate;
        }
        Rect below = candidate.translate(0, occupiedBounds.bottom() - candidate.top() + GAP);
        if (below.bottom() <= viewportHeight) {
            return below;
        }
        Rect above = candidate.translate(0, occupiedBounds.top() - GAP - candidate.bottom());
        if (above.top() >= 0) {
            return above;
        }
        // Neither direction keeps the candidate fully on screen (the two panels, stacked with a
        // gap, are taller than the viewport) - clamp into the viewport as a last resort; this can
        // still touch the other panel in that degenerate case, but never leaves the screen.
        int clampedTop = Math.max(0, Math.min(below.top(), viewportHeight - candidate.height()));
        return candidate.translate(0, clampedTop - candidate.top());
    }

    /** Test-only: drops all reservations so tests don't leak state into each other. */
    static void resetForTesting() {
        java.util.Arrays.fill(occupied, null);
    }
}
