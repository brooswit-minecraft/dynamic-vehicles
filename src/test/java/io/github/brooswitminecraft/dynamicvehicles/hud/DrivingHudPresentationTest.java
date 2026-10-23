package io.github.brooswitminecraft.dynamicvehicles.hud;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DrivingHudPresentationTest {
    @Test void fractionsClampAtZeroAndIntendedMaximumAndRejectInvalidTelemetry() {
        assertEquals(0, DrivingHudPresentation.fraction(0, 40));
        assertEquals(.5, DrivingHudPresentation.fraction(-20, 40));
        assertEquals(1, DrivingHudPresentation.fraction(40, 40));
        assertEquals(1, DrivingHudPresentation.fraction(80, 40));
        assertEquals(0, DrivingHudPresentation.fraction(10, 0));
        assertEquals(0, DrivingHudPresentation.fraction(Double.NaN, 40));
        assertEquals(.5, DrivingHudPresentation.slipFraction(2));
        assertEquals(1, DrivingHudPresentation.slipFraction(8));
    }

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
