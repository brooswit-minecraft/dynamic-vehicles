package io.github.brooswitminecraft.dynamicvehicles;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MINECRAFT-410: the motorboat's Sable rigid body -- a box registered with Sable's physics system, plus
 * the 4-point hull buoyancy, the off-center motor thrust, and the lateral/longitudinal/angular/vertical
 * damping applied to it every tick. Mirrors {@link SableCarBody}'s own structure and conventions: +Z
 * forward, orientation via {@code rotateY(-yaw)}, every impulse applied in the body's own LOCAL frame via
 * {@code applyImpulseAtPoint} (world quantities converted with the inverse of the live orientation), and
 * Sable itself owns gravity and pose integration -- this class only ever ADDS forces via impulses, exactly
 * like {@code SableCarBody} (confirmed by that class's own existing buoyancy/drag impulses, which this
 * follows), never a second gravity/integration step and never a direct write to position or orientation
 * outside of the one entity-pose sync at the end of the tick ({@link #syncEntity}).
 *
 * <p>Replaces the old scalar {@code MotorboatPhysics} model (direct yaw assignment, one whole-body water
 * column, isotropic drag): each of the 4 hull corners and the 1 motor mount samples its OWN local water
 * surface independently ({@link #sampleSurface}), so differential immersion (bow deeper than stern, one
 * side dry) produces real restoring pitch/roll/heave torque from each point's own lever arm, and steering
 * comes from rotating the off-center motor's thrust direction (real torque from a real lever arm), never
 * from writing yaw directly -- the only {@code setYRot} call anywhere in this feature's new code is in
 * {@link #syncEntity} below, reading the body's own already-integrated orientation, exactly like
 * {@code SableCarBody.syncEntity} does for the car.
 *
 * <p><b>No sub-stepping</b> (contrast {@code CarConfig.WHEEL_SUB_STEPS}): the car sub-steps because the
 * tire's relaxation term and its friction-circle saturation are genuinely nonlinear and benefit from a
 * shorter horizon (see {@code SableCarBody}'s own long comment on exactly why). Nothing here has an
 * equivalent saturating nonlinearity: the hull springs are plain linear spring-dampers, and every
 * drag/damping term is already a single bounded, non-overshooting step
 * ({@link MotorboatPhysics#boundedDampingDelta}) by construction regardless of {@code dt}. Sub-stepping
 * would add cost here without adding stability, so this tick is a single pass.
 */
final class SableMotorboatBody {
    /** Bounded probe reach for {@link #sampleSurface}: a few blocks either side of the sampling point, not
     * an unbounded search -- see that method's own javadoc for why, and the ticket's "must not get
     * attracted to unrelated water far above/below" requirement. */
    private static final int PROBE_UP = 3;
    private static final int PROBE_DOWN = 4;

    private final ServerLevel level;
    private final Vector3d halfExtents;
    private final BoxPhysicsObject box;
    private final RigidBodyHandle body;
    private final double[][] hullMounts;
    private final double[] motorMount;
    private double lastSpeed;
    private double lastImmersionFraction;

    private SableMotorboatBody(ServerLevel level, Vector3d halfExtents, BoxPhysicsObject box, RigidBodyHandle body,
            double[][] hullMounts, double[] motorMount) {
        this.level = level;
        this.halfExtents = halfExtents;
        this.box = box;
        this.body = body;
        this.hullMounts = hullMounts;
        this.motorMount = motorMount;
    }

    /** Body orientation for a Minecraft yaw: the body's +Z axis points where the entity faces. */
    static Quaterniond orientationOf(float yawDegrees) {
        return new Quaterniond().rotateY(-Math.toRadians(yawDegrees));
    }

    /** Minecraft yaw (degrees) of a body orientation. */
    static float yawOf(Quaterniond orientation) {
        Vector3d forward = orientation.transform(new Vector3d(0, 0, 1));
        return (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    static SableMotorboatBody create(ServerLevel level, MotorboatEntity boat) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        double halfWidth = MotorboatEntity.halfWidth();
        double halfHeight = MotorboatEntity.halfHeight();
        double halfLength = MotorboatEntity.halfLength();
        Vector3d halfExtents = new Vector3d(halfWidth, halfHeight, halfLength);
        // The entity's origin is the bottom centre of its box; the body's origin is its centre.
        Vector3d centre = new Vector3d(boat.getX(), boat.getY() + halfHeight, boat.getZ());
        org.joml.Quaternionf saved = boat.savedOrientation();
        Quaterniond orientation = saved != null ? new Quaterniond(saved.x, saved.y, saved.z, saved.w) : orientationOf(boat.getYRot());
        Pose3d pose = new Pose3d(centre, orientation, new Vector3d(), new Vector3d(1, 1, 1));
        BoxPhysicsObject box = new BoxPhysicsObject(pose, halfExtents, MotorboatConfig.MASS_KG.get());
        container.physicsSystem().addObject(box);
        // Four hull support points (front/rear x port/starboard) at the hull's bottom in Y, plus one motor
        // mount near the stern/transom -- see the ticket's acceptance criteria 2 and 3.
        double[][] hullMounts = {
                {-halfWidth, -halfHeight, halfLength},
                {halfWidth, -halfHeight, halfLength},
                {-halfWidth, -halfHeight, -halfLength},
                {halfWidth, -halfHeight, -halfLength},
        };
        double[] motorMount = {0.0, -halfHeight, -halfLength};
        return new SableMotorboatBody(level, halfExtents, box, RigidBodyHandle.of(level, box), hullMounts, motorMount);
    }

    void remove() {
        SubLevelContainer.getContainer(level).physicsSystem().removeObject(box);
    }

    /** Apply this tick's buoyancy/thrust/drag/damping impulses, in that order (each re-reading velocity
     * after the previous group so later terms respond to what already changed, same pattern
     * {@code SableCarBody.tick} documents for its own sub-steps). */
    void tick(MotorboatEntity boat, double throttle, double steer, double dt) {
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Vector3d position = pose.position();
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Quaterniond inverse = new Quaterniond(orientation).invert();
        Vector3d linear = body.getLinearVelocity(new Vector3d());
        Vector3d angular = body.getAngularVelocity(new Vector3d());

        double stiffness = MotorboatConfig.HULL_BUOYANCY_STIFFNESS.get();
        double damping = MotorboatConfig.HULL_BUOYANCY_DAMPING.get();
        double maxPointForce = MotorboatConfig.MAX_POINT_BUOYANT_FORCE.get();

        int wetPoints = 0;
        for (double[] mount : hullMounts) {
            Vector3d offset = orientation.transform(new Vector3d(mount[0], mount[1], mount[2]));
            Vector3d worldPoint = new Vector3d(position).add(offset);
            Double surfaceY = sampleSurface(worldPoint.x, worldPoint.y, worldPoint.z);
            if (surfaceY == null) {
                continue;
            }
            double depth = surfaceY - worldPoint.y;
            if (!(depth > 0.0)) {
                continue;
            }
            wetPoints++;
            Vector3d pointVelocity = new Vector3d(angular).cross(offset).add(linear);
            double force = MotorboatPhysics.pointBuoyantForce(depth, pointVelocity.y, stiffness, damping, maxPointForce);
            if (force <= 0.0) {
                continue;
            }
            Vector3d localImpulse = inverse.transform(new Vector3d(0, force * dt, 0));
            body.applyImpulseAtPoint(new Vector3d(mount[0], mount[1], mount[2]), localImpulse);
        }
        double immersionFraction = wetPoints / (double) hullMounts.length;
        this.lastImmersionFraction = immersionFraction;

        // Motor thrust: "dry" (no thrust) whenever the motor's own mount point is not in water, regardless
        // of throttle -- see MotorboatPhysics.thrustVector's own javadoc for the direction/steer-torque
        // convention. Zero throttle already yields the exact zero vector there, so this guard is purely an
        // optimization, not load-bearing for the zero-throttle case.
        Vector3d motorOffset = orientation.transform(new Vector3d(motorMount[0], motorMount[1], motorMount[2]));
        Vector3d motorWorldPoint = new Vector3d(position).add(motorOffset);
        Double motorSurfaceY = sampleSurface(motorWorldPoint.x, motorWorldPoint.y, motorWorldPoint.z);
        boolean motorWet = motorSurfaceY != null && motorSurfaceY - motorWorldPoint.y > 0.0;
        if (motorWet && throttle != 0.0) {
            Vector3d forward3d = orientation.transform(new Vector3d(0, 0, 1));
            double forwardLength = Math.hypot(forward3d.x, forward3d.z);
            if (forwardLength > 1e-9) {
                double fx = forward3d.x / forwardLength;
                double fz = forward3d.z / forwardLength;
                double steerRad = steer * Math.toRadians(MotorboatConfig.MAX_STEER_DEG.get());
                MotorboatPhysics.Vector2 thrust = MotorboatPhysics.thrustVector(fx, fz, steerRad, throttle, MotorboatConfig.MAX_THRUST.get());
                Vector3d localImpulse = inverse.transform(new Vector3d(thrust.x() * dt, 0, thrust.z() * dt));
                body.applyImpulseAtPoint(new Vector3d(motorMount[0], motorMount[1], motorMount[2]), localImpulse);
            }
        }

        // Re-read velocity: the buoyancy/thrust impulses above already changed it (see SableCarBody's own
        // note on applyImpulseAtPoint's immediacy).
        linear = body.getLinearVelocity(new Vector3d());
        angular = body.getAngularVelocity(new Vector3d());
        double massKg = MotorboatConfig.MASS_KG.get();

        // Lateral/longitudinal drag: the boat's own velocity decomposed against its live forward/right
        // axes (still water assumption -- no current/flow model exists to sample, see the ticket), damped
        // by MotorboatPhysics.boundedDampingDelta (bounded, so this can only remove momentum, never
        // overshoot past zero), scaled by overall immersion so an airborne boat feels none of it.
        Vector3d forward = orientation.transform(new Vector3d(0, 0, 1));
        Vector3d right = orientation.transform(new Vector3d(1, 0, 0));
        double vLong = linear.dot(forward);
        double vLat = linear.dot(right);
        double longRate = MotorboatConfig.LONGITUDINAL_DRAG_RATE.get() * immersionFraction;
        double latRate = MotorboatConfig.LATERAL_DRAG_RATE.get() * immersionFraction;
        double deltaLong = MotorboatPhysics.boundedDampingDelta(vLong, longRate, dt);
        double deltaLat = MotorboatPhysics.boundedDampingDelta(vLat, latRate, dt);
        if (deltaLong != 0.0 || deltaLat != 0.0) {
            Vector3d dragImpulseWorld = new Vector3d(forward).mul(deltaLong * massKg).fma(deltaLat * massKg, right);
            body.applyImpulseAtPoint(new Vector3d(0, 0, 0), inverse.transform(dragImpulseWorld));
        }

        // Angular damping (yaw/pitch/roll), same bounded primitive, same immersion scaling. Sable exposes
        // no inertia-tensor accessor from this API, so the per-axis delta is scaled by the boat's own mass
        // as a plain stand-in for inertia (a tunable, not a physically exact angular impulse) -- bounded by
        // boundedDampingDelta regardless, so it can still only ever remove angular momentum.
        double angularRate = MotorboatConfig.ANGULAR_DAMPING_RATE.get() * immersionFraction;
        double dax = MotorboatPhysics.boundedDampingDelta(angular.x, angularRate, dt);
        double day = MotorboatPhysics.boundedDampingDelta(angular.y, angularRate, dt);
        double daz = MotorboatPhysics.boundedDampingDelta(angular.z, angularRate, dt);
        if (dax != 0.0 || day != 0.0 || daz != 0.0) {
            body.applyAngularImpulse(new Vector3d(dax * massKg, day * massKg, daz * massKg));
        }

        // Extra vertical damping beyond the hull springs' own damping term, also immersion-scaled so an
        // airborne boat (e.g. launched off a wave) never feels phantom water resistance.
        double verticalRate = MotorboatConfig.VERTICAL_DAMPING_RATE.get() * immersionFraction;
        double dvy = MotorboatPhysics.boundedDampingDelta(linear.y, verticalRate, dt);
        if (dvy != 0.0) {
            body.applyImpulseAtPoint(new Vector3d(0, 0, 0), inverse.transform(new Vector3d(0, dvy * massKg, 0)));
        }

        lastSpeed = body.getLinearVelocity(new Vector3d()).length();
        if (immersionFraction > 0.0 || throttle != 0.0 || steer != 0.0) {
            box.wakeUp();
        }
    }

    /** Move the entity to the body's current pose; the only place in this feature's new code that calls
     * {@code setYRot}, and it reads the body's own already-integrated orientation, never writes one. */
    void syncEntity(MotorboatEntity boat) {
        box.updatePose();
        Pose3d pose = box.getPose() instanceof Pose3d p ? p : new Pose3d(box.getPose());
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Vector3d p = pose.position();
        boat.setPos(p.x, p.y - halfExtents.y, p.z);
        boat.setYRot(yawOf(orientation));
        boat.yRotO = boat.getYRot();
        boat.publishOrientation(new org.joml.Quaternionf((float) orientation.x, (float) orientation.y, (float) orientation.z, (float) orientation.w));
        Vector3d v = body.getLinearVelocity(new Vector3d());
        boat.setDeltaMovement(v.x / 20.0, v.y / 20.0, v.z / 20.0);
    }

    /** The body's current orientation, for saving. */
    org.joml.Quaternionf orientationF() {
        box.updatePose();
        org.joml.Quaterniondc q = box.getPose().orientation();
        return new org.joml.Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
    }

    /** The body's speed as of the last tick, m/s. */
    double speed() {
        return lastSpeed;
    }

    /** Fraction (0..1) of the 4 hull points that were wet as of the last tick. */
    double immersionFraction() {
        return lastImmersionFraction;
    }

    /**
     * MINECRAFT-410: one hull/motor point's own local water surface Y, or {@code null} when none is found
     * -- the point is dry, whether aground, airborne, or merely occluded from nearby water by a solid
     * block. Bounded to {@link #PROBE_UP}/{@link #PROBE_DOWN} blocks either side of the point (never an
     * unbounded search), scanning top-down so the first water hit is the true (local) surface -- and
     * stopping the scan the instant ANY solid (collidable) block is seen, in either direction, so this
     * never "sees" water on the far side of a floor or ceiling from the point: a boat under a bridge with
     * water on the bridge's roof does not feel that water (the bridge itself is hit first, scanning down
     * from above); a boat resting on a dry dock over water below a floor does not feel that water either
     * (the floor is hit before the water further down). This is the same column-probe idea the old
     * {@code MotorboatEntity.findWaterSurfaceY} used, extended with the occlusion stop and run once per
     * hull/motor point instead of once for the whole boat.
     */
    private Double sampleSurface(double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        for (int dy = PROBE_UP; dy >= -PROBE_DOWN; dy--) {
            BlockPos probe = base.offset(0, dy, 0);
            if (level.getFluidState(probe).is(FluidTags.WATER)) {
                return probe.getY() + 1.0;
            }
            BlockState state = level.getBlockState(probe);
            if (!state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(level, probe).isEmpty()) {
                return null;
            }
        }
        return null;
    }

    /** Debug summary: pitch/roll in degrees and speed, mirroring {@code SableCarBody.describe}. */
    String describe() {
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Quaterniond q = new Quaterniond(pose.orientation());
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        Vector3d fwd = q.transform(new Vector3d(0, 0, 1));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd.y))));
        double roll = Math.toDegrees(Math.atan2(q.transform(new Vector3d(1, 0, 0)).y, up.y));
        return String.format("boat y=%.3f pitch=%.2f roll=%.2f speed=%.2f wet=%.2f",
                pose.position().y, pitch, roll, lastSpeed, lastImmersionFraction);
    }
}
