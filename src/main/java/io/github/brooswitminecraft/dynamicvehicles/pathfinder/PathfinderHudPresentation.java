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

    /**
     * The arrow's on-screen rotation: {@code (dx, dz)}'s absolute bearing, relative to {@code
     * cameraYawDegrees} - MINECRAFT-409 review follow-up: the arrow is a screen-space overlay
     * rotated to match what is actually rendered, so it must be relative to the real render
     * camera's yaw (whatever sets that - ordinarily the player's own {@code getYRot()}, but a
     * fixed-angle camera mode some other mod installs can hold the rendered camera at a constant
     * yaw independent of the player's), never the player entity's own logical facing. {@link
     * PathfinderHudOverlay} passes {@code Minecraft.getInstance().gameRenderer.getMainCamera()
     * .getYRot()} here, not {@code player.getYRot()}, precisely so a fixed-yaw rendered camera is
     * naturally picked up with no dependency on whatever mod sets it: under an ordinary
     * perspective the render camera's yaw already equals the player's, so this changes nothing
     * there.
     */
    public static double relativeBearing(double dx, double dz, double cameraYawDegrees) {
        double absoluteBearing = Math.atan2(dz, dx) * (180.0 / Math.PI) - 90.0;
        return wrapDegrees(absoluteBearing - cameraYawDegrees);
    }

    /** Same wrap as {@code Mth.wrapDegrees}, reimplemented so this class stays Minecraft-free. */
    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }
}
