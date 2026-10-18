package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Shared car constants and the drivetrain gear type. The car is driven by Sable
 * rigid-body physics ({@link SableCarBody}); the old kinematic bicycle model was
 * removed. Free of Minecraft types so it can be unit tested. Units: metres,
 * radians.
 */
public final class CarPhysics {
    public static final double WHEELBASE = 2.4;
    public static final double MAX_STEER = 0.55;

    private CarPhysics() {}

    /**
     * The drivetrain's explicit gear (MINECRAFT-228, MINECRAFT-401): which direction the throttle is
     * allowed to drive the car from a standstill, and (for a forward gear) at what ratio. The owner's real
     * shifter has five manual forward positions, a sixth position that selects {@link #AUTOMATIC} instead
     * of a sixth manual ratio, and reverse -- so the type names {@code D1}..{@code D5} individually rather
     * than a single "forward", letting a caller deriving gear from physical shifter hardware address any
     * manual ratio directly. Keyboard and gamepad only ever select {@link #REVERSE} or
     * {@link #DEFAULT_FORWARD} today; {@link #AUTOMATIC} is reached only through the physical shifter's
     * sixth position (see {@link WheelMapping#shifterGear}). Braking is never gear-gated -- only
     * acceleration away from zero is -- so the hard invariant this buys is simple: with a forward gear,
     * {@link #AUTOMATIC} or {@link #NEUTRAL} selected, speed never goes negative; with {@link #REVERSE}
     * selected, it never goes positive.
     */
    public enum Gear {
        REVERSE, NEUTRAL, D1, D2, D3, D4, D5, AUTOMATIC;

        /** Keyboard/gamepad's single forward selection; see the class javadoc for why D1 is as good as any. */
        public static final Gear DEFAULT_FORWARD = D1;

        /** 0-based index into {@link DrivetrainMath#GEAR_RATIOS} for a manual forward gear, or -1 for
         * {@link #REVERSE}, {@link #NEUTRAL} or {@link #AUTOMATIC} (whose own effective ratio comes from
         * elsewhere -- the automatic shift logic, or the fixed reverse ratio). */
        public int manualIndex() {
            return switch (this) {
                case D1 -> 0;
                case D2 -> 1;
                case D3 -> 2;
                case D4 -> 3;
                case D5 -> 4;
                default -> -1;
            };
        }
    }
}
