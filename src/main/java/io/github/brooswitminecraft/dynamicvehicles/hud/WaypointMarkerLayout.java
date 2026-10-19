package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * Minecraft-free camera projection and edge-clamping for a mission waypoint
 * marker. Coordinates are normalized screen coordinates: (0, 0) is top-left
 * and (1, 1) is bottom-right.
 *
 * <p>MINECRAFT-411: moved here (unchanged) from {@code delivery.WaypointMarkerLayout}
 * so {@code delivery.ContractWaypointOverlay} and Pathfinder's mission overlay
 * share one implementation instead of a second divergent copy.
 */
public final class WaypointMarkerLayout {
    private static final double EPSILON = 1.0e-9;

    private WaypointMarkerLayout() {
    }

    public record Point(double x, double y, boolean clamped, double bearingRadians) {
    }

    /**
     * Projects a world-space displacement through the supplied orthonormal camera
     * basis. A destination behind the camera uses {@code abs(forward)} for its
     * bearing, avoiding the usual mirrored edge indicator.
     */
    public static Point project(
            double deltaX, double deltaY, double deltaZ,
            double forwardX, double forwardY, double forwardZ,
            double rightX, double rightY, double rightZ,
            double upX, double upY, double upZ,
            double horizontalFovDegrees, double aspectRatio, double edgeInset) {
        if (horizontalFovDegrees <= 0.0 || horizontalFovDegrees >= 180.0) {
            throw new IllegalArgumentException("horizontal FOV must be between 0 and 180");
        }
        if (aspectRatio <= 0.0 || edgeInset < 0.0 || edgeInset >= 0.5) {
            throw new IllegalArgumentException("invalid aspect ratio or edge inset");
        }

        double forward = dot(deltaX, deltaY, deltaZ, forwardX, forwardY, forwardZ);
        double right = dot(deltaX, deltaY, deltaZ, rightX, rightY, rightZ);
        double up = dot(deltaX, deltaY, deltaZ, upX, upY, upZ);
        double tangent = Math.tan(Math.toRadians(horizontalFovDegrees) / 2.0);
        double depth = Math.abs(forward);
        if (depth < EPSILON) {
            depth = EPSILON;
        }
        double ndcX = right / (depth * tangent);
        double ndcY = -up * aspectRatio / (depth * tangent);
        boolean onScreen = forward > 0.0 && Math.abs(ndcX) <= 1.0 && Math.abs(ndcY) <= 1.0;
        double x = (ndcX + 1.0) / 2.0;
        double y = (ndcY + 1.0) / 2.0;
        double bearing = Math.atan2(ndcY, ndcX);
        if (onScreen) {
            return new Point(x, y, false, bearing);
        }

        double dx = ndcX;
        double dy = ndcY;
        if (Math.abs(dx) < EPSILON && Math.abs(dy) < EPSILON) {
            // A destination exactly behind the camera has no screen-space side;
            // choose the lower edge rather than leaving it in the centre.
            dy = 1.0;
            bearing = Math.PI / 2.0;
        }
        double limit = 1.0 - 2.0 * edgeInset;
        double scale = limit / Math.max(Math.abs(dx), Math.abs(dy));
        return new Point(0.5 + dx * scale / 2.0, 0.5 + dy * scale / 2.0, true, bearing);
    }

    private static double dot(double ax, double ay, double az, double bx, double by, double bz) {
        return ax * bx + ay * by + az * bz;
    }
}
