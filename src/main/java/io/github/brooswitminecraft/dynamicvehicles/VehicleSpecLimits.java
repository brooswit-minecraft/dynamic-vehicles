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

    /** Wing downforce coefficient (MINECRAFT-446), N per (m/s)^2 applied at one axle. Identity is 0.0 (no
     * wing at all, the whole roster today); generous headroom above the roster's own existing 0.4 N/(m/s)^2
     * global air-drag constant, since a real wing's downforce coefficient is routinely several times its
     * own drag coefficient. Negative has no sensible meaning for a downforce-only field. */
    static final double MIN_WING_DOWNFORCE_COEFF = 0.0;
    static final double MAX_WING_DOWNFORCE_COEFF = 5.0;

    /** Per-vehicle aerodynamic drag coefficient (MINECRAFT-446), N per (m/s)^2. Identity/stock default is
     * 0.4 (the roster's own pre-existing hardcoded {@code AIR_DRAG} constant, same for every vehicle until
     * an operator overrides it). Negative has no sensible meaning (drag always opposes motion). */
    static final double MIN_WING_DRAG_COEFF = 0.0;
    static final double MAX_WING_DRAG_COEFF = 5.0;

    private VehicleSpecLimits() {}

    /** A non-finite value falls back to {@code fallback} so malformed config can never leak NaN/Infinity
     * into wheel physics; a finite out-of-range value is clamped, not rejected. */
    static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
