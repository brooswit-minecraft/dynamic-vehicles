package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * Pure layout for a single marker's label panel (backing box, distance label, and any extra
 * status lines below it), confined to the viewport. Minecraft-free so it is directly
 * unit-testable.
 *
 * <p>PR #177 review: the marker's dot position is already correctly inset within the viewport by
 * {@link WaypointMarkerLayout}'s own edge clamping, but the panel built on top of it previously
 * always grew straight down from the dot by a fixed amount, with no check that the result stayed
 * on screen - a dot clamped near the bottom edge pushed Pathfinder's progress/countdown lines (and,
 * at extremes, even the distance label) below the bottom of the screen. This also keeps the panel
 * clear of the left/right edges, not just the top/bottom.
 *
 * <p>Re-review follow-up: the box was previously built asymmetrically around the dot - always
 * {@code dotX - 7} on the left, but {@code dotX + max(8, halfTextWidth + 7)} on the right - so
 * centred text wider than about 14px already started to the left of the backing box. The box is
 * now symmetric around the dot ({@code dotX ± max(8, halfTextWidth + 7)}), so it always fully
 * contains the centred text regardless of width.
 */
public final class MarkerPanelLayout {
    /** Vertical spacing between stacked lines, matching the original fixed layout. */
    public static final int LINE_HEIGHT = 10;
    private static final int PANEL_HEIGHT_WITHOUT_EXTRA_LINES = 25;
    private static final int TEXT_ORIGIN_OFFSET = 13;
    /** Offset of the first extra line's baseline below the box top, i.e. {@code TEXT_ORIGIN_OFFSET + 11}. */
    private static final int FIRST_EXTRA_LINE_OFFSET = 24;

    private MarkerPanelLayout() {
    }

    /**
     * @param box the backing box, confined to {@code [0, viewportWidth] x [0, viewportHeight]} when it fits
     * @param textCenterX the x to center the distance label and every extra line on
     */
    public record Result(Rect box, int textCenterX) {
        /** The y the distance label draws at. Recomputed from {@code box}, so a caller that shifts the box (e.g. to resolve an inter-mission overlap) keeps the text correctly aligned without having to update a second field. */
        public int textOriginY() {
            return box.top() + TEXT_ORIGIN_OFFSET;
        }

        /** The y extra line {@code index} (0-indexed) draws at. */
        public int extraLineY(int index) {
            return box.top() + FIRST_EXTRA_LINE_OFFSET + LINE_HEIGHT * index;
        }

        /** The same layout, with its box moved to {@code newBox} (text positions follow automatically). */
        public Result withBox(Rect newBox) {
            return new Result(newBox, textCenterX);
        }
    }

    public static Result layout(
            int dotX, int dotY, int halfTextWidth, int extraLineCount, int viewportWidth, int viewportHeight) {
        int panelHeight = PANEL_HEIGHT_WITHOUT_EXTRA_LINES + LINE_HEIGHT * extraLineCount;
        int maxTop = Math.max(0, viewportHeight - panelHeight);
        int boxTop = clamp(dotY - 7, 0, maxTop);
        int boxBottom = boxTop + panelHeight;

        int halfWidth = Math.max(8, halfTextWidth + 7);
        int boxLeft = dotX - halfWidth;
        int boxRight = dotX + halfWidth;
        if (boxRight > viewportWidth) {
            int shift = boxRight - viewportWidth;
            boxLeft -= shift;
            boxRight -= shift;
        }
        if (boxLeft < 0) {
            int shift = -boxLeft;
            boxLeft += shift;
            boxRight += shift;
        }

        Rect box = new Rect(boxLeft, boxTop, boxRight, boxBottom);
        return new Result(box, boxLeft + halfWidth);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.min(Math.max(value, lo), hi);
    }
}
