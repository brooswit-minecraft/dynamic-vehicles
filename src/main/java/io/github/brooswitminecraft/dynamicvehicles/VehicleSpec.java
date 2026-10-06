package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Everything that differs between vehicle types: the shape the physics, renderer and debug overlay share
 * (body frame: origin at the body's centre, x right, y up, z forward), the suspension and the drivetrain.
 * The car's numbers are {@link CarGeometry} and {@link WheelMath}'s, unchanged.
 */
public record VehicleSpec(
        double halfX, double halfY, double halfZ,
        double[][] mounts, double wheelRadius, double wheelWidth,
        /** Height of the body centre above the ground when resting on its springs. */
        double rideHeight,
        /** Distance from a wheel mount to the ground at which the spring is unloaded. */
        double restLength,
        double massKg, double springRate, double dampingRate, double maxSpringForce,
        double wheelbase, double maxSpeed,
        /** Engine force per wheel, and the brakes, relative to the car's. */
        double forceScale,
        /** Multiplies the engine sound's pitch (below 1: deeper). */
        double enginePitch,
        /** Where the driver sits, from the entity origin (bottom centre of the box). */
        double seatY, double seatZ) {

    public static final VehicleSpec CAR = new VehicleSpec(
            CarGeometry.HALF_X, CarGeometry.HALF_Y, CarGeometry.HALF_Z,
            CarGeometry.MOUNTS, CarGeometry.WHEEL_RADIUS, CarGeometry.WHEEL_WIDTH, CarGeometry.RIDE_HEIGHT,
            WheelMath.REST_LENGTH, 1200.0, WheelMath.SPRING_RATE, WheelMath.DAMPING_RATE, WheelMath.MAX_FORCE,
            CarPhysics.WHEELBASE, 32.0, 1.0, 1.0, 0.55, -0.1);

    /**
     * Slightly wider, taller and longer than the car, 2200 kg. The body box bottom rides 0.65 m off the
     * ground (the car's is 0.4), so a half slab (0.5 m) passes under it and the wheels climb on; the
     * springs have 1.0 m of rest length for the extra travel. Spring and damper are the car's scaled to a
     * quarter of the truck's mass, so it sags the same 0.2 m.
     */
    public static final VehicleSpec TRUCK = new VehicleSpec(
            1.1, 0.65, 2.0,
            new double[][] {{-0.95, -0.5, 1.5}, {0.95, -0.5, 1.5}, {-0.95, -0.5, -1.5}, {0.95, -0.5, -1.5}},
            0.5, 0.4, 1.3,
            1.0, 2200.0, 26_980.0, 5_390.0, 73_000.0,
            3.0, 26.0, 2200.0 / 1200.0, 0.75, 0.75, 0.3);

    public double wheelCentreY() {
        return -rideHeight + wheelRadius;
    }

    /** Entity width and height, metres: the physics box. */
    public float width() {
        return (float) (2 * halfX);
    }

    public float height() {
        return (float) (2 * halfY);
    }
}
