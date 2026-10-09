package io.github.brooswitminecraft.dynamicvehicles;

/**
 * A drift-tuned tire model layered on top of {@link WheelMath}'s own friction-circle tire: a pure function
 * of a wheel's own inputs plus its vehicle's {@link VehicleSpec.TireTuning}, used only by
 * {@link SableCarBody#tick} for a wheel whose spec carries a non-{@linkplain VehicleSpec.TireTuning#isIdentity()
 * identity} tuning. Every other vehicle's wheels keep calling {@link WheelMath#tire} directly and never
 * reach this class, so {@code CAR}/{@code TRUCK}/{@code TROPHY} are untouched by construction, not by
 * coincidence of the numbers below happening to cancel out.
 * <p>
 * This does not reimplement the friction circle: it only adjusts the {@code mu} and {@code lateralScale}
 * {@link WheelMath#tire} already takes, then delegates to it, so every other part of the tire's behaviour
 * (rolling resistance, braking relaxation, the friction-circle clamp itself, slip reporting) stays exactly
 * {@code WheelMath}'s.
 */
public final class DriftTireModel {
    private DriftTireModel() {}

    /**
     * The mu {@link WheelMath#tire} should use for this wheel this step: the plain front/rear split, then
     * (only once sliding past {@code slipAngleThreshold}) the falloff curve, then (for a driven rear wheel)
     * throttle bite, then countersteer recovery. Each stage is a no-op at its tuning's identity value, so
     * calling this with {@link VehicleSpec.TireTuning#IDENTITY} returns {@code mu} unchanged.
     *
     * @param mu the surface's own friction coefficient, as passed to {@link WheelMath#tire}
     * @param vLat contact-patch lateral velocity, m/s (same sign convention as {@link WheelMath#tire}'s own {@code vLat})
     * @param driveForce the drive force requested at this wheel this step, N (0 when coasting/braking)
     * @param normalForce load on the tire, N
     * @param steerInput the driver's own steer command, -1..1, positive = left (see {@link CarPhysics#step})
     * @param tuning this vehicle's tire tuning
     * @param front whether this is a front (steered) wheel
     */
    public static double effectiveMu(double mu, double vLat, double driveForce, double normalForce,
            double steerInput, VehicleSpec.TireTuning tuning, boolean front) {
        double baseMu = front ? mu : mu * tuning.rearGripScale();
        if (tuning.gripFalloff() <= 0.0) {
            return baseMu;
        }
        double absVLat = Math.abs(vLat);
        double excess = Math.max(0.0, absVLat - tuning.slipAngleThreshold());
        // Saturates toward (1 - gripFalloff) as excess grows, so a sliding tire always keeps a grip floor
        // (never drops to zero) - that floor is what lets countersteer recovery below have something to
        // restore grip onto; a tire with literally zero mu could never be caught.
        double falloffFactor = 1.0 - tuning.gripFalloff() * (excess / (excess + tuning.slipAngleThreshold() + 1e-6));
        double tunedMu = baseMu * falloffFactor;

        if (!front && tuning.throttleBite() > 0.0 && driveForce != 0.0) {
            // How much of the (untuned) friction circle the drive force alone is already using: the more of
            // it, the less lateral grip this rear tire has left to give - friction-circle style, but a
            // distinct, tunable bite on top of the circle WheelMath.tire already enforces for every vehicle.
            double driveFraction = Math.min(1.0, Math.abs(driveForce) / Math.max(1e-6, mu * normalForce));
            tunedMu *= (1.0 - tuning.throttleBite() * driveFraction);
        }

        if (tuning.counterSteerAssist() > 0.0 && absVLat > 1e-6) {
            // Steering into the slide (the classic countersteer) is steerInput pointing the same way the
            // tire is sliding: -signum(vLat) * steerInput is positive exactly then, and at most 1 since
            // steerInput is -1..1. Recovery linearly interpolates back toward baseMu (the un-sliding mu) -
            // a full countersteer (countering == 1) with counterSteerAssist == 1 restores it exactly.
            double countering = Math.max(0.0, -Math.signum(vLat) * steerInput);
            double recovery = Math.min(1.0, countering) * tuning.counterSteerAssist();
            tunedMu += recovery * (baseMu - tunedMu);
        }
        return tunedMu;
    }

    /**
     * {@link WheelMath#tire}, with {@code mu} and {@code lateralScale} first adjusted for this vehicle's
     * drift tuning. Only called by {@link SableCarBody#tick} when {@code tuning} is not
     * {@linkplain VehicleSpec.TireTuning#isIdentity() identity}; every argument otherwise matches
     * {@link WheelMath#tire}'s own full-argument overload.
     */
    public static WheelMath.Tire tire(double vLong, double vLat, double normalForce, double mu,
            double rollingCoefficient, double lateralScale, double driveForce, double brakeForce,
            double brakeGain, double effectiveMass, double dt, VehicleSpec.TireTuning tuning, boolean front,
            boolean handbrake, double steerInput) {
        double tunedMu = effectiveMu(mu, vLat, driveForce, normalForce, steerInput, tuning, front);
        double tunedLateralScale = lateralScale;
        if (handbrake && !front) {
            tunedLateralScale *= tuning.handbrakeRearGripCut();
        }
        return WheelMath.tire(vLong, vLat, normalForce, tunedMu, rollingCoefficient, tunedLateralScale,
                driveForce, brakeForce, brakeGain, effectiveMass, dt);
    }
}
