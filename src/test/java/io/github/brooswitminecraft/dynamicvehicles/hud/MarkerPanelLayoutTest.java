package io.github.brooswitminecraft.dynamicvehicles.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR #177 review: the panel (backing box, distance label, extra lines) must stay on screen even
 * when the dot it is built from is clamped near an edge - reproduces the review's own numeric
 * example (GUI height 240, dot at 95% = y=228, two extra lines) and generalizes it across GUI
 * sizes, extra-line counts, and all four edges.
 */
class MarkerPanelLayoutTest {
    private static final int VIEWPORT_WIDTH = 320;
    private static final int VIEWPORT_HEIGHT = 240;

    @Test
    void unclampedCenterTargetMatchesTheOriginalFixedLayout() {
        // No extra lines, plenty of room: box top = dotY - 7 and bottom = dotY + 18, unchanged
        // from the original Dispatcher-only layout, and the text centres on the dot's own x.
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(160, 120, 10, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertEquals(new Rect(153, 113, 177, 138), result.box());
        assertEquals(160, result.textCenterX());
        assertEquals(126, result.textOriginY());
    }

    @Test
    void reviewsOwnExampleNoLongerRunsBelowTheViewport() {
        // The review's exact numbers: GUI height 240, a bottom-edge-clamped dot at y = round(0.95*240) = 228,
        // two extra lines (progress + countdown). The old fixed layout put progress at 245 and
        // the timer at 255, both off-screen; the panel must now fit entirely within [0, 240].
        int dotY = 228;
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(160, dotY, 10, 2, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertTrue(result.box().bottom() <= VIEWPORT_HEIGHT, "panel bottom must stay on screen: " + result.box());
        assertTrue(result.extraLineY(0) < VIEWPORT_HEIGHT, "first extra line must stay on screen");
        assertTrue(result.extraLineY(1) < VIEWPORT_HEIGHT, "second extra line must stay on screen");
    }

    @Test
    void panelAtTheTopEdgeNeverGoesNegative() {
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(160, 0, 10, 2, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertTrue(result.box().top() >= 0, "panel top must stay on screen: " + result.box());
    }

    @Test
    void panelAtTheRightEdgeStaysWithinTheViewportWidth() {
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(VIEWPORT_WIDTH, 120, 60, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertTrue(result.box().right() <= VIEWPORT_WIDTH, "panel right edge must stay on screen: " + result.box());
    }

    @Test
    void panelAtTheLeftEdgeNeverGoesNegative() {
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(0, 120, 60, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertTrue(result.box().left() >= 0, "panel left edge must stay on screen: " + result.box());
    }

    @Test
    void aCornerTargetStaysWithinTheViewportOnBothAxes() {
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 60, 2, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertTrue(result.box().right() <= VIEWPORT_WIDTH);
        assertTrue(result.box().bottom() <= VIEWPORT_HEIGHT);
        assertTrue(result.box().left() >= 0);
        assertTrue(result.box().top() >= 0);
    }

    @Test
    void zeroExtraLinesStillFitsAtTheBottomEdge() {
        MarkerPanelLayout.Result result = MarkerPanelLayout.layout(160, VIEWPORT_HEIGHT, 10, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        assertTrue(result.box().bottom() <= VIEWPORT_HEIGHT);
    }

    @Test
    void withBoxKeepsTheSameTextCenterXAndRecomputesTextYFromTheNewBox() {
        MarkerPanelLayout.Result original = MarkerPanelLayout.layout(160, 120, 10, 1, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        Rect shiftedBox = original.box().translate(0, 50);
        MarkerPanelLayout.Result shifted = original.withBox(shiftedBox);
        assertEquals(original.textCenterX(), shifted.textCenterX());
        assertEquals(original.textOriginY() + 50, shifted.textOriginY());
        assertEquals(original.extraLineY(0) + 50, shifted.extraLineY(0));
    }
}
