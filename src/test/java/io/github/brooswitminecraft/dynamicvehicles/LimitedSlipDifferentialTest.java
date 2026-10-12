package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function acceptance checks for MINECRAFT-296's axle torque transfer. */
class LimitedSlipDifferentialTest {
    @Test
    void openDifferentialIsByteForByteIdentity() {
        LimitedSlipDifferential.DrivePair open = LimitedSlipDifferential.distribute(
                2300.0, 2300.0, 30.0, 5.0, 50.0, 5000.0, 0.0);
        assertEquals(2300.0, open.left());
        assertEquals(2300.0, open.right());
    }

    @Test
    void lockMovesTorqueFromSpinningLowGripWheelToItsPartner() {
        LimitedSlipDifferential.DrivePair low = LimitedSlipDifferential.distribute(
                2300.0, 2300.0, 30.0, 5.0, 50.0, 5000.0, 0.25);
        LimitedSlipDifferential.DrivePair high = LimitedSlipDifferential.distribute(
                2300.0, 2300.0, 30.0, 5.0, 50.0, 5000.0, 0.75);
        assertTrue(low.right() > low.left(), "the slower, higher-grip wheel receives the transfer");
        assertTrue(high.right() > low.right(), "more lock sends more torque to the gripping wheel");
        assertEquals(4600.0, high.left() + high.right(), 1e-9, "the differential conserves axle torque");
    }

    @Test
    void fullLockRemainsBoundedAndSane() {
        LimitedSlipDifferential.DrivePair full = LimitedSlipDifferential.distribute(
                2300.0, 2300.0, 100.0, 0.0, 0.0, 5000.0, 1.0);
        assertEquals(0.0, full.left(), 1e-9);
        assertEquals(4600.0, full.right(), 1e-9);
        assertTrue(full.left() >= 0.0 && full.right() >= 0.0, "lock never reverses a driven wheel");
    }
}
