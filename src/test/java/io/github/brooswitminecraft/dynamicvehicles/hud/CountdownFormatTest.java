package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MINECRAFT-411: the countdown formatting shared by the Dispatcher and Pathfinder mission overlays. */
class CountdownFormatTest {

    @Test
    void formatsMinutesAndSeconds() {
        assertEquals("1:05", CountdownFormat.format(20L * 65));
        assertEquals("0:09", CountdownFormat.format(20L * 9));
    }

    @Test
    void zeroTicksIsZeroZero() {
        assertEquals("0:00", CountdownFormat.format(0L));
    }

    @Test
    void pastDeadlineClampsToZeroRatherThanGoingNegative() {
        assertEquals("0:00", CountdownFormat.format(-1L));
        assertEquals("0:00", CountdownFormat.format(-20000L));
    }
}
