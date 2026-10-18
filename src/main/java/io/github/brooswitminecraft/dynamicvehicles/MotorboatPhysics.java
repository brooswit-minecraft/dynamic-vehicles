package io.github.brooswitminecraft.dynamicvehicles;

/**
 * MINECRAFT-380: {@link MotorboatEntity}'s movement model as pure functions, with no Minecraft imports, so
 * it can be unit-tested without the game bootstrap (same reason {@code WheelMath}/{@code CarPhysics} are
 * split out of {@code CarEntity}/{@code SableCarBody}).
 *
 * <p>Every constant and every value in {@link Result} is in Minecraft's own native per-tick units (blocks
 * per tick, degrees per tick) &mdash; the same convention {@code Entity#getDeltaMovement} and {@code
 * Entity#move} already use, with one game tick = 1/20 second fixed. An earlier version of this class (pre
 * review) mixed physical per-second acceleration constants into a per-tick integration with no
 * conversion at the {@code move} boundary, so a "9 m/s" engine accel actually added 9 blocks EVERY TICK
 * (180 blocks/sec at the nominal cap) -- seconds-based constants must never reach {@code move} unconverted.
 * Keeping every constant already in per-tick units here removes that conversion step (and the bug class
 * it enables) entirely, rather than converting at a boundary that a future edit could again skip.
 */
final class MotorboatPhysics {
    private MotorboatPhysics() {}

    /** Blocks/tick^2 added to velocity at full throttle. */
    static final double ENGINE_ACCEL = 0.04;
    /** Blocks/tick: never exceeded regardless of throttle held for however many ticks. */
    static final double MAX_SPEED = 0.5;
    /** Degrees/tick at full rudder and at least half {@link #MAX_SPEED}. */
    static final double TURN_RATE_DEG = 3.5;
    /** Per-tick horizontal velocity retained after drag; {@link #ENGINE_ACCEL}/(1-this) is the throttle-held
     * terminal speed, matched to {@link #MAX_SPEED} so the explicit cap below is a backstop, not the only limit. */
    static final double DRAG = 0.92;
    /** Fraction of the gap to the water surface closed per tick. */
    static final double FLOAT_GAIN = 0.3;
    /** Per-tick vertical velocity retained once at/near the surface (extra damping beyond FLOAT_GAIN's own). */
    static final double FLOAT_DAMP = 0.6;
    /** Vanilla's own per-tick gravity constant, used identically here while airborne/beached. */
    static final double GRAVITY = 0.08;
    static final double FALL_DRAG = 0.98;

    record Result(double vx, double vy, double vz, double yawDeg) {}

    /**
     * Advances one tick. {@code surfaceDelta} is {@code waterSurfaceY - currentY} and is only read when
     * {@code floating} is true.
     */
    static Result step(double vx, double vy, double vz, double yawDeg,
            double throttle, double steer, boolean hasRider, boolean floating, double surfaceDelta) {
        if (floating) {
            vy = (vy + surfaceDelta * FLOAT_GAIN) * FLOAT_DAMP;
        } else {
            vy = (vy - GRAVITY) * FALL_DRAG;
        }
        if (hasRider && floating) {
            double speed = Math.hypot(vx, vz);
            yawDeg += steer * TURN_RATE_DEG * (0.2 + Math.min(1.0, speed / (MAX_SPEED * 0.5)));
            double yaw = Math.toRadians(yawDeg);
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            vx += fx * throttle * ENGINE_ACCEL;
            vz += fz * throttle * ENGINE_ACCEL;
        }
        vx *= DRAG;
        vz *= DRAG;
        double speed = Math.hypot(vx, vz);
        if (speed > MAX_SPEED) {
            vx = vx / speed * MAX_SPEED;
            vz = vz / speed * MAX_SPEED;
        }
        return new Result(vx, vy, vz, yawDeg);
    }
}
