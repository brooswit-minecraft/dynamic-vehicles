package io.github.brooswitminecraft.dynamicvehicles.hud;

/**
 * An inclusive-left/top, exclusive-right/bottom integer screen-space rectangle. Minecraft-free so
 * it is directly unit-testable; used by {@link MarkerPanelLayout} and {@link MissionWaypointLayout}.
 */
public record Rect(int left, int top, int right, int bottom) {
    public Rect {
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("right/bottom must be >= left/top");
        }
    }

    public boolean overlaps(Rect other) {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
    }

    public Rect translate(int dx, int dy) {
        return new Rect(left + dx, top + dy, right + dx, bottom + dy);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }
}
