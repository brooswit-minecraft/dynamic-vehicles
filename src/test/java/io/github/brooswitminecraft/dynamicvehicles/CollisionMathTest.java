package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CollisionMathTest {
    @Test
    void nothingBreaksBelowMinSpeed() {
        assertEquals(0.0, CollisionMath.breakChance(7.9, 0.5, 8.0, 3.0));
    }

    @Test
    void unbreakableAndTooHardNeverBreak() {
        assertEquals(0.0, CollisionMath.breakChance(40, -1.0, 8.0, 3.0));
        assertEquals(0.0, CollisionMath.breakChance(40, 50.0, 8.0, 3.0));
        assertEquals(0.0, CollisionMath.breakChance(40, 3.5, 8.0, 3.0));
    }

    @Test
    void softerAndFasterBreakMoreOften() {
        double dirtSlow = CollisionMath.breakChance(10, 0.5, 8.0, 3.0);
        double dirtFast = CollisionMath.breakChance(20, 0.5, 8.0, 3.0);
        double stoneFast = CollisionMath.breakChance(20, 1.5, 8.0, 3.0);
        assertTrue(dirtFast > dirtSlow);
        assertTrue(dirtFast >= stoneFast);
        assertTrue(dirtFast <= 1.0 && dirtSlow > 0.0);
    }

    @Test
    void minSpeedBoundaryIsInclusive() {
        assertEquals(0.0, CollisionMath.breakChance(7.99, 0.5, 8.0, 3.0), "just under min speed: never breaks");
        assertTrue(CollisionMath.breakChance(8.0, 0.5, 8.0, 3.0) > 0.0, "exactly at min speed: can break");
    }

    @Test
    void maxHardnessBoundaryIsInclusive() {
        assertTrue(CollisionMath.breakChance(40, 3.0, 8.0, 3.0) > 0.0, "exactly at max hardness: can still break");
        assertEquals(0.0, CollisionMath.breakChance(40, 3.01, 8.0, 3.0), "just past max hardness: never breaks");
    }
}
