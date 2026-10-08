package io.github.brooswitminecraft.dynamicvehicles;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.dynamicterrain.SurfaceProperties;
import io.github.brooswitminecraft.dynamicterrain.Surfaces;
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
    private static final double GRAVITY = 9.81;
    private static final double DRIVE_FORCE = 2_300.0; // per wheel, all-wheel drive: about 0.77 g for the car
    private static final double REVERSE_FORCE = 1_400.0;
    private static final double BRAKE_FORCE = 3_000.0;
    private static final double HANDBRAKE_FORCE = 6_000.0;
    private static final double AIR_DRAG = 0.4; // N per (m/s)^2

    private final VehicleSpec spec;
    private final Vector3d halfExtents;
    private final ServerLevel level;
    private final BoxPhysicsObject box;
    private final RigidBodyHandle body;
    private double lastSlipSpeed;

    private SableCarBody(VehicleSpec spec, ServerLevel level, BoxPhysicsObject box, RigidBodyHandle body) {
        this.spec = spec;
        this.halfExtents = new Vector3d(spec.halfX(), spec.halfY(), spec.halfZ());
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
        VehicleSpec spec = car.spec();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        // The entity's origin is the bottom centre of its box; the body's origin is its centre.
        Vector3d centre = new Vector3d(car.getX(), car.getY() + spec.halfY(), car.getZ());
        org.joml.Quaternionf saved = car.savedOrientation();
        Quaterniond orientation = saved != null ? new Quaterniond(saved.x, saved.y, saved.z, saved.w) : orientationOf(car.getYRot());
        Pose3d pose = new Pose3d(centre, orientation, new Vector3d(), new Vector3d(1, 1, 1));
        BoxPhysicsObject box = new BoxPhysicsObject(pose, new Vector3d(spec.halfX(), spec.halfY(), spec.halfZ()), spec.massKg());
        container.physicsSystem().addObject(box);
        return new SableCarBody(spec, level, box, RigidBodyHandle.of(level, box));
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

        Vector3d carForward = orientation.transform(new Vector3d(0, 0, 1));
        double forwardSpeed = linear.dot(carForward);
        boolean touching = false;
        double slipThisTick = 0.0;
        java.util.List<Vector3d> hits = new java.util.ArrayList<>();
        double[] compressions = wheelCompressions(position, orientation, down, car);
        for (int wheel = 0; wheel < spec.mounts().length; wheel++) {
            double[] mount = spec.mounts()[wheel];
            Vector3d local = new Vector3d(mount[0], mount[1], mount[2]);
            Vector3d offset = orientation.transform(new Vector3d(local));
            Vector3d from = new Vector3d(position).add(offset);
            Vector3d to = new Vector3d(from).fma(spec.restLength() + 0.25, down);
            BlockHitResult hit = level.clip(new ClipContext(new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, car));
            if (hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            hits.add(new Vector3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));
            double distance = hit.getLocation().distanceTo(new Vec3(from.x, from.y, from.z));
            double compression = spec.restLength() - distance;
            // Velocity of the mount point: linear + angular x offset. Positive along "down" = squeezing.
            Vector3d pointVelocity = new Vector3d(angular).cross(offset).add(linear);
            double rate = pointVelocity.dot(down);
            double force = WheelMath.suspensionForce(compression, rate, spec.springRate(), spec.dampingRate(), spec.maxSpringForce());
            int partner = partnerOf(wheel);
            if (partner >= 0) {
                force = WheelMath.loadedForce(force, compression, compressions[partner],
                        spec.springRate() * CarConfig.ANTI_ROLL.get(), spec.maxSpringForce());
            }
            if (force <= 0) {
                continue;
            }
            touching = true;
            Vector3d up = new Vector3d(down).negate();
            Vector3d impulseWorld = new Vector3d(up).mul(force * dt);

            // Tire: forward and lateral axes in the plane the tire rolls on, steered on the front axle.
            boolean front = mount[2] > 0;
            Vector3d forward = orientation.transform(new Vector3d(0, 0, 1));
            double steerAngle = front ? steer * WheelMath.maxSteerAngle(forwardSpeed, WheelMath.BASE_FRICTION,
                    spec.wheelbase(), CarPhysics.MAX_STEER) : 0.0;
            forward.rotateAxis(steerAngle, up.x, up.y, up.z);
            forward.fma(-forward.dot(up), up).normalize();
            Vector3d lateral = new Vector3d(up).cross(forward).normalize();
            double vLong = pointVelocity.dot(forward);
            double vLat = pointVelocity.dot(lateral);

            double drive = 0.0;
            double brake = 0.0;
            double lateralScale = 1.0;
            if (throttle > 0) {
                drive = throttle * DRIVE_FORCE * spec.forceScale() * Math.max(0.0, 1.0 - Math.abs(forwardSpeed) / spec.maxSpeed());
            } else if (throttle < 0) {
                if (forwardSpeed > 0.5) {
                    brake = -throttle * BRAKE_FORCE * spec.forceScale();
                } else {
                    drive = throttle * REVERSE_FORCE * spec.forceScale() * Math.max(0.0, 1.0 - Math.abs(forwardSpeed) / 8.0);
                }
            }
            double brakeGain = 1.0;
            if (throttle == 0 && Math.abs(forwardSpeed) < 1.5) {
                brake = BRAKE_FORCE * spec.forceScale(); // parked: hold on a slope instead of rolling away
                brakeGain = 6.0;
            }
            if (handbrake && !front) {
                brake = HANDBRAKE_FORCE * spec.forceScale();
                drive = 0.0;
                lateralScale = 0.35;
            }
            // What the tire feels comes from the block it is on (grip, rolling resistance), via Dynamic Terrain.
            SurfaceProperties surface = Surfaces.at(level, hit.getBlockPos());
            WheelMath.Tire tire = WheelMath.tire(vLong, vLat, force, WheelMath.BASE_FRICTION * Math.min(1.0, surface.grip() * spec.looseGrip()),
                    surface.rollingResistance() * spec.rollingScale(), lateralScale, drive, brake, brakeGain, spec.massKg() / 4.0, dt);
            impulseWorld.fma(tire.longitudinal() * dt, forward).fma(tire.lateral() * dt, lateral);
            slipThisTick = Math.max(slipThisTick, tire.slipSpeed());
            double wearSlip = CarConfig.WEAR_ENABLED.get()
                    ? CarEffectsMath.wearSlip(throttle, forwardSpeed, tire.slipSpeed(), CarConfig.WEAR_STRENGTH.get())
                    : tire.slipSpeed();
            if (wearSlip > 0.3) {
                SlipReporter.report(level, hit.getBlockPos(), wearSlip, force / GRAVITY);
            }

            Vector3d impulseLocal = inverse.transform(impulseWorld);
            // Suspension acts at the contact point. The tire's horizontal force does too (tireForceAtContact),
            // so braking and cornering transfer weight; the anti-roll bars hold the car up. With the option off it
            // is applied at body height as before.
            Vector3d contactLocal = new Vector3d(local.x, local.y - distance, local.z);
            Vector3d suspensionLocal = inverse.transform(new Vector3d(up).mul(force * dt));
            body.applyImpulseAtPoint(contactLocal, suspensionLocal);
            Vector3d horizontalLocal = new Vector3d(impulseLocal).sub(suspensionLocal);
            double tireY = CarConfig.TIRE_FORCE_AT_CONTACT.get() ? contactLocal.y : -0.1;
            body.applyImpulseAtPoint(new Vector3d(local.x, tireY, local.z), horizontalLocal);
        }
        // Air drag along the velocity.
        double speed = linear.length();
        if (speed > 0.5) {
            Vector3d drag = inverse.transform(new Vector3d(linear).mul(-AIR_DRAG * spec.forceScale() * speed * dt));
            body.applyImpulseAtPoint(new Vector3d(0, 0, 0), drag);
        }
        lastSlipSpeed = slipThisTick;
        if (CarDebug.enabled) {
            drawDebug(position, orientation, hits);
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
        car.setPos(p.x, p.y - spec.halfY(), p.z);
        car.setYRot(yawOf(orientation));
        car.yRotO = car.getYRot();
        car.publishOrientation(new org.joml.Quaternionf((float) orientation.x, (float) orientation.y, (float) orientation.z, (float) orientation.w));
        Vector3d v = body.getLinearVelocity(new Vector3d());
        car.setDeltaMovement(v.x / 20.0, v.y / 20.0, v.z / 20.0);
    }

    /** The mount on the same axle on the other side, or -1. */
    private int partnerOf(int wheel) {
        double[][] mounts = spec.mounts();
        for (int i = 0; i < mounts.length; i++) {
            if (i != wheel && Math.abs(mounts[i][2] - mounts[wheel][2]) < 1e-6 && mounts[i][0] * mounts[wheel][0] < 0) {
                return i;
            }
        }
        return -1;
    }

    /** Spring compression at each mount this tick (0 when the ray misses), for the anti-roll bars. */
    private double[] wheelCompressions(Vector3d position, Quaterniond orientation, Vector3d down, CarEntity car) {
        double[][] mounts = spec.mounts();
        double[] result = new double[mounts.length];
        for (int i = 0; i < mounts.length; i++) {
            Vector3d from = new Vector3d(position).add(orientation.transform(new Vector3d(mounts[i][0], mounts[i][1], mounts[i][2])));
            Vector3d to = new Vector3d(from).fma(spec.restLength() + 0.25, down);
            BlockHitResult hit = level.clip(new ClipContext(new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, car));
            if (hit.getType() != HitResult.Type.MISS) {
                result[i] = Math.max(0.0, spec.restLength() - hit.getLocation().distanceTo(new Vec3(from.x, from.y, from.z)));
            }
        }
        return result;
    }

    /** Debug overlay: the body box corners (red) and where each suspension ray meets the ground (green). */
    private void drawDebug(Vector3d position, Quaterniond orientation, java.util.List<Vector3d> hits) {
        net.minecraft.core.particles.DustParticleOptions red = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1f, 0.1f, 0.1f), 1.2f);
        net.minecraft.core.particles.DustParticleOptions green = new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.1f, 1f, 0.1f), 1.2f);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    Vector3d corner = orientation.transform(new Vector3d(sx * spec.halfX(), sy * spec.halfY(), sz * spec.halfZ())).add(position);
                    level.sendParticles(red, corner.x, corner.y, corner.z, 1, 0, 0, 0, 0);
                }
            }
        }
        for (Vector3d hit : hits) {
            level.sendParticles(green, hit.x, hit.y, hit.z, 1, 0, 0, 0, 0);
        }
    }

    /** The largest tire slip speed seen on the last tick, m/s. */
    double lastSlipSpeed() {
        return lastSlipSpeed;
    }

    /** The body's speed, m/s. */
    double speed() {
        return body.getLinearVelocity(new Vector3d()).length();
    }

    /** The body's current orientation, for saving. */
    org.joml.Quaternionf orientationF() {
        box.updatePose();
        org.joml.Quaterniondc q = box.getPose().orientation();
        return new org.joml.Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
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
