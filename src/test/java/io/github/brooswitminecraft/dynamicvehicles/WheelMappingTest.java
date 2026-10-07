package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WheelMappingTest {
    private static final WheelMapping.Settings S = new WheelMapping.Settings(0, 2, 3, false, false, false, 0.05, 1.0, 0.05);

    @Test
    void steeringRightIsNegativeLeftAndDeadzoned() {
        assertEquals(0.0, WheelMapping.steer(0.03, 0.05, 1.0, false), 0.0);
        assertEquals(-1.0, WheelMapping.steer(1.0, 0.05, 1.0, false), 1e-9);
        assertEquals(1.0, WheelMapping.steer(-1.0, 0.05, 1.0, false), 1e-9);
        assertEquals(1.0, WheelMapping.steer(1.0, 0.05, 1.0, true), 1e-9);
        assertEquals(-1.0, WheelMapping.steer(0.6, 0.05, 2.0, false), 1e-9, "scale 2 reaches full lock before full travel");
    }

    @Test
    void pedalsReadFromEitherRestEnd() {
        assertEquals(0.0, WheelMapping.pedal(1.0, 1.0, 0.05), 0.0);
        assertEquals(1.0, WheelMapping.pedal(-1.0, 1.0, 0.05), 1e-9);
        assertEquals(0.0, WheelMapping.pedal(-1.0, -1.0, 0.05), 0.0);
        assertEquals(1.0, WheelMapping.pedal(1.0, -1.0, 0.05), 1e-9);
        assertTrue(WheelMapping.pedal(0.0, 1.0, 0.05) > 0.4 && WheelMapping.pedal(0.0, 1.0, 0.05) < 0.6);
        assertEquals(0.0, WheelMapping.pedal(-1.0, Double.NaN, 0.05), 0.0, "unknown rest never produces a phantom press");
    }

    @Test
    void restDetectionOnlyTrustsAnEndOfTheAxis() {
        assertEquals(1.0, WheelMapping.detectRest(1.0));
        assertEquals(-1.0, WheelMapping.detectRest(-0.95));
        assertTrue(Double.isNaN(WheelMapping.detectRest(0.0)));
        assertEquals(-1.0, WheelMapping.rest(WheelMapping.PedalRest.LOW, Double.NaN));
        assertTrue(Double.isNaN(WheelMapping.rest(WheelMapping.PedalRest.AUTO, Double.NaN)));
    }

    @Test
    void combinedPedalsSplitAtCentre() {
        assertEquals(0.0, WheelMapping.combined(0.02, 0.05, false, 0).forward(), 0.0);
        assertEquals(1.0, WheelMapping.combined(1.0, 0.05, false, 0).throttle(), 1e-9);
        assertEquals(1.0, WheelMapping.combined(-1.0, 0.05, false, 0).brake(), 1e-9);
        assertEquals(1.0, WheelMapping.combined(-1.0, 0.05, true, 0).throttle(), 1e-9);
    }

    @Test
    void mapsAFullSampleAndIgnoresMissingAxes() {
        float[] axes = {0.5f, 0f, -1f, 1f};
        WheelMapping.Output out = WheelMapping.map(axes, S, 1.0, 1.0);
        assertTrue(out.steer() < 0);
        assertEquals(1.0, out.throttle(), 1e-9);
        assertEquals(0.0, out.brake(), 0.0);
        assertEquals(0.0, WheelMapping.map(new float[0], S, 1.0, 1.0).forward(), 0.0);
    }

    @Test
    void keyboardStandsWhenTheWheelIsIdle() {
        assertEquals(1.0f, WheelMapping.merge(1.0f, 0.0));
        assertEquals(-0.4f, WheelMapping.merge(1.0f, -0.4), 1e-6);
        assertEquals(1.0f, WheelMapping.merge(0.0f, 5.0));
    }
}
