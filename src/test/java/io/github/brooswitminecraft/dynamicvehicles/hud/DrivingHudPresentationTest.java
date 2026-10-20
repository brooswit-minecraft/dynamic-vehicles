package io.github.brooswitminecraft.dynamicvehicles.hud;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DrivingHudPresentationTest {
    @Test void speedIsKilometersPerHourAndReverseHasPositiveMagnitude() {
        assertEquals("36 km/h", DrivingHudPresentation.speed(10));
        assertEquals("36 km/h", DrivingHudPresentation.speed(-10));
        assertEquals("0 km/h", DrivingHudPresentation.speed(0));
    }

    @Test void rpmUsesEngineTelemetryIncludingBelowIdleAndAboveNominalLimiter() {
        assertEquals("400 RPM", DrivingHudPresentation.rpm(400));
        assertEquals("6700 RPM", DrivingHudPresentation.rpm(6700));
    }

    @Test void onlyDriverWithTelemetryAndVisibleGuiGetsDashboard() {
        assertTrue(DrivingHudPresentation.visible(false, true, true, true));
        assertFalse(DrivingHudPresentation.visible(true, true, true, true));
        assertFalse(DrivingHudPresentation.visible(false, false, true, true));
        assertFalse(DrivingHudPresentation.visible(false, true, false, true));
        assertFalse(DrivingHudPresentation.visible(false, true, true, false));
    }
}
