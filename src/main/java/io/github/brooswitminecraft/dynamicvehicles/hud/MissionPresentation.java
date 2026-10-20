package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * Pure, Minecraft-free decision for what a mission waypoint overlay should show this frame, given
 * only server-authoritative state: whether the mission is active, and whether the player's current
 * dimension matches the one the mission's next/destination position is expressed in.
 *
 * <p>MINECRAFT-411: generalizes {@code pathfinder.PathfinderHudPresentation#decide}'s own
 * active/dimension check (previously handled as two separate checks - an overlay-level
 * {@code !hud.active()} early return, then a dimension comparison) into one shared decision, so a
 * mission overlay has a single place that says "nothing to show" vs. "show a marker" vs. "show a
 * wrong-dimension status" instead of re-deriving that from two booleans at each call site.
 */
public final class MissionPresentation {

    private MissionPresentation() {
    }

    public enum Mode {
        /** No mission is active: render nothing. */
        HIDDEN,
        /** The mission is active and its position is in the player's current dimension: render the marker. */
        MARKER,
        /** The mission is active but its position is in a different dimension: a marker would be meaningless. */
        WRONG_DIMENSION
    }

    public static Mode decide(boolean active, boolean dimensionMatches) {
        if (!active) {
            return Mode.HIDDEN;
        }
        return dimensionMatches ? Mode.MARKER : Mode.WRONG_DIMENSION;
    }
}
