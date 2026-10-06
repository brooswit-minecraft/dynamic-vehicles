package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure per-wheel arithmetic for the Sable rigid-body car: the spring-damper
 * suspension. Free of Minecraft and Sable types so it can be unit tested.
 * Starting numbers for a 1200 kg car on four wheels, to be tuned in play.
 */
public final class WheelMath {
    /** Distance from a wheel mount to the ground at which the spring is unloaded, metres. */
    public static final double REST_LENGTH = 0.65;
    /** Spring rate, N/m: about 0.15 m of compression under a quarter of the car. */
    public static final double SPRING_RATE = 19_620.0;
    /** Damper rate, N*s/m: roughly 0.7 of critical damping for a 300 kg quarter. */
    public static final double DAMPING_RATE = 3_400.0;
    /** Never pull the car down onto the ground: a spring can only push. */
    public static final double MAX_FORCE = 40_000.0;

    /** Share of the car carried and driven by one wheel, kg (a quarter of 1200 kg). */
    public static final double EFFECTIVE_MASS = 300.0;
    /** Peak tire friction coefficient on a reference (dry pavement) surface. */
    public static final double BASE_FRICTION = 1.1;
    /** Rolling resistance coefficient: a force of this times the wheel load opposes rolling. */
    public static final double ROLLING_RESISTANCE = 0.015;
    /** How much of the correction to a sliding tire is applied per tick; below 1 so it never overshoots. */
    public static final double RELAXATION = 0.6;

    private WheelMath() {}

    /**
     * The widest front-wheel angle (radians) the car can hold without exceeding the tires' grip at this speed:
     * cornering needs a lateral acceleration of v^2 * tan(angle) / wheelbase, and we allow 80% of mu * g.
     * At low speed the mechanical limit applies instead.
     */
    public static double maxSteerAngle(double speed, double mu, double wheelbase, double mechanicalLimit) {
        double v2 = Math.max(speed * speed, 1.0);
        return Math.min(mechanicalLimit, Math.atan(0.8 * mu * 9.81 * wheelbase / v2));
    }

    /**
     * What one tire does this tick.
     *
     * @param longitudinal force along the wheel's rolling direction, N
     * @param lateral force across it, N
     * @param slipSpeed metres per second of sliding the tire could not correct this tick (0 while gripping)
     */
    public record Tire(double longitudinal, double lateral, double slipSpeed) {}

    /**
     * A friction-circle tire: it wants to cancel sideways sliding and to deliver the drive or brake force,
     * but the combined force cannot exceed {@code mu * normalForce}; whatever the circle cannot supply shows
     * up as slip.
     *
     * @param vLong contact-patch velocity along the wheel's rolling direction, m/s
     * @param vLat contact-patch velocity across it, m/s
     * @param normalForce load on the tire (the suspension force), N
     * @param mu friction coefficient available on this surface
     * @param lateralScale 1 normally; below 1 lets the tire slide (handbrake)
     * @param driveForce engine force wanted at the contact, N (negative: reverse)
     * @param brakeForce maximum braking force, N (0 when not braking)
     */
    public static Tire tire(double vLong, double vLat, double normalForce, double mu, double lateralScale,
            double driveForce, double brakeForce, double dt) {
        if (!(normalForce > 0) || !(mu > 0)) {
            return new Tire(0.0, 0.0, 0.0);
        }
        double limit = mu * normalForce;
        double wantLat = -EFFECTIVE_MASS * vLat / dt * RELAXATION * lateralScale;
        double wantLong;
        if (brakeForce > 0) {
            double stop = EFFECTIVE_MASS * vLong / dt * RELAXATION;
            wantLong = -Math.max(-brakeForce, Math.min(brakeForce, stop));
        } else {
            double rolling = Math.abs(vLong) > 0.05 ? -Math.signum(vLong) * ROLLING_RESISTANCE * normalForce : 0.0;
            wantLong = driveForce + rolling;
        }
        double demand = Math.hypot(wantLong, wantLat);
        double scale = demand > limit ? limit / demand : 1.0;
        double slip = demand > limit ? (demand - limit) * dt / EFFECTIVE_MASS : 0.0;
        return new Tire(wantLong * scale, wantLat * scale, slip);
    }

    /**
     * @param compression metres the spring is shorter than its rest length (negative: not touching)
     * @param compressionRate m/s the spring is being squeezed (positive: the wheel is moving toward the car body)
     * @return the force the spring pushes the car up with, N (never negative)
     */
    public static double suspensionForce(double compression, double compressionRate) {
        if (!(compression > 0)) {
            return 0.0;
        }
        double force = SPRING_RATE * compression + DAMPING_RATE * compressionRate;
        return Math.max(0.0, Math.min(MAX_FORCE, force));
    }
}
