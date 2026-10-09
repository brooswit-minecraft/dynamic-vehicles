package io.github.brooswitminecraft.dynamicvehicles.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINECRAFT-109 AC3/AC8: danger-to-gauge/category mapping, including
 * boundary values, and that configured (non 0..1) bounds are honoured
 * rather than an assumed 0..1 span.
 */
class DangerGaugeTest {

    @Test
    void normalize_rejectsNonPositiveSpan() {
        assertThrows(IllegalArgumentException.class, () -> DangerGauge.normalize(0.5, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> DangerGauge.normalize(0.5, 1.0, 0.0));
    }

    @Test
    void normalize_clampsOutOfRangeValues() {
        assertEquals(0.0, DangerGauge.normalize(-5.0, 0.0, 1.0), 1e-9);
        assertEquals(1.0, DangerGauge.normalize(5.0, 0.0, 1.0), 1e-9);
    }

    @Test
    void normalize_honoursConfiguredBoundsNotAnAssumedZeroToOneSpan() {
        // A village deterministically rolled danger=5 on a 0..1-scale config with these bounds
        // would be impossible in practice, but the point under test is that normalize reads the
        // bounds it is given rather than assuming 0..1: with bounds 4..6, 5 is the midpoint.
        assertEquals(0.5, DangerGauge.normalize(5.0, 4.0, 6.0), 1e-9);
    }

    @Test
    void categoryFor_boundaryValues() {
        assertEquals(DangerGauge.Category.SAFE, DangerGauge.categoryFor(0.0, 0.0, 1.0));
        assertEquals(DangerGauge.Category.EXTREME, DangerGauge.categoryFor(1.0, 0.0, 1.0));
        assertEquals(DangerGauge.Category.SAFE, DangerGauge.categoryFor(0.19, 0.0, 1.0));
        assertEquals(DangerGauge.Category.LOW, DangerGauge.categoryFor(0.2, 0.0, 1.0));
        assertEquals(DangerGauge.Category.MODERATE, DangerGauge.categoryFor(0.4, 0.0, 1.0));
        assertEquals(DangerGauge.Category.HIGH, DangerGauge.categoryFor(0.6, 0.0, 1.0));
        assertEquals(DangerGauge.Category.EXTREME, DangerGauge.categoryFor(0.8, 0.0, 1.0));
    }

    @Test
    void categoryFor_scalesWithConfiguredBounds() {
        // dangerMin=0.2, dangerMax=0.4: the midpoint 0.3 should land in the same relative
        // category as 0.5 does on a 0..1 scale (MODERATE), not be judged against 0..1 directly.
        assertEquals(DangerGauge.Category.MODERATE, DangerGauge.categoryFor(0.3, 0.2, 0.4));
    }

    @Test
    void colorArgb_safeIsGreenerThanExtremeIsRedder() {
        int safeColor = DangerGauge.colorArgb(0.0, 0.0, 1.0);
        int extremeColor = DangerGauge.colorArgb(1.0, 0.0, 1.0);

        int safeRed = (safeColor >> 16) & 0xFF;
        int safeGreen = (safeColor >> 8) & 0xFF;
        int extremeRed = (extremeColor >> 16) & 0xFF;
        int extremeGreen = (extremeColor >> 8) & 0xFF;

        assertTrue(safeGreen > extremeGreen, "safe should be greener than extreme");
        assertTrue(extremeRed > safeRed, "extreme should be redder than safe");
        assertEquals(0xFF, (safeColor >>> 24) & 0xFF, "colour must be opaque");
    }
}
