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
    private double lastSpinSlip;
    // One real spin rate per wheel (rad/s), persisted tick to tick and advanced sub-step to sub-step by
    // WheelMath.spinRate - state, not a value re-derived from scratch each call. See spinRate's own
    // javadoc for why a gripping wheel is pulled to ground speed rather than carrying forward a stale slip.
    // Scope note (MINECRAFT-118): lastSpinSlip currently feeds only the debug describe() string below,
    // deliberately - the OFFICIAL reported slip (slipThisTick/lastSlipSpeed/wearSlip/SlipReporter) must
    // keep coming from WheelSubStepper.subStepSlipSpeed alone (see that call site's own comment); folding
    // this state into it would re-entangle the tick-dt-evaluated reporting contract with a value that is
    // itself integrated sub-step by sub-step, which is exactly what that contract exists to avoid.
    private final double[] wheelSpin;
    // Per-wheel values published to clients for rendering (MINECRAFT-122). Airborne/zero-load/raycast-miss
    // behaviour differs per value, each deliberate:
    //  - suspensionTravel is replaced wholesale every tick from pass 1's own compressions[] (the single
    //    raycast per wheel), never recomputed, just handed out (copied in, below) - a raycast-miss wheel
    //    publishes 0.0 (fully extended) because compressions is a fresh zeroed array every tick.
    //  - steerAngle is a pure function of front/steer/forwardSpeed/spec, with no dependence on ground
    //    contact, so it is computed and published for EVERY wheel every sub-step, above the force<=0
    //    guard: an airborne wheel's published angle tracks input exactly like a grounded one's.
    //  - wheelSpin is written below the force<=0 guard and freezes at its last value for an
    //    airborne/zero-load/raycast-miss wheel: deliberate, see that guard's own comment.
    private final double[] suspensionTravel;
    private final double[] steerAngle;

    private SableCarBody(VehicleSpec spec, ServerLevel level, BoxPhysicsObject box, RigidBodyHandle body) {
        this.spec = spec;
        this.halfExtents = new Vector3d(spec.halfX(), spec.halfY(), spec.halfZ());
        this.level = level;
        this.box = box;
        this.body = body;
        this.wheelSpin = new double[spec.mounts().length];
        this.suspensionTravel = new double[spec.mounts().length];
        this.steerAngle = new double[spec.mounts().length];
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
    void tick(CarEntity car, double throttle, double steer, boolean handbrake, CarPhysics.Gear gear, double dt) {
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Vector3d position = pose.position();
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Quaterniond inverse = new Quaterniond(orientation).invert();
        Vector3d linear = body.getLinearVelocity(new Vector3d());
        Vector3d angular = body.getAngularVelocity(new Vector3d());
        Vector3d down = orientation.transform(new Vector3d(0, -1, 0));

        Vector3d carForward = orientation.transform(new Vector3d(0, 0, 1));
        boolean touching = false;
        double slipThisTick = 0.0;
        double spinSlipThisTick = 0.0;
        java.util.List<Vector3d> hits = new java.util.ArrayList<>();

        // Pass 1 (once per tick, not per sub-step): one raycast per wheel, giving every wheel's distance
        // and compression up front - fixed for the whole tick, because the body's POSE does not move
        // between our own sub-steps (Sable integrates pose once per game tick, not per call we make here),
        // so re-casting the same ray from the same pose every sub-step would just repeat the same answer.
        // This keeps raycasts at exactly 1 per wheel per car per tick, sub-stepped or not.
        int wheelCount = spec.mounts().length;
        BlockHitResult[] wheelHits = new BlockHitResult[wheelCount];
        Vector3d[] wheelOffsets = new Vector3d[wheelCount];
        double[] distances = new double[wheelCount];
        double[] compressions = new double[wheelCount];
        int[] partners = new int[wheelCount];
        for (int wheel = 0; wheel < wheelCount; wheel++) {
            double[] mount = spec.mounts()[wheel];
            Vector3d offset = orientation.transform(new Vector3d(mount[0], mount[1], mount[2]));
            wheelOffsets[wheel] = offset;
            partners[wheel] = partnerOf(wheel);
            Vector3d from = new Vector3d(position).add(offset);
            Vector3d to = new Vector3d(from).fma(spec.restLength() + 0.25, down);
            BlockHitResult hit = level.clip(new ClipContext(new Vec3(from.x, from.y, from.z), new Vec3(to.x, to.y, to.z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, car));
            wheelHits[wheel] = hit;
            if (hit.getType() == HitResult.Type.MISS) {
                continue;
            }
            double distance = hit.getLocation().distanceTo(new Vec3(from.x, from.y, from.z));
            distances[wheel] = distance;
            compressions[wheel] = spec.restLength() - distance;
            hits.add(new Vector3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));
        }
        // Published to clients as-is: this IS the single per-wheel raycast result above, not a second
        // one. Copied into the final field (not reassigned to the local array itself) so a future write
        // to this tick's own `compressions` local cannot alias and mutate already-published state.
        System.arraycopy(compressions, 0, this.suspensionTravel, 0, wheelCount);

        // Sub-stepping: run the force step N times per tick instead of once, each at dt/N, so the tire's
        // relaxation term (demand = effectiveMass * v / dt * RELAXATION - dt is load-bearing there, not
        // just an output multiplier) and the suspension's damping see a shorter, steadier horizon instead
        // of one big impulse that can overshoot under hard cornering/braking. Total impulse per tick stays
        // consistent because every impulse below is force * subDt, summed N times.
        //
        // What is held for the tick vs. re-read per sub-step: pose (and so distance/compression/hit, and
        // the fixed "down"/"carForward" axes) is held, because Sable does not integrate it between our own
        // calls. Linear/angular VELOCITY is re-read from the body after each sub-step's impulses: unlike
        // pose, applyImpulseAtPoint changes the body's velocity state immediately, and re-reading it is
        // exactly what lets each sub-step's tire/suspension force respond to the impulse the previous
        // sub-step just applied - without that, running N sub-steps from a frozen snapshot would apply the
        // same force N times at dt/N each, summing to precisely the single-step result, i.e. no benefit at
        // all. So suspensionForce (which depends on compression RATE, itself velocity-derived) is
        // recomputed every sub-step even though compression itself is not.
        //
        // That applyImpulseAtPoint changes what getLinearVelocity/getAngularVelocity return immediately
        // (rather than being accumulated into a force buffer the next physics step consumes and clears) is
        // PROVEN, not reasoned from the API's naming: the epic's MINECRAFT-72 review on PR #30 inspected
        // the native code in the Sable 2.0.5 jar this build already downloads (its sha512 checked against
        // gradle.properties first) and confirmed applyForce's read-modify-write and getLinearVelocity's
        // read hit the exact same body offsets, and that the separate, obviously-immediate
        // addLinearAngularVelocities entry point writes those same offsets too. Verify it yourself against
        // that artifact rather than trusting transcribed offsets, including this comment's.
        //
        // Sub-stepping is not merely a stability knob: pursuing the tire's relaxation target N times
        // instead of once genuinely changes the force delivered, because the friction circle can now
        // saturate where a single big step would not have (the epic's mild-cornering measurement: lateral
        // impulse per tick goes from -144 N*s at N=1 to -165 N*s at N>=2). MINECRAFT-75 (tuning) inherits
        // this grip change. Nor is wheelSubSteps=1 a behaviour-identical "off": air drag below still reads
        // subLinear, the velocity AFTER that one sub-step's impulses, where pre-sub-stepping it read the
        // tick-start velocity before any wheel force was applied.
        //
        // Reported slip (slipThisTick below, which feeds lastSlipSpeed/wearSlip/SlipReporter) is a
        // SEPARATE question from the force above and is handled by WheelSubStepper.subStepSlipSpeed, not
        // by this sub-step's own WheelMath.tire(..., subDt) call: that call's own slipSpeed carries subDt,
        // which makes the reported number scale with N two different, opposite ways depending on what is
        // saturating the tire (see subStepSlipSpeed's own comment for the full account) - wrong either way
        // for a gate (SlipReporter/wearSlip, both "> 0.3") that is supposed to mean the same thing
        // regardless of wheelSubSteps. subStepSlipSpeed fixes this by evaluating the identical, untouched
        // tire() formula at the TICK's dt instead, at the velocity as it stands this sub-step - exactly
        // what a single un-sub-stepped tick would have reported from that velocity, so N=1 is unaffected
        // and every other N reads off the same scale.
        int subSteps = Math.max(1, Math.min(4, CarConfig.WHEEL_SUB_STEPS.get()));
        double subDt = dt / subSteps;
        double barRate = spec.springRate() * CarConfig.ANTI_ROLL.get() * spec.antiRollScale();
        Vector3d subLinear = new Vector3d(linear);
        Vector3d subAngular = new Vector3d(angular);
        double[] wearSlipMax = new double[wheelCount];
        double[] wearSlipForce = new double[wheelCount];

        for (int sub = 0; sub < subSteps; sub++) {
            double forwardSpeed = subLinear.dot(carForward);

            // Pass 1 of this sub-step: every wheel's suspension force from the CURRENT velocity snapshot,
            // computed for every wheel before any wheel's pass 2 runs below - so both wheels of an axle
            // read the same sub-step snapshot, which is what keeps WheelSubStepper.afterAntiRoll's transfer
            // exactly antisymmetric (see WheelSubStepperTest, which drives this same method).
            double[] subSuspension = new double[wheelCount];
            for (int wheel = 0; wheel < wheelCount; wheel++) {
                if (wheelHits[wheel].getType() == HitResult.Type.MISS) {
                    continue;
                }
                Vector3d pointVelocity = new Vector3d(subAngular).cross(wheelOffsets[wheel]).add(subLinear);
                double rate = pointVelocity.dot(down);
                subSuspension[wheel] = WheelMath.suspensionForce(compressions[wheel], rate, spec.springRate(), spec.dampingRate(), spec.maxSpringForce());
            }
            double[] forces = WheelSubStepper.afterAntiRoll(subSuspension, compressions, partners, barRate);

            // Pass 2 of this sub-step: suspension and tire forces from this sub-step's forces[].
            for (int wheel = 0; wheel < wheelCount; wheel++) {
                // Steer angle is a pure function of front/steer/forwardSpeed/spec - none of which depend
                // on the raycast or the force guard below - so it is published for EVERY wheel, every
                // sub-step, ahead of both guards. See the steerAngle field's own comment for the contract.
                double[] mount = spec.mounts()[wheel];
                boolean front = mount[2] > 0;
                double wheelSteerAngle = front ? steer * WheelMath.maxSteerAngle(forwardSpeed, WheelMath.BASE_FRICTION,
                        spec.wheelbase(), CarPhysics.MAX_STEER) : 0.0;
                this.steerAngle[wheel] = wheelSteerAngle;

                BlockHitResult hit = wheelHits[wheel];
                if (hit.getType() == HitResult.Type.MISS) {
                    continue;
                }
                double force = forces[wheel];
                // A wheel whose anti-roll transfer leaves it at exactly 0 (or, if its partner's transfer
                // pushed it slightly past spec.maxSpringForce(), the skip test below does not catch that -
                // see the maxForce note on antiRollTransfer()) is skipped here. For impulses and reported
                // slip that is still behaviour-equivalent to letting it through: WheelMath.tire() itself
                // returns Tire(0, 0, 0, 0) whenever the force passed in is not positive, so a wheel this
                // guard let past with force <= 0 would contribute no drive/brake/lateral impulse and no
                // slip either way. It is NOT equivalent for wheelSpin[wheel], which is written below this
                // guard: skipping here deliberately freezes wheelSpin[wheel] at its last value, while
                // letting the wheel through would snap it to groundSpeed/wheelRadius via spinRate(). That
                // freeze is intentional - an airborne / zero-load / raycast-miss wheel keeps rotating with
                // no driveline torque applied to it, so it should neither snap to ground speed nor free-spin.
                // Skipping it early also avoids the raycast-adjacent bookkeeping (touching, wakeUp) for a
                // wheel that could not have mattered to this sub-step's impulses.
                // (maxSpringForce overshoot across sub-steps: each sub-step independently recomputes forces[]
                // from the live velocity, so a receiver that overshoots to ~2x maxSpringForce in one sub-step
                // is not compounding on top of a PRIOR sub-step's overshoot - it is the same already-accepted
                // per-computation overshoot, just evaluated more often at proportionally smaller dt, so the
                // extra impulse it contributes over the whole tick is the same order as the un-sub-stepped
                // case, not growing with N.)
                if (force <= 0) {
                    continue;
                }
                touching = true;
                Vector3d local = new Vector3d(mount[0], mount[1], mount[2]);
                double distance = distances[wheel];
                Vector3d pointVelocity = new Vector3d(subAngular).cross(wheelOffsets[wheel]).add(subLinear);
                Vector3d up = new Vector3d(down).negate();
                Vector3d impulseWorld = new Vector3d(up).mul(force * subDt);

                // Tire: forward and lateral axes in the plane the tire rolls on, steered on the front axle.
                Vector3d forward = orientation.transform(new Vector3d(0, 0, 1));
                forward.rotateAxis(wheelSteerAngle, up.x, up.y, up.z);
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
                    } else if (gear == CarPhysics.Gear.REVERSE) {
                        drive = throttle * REVERSE_FORCE * spec.forceScale() * Math.max(0.0, 1.0 - Math.abs(forwardSpeed) / 8.0);
                    }
                    // gear is NEUTRAL or a forward gear and the car is near a standstill: holding brake must
                    // never reverse it (MINECRAFT-228 hard acceptance criterion) -- only an explicit REVERSE
                    // gear lets throttle < 0 drive the car backward.
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
                // Downforce (MINECRAFT-184): spec.downforceGripMultiplier() is exactly 1.0 for every vehicle
                // whose downforceGripPerSpeed is 0.0 (every existing vehicle), so this multiply is a no-op
                // for them - see VehicleSpec.INDY's own javadoc for the one vehicle that sets it nonzero.
                double gripMu = WheelMath.BASE_FRICTION * Math.min(1.0, surface.grip() * spec.looseGrip())
                        * spec.downforceGripMultiplier(forwardSpeed);
                double rollingCoefficient = surface.rollingResistance() * spec.rollingScale();
                double effectiveMass = spec.massKg() / 4.0;
                // isIdentity() is checked on the SPEC's own hard-wired tireTuning(), never on config, so
                // CAR/TRUCK/TROPHY (always IDENTITY in code) can never be routed into DriftTireModel no
                // matter what an operator sets in CarConfig's drift tire tuning. Only once a vehicle has
                // already opted in does the actual tuning VALUES come from config (CarConfig.driftTireTuning(),
                // live-reloadable) rather than the spec's own numbers, which exist only to mark the opt-in.
                boolean driftTuned = !spec.tireTuning().isIdentity();
                WheelMath.Tire tire = !driftTuned
                        ? WheelMath.tire(vLong, vLat, force, gripMu, rollingCoefficient, lateralScale,
                                drive, brake, brakeGain, effectiveMass, subDt)
                        : DriftTireModel.tire(vLong, vLat, force, gripMu, rollingCoefficient, lateralScale,
                                drive, brake, brakeGain, effectiveMass, subDt, CarConfig.driftTireTuning(),
                                front, handbrake, steer);
                impulseWorld.fma(tire.longitudinal() * subDt, forward).fma(tire.lateral() * subDt, lateral);
                // Per-wheel spin state (MINECRAFT-73/MINECRAFT-118): advanced from how much of THIS SAME
                // tire() call's own commandLongitudinal (its internal wantLong, unscaled) the friction
                // circle could not deliver as longitudinal (that same wantLong, scaled) - both already
                // computed above, no extra WheelMath.tire call. Using tire()'s own commandLongitudinal
                // rather than reconstructing a command externally from drive/brake is deliberate: an
                // external reconstruction missing rolling resistance or the brake's relaxation target would
                // make excessForce nonzero even while gripping, so a gripping wheel would never stop
                // "spinning" rather than snapping to ground speed - see WheelMath.spinRate's javadoc.
                double excessForce = tire.commandLongitudinal() - tire.longitudinal();
                wheelSpin[wheel] = WheelMath.spinRate(wheelSpin[wheel], spec.wheelRadius(), vLong, excessForce,
                        WheelMath.WHEEL_INERTIA, subDt);
                double spinSlip = WheelMath.slipFromSpin(wheelSpin[wheel], spec.wheelRadius(), vLong);
                spinSlipThisTick = Math.max(spinSlipThisTick, Math.abs(spinSlip));
                // Reported slip (feeds slipThisTick/lastSlipSpeed/wearSlip/SlipReporter) is evaluated at the
                // TICK's own dt, not subDt, so it reads the same regardless of wheelSubSteps - see
                // WheelSubStepper.subStepSlipSpeed's own comment for why. The force above, which this does
                // NOT touch, still comes from the subDt call: delivered impulse is unaffected.
                double reportedSlip = WheelSubStepper.subStepSlipSpeed(vLong, vLat, force, gripMu, rollingCoefficient,
                        lateralScale, drive, brake, brakeGain, effectiveMass, dt);
                slipThisTick = Math.max(slipThisTick, reportedSlip);
                // SlipReporter must fire at most once per wheel per tick (not once per sub-step), so we only
                // track the worst wearSlip seen across this tick's sub-steps here, paired with the force at
                // the sub-step that produced it (so the reported load matches the reported slip), and report
                // after the sub-step loop below.
                double wearSlip = CarConfig.WEAR_ENABLED.get()
                        ? CarEffectsMath.wearSlip(throttle, forwardSpeed, reportedSlip, CarConfig.WEAR_STRENGTH.get())
                        : reportedSlip;
                if (wearSlip > wearSlipMax[wheel]) {
                    wearSlipMax[wheel] = wearSlip;
                    wearSlipForce[wheel] = force;
                }

                Vector3d impulseLocal = inverse.transform(impulseWorld);
                // Suspension acts at the contact point. The tire's horizontal force does too (tireForceAtContact),
                // so braking and cornering transfer weight; the anti-roll bars hold the car up. With the option off it
                // is applied at body height as before.
                Vector3d contactLocal = new Vector3d(local.x, local.y - distance, local.z);
                Vector3d suspensionLocal = inverse.transform(new Vector3d(up).mul(force * subDt));
                body.applyImpulseAtPoint(contactLocal, suspensionLocal);
                Vector3d horizontalLocal = new Vector3d(impulseLocal).sub(suspensionLocal);
                double tireY = CarConfig.TIRE_FORCE_AT_CONTACT.get() ? contactLocal.y : -0.1;
                body.applyImpulseAtPoint(new Vector3d(local.x, tireY, local.z), horizontalLocal);
            }
            // Re-read velocity so the next sub-step's forces respond to the impulses just applied above -
            // see the note before this loop on why this, and not pose, is what makes sub-stepping matter.
            subLinear = body.getLinearVelocity(new Vector3d());
            subAngular = body.getAngularVelocity(new Vector3d());
        }
        for (int wheel = 0; wheel < wheelCount; wheel++) {
            if (wearSlipMax[wheel] > 0.3) {
                SlipReporter.report(level, wheelHits[wheel].getBlockPos(), wearSlipMax[wheel], wearSlipForce[wheel] / GRAVITY);
            }
        }
        // Air drag along the velocity: once per tick in total, at the full dt, not once per sub-step -
        // using the velocity as it stands after every sub-step's impulses.
        double speed = subLinear.length();
        if (speed > 0.5) {
            Vector3d drag = inverse.transform(new Vector3d(subLinear).mul(-AIR_DRAG * spec.forceScale() * speed * dt));
            body.applyImpulseAtPoint(new Vector3d(0, 0, 0), drag);
        }
        lastSlipSpeed = slipThisTick;
        lastSpinSlip = spinSlipThisTick;
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

    /**
     * The mount on the same axle on the other side, or -1. Requires mounts[i][0] and mounts[wheel][0] to
     * have opposite signs, so a mount sitting exactly on the centreline (x == 0) silently gets no partner
     * and so no anti-roll bar at all - fine for every vehicle here (none has one), a trap if a future
     * vehicle adds a centreline wheel.
     */
    private int partnerOf(int wheel) {
        double[][] mounts = spec.mounts();
        for (int i = 0; i < mounts.length; i++) {
            if (i != wheel && Math.abs(mounts[i][2] - mounts[wheel][2]) < 1e-6 && mounts[i][0] * mounts[wheel][0] < 0) {
                return i;
            }
        }
        return -1;
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

    /** Each wheel's suspension travel as of the last tick's single raycast, metres (a copy; not live state). */
    double[] suspensionTravel() {
        return suspensionTravel.clone();
    }

    /** Each wheel's steer angle as of the last tick it was grounded, radians (a copy; not live state). */
    double[] steerAngles() {
        return steerAngle.clone();
    }

    /** Each wheel's own spin rate as of the last tick it was grounded, rad/s (a copy; not live state). */
    double[] wheelSpinRates() {
        return wheelSpin.clone();
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
        return String.format("body y=%.3f pitch=%.2f roll=%.2f speed=%.2f spinSlip=%.2f",
                pose.position().y, pitch, roll, v.length(), lastSpinSlip);
    }
}
