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

    /** MINECRAFT-445: same range as the existing {@code dampingRate} field above -- a fast-compression,
     * slow-rebound or fast-rebound rate is physically the same kind of quantity, just applied in a
     * different speed/direction regime. */
    static final double MIN_TIRE_KIT_DAMPING = MIN_DAMPING_RATE;
    static final double MAX_TIRE_KIT_DAMPING = MAX_DAMPING_RATE;

    /** MINECRAFT-445: the compression-rate magnitude (m/s) separating "slow" from "fast" damping. 0.0 (every
     * speed is "fast") is a valid, finite configuration; the roster's wheels rarely see compression rates
     * much above a few m/s even over rough terrain, so 10.0 is a generous upper bound. */
    static final double MIN_DAMPING_KNEE_SPEED = 0.0;
    static final double MAX_DAMPING_KNEE_SPEED = 10.0;

    /** MINECRAFT-445: metres of compression before the progressive bump stop engages. 0.0 (engaged from the
     * very first touch of compression) is a valid, finite configuration; the upper bound deliberately
     * tracks {@link #MAX_RIDE_HEIGHT} (no car's suspension travel can exceed the tallest ride height this
     * format allows) rather than restating its number, so the two can never drift apart. A value a
     * particular car's own geometry can never actually reach is simply inert (the bump stop never engages),
     * not degenerate, so unlike {@code rideHeight} this field needs no per-car {@code isUsable} check. */
    static final double MIN_BUMP_STOP_TRAVEL = 0.0;
    static final double MAX_BUMP_STOP_TRAVEL = MAX_RIDE_HEIGHT;

    /** MINECRAFT-445: N/m applied only to the compression past {@code bumpStopTravel}. 0.0 (no extra bump
     * stop force at all, same as an unreachable {@code bumpStopTravel}) is a valid, finite configuration;
     * the upper bound is an order of magnitude past the roster's stiffest {@code springRate} ({@code
     * CARGO_TRUCK}'s 78,480), generous headroom for a genuinely hard mechanical stop. */
    static final double MIN_BUMP_STOP_RATE = 0.0;
    static final double MAX_BUMP_STOP_RATE = 500_000.0;

    private VehicleSpecLimits() {}

    /** A non-finite value falls back to {@code fallback} so malformed config can never leak NaN/Infinity
     * into wheel physics; a finite out-of-range value is clamped, not rejected. */
    static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
