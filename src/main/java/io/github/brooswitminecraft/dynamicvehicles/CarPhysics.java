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
     * The drivetrain's explicit gear (MINECRAFT-228): which direction the throttle is allowed to drive the
     * car from a standstill. The owner's real shifter is six forward gears plus reverse (6+R), so the type
     * names all of them -- {@code D1}..{@code D6} -- rather than a single "forward", so a future caller
     * deriving gear from physical shifter hardware can address any of the six directly without reshaping
     * this type. Keyboard and gamepad only ever select {@link #REVERSE} or {@link #DEFAULT_FORWARD} today;
     * nothing yet gives the six forward gears different ratios (see the PR description for that proposal),
     * so which forward gear is selected has no physics effect yet -- the Sable car only distinguishes
     * {@link #REVERSE} from the rest. Braking is never gear-gated -- only acceleration away from zero is -- so the hard
     * invariant this buys is simple: with a forward gear or {@link #NEUTRAL} selected, speed never goes
     * negative; with {@link #REVERSE} selected, it never goes positive.
     */
    public enum Gear {
        REVERSE, NEUTRAL, D1, D2, D3, D4, D5, D6;

        /** Keyboard/gamepad's single forward selection; see the class javadoc for why D1 is as good as any. */
        public static final Gear DEFAULT_FORWARD = D1;
    }
}
