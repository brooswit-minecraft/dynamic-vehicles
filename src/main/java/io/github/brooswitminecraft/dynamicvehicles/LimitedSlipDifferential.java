package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure axle-pair torque distribution for a clutch-type limited-slip differential.
 * An open differential leaves each wheel's requested drive force alone.  Lock moves
 * equal and opposite drive force away from the faster/lower-grip wheel and toward
 * its slower/higher-grip partner, preserving the axle's total requested drive.
 */
public final class LimitedSlipDifferential {
    private LimitedSlipDifferential() {}

    public record DrivePair(double left, double right) {}

    /**
     * Applies a bounded lock transfer to an axle's two requested drive forces.
     * At lock 1, no wheel can receive more than the axle's total requested drive
     * and no wheel can be driven through zero by this correction.
     *
     * @param leftDrive requested drive force at the left wheel, N
     * @param rightDrive requested drive force at the right wheel, N
     * @param leftSurfaceSpeed left wheel's rim speed, m/s
     * @param rightSurfaceSpeed right wheel's rim speed, m/s
     * @param leftGripForce left wheel's available longitudinal grip, N
     * @param rightGripForce right wheel's available longitudinal grip, N
     * @param lockPercent 0 for open, 1 for fully locked
     */
    public static DrivePair distribute(double leftDrive, double rightDrive,
            double leftSurfaceSpeed, double rightSurfaceSpeed,
            double leftGripForce, double rightGripForce, double lockPercent) {
        // This exact early return is the identity gate for every non-opt-in vehicle.
        if (!(lockPercent > 0.0) || leftDrive == 0.0 || rightDrive == 0.0
                || Math.signum(leftDrive) != Math.signum(rightDrive)) {
            return new DrivePair(leftDrive, rightDrive);
        }
        double lock = Math.min(1.0, lockPercent);
        double speedDifference = Math.abs(leftSurfaceSpeed - rightSurfaceSpeed)
                / Math.max(1.0, Math.max(Math.abs(leftSurfaceSpeed), Math.abs(rightSurfaceSpeed)));
        double gripDifference = Math.abs(leftGripForce - rightGripForce)
                / Math.max(1.0, Math.max(Math.abs(leftGripForce), Math.abs(rightGripForce)));
        double imbalance = Math.min(1.0, Math.max(speedDifference, gripDifference));
        if (imbalance == 0.0) {
            return new DrivePair(leftDrive, rightDrive);
        }

        // The faster wheel normally has less usable traction.  When speeds tie,
        // the grip comparison still directs torque toward the wheel that can use it.
        boolean leftGetsMore = leftSurfaceSpeed < rightSurfaceSpeed
                || (leftSurfaceSpeed == rightSurfaceSpeed && leftGripForce > rightGripForce);
        double donor = leftGetsMore ? rightDrive : leftDrive;
        double transfer = Math.min(Math.abs(donor), (Math.abs(leftDrive) + Math.abs(rightDrive)) / 2.0)
                * lock * imbalance;
        transfer = Math.copySign(transfer, leftDrive);
        return leftGetsMore
                ? new DrivePair(leftDrive + transfer, rightDrive - transfer)
                : new DrivePair(leftDrive - transfer, rightDrive + transfer);
    }
}
