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
}
