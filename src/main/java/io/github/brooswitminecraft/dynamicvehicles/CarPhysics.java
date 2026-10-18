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
     * {@link #DEFAULT_FORWARD} today, and never any individual manual gear. Braking is never gear-gated --
     * only acceleration away from zero is -- so the hard invariant this buys is simple: with a forward
     * gear, {@link #AUTOMATIC} or {@link #NEUTRAL} selected, speed never goes negative; with
     * {@link #REVERSE} selected, it never goes positive.
     */
    public enum Gear {
        REVERSE, NEUTRAL, D1, D2, D3, D4, D5, AUTOMATIC;

        /**
         * Keyboard/gamepad's single forward selection (MINECRAFT-401 review): {@link #AUTOMATIC}, not a
         * fixed manual gear. Keyboard/gamepad players can only ever toggle between this and
         * {@link #REVERSE} ({@link CarEntity#toggleGear()}) -- they have no way to pick a specific manual
         * ratio -- so defaulting them to a fixed low gear (as this did before MINECRAFT-401 introduced
         * ratios that actually matter) would permanently multiply their drive force by D1's own ratio with
         * no way to ever shift out of it. Automatic mode is the intentional, usable policy for every
         * non-shifter driver; the physical shifter's own H-pattern sixth position reaches the exact same
         * state deliberately (see {@link WheelMapping#shifterGear}).
         */
        public static final Gear DEFAULT_FORWARD = AUTOMATIC;

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
