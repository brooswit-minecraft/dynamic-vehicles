package io.github.brooswitminecraft.dynamicvehicles;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveAllocationTest {
    private static DriveAllocation allocation(double[] factors, double[] spins, double lock) {
        return DriveAllocation.freeze(factors, new int[] {1, 0, 3, 2}, spins, 0.35, new double[4], lock);
    }

    @Test
    void rallyShaftSpeedAndInertiaUseTwentyEightyShares() {
        DriveAllocation a = allocation(new double[] {.4, .4, 1.6, 1.6}, new double[] {100, 100, 0, 0}, 0);
        assertEquals(20, a.weightedSpeed(new double[] {100, 100, 0, 0}, false), 1e-12);
        assertEquals(-20, a.weightedSpeed(new double[] {-100, -100, 0, 0}, false), 1e-12);
        assertEquals(20, a.weightedSpeed(new double[] {-100, -100, 0, 0}, true), 1e-12);
        assertEquals(.34, a.shareSquares(), 1e-12);
        assertEquals(1.2 / (144 * .34), DriveAllocation.reflectedInertia(1.2, 12, 1, a.shareSquares()), 1e-12);
    }

    @Test
    void pairRemainsConservativeAfterFirstWheelChangesSpin() {
        double[] spins = {0, 10, 0, 10};
        DriveAllocation a = allocation(new double[] {1, 1, 1, 1}, spins, 1);
        assertEquals(200, 100 * a.factor(0));
        spins[0] += 200 * .35 * .05 / 1.2;
        assertEquals(0, 100 * a.factor(1));
        assertEquals(200, 100 * (a.factor(0) + a.factor(1)));
        assertEquals(-200, -100 * (a.factor(0) + a.factor(1)));
    }

    @Test
    void equalSharesAndDisconnectedOrZeroVectoringSharesRemainBounded() {
        DriveAllocation equal = allocation(new double[] {1, 1, 1, 1}, new double[4], 0);
        DriveAllocation fwd = allocation(new double[] {2, 2, 0, 0}, new double[4], 0);
        DriveAllocation outsideOnly = allocation(new double[] {4, 0, 0, 0}, new double[4], 0);
        assertEquals(.25, equal.shareSquares());
        assertEquals(.5, fwd.shareSquares());
        assertEquals(1, outsideOnly.shareSquares());
        assertEquals(0, fwd.weightedSpeed(new double[] {0, 0, 100, 100}, false));
        assertTrue(Double.isFinite(DriveAllocation.reflectedInertia(1.2, 12, 1, outsideOnly.shareSquares())));
    }

    @Test
    void frozenAllocationConservesTotalTorqueAndPowerForSignedSubsteps() {
        for (double front : new double[] {0, .2, .4, .5, 1}) {
            for (double lock : new double[] {0, .5, 1}) {
                for (int steps : new int[] {1, 4}) {
                    double[] spins = {2, 10, 7, 3};
                    for (int sign : new int[] {-1, 1}) {
                        DriveAllocation a = allocation(new double[] {2*front, 2*front, 2*(1-front), 2*(1-front)}, spins, lock);
                        double torque = sign * 200;
                        double totalTorque = 0, wheelWork = 0;
                        double[] midpoint = new double[4];
                        for (int w = 0; w < 4; w++) {
                            double wheelTorque = torque * a.share(w);
                            totalTorque += wheelTorque;
                            double impulse = wheelTorque * .05 / steps;
                            midpoint[w] = spins[w] + impulse / (2 * 1.2);
                            wheelWork += impulse * midpoint[w];
                        }
                        assertEquals(torque, totalTorque, 1e-10);
                        assertEquals(wheelWork, torque * .05 / steps * a.weightedSpeed(midpoint, false), 1e-10);
                    }
                }
            }
        }
    }
}
