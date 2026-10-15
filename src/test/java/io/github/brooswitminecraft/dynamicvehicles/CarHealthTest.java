package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CarHealthTest {
    @Test
    void thresholds() {
        assertFalse(CarHealth.smoking(50.0f));
        assertTrue(CarHealth.smoking(49.9f));
        assertFalse(CarHealth.burning(33.0f));
        assertTrue(CarHealth.burning(32.0f));
        assertFalse(CarHealth.burning(0.0f));
        assertFalse(CarHealth.wrecked(10.1f));
        assertTrue(CarHealth.wrecked(10.0f));
    }

    @Test
    void crashDamageScalesAboveThreshold() {
        assertEquals(0.0f, CarHealth.crashDamage(2.9));
        assertTrue(CarHealth.crashDamage(10.0) > CarHealth.crashDamage(4.0));
        assertTrue(CarHealth.crashDamage(4.0) > 0.0f);
    }

    @Test
    void flippedCarEventuallyDies() {
        assertTrue(CarHealth.flipped(-1.0));
        assertTrue(CarHealth.flipped(0.2));
        assertFalse(CarHealth.flipped(0.9));
        assertTrue(CarHealth.flippedDamage(CarHealth.MAX / CarHealth.FLIPPED_DAMAGE_PER_SECOND + 1) >= CarHealth.MAX);
    }
}
