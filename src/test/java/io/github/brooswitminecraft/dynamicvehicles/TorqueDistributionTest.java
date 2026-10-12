package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure-function acceptance checks for MINECRAFT-301 torque split and vectoring. */
class TorqueDistributionTest {
    private static final VehicleSpec.VehicleTuning IDENTITY = VehicleSpec.VehicleTuning.IDENTITY;

    @Test
    void identityIsByteForByteTheHistoricalPerWheelCommand() {
        assertEquals(2300.0, TorqueDistribution.distribute(2300.0, true, true, IDENTITY, 0.7));
        assertEquals(-800.0, TorqueDistribution.distribute(-800.0, false, false, IDENTITY, -0.4));
    }

    @Test
    void splitChangesFrontRearProportionsWhilePreservingTotalDrive() {
        VehicleSpec.VehicleTuning rearBiased = new VehicleSpec.VehicleTuning(0.0, 0.25, 0.0, 0.0, 0.0);
        double front = TorqueDistribution.distribute(100.0, true, true, rearBiased, 0.0);
        double rear = TorqueDistribution.distribute(100.0, false, true, rearBiased, 0.0);
        assertEquals(50.0, front, 1e-12);
        assertEquals(150.0, rear, 1e-12);
        assertEquals(400.0, 2.0 * front + 2.0 * rear, 1e-12);
    }

    @Test
    void vectoringBiasesOutsideWheelAndMirrorsLeftRightTurns() {
        VehicleSpec.VehicleTuning vectoring = new VehicleSpec.VehicleTuning(0.0, 0.5, 0.25, 0.0, 0.0);
        double positiveLeft = TorqueDistribution.distribute(100.0, true, true, vectoring, 0.6);
        double positiveRight = TorqueDistribution.distribute(100.0, true, false, vectoring, 0.6);
        double negativeLeft = TorqueDistribution.distribute(100.0, true, true, vectoring, -0.6);
        double negativeRight = TorqueDistribution.distribute(100.0, true, false, vectoring, -0.6);
        assertEquals(125.0, positiveLeft, 1e-12, "left is outside in a positive turn");
        assertEquals(75.0, positiveRight, 1e-12);
        assertEquals(75.0, negativeLeft, 1e-12);
        assertEquals(125.0, negativeRight, 1e-12, "right is outside in a negative turn");
        assertEquals(positiveLeft, negativeRight, 1e-12, "left/right turns must mirror");
        assertEquals(positiveRight, negativeLeft, 1e-12, "left/right turns must mirror");
    }
}
