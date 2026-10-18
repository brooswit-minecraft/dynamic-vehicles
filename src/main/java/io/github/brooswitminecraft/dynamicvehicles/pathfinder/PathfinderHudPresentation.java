package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * Pure, Minecraft-free decision for what {@link PathfinderHudOverlay} should show, extracted so
 * it is unit-testable without a client. Review follow-up: the HUD payload always carried a
 * dimension, but the overlay never checked it against the player's own current dimension, so a
 * player who changed dimension (even just to the Nether and back) saw a directional arrow
 * pointing at a position computed in a completely different coordinate space - not a "false
 * waypoint" in the sense of a wrong direction, but a meaningless one.
 */
public final class PathfinderHudPresentation {

    private PathfinderHudPresentation() {
    }

    public enum Mode {
        /** The player is in the checkpoint's own dimension: the directional arrow is meaningful. */
        ARROW,
        /** The player is in a different dimension: show cross-dimension guidance instead of an arrow. */
        WRONG_DIMENSION
    }

    public static Mode decide(String hudDimension, String playerDimension) {
        return hudDimension.equals(playerDimension) ? Mode.ARROW : Mode.WRONG_DIMENSION;
    }
}
