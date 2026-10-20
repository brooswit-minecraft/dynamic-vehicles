package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The safe operating range for the Diffuser KIT's per-car config fields (MINECRAFT-447), the same
 * generous-around-the-roster convention {@link VehicleSpecLimits}/{@link VehicleTuningLimits} already use.
 * No existing car configures a non-identity value for any of these (every stock {@link
 * VehicleSpec.DiffuserTuning} is {@link VehicleSpec.DiffuserTuning#IDENTITY}), so these bounds are chosen
 * around what the assist math itself ({@link DiffuserAssistMath}) and the real GameTests that exercised it
 * needed, not an audited existing spread.
 */
final class DiffuserTuningLimits {
    /** A torque bound of 0 disables that assist outright (see {@link VehicleSpec.DiffuserTuning}'s own
     * javadoc); the upper bound is generously above the strongest value exercised in this ticket's own
     * GameTests (25,000 N*m) without being large enough to risk a non-finite intermediate in the force-
     * couple realization ({@code SableCarBody#applyWorldTorqueAsCouple}). */
    static final double MIN_MAX_TORQUE = 0.0;
    static final double MAX_MAX_TORQUE = 100_000.0;

    /** Proportional/derivative gains: 0 means that term contributes nothing (still a valid, finite, fully
     * inert configuration for that term alone, distinct from the assist's own enabled flag or its
     * {@code MaxTorque} bound). */
    static final double MIN_GAIN = 0.0;
    static final double MAX_GAIN = 100_000.0;
    static final double MIN_DAMPING = 0.0;
    static final double MAX_DAMPING = 20_000.0;

    /** R17's own authority-ramp window, seconds: 0 means the assist never activates (identity); the upper
     * bound is generous against any plausible jump/landing arc this mod's existing vehicles can produce. */
    static final double MIN_PITCH_LANDING_HORIZON_SECONDS = 0.0;
    static final double MAX_PITCH_LANDING_HORIZON_SECONDS = 10.0;

    /** R18's player-steering authority suppression: a fraction, 0 (steering never reduces this assist) to
     * 1 (full steer input fully overrides it). */
    static final double MIN_STEER_SUPPRESSION = 0.0;
    static final double MAX_STEER_SUPPRESSION = 1.0;

    /** R19's own near-zero-speed cutoff, m/s: 0 means the assist is never gated off by low speed (not
     * recommended, but a valid finite configuration); the upper bound is above every existing vehicle's own
     * top speed (the Indy Car's 48 m/s), so a config file cannot accidentally disable this assist across
     * this roster's entire usable speed range. */
    static final double MIN_TRAJECTORY_MIN_SPEED = 0.0;
    static final double MAX_TRAJECTORY_MIN_SPEED = 60.0;

    private DiffuserTuningLimits() {}

    /** Clamps config/test input. A non-finite value falls back to {@code defaults}' own value for that
     * field, so malformed config can never leak NaN/Infinity into the diffuser torque math; a missing
     * {@code enabled} flag (passed through as whatever {@code requested} already resolved it to, by the
     * caller) is not touched here. */
    static VehicleSpec.DiffuserTuning clamp(VehicleSpec.DiffuserTuning requested, VehicleSpec.DiffuserTuning defaults) {
        return new VehicleSpec.DiffuserTuning(
                requested.rollSelfRightingEnabled(),
                clamp(requested.rollSelfRightingMaxTorque(), MIN_MAX_TORQUE, MAX_MAX_TORQUE, defaults.rollSelfRightingMaxTorque()),
                clamp(requested.rollSelfRightingGain(), MIN_GAIN, MAX_GAIN, defaults.rollSelfRightingGain()),
                clamp(requested.rollSelfRightingDamping(), MIN_DAMPING, MAX_DAMPING, defaults.rollSelfRightingDamping()),
                requested.pitchLandingEnabled(),
                clamp(requested.pitchLandingMaxTorque(), MIN_MAX_TORQUE, MAX_MAX_TORQUE, defaults.pitchLandingMaxTorque()),
                clamp(requested.pitchLandingHorizonSeconds(), MIN_PITCH_LANDING_HORIZON_SECONDS,
                        MAX_PITCH_LANDING_HORIZON_SECONDS, defaults.pitchLandingHorizonSeconds()),
                clamp(requested.pitchLandingGain(), MIN_GAIN, MAX_GAIN, defaults.pitchLandingGain()),
                clamp(requested.pitchLandingDamping(), MIN_DAMPING, MAX_DAMPING, defaults.pitchLandingDamping()),
                requested.headingAlignmentEnabled(),
                clamp(requested.headingAlignmentMaxTorque(), MIN_MAX_TORQUE, MAX_MAX_TORQUE, defaults.headingAlignmentMaxTorque()),
                clamp(requested.headingAlignmentGain(), MIN_GAIN, MAX_GAIN, defaults.headingAlignmentGain()),
                clamp(requested.headingAlignmentDamping(), MIN_DAMPING, MAX_DAMPING, defaults.headingAlignmentDamping()),
                clamp(requested.headingAlignmentSteerSuppression(), MIN_STEER_SUPPRESSION, MAX_STEER_SUPPRESSION,
                        defaults.headingAlignmentSteerSuppression()),
                requested.trajectoryAlignmentEnabled(),
                clamp(requested.trajectoryAlignmentMaxTorque(), MIN_MAX_TORQUE, MAX_MAX_TORQUE, defaults.trajectoryAlignmentMaxTorque()),
                clamp(requested.trajectoryAlignmentGain(), MIN_GAIN, MAX_GAIN, defaults.trajectoryAlignmentGain()),
                clamp(requested.trajectoryAlignmentDamping(), MIN_DAMPING, MAX_DAMPING, defaults.trajectoryAlignmentDamping()),
                clamp(requested.trajectoryAlignmentMinSpeed(), MIN_TRAJECTORY_MIN_SPEED, MAX_TRAJECTORY_MIN_SPEED,
                        defaults.trajectoryAlignmentMinSpeed()));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
