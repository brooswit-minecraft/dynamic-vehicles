package io.github.brooswitminecraft.dynamicvehicles;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MotorboatTravelLiftTest {
    private static final double[] X = {-1, 1, -1, 1};
    private static final double[] Z = {2, 2, -2, -2};
    private static double[] forces(double x, double z, double[] depths, double coefficient, double cap) {
        return MotorboatPhysics.travelSideLift(x,z,X,Z,depths,coefficient,cap);
    }
    @Test void directionSelectsForwardReverseAndLateralEdge() {
        double[] wet = {1,1,1,1};
        assertArrayEquals(new double[]{256,256,0,0}, forces(0,8,wet,8,1000), 1e-9);
        assertArrayEquals(new double[]{0,0,256,256}, forces(0,-8,wet,8,1000), 1e-9);
        assertArrayEquals(new double[]{0,144,0,144}, forces(6,0,wet,8,1000), 1e-9);
        assertArrayEquals(new double[]{144,0,144,0}, forces(-6,0,wet,8,1000), 1e-9);
    }
    @Test void zeroDryDisabledAndNonfiniteInputsStayQuiet() {
        for (double[] result : new double[][] {forces(0,0,new double[]{1,1,1,1},8,1000),
                forces(0,8,new double[4],8,1000), forces(0,8,new double[]{1,1,1,1},0,1000),
                forces(0,8,new double[]{1,1,1,1},8,0), forces(Double.NaN,8,new double[]{1,1,1,1},8,1000)}) {
            assertArrayEquals(new double[4],result,0);
        }
    }
    @Test void totalCapAndPartialImmersionDoNotRedistributeDryPointLift() {
        assertEquals(1000,java.util.Arrays.stream(forces(60,80,new double[]{1,1,1,1},8,1000)).sum(),1e-9);
        assertArrayEquals(new double[]{128,0,0,0},forces(0,8,new double[]{.125,0,1,1},8,1000),1e-9);
        assertTrue(java.util.Arrays.stream(forces(1e150,1e150,new double[]{1,1,1,1},500,1000))
                .allMatch(v -> Double.isFinite(v) && v>=0 && v<=1000));
    }
}
