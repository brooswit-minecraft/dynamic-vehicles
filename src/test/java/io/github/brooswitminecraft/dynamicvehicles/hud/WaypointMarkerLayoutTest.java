package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import io.github.brooswitminecraft.dynamicvehicles.delivery.DangerGauge;
import io.github.brooswitminecraft.dynamicvehicles.delivery.OfferDistanceFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MINECRAFT-411: moved here (unchanged) from {@code delivery.WaypointMarkerLayoutTest} alongside the projection/clamping it tests. */
class WaypointMarkerLayoutTest {
    private static WaypointMarkerLayout.Point project(double x, double y, double z) {
        return WaypointMarkerLayout.project(x, y, z,
                0, 0, 1, 1, 0, 0, 0, 1, 0,
                90, 16.0 / 9.0, 0.05);
    }

    @Test
    void projectsDestinationInFrontToScreenCenter() {
        WaypointMarkerLayout.Point point = project(0, 0, 10);
        assertFalse(point.clamped());
        assertEquals(0.5, point.x(), 1e-9);
        assertEquals(0.5, point.y(), 1e-9);
    }

    @Test
    void clampsOffScreenDestinationToRightEdge() {
        WaypointMarkerLayout.Point point = project(20, 0, 10);
        assertTrue(point.clamped());
        assertEquals(0.95, point.x(), 1e-9);
        assertEquals(0.5, point.y(), 1e-9);
    }

    @Test
    void clampsCornerAlongTrueBearing() {
        WaypointMarkerLayout.Point point = project(20, 20, 10);
        assertTrue(point.clamped());
        assertEquals(0.05, point.y(), 1e-9);
        assertTrue(point.x() > 0.5, "upper-right bearing should retain its rightward component");
    }

    @Test
    void behindCameraDoesNotMirrorRightBearingToLeft() {
        WaypointMarkerLayout.Point point = project(20, 0, -10);
        assertTrue(point.clamped());
        assertEquals(0.95, point.x(), 1e-9);
    }

    @Test
    void directlyBehindUsesAnEdgeInsteadOfCenter() {
        WaypointMarkerLayout.Point point = project(0, 0, -10);
        assertTrue(point.clamped());
        assertEquals(0.5, point.x(), 1e-9);
        assertEquals(0.95, point.y(), 1e-9);
    }

    @Test
    void rejectsInvalidProjectionInputs() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> WaypointMarkerLayout.project(0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0,
                        180, 1, 0));
    }

    @Test
    void usesExistingDistanceFormatAndThreatPalette() {
        // This projection is shared by both Dispatcher and Pathfinder mission waypoints
        // (MINECRAFT-411); it stays agnostic of the delivery-specific formatting/colour classes
        // callers apply on top, which this just confirms are unaffected by the move.
        assertEquals("1.2 km", OfferDistanceFormat.format(1234.0));
        assertEquals(DangerGauge.colorArgb(0.0, 0.0, 1.0), DangerGauge.colorArgb(-1.0, 0.0, 1.0));
        assertEquals(DangerGauge.colorArgb(1.0, 0.0, 1.0), DangerGauge.colorArgb(2.0, 0.0, 1.0));
    }
}
