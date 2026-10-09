package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure composition of one sub-step's pass 2 (anti-roll) for every wheel of a car, used by
 * {@link SableCarBody#tick} so a test driving this method is exercising the real sub-step path, not a
 * reimplementation of it. Free of Minecraft and Sable types so it can be unit tested.
 */
public final class WheelSubStepper {
    private WheelSubStepper() {}

    /**
     * Each wheel's suspension force after its axle's anti-roll transfer, for one sub-step.
     *
     * @param suspensionForces this sub-step's per-wheel suspension force (pass 1 of the sub-step)
     * @param compressions per-wheel spring compression, fixed for the tick (the ray is cast once)
     * @param partners partners[i] is the axle partner of wheel i, or -1 for none
     * @param barRate anti-roll bar stiffness, N per metre of compression difference
     * @return per-wheel force after anti-roll; by construction, for any partnered pair (i, partners[i]),
     *         {@code (result[i] - suspensionForces[i]) + (result[partners[i]] - suspensionForces[partners[i]])}
     *         is exactly 0 - see WheelMath.antiRollTransfer's own antisymmetry guarantee, which this simply
     *         calls once per wheel against the SAME snapshot passed in.
     */
    public static double[] afterAntiRoll(double[] suspensionForces, double[] compressions, int[] partners, double barRate) {
        int wheelCount = suspensionForces.length;
        double[] result = new double[wheelCount];
        for (int wheel = 0; wheel < wheelCount; wheel++) {
            double force = suspensionForces[wheel];
            int partner = partners[wheel];
            if (partner >= 0) {
                force += WheelMath.antiRollTransfer(suspensionForces[wheel], compressions[wheel],
                        suspensionForces[partner], compressions[partner], barRate);
            }
            result[wheel] = force;
        }
        return result;
    }

    /**
     * What one wheel's tire would report as slip for THIS sub-step, made comparable across every value of
     * {@code wheelSubSteps} by evaluating {@link WheelMath#tire} at the TICK's own {@code tickDt} - never
     * the sub-step's {@code subDt} - while the force actually applied to the body still comes from the
     * caller's own, separate {@code WheelMath.tire(..., subDt)} call and is untouched by this method.
     *
     * <p>Why: {@code WheelMath.tire}'s slip is {@code (demand - limit) * dt / effectiveMass}, and
     * {@code dt} appears in two different roles inside {@code demand} - which, called with {@code subDt}
     * the way SableCarBody.tick must for the force it actually applies, makes the reported number carry N
     * (the sub-step count) in two opposite directions:
     * <ul>
     * <li>For a demand that does NOT itself scale with dt (wheelspin pinned at {@code driveForce}; braking
     * clamped to {@code brakeForce}), {@code demand - limit} is the same at every N, so the lone remaining
     * {@code subDt} factor divides the reported slip by N - a gripping/slipping wheel would silently fall
     * through the {@code > 0.3} gate SlipReporter/wearSlip use merely because {@code wheelSubSteps} went
     * up, not because the tire gripped any better.
     * <li>For a demand built from the relaxation target ({@code effectiveMass * v / dt * RELAXATION}, used
     * for cornering and for braking before it clamps), that target itself grows as {@code 1/subDt}, so a
     * wheel that was genuinely gripping at N=1 (demand under the friction circle) can be reported as
     * slipping at higher N purely because asking the relaxation to close the same fraction of slip in a
     * shorter sub-step takes more force - a real difference in the force the relaxation target WANTS, but
     * not the kind of sliding SlipReporter/wearSlip/screech/dust exist to catch.
     * </ul>
     * Re-running the identical, untouched {@code tire()} formula at {@code tickDt} - the same dt a
     * single, un-sub-stepped tick would have used - at the velocity as it stands this sub-step reports
     * exactly what that one un-sub-stepped tick would have reported from that velocity, regardless of how
     * many sub-steps the real tick actually takes to get there: N=1 is unaffected (there {@code subDt ==
     * tickDt} already), and every other N reads off the same scale. This trades away one thing: at N>1 the
     * relaxation term genuinely does pursue a bigger correction per sub-step (see SableCarBody.tick's
     * sub-stepping note and the PR body's grip-change note) and this method's slip does not reflect that
     * growing aggressiveness mid-tick - only the gate-worthy, tick-comparable amount the tire could not
     * correct, which is what SlipReporter/wearSlip/lastSlipSpeed actually need.
     */
    public static double subStepSlipSpeed(double vLong, double vLat, double normalForce, double mu,
            double rollingCoefficient, double lateralScale, double driveForce, double brakeForce,
            double brakeGain, double effectiveMass, double tickDt) {
        return WheelMath.tire(vLong, vLat, normalForce, mu, rollingCoefficient, lateralScale, driveForce,
                brakeForce, brakeGain, effectiveMass, tickDt).slipSpeed();
    }
}
