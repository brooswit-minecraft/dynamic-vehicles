package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class WheelSyncTest {
    private static final double[] TRAVEL = {0.10, 0.12, 0.08, 0.09};
    private static final double[] STEER = {0.30, 0.30, 0.0, 0.0};
    private static final double[] SPIN = {4.0, 4.5, -1.0, 6.0};

    @Test
    void encodeRoundTripsEveryWheelsThreeFieldsInOrder() {
        float[] packed = WheelSync.encode(TRAVEL, STEER, SPIN);
        assertEquals(4, WheelSync.wheelCount(packed));
        for (int wheel = 0; wheel < 4; wheel++) {
            assertEquals((float) TRAVEL[wheel], WheelSync.travel(packed, wheel), 0);
            assertEquals((float) STEER[wheel], WheelSync.steerAngle(packed, wheel), 0);
            assertEquals((float) SPIN[wheel], WheelSync.spin(packed, wheel), 0);
        }
    }

    @Test
    void lerpAtZeroIsTheFromFrame() {
        float[] from = WheelSync.encode(TRAVEL, STEER, SPIN);
        float[] to = WheelSync.encode(new double[] {1, 1, 1, 1}, new double[] {1, 1, 1, 1}, new double[] {1, 1, 1, 1});
        assertArrayEquals(from, WheelSync.lerp(from, to, 0.0f), 0);
    }

    @Test
    void lerpAtOneIsTheToFrame() {
        float[] from = WheelSync.encode(TRAVEL, STEER, SPIN);
        float[] to = WheelSync.encode(new double[] {1, 1, 1, 1}, new double[] {1, 1, 1, 1}, new double[] {1, 1, 1, 1});
        assertArrayEquals(to, WheelSync.lerp(from, to, 1.0f), 0);
    }

    @Test
    void lerpAtHalfIsTheMidpointOfEveryField() {
        float[] from = WheelSync.encode(new double[] {0.0}, new double[] {0.0}, new double[] {0.0});
        float[] to = WheelSync.encode(new double[] {2.0}, new double[] {-1.0}, new double[] {10.0});
        float[] mid = WheelSync.lerp(from, to, 0.5f);
        assertEquals(1.0f, WheelSync.travel(mid, 0), 0);
        assertEquals(-0.5f, WheelSync.steerAngle(mid, 0), 0);
        assertEquals(5.0f, WheelSync.spin(mid, 0), 0);
    }

    @Test
    void lerpOfMismatchedWheelCountsReturnsToUnchanged() {
        float[] from = WheelSync.encode(new double[] {1, 1}, new double[] {1, 1}, new double[] {1, 1});
        float[] to = WheelSync.encode(TRAVEL, STEER, SPIN);
        assertSame(to, WheelSync.lerp(from, to, 0.5f));
    }
}
