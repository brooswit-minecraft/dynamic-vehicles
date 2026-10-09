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
        double seatY, double seatZ,
        /** Grip on loose surfaces is multiplied by this (capped at the pavement's), and soft ground's rolling resistance by rollingScale. */
        double looseGrip, double rollingScale,
        /** This vehicle's tire model tuning; {@link TireTuning#IDENTITY} for every vehicle that has not opted in. */
        TireTuning tireTuning) {

    /**
     * Per-vehicle knobs for {@link DriftTireModel}, the pure-function tire model layered on top of
     * {@link WheelMath}'s own friction-circle tire. {@link #IDENTITY} is the value every field must take
     * for {@link SableCarBody#tick} to skip {@link DriftTireModel} entirely and run the untouched
     * {@link WheelMath#tire} path instead (see {@link #isIdentity()}) &mdash; the hard constraint that
     * {@code CAR}, {@code TRUCK} and {@code TROPHY} stay byte-for-byte unchanged depends on every one of
     * them carrying exactly {@code IDENTITY}.
     *
     * @param rearGripScale rear tire grip (mu) as a fraction of the front's; 1.0 = no front/rear split (identity)
     * @param slipAngleThreshold lateral slip speed, m/s, below which a tire grips at its full (scaled) mu;
     *        beyond it grip falls off toward {@code 1 - gripFalloff}. Unused while {@code gripFalloff} is 0
     * @param gripFalloff fraction of grip a tire can lose once sliding well past {@code slipAngleThreshold};
     *        0 = no falloff curve at all (identity) &mdash; grip stays at the plain scaled mu regardless of slip
     * @param handbrakeRearGripCut extra multiplier on the rear handbrake's existing lateral-grip cut
     *        (applied on top of {@code SableCarBody}'s own handbrake {@code lateralScale}, which every
     *        vehicle already gets); 1.0 = no extra cut (identity)
     * @param throttleBite fraction of a rear tire's grip given up to the drive force's own share of the
     *        friction circle (friction-circle style: the more of the circle the drive force is already
     *        using, the less lateral grip is left); 0 = no extra bite beyond the plain friction circle
     *        every vehicle already has (identity)
     * @param counterSteerAssist fraction of the grip lost to {@code gripFalloff} restored when the driver
     *        steers into the slide (the classic countersteer) at this wheel; 0 = no recovery assist (identity)
     */
    public record TireTuning(double rearGripScale, double slipAngleThreshold, double gripFalloff,
            double handbrakeRearGripCut, double throttleBite, double counterSteerAssist) {

        /** No front/rear split, no falloff, no extra handbrake cut, no throttle bite, no countersteer assist. */
        public static final TireTuning IDENTITY = new TireTuning(1.0, 999.0, 0.0, 1.0, 0.0, 0.0);

        /** Whether this tuning is exactly {@link #IDENTITY} &mdash; the gate {@code SableCarBody.tick} uses to skip {@link DriftTireModel}. */
        public boolean isIdentity() {
            return this.equals(IDENTITY);
        }
    }

    public static final VehicleSpec CAR = new VehicleSpec(
            CarGeometry.HALF_X, CarGeometry.HALF_Y, CarGeometry.HALF_Z,
            CarGeometry.MOUNTS, CarGeometry.WHEEL_RADIUS, CarGeometry.WHEEL_WIDTH, CarGeometry.RIDE_HEIGHT,
            WheelMath.REST_LENGTH, 1200.0, WheelMath.SPRING_RATE, WheelMath.DAMPING_RATE, WheelMath.MAX_FORCE,
            CarPhysics.WHEELBASE, 32.0, 1.0, 1.0, 0.55, -0.1, 1.0, 1.0, TireTuning.IDENTITY);

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
            3.0, 26.0, 2200.0 / 1200.0, 0.75, 0.75, 0.3, 1.0, 1.0, TireTuning.IDENTITY);

    /**
     * An offroad racer: 2.6 m wide (a wide track keeps it from rolling), a low 1.0 m body riding 1.1 m off
     * the ground on long, soft suspension (1.6 m rest length, sagging 0.3 m), 1500 kg with strong drive for
     * its weight, and tires that keep more grip and roll easier on loose ground. The body's bottom clears a
     * full block.
     */
    public static final VehicleSpec TROPHY = new VehicleSpec(
            1.3, 0.5, 1.9,
            new double[][] {{-1.25, -0.3, 1.4}, {1.25, -0.3, 1.4}, {-1.25, -0.3, -1.4}, {1.25, -0.3, -1.4}},
            0.6, 0.5, 1.6,
            1.6, 1500.0, 12_260.0, 2_570.0, 50_000.0,
            2.8, 40.0, 1.8, 1.25, 0.5, 0.1, 1.35, 0.6, TireTuning.IDENTITY);

    /**
     * A low, sporty car: slightly narrower and noticeably lower than the car (0.75 m ride height against
     * the car's 0.9), 1100 kg with a shorter 0.6 m spring rest length to match the lower stance. Spring and
     * damper are the car's scaled to its mass. Drive force, top speed and grip match the car's exactly
     * (see {@code VehicleSpecTest.driftDrivesLikeTheCarForNow}); what makes it drift is its own
     * {@code tireTuning}, applied by {@link DriftTireModel} on top of the shared per-wheel physics: lower
     * rear grip than front, an early, forgiving slide onset, a sharper handbrake-induced rear-grip cut,
     * throttle-induced oversteer, and countersteer recovery.
     */
    private static final TireTuning TIRE_TUNING = new TireTuning(0.72, 1.2, 0.45, 0.5, 0.5, 0.6);

    public static final VehicleSpec DRIFT = new VehicleSpec(
            0.9, 0.42, 1.5,
            new double[][] {{-0.75, -0.35, 1.2}, {0.75, -0.35, 1.2}, {-0.75, -0.35, -1.2}, {0.75, -0.35, -1.2}},
            0.35, 0.3, 0.75,
            0.6, 1100.0, 17_985.0, 3_117.0, 36_667.0,
            2.4, 32.0, 1.0, 1.0, 0.5, -0.1, 1.0, 1.0, TIRE_TUNING);

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
