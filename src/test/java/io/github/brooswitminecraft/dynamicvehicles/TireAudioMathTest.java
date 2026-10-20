package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TireAudioMathTest {
    private double audio(double load, double limit, double spinSlip, double lateral, double drive) {
        return TireAudioMath.slipSpeed(load, limit, spinSlip, 10, lateral, 0, 1, drive, 0, 1, 300, 0.05);
    }

    @Test void noBrakeWheelspinAndReverseAreAudibleIncludingRecoveryAfterThrottleRelease() {
        assertEquals(3, audio(3000, 3000, 3, 0, 0));
        assertEquals(3, audio(3000, 3000, -3, 0, 0));
        assertTrue(CarSoundMath.skidVolume(CarSoundMath.slipLevel(audio(3000, 3000, 3, 0, 0))) > 0.5);
        assertEquals(0, audio(3000, 3000, 0.2, 0, 0));
    }

    @Test void noBrakeSlideUsesActualReducedGripRatherThanUntunedCircle() {
        double vLat = 0.8;
        WheelMath.Tire untuned = WheelMath.tire(10, vLat, 3000, 1, 0, 1, 0, 0, 1, 300, 0.05);
        WheelMath.Tire drifting = DriftTireModel.tire(10, vLat, 3000, 1, 0, 1,
                0, 0, 1, 300, 0.05,
                new VehicleSpec.TireTuning(0.2, 999, 0, 1, 0, 0), false, false, 0);
        assertEquals(0, untuned.slipSpeed());
        assertTrue(audio(3000, drifting.frictionLimit(), 0, vLat, 0) > 0.3);
        assertEquals(0, audio(3000, untuned.frictionLimit(), 0, vLat, 0));
    }

    @Test void contactLossAndNoTractionCannotSquealEvenWithFreeWheelSpin() {
        assertEquals(0, audio(0, 3000, 100, 10, 10000));
        assertEquals(0, audio(3000, 0, 100, 10, 10000));
    }

    @Test void grippingCornerAndStoppedWheelAreSilent() {
        assertEquals(0, audio(3000, 3000, 0, 0.2, 0));
        assertEquals(0, audio(3000, 3000, 0, 0, 0));
    }

    @Test void quietStartsAndStopsAlwaysSynchronizeAndSmallChangesDoNotSpam() {
        assertTrue(TireAudioMath.shouldSync(0, 0.001f));
        assertTrue(TireAudioMath.shouldSync(0.001f, 0));
        assertTrue(TireAudioMath.shouldSync(0.1f, 0.12f));
        assertFalse(TireAudioMath.shouldSync(0.1f, 0.101f));
    }
}


