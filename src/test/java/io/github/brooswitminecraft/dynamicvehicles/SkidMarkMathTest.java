package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SkidMarkMathTest {
    @Test
    void skiddingOnlyAboveThreshold() {
        assertFalse(SkidMarkMath.isSkidding(0.0));
        assertFalse(SkidMarkMath.isSkidding(SkidMarkMath.SKID_THRESHOLD - 0.01));
        assertTrue(SkidMarkMath.isSkidding(SkidMarkMath.SKID_THRESHOLD));
        assertTrue(SkidMarkMath.isSkidding(1.0));
    }

    @Test
    void spawnOnlyPastMinSpacing() {
        assertFalse(SkidMarkMath.shouldSpawn(0.0));
        assertFalse(SkidMarkMath.shouldSpawn(SkidMarkMath.MIN_SPACING - 0.01));
        assertTrue(SkidMarkMath.shouldSpawn(SkidMarkMath.MIN_SPACING));
        assertTrue(SkidMarkMath.shouldSpawn(SkidMarkMath.MIN_SPACING + 5.0));
    }

    @Test
    void alphaRampsLinearlyDownToZeroAtLifetimeAndNeverExceedsBase() {
        assertEquals(SkidMarkMath.BASE_ALPHA, SkidMarkMath.alpha(0L, SkidMarkMath.LIFETIME_MS), 1e-9);
        assertEquals(SkidMarkMath.BASE_ALPHA / 2.0, SkidMarkMath.alpha(SkidMarkMath.LIFETIME_MS / 2, SkidMarkMath.LIFETIME_MS), 1e-9);
        assertEquals(0.0, SkidMarkMath.alpha(SkidMarkMath.LIFETIME_MS, SkidMarkMath.LIFETIME_MS), 1e-9);
        assertEquals(0.0, SkidMarkMath.alpha(SkidMarkMath.LIFETIME_MS * 10, SkidMarkMath.LIFETIME_MS), 1e-9, "well past lifetime never goes negative");
        assertEquals(SkidMarkMath.BASE_ALPHA, SkidMarkMath.alpha(-500L, SkidMarkMath.LIFETIME_MS), 1e-9, "a mark 'from the future' never exceeds base alpha");
    }

    @Test
    void alphaIsZeroForNonPositiveLifetime() {
        assertEquals(0.0, SkidMarkMath.alpha(0L, 0L), 0.0);
        assertEquals(0.0, SkidMarkMath.alpha(0L, -100L), 0.0);
    }

    @Test
    void expiredAtAndPastLifetimeOnly() {
        assertFalse(SkidMarkMath.isExpired(SkidMarkMath.LIFETIME_MS - 1, SkidMarkMath.LIFETIME_MS));
        assertTrue(SkidMarkMath.isExpired(SkidMarkMath.LIFETIME_MS, SkidMarkMath.LIFETIME_MS));
        assertTrue(SkidMarkMath.isExpired(SkidMarkMath.LIFETIME_MS + 1, SkidMarkMath.LIFETIME_MS));
    }

    @Test
    void capToTotalLeavesAnUnderCapListAloneByReference() {
        List<SkidMark> marks = List.of(mark(1), mark(2));
        assertSame(marks, SkidMarkMath.capToTotal(marks, 5));
    }

    @Test
    void capToTotalKeepsOnlyTheMostRecentlySpawned() {
        List<SkidMark> marks = List.of(mark(1), mark(5), mark(3), mark(4), mark(2));
        List<SkidMark> capped = SkidMarkMath.capToTotal(marks, 2);
        assertEquals(2, capped.size());
        assertEquals(5L, capped.get(0).spawnTimeMs());
        assertEquals(4L, capped.get(1).spawnTimeMs());
    }

    private static SkidMark mark(long spawnTimeMs) {
        return new SkidMark(0.0, 0.0, 0.0, 0.0f, spawnTimeMs);
    }
}
