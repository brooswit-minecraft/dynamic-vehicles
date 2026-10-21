package io.github.brooswitminecraft.dynamicvehicles;

/** One substep's drive allocation, frozen before any wheel updates its spin.
 * Factors multiply the historical per-wheel command. Shares are normalized only for shaft feedback.
 * The existing LSD algorithm runs once per axle; both outputs then remain paired for prediction and use. */
final class DriveAllocation {
    private final double[] factors;
    private final double total;

    private DriveAllocation(double[] factors) {
        this.factors = factors;
        double sum = 0;
        for (double factor : factors) sum += factor;
        this.total = sum;
    }

    static DriveAllocation freeze(double[] requested, int[] partners, double[] spins,
            double radius, double[] grips, double lock) {
        double[] factors = requested.clone();
        for (int wheel = 0; wheel < factors.length; wheel++) {
            int partner = partners[wheel];
            if (partner <= wheel) continue;
            LimitedSlipDifferential.DrivePair pair = LimitedSlipDifferential.distribute(
                    requested[wheel], requested[partner], spins[wheel] * radius, spins[partner] * radius,
                    grips[wheel], grips[partner], lock);
            factors[wheel] = pair.left();
            factors[partner] = pair.right();
        }
        return new DriveAllocation(factors);
    }

    double factor(int wheel) { return factors[wheel]; }
    double share(int wheel) { return total > 0 ? factors[wheel] / total : 0; }

    double weightedSpeed(double[] spins, boolean magnitude) {
        double speed = 0;
        for (int wheel = 0; wheel < factors.length; wheel++) {
            speed += share(wheel) * (magnitude ? Math.abs(spins[wheel]) : spins[wheel]);
        }
        return speed;
    }

    double shareSquares() {
        double sum = 0;
        for (int wheel = 0; wheel < factors.length; wheel++) sum += share(wheel) * share(wheel);
        return sum;
    }

    /** Power-conjugate reflected inertia for the nonzero mobile shares. */
    static double reflectedInertia(double wheelInertia, double gearing, double forceScale, double mobileShareSquares) {
        return wheelInertia / (forceScale * gearing * gearing * mobileShareSquares);
    }
}
