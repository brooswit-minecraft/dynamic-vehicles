package io.github.brooswitminecraft.dynamicvehicles;

/** Contact-only audio telemetry; does not feed back into forces or terrain wear. */
public final class TireAudioMath {
    private TireAudioMath() {}

    public static double slipSpeed(double normalForce, double frictionLimit, double spinSlip,
            double vLong, double vLat, double rollingCoefficient, double lateralScale,
            double driveForce, double brakeForce, double brakeGain, double effectiveMass, double tickDt) {
        if (!(normalForce > 0) || !(frictionLimit > 0)) return 0;
        // The actual tire already includes surface, pressure, alignment and drift grip.
        // Reuse that limit, on a tick time scale, so substepping alone cannot create corner noise.
        double sliding = WheelSubStepper.subStepSlipSpeed(vLong, vLat, normalForce,
                frictionLimit / normalForce, rollingCoefficient, lateralScale,
                driveForce, brakeForce, brakeGain, effectiveMass, tickDt);
        double speed = Math.max(sliding, Math.abs(spinSlip));
        // Ignore tiny contact/rolling discrepancies, including the relaxation tail.
        return speed > 0.3 ? speed : 0;
    }

    public static boolean shouldSync(float previous, float current) {
        // Always transmit start/stop; never strand a quiet loop below the change threshold.
        return (previous == 0) != (current == 0) || Math.abs(previous - current) > 0.01f;
    }
}
