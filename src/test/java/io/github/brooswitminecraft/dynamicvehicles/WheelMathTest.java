package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WheelMathTest {
    @Test
    void noGroundContactMeansNoForce() {
        assertEquals(0.0, WheelMath.suspensionForce(-0.1, 5.0), 0);
        assertEquals(0.0, WheelMath.suspensionForce(0.0, 5.0), 0);
        assertEquals(0.0, WheelMath.suspensionForce(Double.NaN, 0.0), 0);
    }

    @Test
    void staticLoadGivesAboutQuarterOfTheCarsWeightAtFifteenCentimetres() {
        double quarterWeight = 1200.0 * 9.81 / 4.0;
        assertEquals(quarterWeight, WheelMath.suspensionForce(0.15, 0.0), quarterWeight * 0.01);
    }

    @Test
    void moreCompressionPushesHarder() {
        assertTrue(WheelMath.suspensionForce(0.2, 0) > WheelMath.suspensionForce(0.1, 0));
    }

    @Test
    void theDamperResistsSqueezingAndEasesRebound() {
        double still = WheelMath.suspensionForce(0.15, 0.0);
        assertTrue(WheelMath.suspensionForce(0.15, 1.0) > still);
        assertTrue(WheelMath.suspensionForce(0.15, -1.0) < still);
    }

    @Test
    void aSpringNeverPullsAndNeverExceedsTheCap() {
        assertEquals(0.0, WheelMath.suspensionForce(0.01, -50.0), 0);
        assertEquals(WheelMath.MAX_FORCE, WheelMath.suspensionForce(0.6, 50.0), 0);
    }
}
