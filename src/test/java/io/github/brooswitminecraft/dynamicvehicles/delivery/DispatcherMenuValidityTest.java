package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-110 AC12: the Minecraft-free part of {@code stillValid} -
 * {@link DispatcherOfferMenu} resolves "is the villager alive / still a
 * Dispatcher / in the same level" live (those require a real entity
 * reference) and passes only these booleans/distance in.
 */
class DispatcherMenuValidityTest {

    private static final double RANGE = DispatcherMenuValidity.INTERACTION_RANGE_BLOCKS;

    @Test
    void isValid_trueWhenAllConditionsHold() {
        assertTrue(DispatcherMenuValidity.isValid(true, true, true, 1.0));
    }

    @Test
    void isValid_falseWhenDispatcherIsNotAlive() {
        assertFalse(DispatcherMenuValidity.isValid(false, true, true, 1.0));
    }

    @Test
    void isValid_falseWhenProfessionNoLongerDispatcher() {
        assertFalse(DispatcherMenuValidity.isValid(true, false, true, 1.0));
    }

    @Test
    void isValid_falseWhenNotInTheSameLevel() {
        assertFalse(DispatcherMenuValidity.isValid(true, true, false, 1.0));
    }

    @Test
    void isValid_trueAtExactlyTheRangeBoundary() {
        assertTrue(DispatcherMenuValidity.isValid(true, true, true, RANGE * RANGE));
    }

    @Test
    void isValid_falseJustBeyondTheRangeBoundary() {
        double justOver = RANGE * RANGE + 0.001;
        assertFalse(DispatcherMenuValidity.isValid(true, true, true, justOver));
    }

    @Test
    void isValid_falseFarAway() {
        assertFalse(DispatcherMenuValidity.isValid(true, true, true, 10_000.0));
    }
}
