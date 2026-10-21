package io.github.brooswitminecraft.dynamicvehicles;

/**
 * MINECRAFT-410: pure rigid-body hull/thrust/drag math for the motorboat, in real SI units (N, kg, m/s, m,
 * s) exactly like {@code WheelMath}/{@code EnvironmentMath} -- free of Minecraft and Sable (and JOML)
 * types so it can be unit tested without the game bootstrap, mirroring why those two classes are split out
 * of {@code SableCarBody}/{@code CarEntity}. Conversion to Minecraft's own blocks/tick convention happens
 * ONLY at {@code SableMotorboatBody.syncEntity}'s final velocity sync (divide by 20.0 ticks/sec), never
 * here -- see that class's own javadoc, and the old {@code MotorboatPhysics}' javadoc (superseded by this
 * class) for the unit-mixing bug this convention avoids.
 *
 * <p>This replaces the old scalar single-water-column, direct-yaw-assignment model with: real 4-point hull
 * buoyancy ({@link #pointBuoyantForce}, one independent spring-damper per hull corner, called once per
 * corner by {@code SableMotorboatBody} so differential immersion produces real restoring pitch/roll torque
 * from each point's own lever arm, never a direct rotation write), off-center motor thrust whose direction
 * rotates with the body's live orientation and the steer input ({@link #thrustVector}, producing yaw
 * torque through the motor mount's own lever arm -- see that method's own javadoc for the exact sign
 * convention), and a damping primitive ({@link #boundedDampingDelta}) used for lateral/longitudinal slip
 * resistance and angular/vertical damping alike, bounded so it can only ever remove velocity, never add it
 * or overshoot past zero, regardless of {@code dt} or how many times it is applied per tick.
 */
final class MotorboatPhysics {
    private MotorboatPhysics() {}

    /** World-up lift on the immersed, travel-facing hull edge. Total force is capped, not per point.
     * Still water: direction comes from horizontal hull velocity, including reverse and lateral travel.
     * Dry points receive no force; shallow immersion fades continuously over 0.25m. */
    static double[] travelSideLift(double vx, double vz, double[] offsetX, double[] offsetZ,
            double[] depths, double coefficient, double maxForce) {
        double[] forces = new double[depths.length];
        double speed = Math.hypot(vx, vz);
        if (!(speed > 1e-6) || !Double.isFinite(speed) || !(coefficient > 0)
                || !Double.isFinite(coefficient) || !(maxForce > 0) || !Double.isFinite(maxForce)) return forces;
        double totalWeight = 0;
        for (int i = 0; i < forces.length; i++) {
            forces[i] = Math.max(0, offsetX[i] * (vx / speed) + offsetZ[i] * (vz / speed));
            totalWeight += forces[i];
        }
        if (!(totalWeight > 0) || !Double.isFinite(totalWeight)) return new double[depths.length];
        double totalForce = Math.min(maxForce, coefficient * speed * speed);
        for (int i = 0; i < forces.length; i++) {
            double immersion = Double.isFinite(depths[i]) ? Math.clamp(depths[i] / 0.25, 0, 1) : 0;
            forces[i] = totalForce * (forces[i] / totalWeight) * immersion;
        }
        return forces;
    }

    /** A plain horizontal (X/Z) vector: thrust force components, N. */
    record Vector2(double x, double z) {}

    /**
     * One hull point's buoyant force, N (never negative, never above {@code maxForce}): a spring pushing
     * the point up proportional to how far it is submerged ({@code depth}, metres below the local water
     * surface -- zero or negative means dry, no force), damped against that point's own vertical velocity
     * so it settles rather than ringing forever. Clamping to {@code maxForce} is what keeps a point that
     * is suddenly deep underwater (e.g. the boat was just teleported into a lake) from producing an
     * exploding one-tick impulse -- see the ticket's robustness requirement.
     *
     * @param depth metres the point is below the local water surface; {@code <= 0} is dry
     * @param pointVelocityY the point's own vertical velocity (not just the body's linear velocity --
     *        includes the rotational contribution from the body's angular velocity and this point's own
     *        offset from the centre of mass), m/s
     * @param stiffness N per metre of depth
     * @param damping N*s per m/s of the point's own vertical velocity
     * @param maxForce hard cap, N
     */
    static double pointBuoyantForce(double depth, double pointVelocityY, double stiffness, double damping, double maxForce) {
        if (!(depth > 0.0)) {
            return 0.0;
        }
        double force = stiffness * depth - damping * pointVelocityY;
        return Math.max(0.0, Math.min(maxForce, force));
    }

    /**
     * The motor's thrust force vector, N, as X/Z components of a 2D vector in ANY single consistent
     * coordinate frame: {@code SableMotorboatBody} always calls this with {@code forwardX=0, forwardZ=1}
     * -- the boat's own LOCAL forward axis, never a world-space vector -- and applies the result directly
     * as a LOCAL impulse at the LOCAL motor mount, with no further rotation by the body's orientation. That
     * is what lets the thrust direction track the hull's actual pitch/roll/yaw exactly: computing and
     * applying it purely in local space means it is correct in 3D the instant Sable rotates the body by its
     * live orientation, rather than this method (or its caller) first projecting onto a world-horizontal
     * plane and losing the pitch/roll component -- the bug this fixed. {@code steerAngleRadians} (the
     * rudder's own deflection, already scaled from the raw -1..1 steer input to a maximum angle) further
     * rotates that local forward axis around the local Y (the motor's own steering plane), scaled by
     * {@code throttle} (-1..1, negative is reverse) times {@code maxThrust}.
     *
     * <p>Zero throttle gives the exact zero vector (never a residual force from floating-point rotation of
     * a zero-length result) -- see the zero-throttle test. Reverse throttle (negative) points the thrust
     * backward along this SAME steered direction: this motor steers in reverse too (a real outboard does),
     * a deliberate simplification documented here and pinned down by a dedicated test, rather than
     * silently zeroing the steer term only in reverse.
     *
     * <p><b>Steer-torque sign convention</b> (test-pinned, not just "nonzero"): the rotation below is
     * {@code rotatedX = fx*cos(theta) - fz*sin(theta)}, {@code rotatedZ = fx*sin(theta) + fz*cos(theta)}.
     * For a boat facing world +Z ({@code fx=0, fz=1}) and a positive {@code steerAngleRadians}, the
     * resulting thrust gets a NEGATIVE X component. Applied at the stern mount (whose local Z offset is
     * negative, behind the centre of mass), the torque about the vertical (Y) axis from
     * {@code torqueY = mountX*forceZ - mountZ*forceX} is therefore POSITIVE for a positive steer input
     * (since {@code -mountZ} is positive and {@code forceX} is negative, their product's negation is
     * positive) -- i.e. positive steer reliably produces one consistent torque sign, negative steer the
     * exact opposite. Callers/tests must not assume this maps to "left"/"right" by name; it is internally
     * consistent and that is what is tested.
     */
    static Vector2 thrustVector(double forwardX, double forwardZ, double steerAngleRadians, double throttle, double maxThrust) {
        if (throttle == 0.0) {
            return new Vector2(0.0, 0.0);
        }
        double cos = Math.cos(steerAngleRadians);
        double sin = Math.sin(steerAngleRadians);
        double rotatedX = forwardX * cos - forwardZ * sin;
        double rotatedZ = forwardX * sin + forwardZ * cos;
        double magnitude = throttle * maxThrust;
        return new Vector2(rotatedX * magnitude, rotatedZ * magnitude);
    }

    /**
     * A bounded damping delta (m/s, to be added to the velocity component it was computed from) that
     * removes {@code ratePerSecond} (1/s) of {@code v} per second of {@code dt}, but is clamped so its
     * magnitude can never exceed {@code |v|} itself -- i.e. it can slow a velocity component toward zero
     * and stop exactly at zero, but can never push it past zero into the opposite sign, no matter how
     * large {@code ratePerSecond * dt} is (a big nominal rate, a long dt, several applications per tick).
     * This is what makes every damping term built on this (lateral/longitudinal slip resistance, angular
     * damping, extra vertical damping) strictly energy-removing and stable at any timestep/sub-step choice
     * -- see the ticket's "never flips sign / overshoots" and "stable across timestep choices" robustness
     * requirements -- and stable at/near zero speed (repeated application from a small residual velocity
     * converges to exactly zero, never oscillates around it, since each step's delta magnitude shrinks
     * with {@code |v|} itself).
     *
     * @param v the velocity component, m/s
     * @param ratePerSecond how much of it to remove per second (1/s); 0 disables damping entirely
     * @param dt seconds this step covers
     */
    static double boundedDampingDelta(double v, double ratePerSecond, double dt) {
        if (v == 0.0 || ratePerSecond <= 0.0) {
            return 0.0;
        }
        double fraction = Math.max(0.0, Math.min(1.0, ratePerSecond * dt));
        return -v * fraction;
    }
}
