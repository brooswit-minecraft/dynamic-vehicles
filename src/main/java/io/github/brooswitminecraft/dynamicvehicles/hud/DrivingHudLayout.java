package io.github.brooswitminecraft.dynamicvehicles.hud;

/** GUI-scaled pixel bounds shared by the renderer and its acceptance checks. */
public record DrivingHudLayout(Rect health, Rect speed, Rect slip, Rect rpm) {
    public static final int ROW_HEIGHT = 10;
    public static final int AIR_OFFSET = 58;
    public static final int LEFT_OFFSET = 70;

    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean intersects(Rect other) {
            return x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y;
        }
    }

    public static DrivingHudLayout at(int width, int height) {
        int left = width / 2 - 91;
        return new DrivingHudLayout(new Rect(left, height - 46, 81, ROW_HEIGHT),
                new Rect(left + 101, height - 46, 81, ROW_HEIGHT),
                new Rect(left, height - 58, 81, ROW_HEIGHT),
                new Rect(left, height - 34, 182, ROW_HEIGHT));
    }

    /** Vanilla adds 14 to the selected-item label's Y in creative; compensate its reservation. */
    public static int itemNameOffset(boolean canHurtPlayer) {
        return LEFT_OFFSET + (canHurtPlayer ? 0 : 14);
    }
}
