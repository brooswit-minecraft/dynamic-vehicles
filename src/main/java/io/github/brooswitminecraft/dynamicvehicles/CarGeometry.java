package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The one description of the car's shape, shared by the physics body, the
 * renderer and the debug overlay so what you see is what the engine simulates.
 * Body frame: origin at the body's centre, x right, y up, z forward.
 */
public final class CarGeometry {
    /** Half extents of the rigid-body box, metres. */
    public static final double HALF_X = 0.95;
    public static final double HALF_Y = 0.5;
    public static final double HALF_Z = 1.5;
    /** Wheel mounts in the body frame (x, y, z), where each suspension ray starts. */
    public static final double[][] MOUNTS = {{-0.8, -0.4, 1.2}, {0.8, -0.4, 1.2}, {-0.8, -0.4, -1.2}, {0.8, -0.4, -1.2}};
    public static final double WHEEL_RADIUS = 0.35;
    public static final double WHEEL_WIDTH = 0.3;
    /** Height of the body centre above the ground when the car rests on its springs (see WheelMath). */
    public static final double RIDE_HEIGHT = 0.9;

    private CarGeometry() {}

    /** Centre height of a wheel in the body frame when the tire touches the ground at rest. */
    public static double wheelCentreY() {
        return -RIDE_HEIGHT + WHEEL_RADIUS;
    }
}
