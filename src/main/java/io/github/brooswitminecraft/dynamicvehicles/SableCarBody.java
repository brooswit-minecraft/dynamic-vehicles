package io.github.brooswitminecraft.dynamicvehicles;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The car's Sable rigid body: a box registered with Sable's physics system,
 * plus the per-wheel forces applied to it. Sable's impulse API takes the point
 * and the impulse in the body's own frame, so world quantities are converted
 * with the inverse of the body's orientation.
 */
final class SableCarBody {
    static final Vector3d HALF_EXTENTS = new Vector3d(0.95, 0.5, 1.5);
    static final double MASS_KG = 1200.0;
    /** Wheel mounts in the body frame: x right, y up, z forward. */
    static final double[][] MOUNTS = {{-0.8, -0.4, 1.2}, {0.8, -0.4, 1.2}, {-0.8, -0.4, -1.2}, {0.8, -0.4, -1.2}};
    private static final double GRAVITY = 9.81;

    private final ServerLevel level;
    private final BoxPhysicsObject box;
    private final RigidBodyHandle body;

    private SableCarBody(ServerLevel level, BoxPhysicsObject box, RigidBodyHandle body) {
        this.level = level;
        this.box = box;
        this.body = body;
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

    static SableCarBody create(ServerLevel level, CarEntity car) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        // The entity's origin is the bottom centre of its box; the body's origin is its centre.
        Vector3d centre = new Vector3d(car.getX(), car.getY() + HALF_EXTENTS.y, car.getZ());
        Pose3d pose = new Pose3d(centre, orientationOf(car.getYRot()), new Vector3d(), new Vector3d(1, 1, 1));
        BoxPhysicsObject box = new BoxPhysicsObject(pose, new Vector3d(HALF_EXTENTS), MASS_KG);
        container.physicsSystem().addObject(box);
        return new SableCarBody(level, box, RigidBodyHandle.of(level, box));
    }

    void remove() {
        SubLevelContainer.getContainer(level).physicsSystem().removeObject(box);
    }

    /** Apply this tick's wheel forces, then move the entity to where the body is. */
    void tick(CarEntity car, double throttle, double steer, boolean handbrake, double dt) {
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Vector3d position = pose.position();
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Quaterniond inverse = new Quaterniond(orientation).invert();
        Vector3d linear = body.getLinearVelocity(new Vector3d());
        Vector3d angular = body.getAngularVelocity(new Vector3d());
        Vector3d down = orientation.transform(new Vector3d(0, -1, 0));

        boolean touching = false;
        for (double[] mount : MOUNTS) {
            Vector3d local = new Vector3d(mount[0], mount[1], mount[2]);
            Vector3d offset = orientation.transform(new Vector3d(local));
            Vector3d from = new Vector3d(position).add(offset);
            Vector3d to = new Vector3d(from).fma(WheelMath.REST_LENGTH + 0.25, down);
            BlockHitResult hit = level.clip(new ClipContext(new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, car));
            if (hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            double distance = hit.getLocation().distanceTo(new Vec3(from.x, from.y, from.z));
            double compression = WheelMath.REST_LENGTH - distance;
            // Velocity of the mount point: linear + angular x offset. Positive along "down" = squeezing.
            Vector3d pointVelocity = new Vector3d(angular).cross(offset).add(linear);
            double rate = pointVelocity.dot(down);
            double force = WheelMath.suspensionForce(compression, rate);
            if (force <= 0) {
                continue;
            }
            touching = true;
            Vector3d impulseWorld = new Vector3d(down).mul(-force * dt);
            Vector3d impulseLocal = inverse.transform(impulseWorld);
            Vector3d contactLocal = new Vector3d(local.x, local.y - distance, local.z);
            body.applyImpulseAtPoint(contactLocal, impulseLocal);
        }
        if (touching || throttle != 0 || steer != 0 || handbrake) {
            box.wakeUp();
        }
    }

    /** Move the entity to the body's current pose (position and yaw; pitch and roll come with the sync work). */
    void syncEntity(CarEntity car) {
        box.updatePose();
        Pose3d pose = box.getPose() instanceof Pose3d p ? p : new Pose3d(box.getPose());
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Vector3d p = pose.position();
        car.setPos(p.x, p.y - HALF_EXTENTS.y, p.z);
        car.setYRot(yawOf(orientation));
        car.yRotO = car.getYRot();
        Vector3d v = body.getLinearVelocity(new Vector3d());
        car.setDeltaMovement(v.x / 20.0, v.y / 20.0, v.z / 20.0);
    }

    /** Debug summary: height of the body centre above the ground below it, pitch and roll in degrees. */
    String describe() {
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Quaterniond q = new Quaterniond(pose.orientation());
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        Vector3d fwd = q.transform(new Vector3d(0, 0, 1));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd.y))));
        double roll = Math.toDegrees(Math.atan2(q.transform(new Vector3d(1, 0, 0)).y, up.y));
        Vector3d v = body.getLinearVelocity(new Vector3d());
        return String.format("body y=%.3f pitch=%.2f roll=%.2f speed=%.2f", pose.position().y, pitch, roll, v.length());
    }
}
