package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure per-wheel drive-force distribution for the front/rear split and torque-vectoring tuning dials.
 *
 * <p>The identity tuning has a deliberately exact early return: it leaves the historical per-wheel drive
 * command untouched. A non-identity split scales each axle around that historical command (so 0.5/0.5 is
 * unchanged), while vectoring moves equal force from the inside to the outside wheel without changing the
 * axle total.
 */
public final class TorqueDistribution {
    private TorqueDistribution() {}

    /**
     * Returns this wheel's requested drive force before any limited-slip transfer on its axle.
     *
     * @param drive the historical per-wheel drive command
     * @param front whether this wheel is on the front axle
     * @param left whether this wheel is on the vehicle's left side (negative local x)
     * @param tuning vehicle torque-split/vectoring settings
     * @param steer signed steering input; positive steering turns toward positive local x
     */
    public static double distribute(double drive, boolean front, boolean left,
            VehicleSpec.VehicleTuning tuning, double steer) {
        // Do not turn the established drivetrain path into an algebraically equivalent approximation.
        if (tuning.frontTorqueSplit() == 0.5 && tuning.vectoringGain() == 0.0) {
            return drive;
        }

        double frontShare = Math.clamp(tuning.frontTorqueSplit(), 0.0, 1.0);
        double axleDrive = drive * (front ? 2.0 * frontShare : 2.0 * (1.0 - frontShare));
        double gain = Math.clamp(tuning.vectoringGain(), 0.0, 1.0);
        if (gain == 0.0 || steer == 0.0) {
            return axleDrive;
        }

        // Positive steer points forward (+z) toward +x, so the -x/left wheel is outside on a positive turn.
        boolean outside = (steer > 0.0) == left;
        return axleDrive * (outside ? 1.0 + gain : 1.0 - gain);
    }
}
