package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeadlightMathTest {
    @Test
    void autoLightsComeOnAtNightAndInRain() {
        assertFalse(HeadlightMath.isDark(6000L, 0f), "noon");
        assertTrue(HeadlightMath.isDark(18000L, 0f), "midnight");
        assertTrue(HeadlightMath.isDark(6000L + 24000L * 3, 0.9f), "heavy rain at noon");
        assertFalse(HeadlightMath.isDark(6000L, 0.3f), "drizzle");
        assertTrue(HeadlightMath.isDark(13000L + 24000L * 5, 0f), "dusk on a later day");
        assertFalse(HeadlightMath.isDark(23500L, 0f), "after dawn");
    }
}
