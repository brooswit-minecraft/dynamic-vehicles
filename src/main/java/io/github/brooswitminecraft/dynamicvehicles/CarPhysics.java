package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure car dynamics, free of Minecraft types so it can be unit tested. A
 * kinematic bicycle model with a lateral-grip limit: the car steers by the
 * front wheels, accelerates and brakes along its heading, and when cornering or
 * throttle asks for more than the tires can hold, the excess shows up as slip
 * speed. The entity reports that slip to Dynamic Terrain; this class never
 * touches terrain. Starting numbers, to be tuned in play. Units: metres,
 * seconds, radians.
 */
public final class CarPhysics {
    public static final double WHEELBASE = 2.4;
    public static final double MAX_STEER = 0.55;
    public static final double ACCEL = 7.0;
    public static final double BRAKE = 12.0;
    public static final double REVERSE_MAX = 6.0;
    public static final double MAX_SPEED = 32.0;
    public static final double DRAG = 0.012;
    public static final double ROLLING = 0.4;
    /** Lateral acceleration the tires hold before they slide (m/s^2). A surface model will scale this later. */
    public static final double LATERAL_GRIP = 9.0;
    /** Longitudinal acceleration the tires hold before the wheels spin. */
    public static final double LONGITUDINAL_GRIP = 8.0;

    private CarPhysics() {}

    /** Car state: speed along the heading (m/s, negative = reversing) and heading (radians). */
    public record State(double speed, double heading) {}

    /** What one step produced: the new state plus how hard the tires are sliding. */
    public record Step(State state, double lateralSlip, double wheelSpin, double lockedBraking) {
        /** The largest slip of any kind, for reporting to terrain. */
        public double slipSpeed() {
            return Math.max(lateralSlip, Math.max(wheelSpin, lockedBraking));
        }
    }

    /**
     * The drivetrain's explicit gear (MINECRAFT-228): which direction the throttle is allowed to drive the
     * car from a standstill. The owner's real shifter is six forward gears plus reverse (6+R), so the type
     * names all of them -- {@code D1}..{@code D6} -- rather than a single "forward", so a future caller
     * deriving gear from physical shifter hardware can address any of the six directly without reshaping
     * this type. Keyboard and gamepad only ever select {@link #REVERSE} or {@link #DEFAULT_FORWARD} today;
     * nothing yet gives the six forward gears different ratios (see the PR description for that proposal),
     * so which forward gear is selected has no physics effect yet -- only {@link #isForward()} matters to
     * {@link #step}. Braking is never gear-gated -- only acceleration away from zero is -- so the hard
     * invariant this buys is simple: with a forward gear or {@link #NEUTRAL} selected, speed never goes
     * negative; with {@link #REVERSE} selected, it never goes positive.
     */
    public enum Gear {
        REVERSE, NEUTRAL, D1, D2, D3, D4, D5, D6;

        /** Keyboard/gamepad's single forward selection; see the class javadoc for why D1 is as good as any. */
        public static final Gear DEFAULT_FORWARD = D1;

        public boolean isForward() {
            return this != REVERSE && this != NEUTRAL;
        }
    }

    /**
     * @param throttle -1..1 (negative = brake while moving forward; only drives the car backward from a
     *                  standstill when {@code gear} is {@link Gear#REVERSE} -- braking never becomes reverse)
     * @param steer -1..1 (positive = left)
     * @param handbrake locks the rear wheels
     * @param gear which direction the throttle may accelerate the car from a standstill
     */
    public static Step step(State state, double throttle, double steer, boolean handbrake, Gear gear, double dt) {
        double speed = state.speed();
        double steerAngle = steer * MAX_STEER / (1.0 + Math.abs(speed) / 14.0);

        double wheelSpin = 0.0;
        double lockedBraking = 0.0;
        double accel = 0.0;
        if (throttle > 0) {
            if (speed < 0) {
                // Driving forward out of a reverse roll is braking that reverse motion first, in any gear.
                double demand = throttle * ACCEL + BRAKE * throttle;
                accel = Math.min(demand, LONGITUDINAL_GRIP);
                wheelSpin = Math.max(0.0, demand - LONGITUDINAL_GRIP) * 1.5;
            } else if (gear.isForward()) {
                double demand = throttle * ACCEL;
                accel = Math.min(demand, LONGITUDINAL_GRIP);
                wheelSpin = Math.max(0.0, demand - LONGITUDINAL_GRIP) * 1.5;
            }
            // gear is NEUTRAL or REVERSE and speed >= 0: the throttle asks for forward drive the current
            // gear does not allow; nothing accelerates (shift to a forward gear first), same as a real
            // car's gear lockout -- neutral simply coasts.
        } else if (throttle < 0) {
            if (speed > 0.2) {
                double demand = -throttle * BRAKE;
                accel = -Math.min(demand, LONGITUDINAL_GRIP * 1.4);
                // Braking harder than the tires hold locks them up and skids.
                lockedBraking = Math.max(0.0, demand - LONGITUDINAL_GRIP * 1.4) * 1.5;
            } else if (gear == Gear.REVERSE) {
                accel = -Math.min(-throttle * ACCEL * 0.6, ACCEL);
            }
            // gear is NEUTRAL or a forward gear and speed <= 0.2: holding brake at a standstill (or
            // already stopped) must never reverse the car -- the hard acceptance criterion this ticket
            // exists for. Only an explicit REVERSE gear lets throttle < 0 drive the car backward.
        }
        if (handbrake) {
            accel -= Math.signum(speed) * BRAKE;
            lockedBraking = Math.max(lockedBraking, Math.abs(speed) * 0.5);
        }
        accel -= Math.signum(speed) * (ROLLING + DRAG * speed * speed);

        double next = speed + accel * dt;
        if (throttle == 0 && !handbrake && Math.signum(next) != Math.signum(speed)) {
            next = 0.0; // friction and drag stop the car, they do not reverse it
        }
        if (handbrake && Math.signum(next) != Math.signum(speed)) {
            next = 0.0;
        }
        // Belt and suspenders on the same invariant: whatever the accel math above did, the current gear
        // still never lets speed cross zero into the direction it does not allow. Neutral is grouped with
        // the forward gears here (not reverse) -- it must never let brake alone put the car in reverse.
        if (gear != Gear.REVERSE && next < 0.0) {
            next = 0.0;
        }
        if (gear == Gear.REVERSE && next > 0.0) {
            next = 0.0;
        }
        next = Math.max(-REVERSE_MAX, Math.min(MAX_SPEED, next));

        double yawRate = next * Math.tan(steerAngle) / WHEELBASE;
        double lateralAccel = Math.abs(next * yawRate);
        // A proxy for how fast the contact patches slide sideways: the unmet lateral acceleration, halved.
        double lateralSlip = lateralAccel > LATERAL_GRIP ? (lateralAccel - LATERAL_GRIP) * 0.5 : 0.0;
        if (lateralAccel > LATERAL_GRIP) {
            // Past the grip limit the car cannot turn as tightly as asked.
            yawRate *= LATERAL_GRIP / lateralAccel;
        }
        double heading = state.heading() + yawRate * dt;
        return new Step(new State(next, heading), lateralSlip, wheelSpin, lockedBraking);
    }
}
