package io.github.brooswitminecraft.dynamicvehicles;

/**
 * MINECRAFT-380: {@link ChopperEntity}'s movement model as pure functions, with no Minecraft imports (see
 * {@link MotorboatPhysics}'s own javadoc for why). All constants are in Minecraft's native per-tick units.
 *
 * <p>Vertical speed is a RATE-LIMITED TARGET, not an unbounded integral: each tick, {@code vy} moves at
 * most {@link #VERTICAL_ACCEL} toward {@code -sin(pitch) * MAX_CLIMB_SPEED} (or, unpiloted, toward
 * {@code -MAX_FALL_SPEED}). Holding a climb pitch therefore converges to and never exceeds {@link
 * #MAX_CLIMB_SPEED}, and leveling the view (pitch back to 0) pulls {@code vy} back toward 0 at the same
 * bounded rate instead of leaving a previously accumulated ascent/descent unchanged -- the defect a review
 * of this class's pre-fix version flagged (unbounded {@code vy += ...} every tick, with no cap and no path
 * back to level once the view levelled).
 */
final class ChopperPhysics {
    private ChopperPhysics() {}

    /** Blocks/tick^2 added to horizontal velocity at full throttle. */
    static final double FORWARD_ACCEL = 0.036;
    /** Blocks/tick: horizontal speed cap, matched to FORWARD_ACCEL/(1-DRAG) so the cap is a backstop. */
    static final double MAX_SPEED = 0.6;
    static final double TURN_RATE_DEG = 3.0;
    static final double DRAG = 0.94;
    /** Blocks/tick: the climb/dive rate a full nose-up/down pitch converges to. */
    static final double MAX_CLIMB_SPEED = 0.3;
    /** Blocks/tick: the unpiloted sink rate autorotation settles to. */
    static final double MAX_FALL_SPEED = 0.3;
    /** Blocks/tick^2: how fast vy is allowed to move toward its current target each tick. */
    static final double VERTICAL_ACCEL = 0.04;

    record Result(double vx, double vy, double vz, double yawDeg) {}

    static Result step(double vx, double vy, double vz, double yawDeg,
            double throttle, double steer, double pitchDeg, boolean hasRider) {
        if (hasRider) {
            yawDeg += steer * TURN_RATE_DEG;
            double yaw = Math.toRadians(yawDeg);
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            vx += fx * throttle * FORWARD_ACCEL;
            vz += fz * throttle * FORWARD_ACCEL;
        }
        double targetVy = hasRider ? -Math.sin(Math.toRadians(pitchDeg)) * MAX_CLIMB_SPEED : -MAX_FALL_SPEED;
        vy += clamp(targetVy - vy, -VERTICAL_ACCEL, VERTICAL_ACCEL);
        vx *= DRAG;
        vz *= DRAG;
        double speed = Math.hypot(vx, vz);
        if (speed > MAX_SPEED) {
            vx = vx / speed * MAX_SPEED;
            vz = vz / speed * MAX_SPEED;
        }
        return new Result(vx, vy, vz, yawDeg);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
