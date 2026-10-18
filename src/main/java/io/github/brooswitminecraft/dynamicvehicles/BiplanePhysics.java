package io.github.brooswitminecraft.dynamicvehicles;

/**
 * MINECRAFT-380: {@link BiplaneEntity}'s movement model as pure functions, with no Minecraft imports (see
 * {@link MotorboatPhysics}'s own javadoc for why). All constants are in Minecraft's native per-tick units.
 *
 * <p>Vertical speed is the same RATE-LIMITED TARGET {@link ChopperPhysics} uses, for the same reason: the
 * pre-fix version's {@code vy += -sin(pitch) * CLIMB_RATE * dt} every tick, with no cap, meant holding a
 * climb pitch accumulated {@code vy} without bound, and levelling the view afterward left that
 * accumulated ascent/descent completely unchanged.
 *
 * <p>The ground-to-air transition is a speed-and-pitch-qualified check ({@code liftingOffOrFlyingFast} in
 * {@link #step}) evaluated BEFORE the grounded sink fallback, not after: {@code grounded} reflects the
 * PREVIOUS tick's collision result, so a plane that only just cleared {@link #TAKEOFF_SPEED} this tick is
 * still reported grounded, and an earlier version of this method checked {@code grounded} first -- which
 * meant the sink target always won and the plane could never actually leave the ground no matter how fast
 * or how hard it pulled up (caught by review, not by this class's own unit tests, since those called
 * {@link #step} with {@code grounded} already false to test climbing). Once genuinely grounded with no
 * qualifying climb attempt, the target is a small constant sink ({@link #GROUND_GRAVITY}), not exactly 0;
 * see that field's own javadoc for why.
 */
final class BiplanePhysics {
    private BiplanePhysics() {}

    static final double THRUST_ACCEL = 0.012;
    static final double MAX_SPEED = 0.8;
    static final double GROUND_TURN_RATE_DEG = 1.75;
    static final double AIR_TURN_RATE_DEG = 2.25;
    static final double DRAG = 0.985;
    /** Blocks/tick: forward speed needed before nose-up pitch is allowed to climb at all. */
    static final double TAKEOFF_SPEED = 0.35;
    /** Blocks/tick: below this while airborne, the wings are stalled and gravity (bounded, see
     * {@link #MAX_FALL_SPEED}) takes over regardless of pitch. */
    static final double STALL_SPEED = 0.2;
    static final double MAX_CLIMB_SPEED = 0.25;
    static final double MAX_FALL_SPEED = 0.35;
    static final double VERTICAL_ACCEL = 0.03;
    /** Grounded vy snaps to its target immediately rather than ramping at {@link #VERTICAL_ACCEL}. */
    private static final double GROUND_SNAP = Double.MAX_VALUE;
    /** Vanilla's own per-tick gravity constant: grounded vy targets a small constant SINK, not exactly 0.
     * A real game-test run (not just the pure-function tests) caught why this matters: vanilla's own
     * {@code onGround} flag is only reconfirmed when an entity actually ATTEMPTS a downward move that
     * collision then clamps back to the surface it is resting on. Pinning vy to exactly 0 while grounded
     * makes no such attempt, so {@code onGround} can go stale (read false) a tick or two later even though
     * the plane never actually left the ground -- {@link BiplaneEntity}'s own post-move clamp zeroes the
     * actual delta movement back out each tick anyway, so this constant sink never visibly moves the
     * plane, it just keeps the resting contact honest every tick. */
    static final double GROUND_GRAVITY = 0.08;

    record Result(double vx, double vy, double vz, double yawDeg) {}

    static Result step(double vx, double vy, double vz, double yawDeg,
            double throttle, double steer, double pitchDeg, boolean hasRider, boolean grounded) {
        double speed = Math.hypot(vx, vz);
        if (hasRider) {
            double turnRate = grounded ? GROUND_TURN_RATE_DEG : AIR_TURN_RATE_DEG * Math.min(1.0, speed / TAKEOFF_SPEED);
            yawDeg += steer * turnRate;
            double yaw = Math.toRadians(yawDeg);
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            vx += fx * throttle * THRUST_ACCEL;
            vz += fz * throttle * THRUST_ACCEL;
        }
        // Positive = nose-up (climb intent), negative = nose-down (dive intent).
        double climbComponent = hasRider ? -Math.sin(Math.toRadians(pitchDeg)) : 0.0;
        // The ground-to-air transition: fast enough AND nose-up, checked BEFORE the grounded fallback
        // below. onGround() is reported from the PREVIOUS tick's collision result, so a plane that just
        // cleared takeoff speed this tick is still "grounded" as far as that stale flag is concerned --
        // checking grounded first (an earlier version of this method did exactly that) meant the sink
        // target below always won, and a biplane could never actually leave the ground no matter how
        // fast it taxied. Once genuinely airborne (speed >= TAKEOFF_SPEED, grounded already false), pitch
        // keeps responding the same way, climb or dive, without needing the nose-up qualifier again.
        boolean liftingOffOrFlyingFast = hasRider && speed >= TAKEOFF_SPEED && (!grounded || climbComponent > 0.0);
        double targetVy;
        double verticalAccel;
        if (liftingOffOrFlyingFast) {
            targetVy = climbComponent * MAX_CLIMB_SPEED;
            verticalAccel = VERTICAL_ACCEL;
        } else if (grounded) {
            targetVy = -GROUND_GRAVITY;
            verticalAccel = GROUND_SNAP;
        } else if (speed < STALL_SPEED) {
            targetVy = -MAX_FALL_SPEED;
            verticalAccel = VERTICAL_ACCEL;
        } else {
            // Gliding below takeoff speed but not yet stalled: hold the current vy (no new vertical
            // accel), still subject to the same bounded rate limit if something else changes it next tick.
            targetVy = vy;
            verticalAccel = VERTICAL_ACCEL;
        }
        vy += clamp(targetVy - vy, -verticalAccel, verticalAccel);
        vx *= DRAG;
        vz *= DRAG;
        double newSpeed = Math.hypot(vx, vz);
        if (newSpeed > MAX_SPEED) {
            vx = vx / newSpeed * MAX_SPEED;
            vz = vz / newSpeed * MAX_SPEED;
        }
        return new Result(vx, vy, vz, yawDeg);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
