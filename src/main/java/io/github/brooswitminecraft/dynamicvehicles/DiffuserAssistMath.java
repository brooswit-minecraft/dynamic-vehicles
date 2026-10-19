package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure arithmetic for the Diffuser KIT's four arcade orientation assists (MINECRAFT-447, recovering
 * MINECRAFT-395's R02/R16-R19 from the original "Minecraft physics update implementation plan"): roll
 * self-righting, pitch landing help, heading alignment and trajectory alignment. Free of Minecraft/Sable
 * types (angles and rates come in as plain radians/rad-per-second, already extracted from the body's
 * orientation/angular-velocity by the caller) so every assist's activation condition, bounded authority
 * and time scaling is directly unit-testable, the same tested-core/thin-glue split {@link WheelMath} and
 * {@link DriftTireModel} already use.
 *
 * <p>Every method returns a single body-space torque component (N*m) about one axis, already clamped to
 * its own vehicle's {@link VehicleSpec.DiffuserTuning} bound: {@code 0.0} whenever that assist is disabled,
 * its bound is {@code 0.0}, or its own activation condition is not met (see each method's own doc) &mdash;
 * never NaN or infinite for any finite input, so a caller can sum these directly into one tick's torque
 * impulse with no extra guarding. These are deliberately artificial game-feel corrections, keyed to an
 * orientation error rather than to airflow, and are kept distinct in telemetry and docs from two separate
 * mechanisms they must not be confused with or double-count against: MINECRAFT-446's wing work, which
 * produces genuine applied aerodynamic forces and moments, and MINECRAFT-184's already-shipped
 * {@code VehicleSpec.downforceGripPerSpeed}, which is a tire-grip multiplier rather than a force or moment
 * at all. Nothing here reads or writes either of them.
 */
public final class DiffuserAssistMath {
    private DiffuserAssistMath() {}

    private static double clamp(double value, double bound) {
        if (!(bound > 0.0) || !Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(-bound, Math.min(bound, value));
    }

    /**
     * R16: roll self-righting. A proportional-derivative torque pushing {@code rollAngleRad} (signed,
     * radians, 0 = upright, &plusmn;&pi; = fully inverted, positive the direction {@code rollRateRadPerSec}
     * is measured in) back toward 0, damped by the current roll rate. Uses {@code sin(rollAngleRad)} rather
     * than the raw angle so the torque is exactly zero at both the stable equilibrium (upright, 0) and the
     * unstable one (fully inverted, &plusmn;&pi;) &mdash; a car balanced exactly upside down gets no
     * spurious kick in either direction, matching a real anti-roll-bar-style restoring force rather than a
     * spring that would fight itself at the inverted extreme. Grounded/airborne gating is the caller's: an
     * assist that only makes sense while airborne (recovering from a rollover) is gated by the caller
     * checking contact state before calling this, not by this method, which only knows the angle and rate.
     */
    public static double rollSelfRightingTorque(double rollAngleRad, double rollRateRadPerSec,
            VehicleSpec.DiffuserTuning tuning) {
        if (!tuning.rollSelfRightingEnabled()) {
            return 0.0;
        }
        double torque = -tuning.rollSelfRightingGain() * Math.sin(rollAngleRad)
                - tuning.rollSelfRightingDamping() * rollRateRadPerSec;
        return clamp(torque, tuning.rollSelfRightingMaxTorque());
    }

    /**
     * R17: pitch and landing help from a predicted time-to-touchdown. Inactive (returns 0) whenever
     * {@code timeToTouchdownSeconds} is not a finite, non-negative number (no ground found within probe
     * range, already touching, or any other "invalid estimate" the caller's prediction reports), or is at
     * or beyond {@link VehicleSpec.DiffuserTuning#pitchLandingHorizonSeconds}. Otherwise this assist's
     * authority ramps linearly from 0 at the horizon to 1 at touchdown ({@code 1 - ttd/horizon}), so a car
     * that just left the ground gets no sudden correction and the full bounded correction only arrives in
     * the last instants before landing &mdash; a deliberate "landing help", not a mid-air attitude lock.
     * The underlying torque itself levels {@code pitchAngleRad} (0 = level) with the same PD shape as
     * {@link #rollSelfRightingTorque}, then both the proportional/derivative output AND the bound it is
     * clamped to are scaled by that same authority, so the assist's own bound shrinks smoothly to 0 at the
     * horizon rather than switching on at full strength.
     */
    public static double pitchLandingTorque(double pitchAngleRad, double pitchRateRadPerSec,
            double timeToTouchdownSeconds, VehicleSpec.DiffuserTuning tuning) {
        if (!tuning.pitchLandingEnabled() || !(tuning.pitchLandingHorizonSeconds() > 0.0)) {
            return 0.0;
        }
        if (!Double.isFinite(timeToTouchdownSeconds) || timeToTouchdownSeconds < 0.0
                || timeToTouchdownSeconds >= tuning.pitchLandingHorizonSeconds()) {
            return 0.0;
        }
        double authority = 1.0 - timeToTouchdownSeconds / tuning.pitchLandingHorizonSeconds();
        double torque = authority * (-tuning.pitchLandingGain() * pitchAngleRad
                - tuning.pitchLandingDamping() * pitchRateRadPerSec);
        return clamp(torque, authority * tuning.pitchLandingMaxTorque());
    }

    /**
     * R18: heading alignment, toward a target supplied by the caller as {@code headingErrorRad} (signed,
     * radians, the shortest turn from the car's current heading to its target heading &mdash; wrapped to
     * {@code (-pi, pi]} by the caller before this call, since this method does no wrapping of its own).
     * Deliberately distinct from {@link #trajectoryAlignmentTorque} (R19): callers pick this assist's own
     * target independently of the velocity vector (e.g. the heading the car held before leaving the
     * ground), so the two never read the same error term. Player steering reduces this assist's authority
     * linearly with steer magnitude (0 = centered, 1 = full lock either direction) by
     * {@code headingAlignmentSteerSuppression}, so a deliberate full-lock steering input can fully
     * override the assist when that suppression is 1, and never reduce it at all when 0.
     */
    public static double headingAlignmentTorque(double headingErrorRad, double yawRateRadPerSec,
            double steerInput, VehicleSpec.DiffuserTuning tuning) {
        if (!tuning.headingAlignmentEnabled()) {
            return 0.0;
        }
        double suppression = Math.max(0.0, Math.min(1.0,
                tuning.headingAlignmentSteerSuppression() * Math.abs(steerInput)));
        double authority = 1.0 - suppression;
        double torque = authority * (tuning.headingAlignmentGain() * headingErrorRad
                - tuning.headingAlignmentDamping() * yawRateRadPerSec);
        return clamp(torque, authority * tuning.headingAlignmentMaxTorque());
    }

    /**
     * R19: trajectory alignment, pulling the car's nose toward its own horizontal velocity direction (the
     * caller supplies {@code trajectoryErrorRad}, signed radians, the shortest turn from the car's current
     * heading to its horizontal velocity direction, wrapped to {@code (-pi, pi]} by the caller). Inactive
     * (returns 0) below {@code trajectoryAlignmentMinSpeed} (m/s): at near-zero horizontal speed the
     * velocity direction is numerically noisy and not a trustworthy target, exactly the "near-zero-speed/
     * invalid estimate" case this assist must not chase. Also inactive while {@code intentionalSlide} is
     * true, the caller's own signal (e.g. handbrake held, or steering strongly opposed to the direction of
     * travel) that the player is deliberately sliding sideways rather than wanting their nose pulled back
     * in line &mdash; this assist must never fight an intentional drift.
     */
    public static double trajectoryAlignmentTorque(double trajectoryErrorRad, double yawRateRadPerSec,
            double horizontalSpeed, boolean intentionalSlide, VehicleSpec.DiffuserTuning tuning) {
        if (!tuning.trajectoryAlignmentEnabled() || intentionalSlide) {
            return 0.0;
        }
        if (!(horizontalSpeed >= tuning.trajectoryAlignmentMinSpeed())) {
            return 0.0;
        }
        double torque = tuning.trajectoryAlignmentGain() * trajectoryErrorRad
                - tuning.trajectoryAlignmentDamping() * yawRateRadPerSec;
        return clamp(torque, tuning.trajectoryAlignmentMaxTorque());
    }

    /**
     * Wraps any angle (radians) to {@code (-pi, pi]} &mdash; the convention every {@code *ErrorRad}
     * parameter above requires its caller to have already applied. Exposed because computing a heading or
     * trajectory error (target minus current, both arbitrary angles) needs exactly this wrap and every
     * caller would otherwise duplicate it.
     */
    public static double wrapAngle(double angleRad) {
        double wrapped = (angleRad + Math.PI) % (2 * Math.PI);
        if (wrapped <= 0.0) {
            wrapped += 2 * Math.PI;
        }
        return wrapped - Math.PI;
    }
}
