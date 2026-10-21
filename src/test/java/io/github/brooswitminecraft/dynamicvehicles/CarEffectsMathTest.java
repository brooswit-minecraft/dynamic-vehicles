package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarEffectsMathTest {
    @Test
    void wearGrowsWithThrottleAndSpeedAndAlwaysAddsToRealSlip() {
        double scale = CarEffectsMath.WEAR_SCALE;
        assertEquals(0.0, CarEffectsMath.wearSlip(0.0, 10.0, 0.0, 1.0), 0.0);
        double crawl = CarEffectsMath.wearSlip(1.0, 0.0, 0.0, 1.0);
        double fast = CarEffectsMath.wearSlip(1.0, 20.0, 0.0, 1.0);
        assertEquals(2.0 * scale, crawl, 1e-9);
        assertEquals(4.0 * scale, fast, 1e-9);
        assertTrue(CarEffectsMath.wearSlip(0.5, 10.0, 0.0, 1.0) < CarEffectsMath.wearSlip(1.0, 10.0, 0.0, 1.0));
        assertEquals(7.0 + 4.0 * scale, CarEffectsMath.wearSlip(1.0, 20.0, 7.0, 1.0), 1e-9, "real slip adds to the wear term");
        assertEquals(1.0, CarEffectsMath.wearSlip(1.0, 20.0, 1.0, 0.0), 1e-9, "strength 0 leaves only real slip");
    }

    @Test
    void aBurnoutWearsMoreThanPlainAcceleration() {
        assertTrue(CarEffectsMath.wearSlip(1.0, 0.0, 8.0, 5.0) > 2.0 * CarEffectsMath.wearSlip(1.0, 0.0, 0.0, 5.0));
    }

    @Test
    void brakingWearsOnlyWhenMovingFast() {
        assertEquals(0.0, CarEffectsMath.wearSlip(-1.0, 2.0, 0.0, 1.0), 0.0);
        assertEquals(1.5 * CarEffectsMath.WEAR_SCALE, CarEffectsMath.wearSlip(-1.0, 10.0, 0.0, 1.0), 1e-9);
    }

    @Test
    void exhaustOnlyWhenOnTheThrottleAndMoving() {
        assertEquals(0, CarEffectsMath.exhaustAmount(0.0, 10.0, 1.0));
        assertEquals(0, CarEffectsMath.exhaustAmount(1.0, 0.5, 1.0), "a revving car standing still emits nothing");
        assertEquals(4, CarEffectsMath.exhaustAmount(1.0, 10.0, 1.0));
        assertTrue(CarEffectsMath.exhaustAmount(0.3, 10.0, 1.0) < CarEffectsMath.exhaustAmount(1.0, 10.0, 1.0));
        assertEquals(0, CarEffectsMath.exhaustAmount(1.0, 10.0, 0.0));
    }

    @Test
    void dustFollowsSpeedAndSlip() {
        assertEquals(0, CarEffectsMath.dustAmount(3.0, 0.0, 1.0));
        assertTrue(CarEffectsMath.dustAmount(20.0, 0.0, 1.0) > CarEffectsMath.dustAmount(6.0, 0.0, 1.0));
        assertTrue(CarEffectsMath.dustAmount(0.0, 8.0, 1.0) > 0, "a slide kicks dust even slowly");
        assertEquals(0, CarEffectsMath.dustAmount(30.0, 0.0, 0.0));
    }

    @Test
    void exhaustStartsExactlyAtMinSpeed() {
        assertEquals(0, CarEffectsMath.exhaustAmount(1.0, CarEffectsMath.MIN_EXHAUST_SPEED - 0.01, 1.0),
                "just under the threshold: nothing yet");
        assertTrue(CarEffectsMath.exhaustAmount(1.0, CarEffectsMath.MIN_EXHAUST_SPEED, 1.0) > 0,
                "right at the threshold: exhaust starts");
    }

    @Test
    void exhaustThrottleRampHonorsDeadBandCurveScaleAndPerEmissionCap() {
        assertEquals(0, CarEffectsMath.exhaustAmount(0.20, 10.0, 1.0, 0.20, 1.0, 32),
                "configured throttle dead band must suppress exhaust at release");
        int low = CarEffectsMath.exhaustAmount(0.35, 10.0, 2.5, 0.20, 1.0, 32);
        int high = CarEffectsMath.exhaustAmount(1.0, 10.0, 2.5, 0.20, 1.0, 32);
        assertTrue(low > 0 && low < high, "high throttle must emit more than a configured low-throttle level");
        assertEquals(0, CarEffectsMath.exhaustAmount(1.0, 10.0, 0.0, 0.20, 1.0, 32),
                "zero exhaust scale must disable even full throttle");
        assertTrue(CarEffectsMath.exhaustAmount(0.50, 10.0, 2.5, 0.20, 2.0, 32) <
                CarEffectsMath.exhaustAmount(0.50, 10.0, 2.5, 0.20, 1.0, 32),
                "a steeper curve must defer the ramp at mid throttle");
        assertEquals(7, CarEffectsMath.exhaustAmount(1.0, 10.0, 10.0, 0.0, 1.0, 7),
                "one emission must never exceed its configured cap");
    }

    @Test
    void dustStartsExactlyAtMinSpeedOrOnSlipAlone() {
        assertEquals(0, CarEffectsMath.dustAmount(CarEffectsMath.MIN_DUST_SPEED - 0.01, 0.0, 1.0),
                "just under the speed threshold with no slip: nothing yet");
        assertTrue(CarEffectsMath.dustAmount(CarEffectsMath.MIN_DUST_SPEED, 0.0, 1.0) > 0,
                "right at the speed threshold: dust starts");
        assertEquals(0, CarEffectsMath.dustAmount(0.0, 2.99, 1.0), "just under the slip-alone threshold");
        assertTrue(CarEffectsMath.dustAmount(0.0, 3.0, 1.0) > 0, "right at the slip-alone threshold");
    }

    @Test
    void configuredWheelDustKeepsSpeedAndSlipIndependentAndBounded() {
        assertEquals(0, CarEffectsMath.dustAmount(4.9, 0.0, 1.0, 5.0, 3.0, 8));
        assertTrue(CarEffectsMath.dustAmount(5.0, 0.0, 1.0, 5.0, 3.0, 8) > 0,
                "ordinary grounded driving starts at the configured actual-speed threshold");
        assertEquals(0, CarEffectsMath.dustAmount(0.0, 2.9, 1.0, 5.0, 3.0, 8));
        assertTrue(CarEffectsMath.dustAmount(0.0, 3.0, 1.0, 5.0, 3.0, 8) > 0,
                "stationary wheelspin starts at the configured per-wheel slip threshold");
        assertEquals(0, CarEffectsMath.dustAmount(30.0, 10.0, 0.0, 5.0, 3.0, 8));
        assertEquals(8, CarEffectsMath.dustAmount(300.0, 100.0, 10.0, 5.0, 3.0, 8),
                "one wheel cannot exceed the configured per-emission dust cap");
    }
}
