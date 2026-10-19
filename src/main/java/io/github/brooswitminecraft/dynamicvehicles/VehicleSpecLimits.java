package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The safe operating range for the per-car config file fields that are not already covered by
 * {@link VehicleTuningLimits} (MINECRAFT-443). These bounds are chosen generously around the existing
 * roster's own spread (see {@code docs/per-car-config.md} for the audited per-vehicle numbers) so an
 * operator can retune a stock car well past its current feel without opening the door to a non-finite,
 * negative-physics or otherwise nonsensical value reaching {@code SableCarBody}/{@code WheelMath}.
 */
final class VehicleSpecLimits {
    /** Roster today: 0.5 (Indy) to 2.3 (Monster Truck). Zero or negative has no sensible meaning
     * ({@link VehicleSpec#wheelCentreY()} subtracts this from the wheel radius). */
    static final double MIN_RIDE_HEIGHT = 0.1;
    static final double MAX_RIDE_HEIGHT = 5.0;

    /** Roster today: 1841.67 (Indy) to 13,600 (Cargo Truck). Zero (no damping at all) is physically
     * degenerate but not non-finite, so it is allowed rather than special-cased. */
    static final double MIN_DAMPING_RATE = 0.0;
    static final double MAX_DAMPING_RATE = 40_000.0;

    /** Identity is 1.0; the roster's own tuned high end is 2.0 (Rock Crawler). 0.0 means no anti-roll bar
     * contribution at all (softer than every existing vehicle), still a valid, finite configuration. */
    static final double MIN_ANTI_ROLL_SCALE = 0.0;
    static final double MAX_ANTI_ROLL_SCALE = 5.0;

    /** Identity is 1.0; the roster's own tuned low end is 0.5 (Rock Crawler, more loose-ground roll).
     * 0.0 (no rolling resistance at all) is a valid, finite configuration. */
    static final double MIN_ROLLING_SCALE = 0.0;
    static final double MAX_ROLLING_SCALE = 5.0;

    /** Identity is 1.0 (every vehicle today). 0.0 (no handbrake braking force at all, though the fixed
     * lateral-slide cut in {@code SableCarBody.tick} still applies) is a valid, finite configuration. */
    static final double MIN_HANDBRAKE_SCALE = 0.0;
    static final double MAX_HANDBRAKE_SCALE = 5.0;

    /** Engine/clutch coupling (MINECRAFT-444). Stock for every car on the roster is
     * {@link VehicleSpec.DrivetrainCoupling#STOCK} (i.e. {@link DrivetrainMath}'s own constants); these
     * bounds are generous around it while keeping a configured engine physically meaningful. A zero or
     * negative inertia is excluded outright rather than clamped loosely: it is a divisor in
     * {@link DrivetrainMath#engineAngularVelocity}, so zero would produce a non-finite engine speed. */
    static final double MIN_ENGINE_INERTIA = 0.01;
    static final double MAX_ENGINE_INERTIA = 5.0;

    /** Zero peak torque (an engine that cannot drive the car at all) is degenerate but finite and valid. */
    static final double MIN_ENGINE_PEAK_TORQUE = 0.0;
    static final double MAX_ENGINE_PEAK_TORQUE = 10_000.0;

    /** Zero means a freewheeling engine: no engine braking at all, still a valid configuration. */
    static final double MIN_ENGINE_BRAKING_TORQUE = 0.0;
    static final double MAX_ENGINE_BRAKING_TORQUE = 5_000.0;

    /** Zero capacity is a permanently slipping clutch that transmits nothing -- degenerate, but finite and
     * well-defined (the car simply never drives), so it is allowed rather than special-cased. */
    static final double MIN_CLUTCH_TORQUE_CAPACITY = 0.0;
    static final double MAX_CLUTCH_TORQUE_CAPACITY = 20_000.0;

    private VehicleSpecLimits() {}

    /** A non-finite value falls back to {@code fallback} so malformed config can never leak NaN/Infinity
     * into wheel physics; a finite out-of-range value is clamped, not rejected. */
    static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
