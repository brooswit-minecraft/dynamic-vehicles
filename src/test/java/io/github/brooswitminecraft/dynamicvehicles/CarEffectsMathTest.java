package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarEffectsMathTest {
    @Test
    void wearGrowsWithThrottleAndSpeedAndNeverBelowRealSlip() {
        assertEquals(0.0, CarEffectsMath.wearSlip(0.0, 10.0, 0.0, 1.0), 0.0);
        double crawl = CarEffectsMath.wearSlip(1.0, 0.0, 0.0, 1.0);
        double fast = CarEffectsMath.wearSlip(1.0, 20.0, 0.0, 1.0);
        assertEquals(2.0, crawl, 1e-9);
        assertEquals(4.0, fast, 1e-9);
        assertTrue(CarEffectsMath.wearSlip(0.5, 10.0, 0.0, 1.0) < CarEffectsMath.wearSlip(1.0, 10.0, 0.0, 1.0));
        assertEquals(7.0, CarEffectsMath.wearSlip(1.0, 20.0, 7.0, 1.0), 1e-9, "real slip wins when larger");
        assertEquals(1.0, CarEffectsMath.wearSlip(1.0, 20.0, 1.0, 0.0), 1e-9, "strength 0 leaves only real slip");
    }

    @Test
    void brakingWearsOnlyWhenMovingFast() {
        assertEquals(0.0, CarEffectsMath.wearSlip(-1.0, 2.0, 0.0, 1.0), 0.0);
        assertEquals(1.5, CarEffectsMath.wearSlip(-1.0, 10.0, 0.0, 1.0), 1e-9);
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
    void dustStartsExactlyAtMinSpeedOrOnSlipAlone() {
        assertEquals(0, CarEffectsMath.dustAmount(CarEffectsMath.MIN_DUST_SPEED - 0.01, 0.0, 1.0),
                "just under the speed threshold with no slip: nothing yet");
        assertTrue(CarEffectsMath.dustAmount(CarEffectsMath.MIN_DUST_SPEED, 0.0, 1.0) > 0,
                "right at the speed threshold: dust starts");
        assertEquals(0, CarEffectsMath.dustAmount(0.0, 2.99, 1.0), "just under the slip-alone threshold");
        assertTrue(CarEffectsMath.dustAmount(0.0, 3.0, 1.0) > 0, "right at the slip-alone threshold");
    }
}
