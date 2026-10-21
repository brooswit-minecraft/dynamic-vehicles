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

    /** MINECRAFT-445: the fraction of front steer angle applied to the rear wheels. Bounded at +/-1.0 --
     * a magnitude of 1.0 already means the rear wheels turn as hard as the fronts, and anything beyond that
     * is not a steering geometry any real car has. Negative is opposite-phase, positive same-phase; see
     * {@code VehicleSpec.SteeringTuning#rearSteerRatio}. */
    static final double MIN_REAR_STEER_RATIO = -1.0;
    static final double MAX_REAR_STEER_RATIO = 1.0;

    /** MINECRAFT-445: tire pressure in bar. The floor is strictly positive -- both pressure multipliers
     * divide by it -- and the range spans a genuinely flat off-road tire (0.5) to well past a loaded
     * truck tire (8.0), around the {@link WheelMath#NOMINAL_TIRE_PRESSURE_BAR} every car ships at. */
    static final double MIN_TIRE_PRESSURE_BAR = 0.5;
    static final double MAX_TIRE_PRESSURE_BAR = 8.0;

    /** MINECRAFT-445: anti-dive / anti-squat fractions. 0.0 is today's behaviour (full weight transfer)
     * and 1.0 cancels the pitch couple entirely; beyond 1.0 would apply the force ABOVE the body and
     * invert the pitch, which is not a geometry any real suspension has. */
    static final double MIN_PITCH_RESIST = 0.0;
    static final double MAX_PITCH_RESIST = 1.0;

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

    /** Computer-assist authority: zero is deliberately the disabled/stock behaviour. */
    static final double MIN_COMPUTER_ASSIST_LEVEL = 0.0;
    static final double MAX_COMPUTER_ASSIST_LEVEL = 1.0;

    private VehicleSpecLimits() {}

    /** A non-finite value falls back to {@code fallback} so malformed config can never leak NaN/Infinity
     * into wheel physics; a finite out-of-range value is clamped, not rejected. */
    static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
