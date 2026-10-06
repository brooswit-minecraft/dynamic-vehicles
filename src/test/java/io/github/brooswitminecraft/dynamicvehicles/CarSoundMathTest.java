package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarSoundMathTest {
    @Test
    void idleLoopDominatesAtRestAndHighLoopAtSpeed() {
        double[] stopped = CarSoundMath.engineMix(0);
        assertEquals(1.0, stopped[0], 1e-9);
        assertEquals(0.0, stopped[2], 1e-9);
        double[] fast = CarSoundMath.engineMix(28);
        assertEquals(0.0, fast[0], 1e-9);
        assertTrue(fast[2] > fast[1]);
        assertTrue(CarSoundMath.engineMix(12)[1] > 0.9);
    }

    @Test
    void theEngineCrossFadeNeverGoesSilentOrLoud() {
        for (double s = 0; s <= 40; s += 0.5) {
            double[] mix = CarSoundMath.engineMix(s);
            double sum = mix[0] + mix[1] + mix[2];
            assertTrue(sum > 0.4 && sum < 2.1, "speed " + s + " mix sum " + sum);
            for (double v : mix) {
                assertTrue(v >= 0.0 && v <= 1.0);
            }
        }
    }

    @Test
    void pitchRisesWithSpeedAndThrottleAndStaysBounded() {
        assertTrue(CarSoundMath.enginePitch(20, 0) > CarSoundMath.enginePitch(2, 0));
        assertTrue(CarSoundMath.enginePitch(10, 1) > CarSoundMath.enginePitch(10, 0));
        assertTrue(CarSoundMath.enginePitch(500, 5) <= 1.5 + 1e-9);
        assertTrue(CarSoundMath.enginePitch(0, 0) >= 0.8 - 1e-9);
    }

    @Test
    void impactSeverityBinsAndVolumeIncrease() {
        assertEquals(0, CarSoundMath.impactSeverity(1.0));
        assertEquals(1, CarSoundMath.impactSeverity(4.0));
        assertEquals(2, CarSoundMath.impactSeverity(8.0));
        assertEquals(3, CarSoundMath.impactSeverity(12.0));
        assertEquals(4, CarSoundMath.impactSeverity(30.0));
        assertEquals(0.0, CarSoundMath.impactVolume(0), 0);
        assertTrue(CarSoundMath.impactVolume(4) > CarSoundMath.impactVolume(1));
    }

    @Test
    void tireNoiseGrowsWithSpeedAndSkidWithSlip() {
        assertEquals(0.0, CarSoundMath.rollingVolume(0), 0);
        assertTrue(CarSoundMath.rollingVolume(15) > CarSoundMath.rollingVolume(3));
        assertEquals(CarSoundMath.rollingVolume(14), CarSoundMath.rollingVolume(40), 1e-9);
        assertTrue(CarSoundMath.skidVolume(CarSoundMath.slipLevel(3)) > CarSoundMath.skidVolume(CarSoundMath.slipLevel(1)));
        assertEquals(0.0, CarSoundMath.skidVolume(0), 0);
        assertTrue(!CarSoundMath.hardSkid(0.2) && CarSoundMath.hardSkid(0.8));
    }
}
