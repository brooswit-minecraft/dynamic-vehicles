package io.github.brooswitminecraft.dynamicvehicles;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.dynamicterrain.Erosion;
import io.github.brooswitminecraft.dynamicterrain.SurfaceProperties;
import io.github.brooswitminecraft.dynamicterrain.Surfaces;
import io.github.brooswitminecraft.dynamicterrain.TireContactSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
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
    /** Total speed (m/s) at or below which NO aerodynamic/fluid/foliage drag impulse is applied at all
     * (MINECRAFT-446 review, comment 49064: named and documented rather than left as a bare literal, since
     * it is part of the per-car drag contract's own speed dependence). Pre-existing behaviour, unchanged by
     * this ticket; {@code docs/per-car-config.md}'s `wing` section documents it, and {@code
     * WingAeroGameTests} pins that the documented value still matches this one. Note the wing DOWNFORCE
     * path below has no such dead band: it applies at any nonzero airflow speed. */
    static final double DRAG_SPEED_DEADBAND = 0.5;
    // MINECRAFT-446: the old hardcoded "AIR_DRAG = 0.4 N per (m/s)^2" constant moved to
    // VehicleSpec#wingDragCoeff (defaulted to 0.4 for the whole roster), so it is per-car configurable
    // through the wing section of a per-car config file.
    /** The body-frame height a horizontal tire force acts at when it is NOT applied at the contact patch:
     * the pre-existing literal behind {@code CarConfig.TIRE_FORCE_AT_CONTACT}'s off branch, named so
     * MINECRAFT-445's anti-dive/anti-squat blend has something meaningful to blend TOWARD. */
    private static final double BODY_TIRE_FORCE_HEIGHT = -0.1;

    private final VehicleSpec spec;
    private final Vector3d halfExtents;
    private final ServerLevel level;
    private final BoxPhysicsObject box;
    private final RigidBodyHandle body;
    private double lastSlipSpeed;
    private double lastSpinSlip;
    // MINECRAFT-401 review, round 4: the REAL arguments the production path actually passed to
    // SlipReporter.report() this tick, and the REAL Erosion.Result it actually returned -- captured at
    // the one real call site below, not recomputed by a test from a peak value and an assumed load.
    // DISABLED/SKIPPED/RESISTED whenever no wheel's wearSlipMax crossed the report threshold this tick
    // (the loop below never calls report() at all, so these simply hold their last real value, same as
    // lastSlipSpeed above).
    private double lastErosionReportSlipSpeed;
    private double lastErosionReportLoadKg;
    private Erosion.Result lastErosionResult = Erosion.Result.SKIPPED;
    // One real spin rate per wheel (rad/s), persisted tick to tick and advanced sub-step to sub-step by
    // WheelMath.spinRate - state, not a value re-derived from scratch each call. See spinRate's own
    // javadoc for why a gripping wheel is pulled to ground speed rather than carrying forward a stale slip.
    // Scope note (MINECRAFT-118): lastSpinSlip currently feeds only the debug describe() string below,
    // deliberately - the OFFICIAL reported slip (slipThisTick/lastSlipSpeed/wearSlip/SlipReporter) must
    // keep coming from WheelSubStepper.subStepSlipSpeed alone (see that call site's own comment); folding
    // this state into it would re-entangle the tick-dt-evaluated reporting contract with a value that is
    // itself integrated sub-step by sub-step, which is exactly what that contract exists to avoid.
    private final double[] wheelSpin;
    // Drivetrain state (MINECRAFT-401), persisted tick to tick like wheelSpin above. automaticGearIndex is
    // only ever advanced while gear == AUTOMATIC (see tick()'s own call to DrivetrainMath.automaticGearIndex);
    // it otherwise holds whatever it last was, so re-selecting AUTOMATIC resumes from a sensible gear
    // instead of always restarting at D1. engineRpm is read by SableCompat for the client-facing sound/debug
    // telemetry the ticket asks for; both are derived once per tick, not per sub-step, from the SAME
    // wheelSpin snapshot the sub-step loop below starts from -- one tick's lag behind the spin the loop
    // itself produces, the same lag every other "fixed for the whole tick" value in this method already has.
    private int automaticGearIndex;
    private double engineRpm = DrivetrainMath.IDLE_RPM;
    private double gearRatio = DrivetrainMath.ratioFor(DrivetrainMath.GEAR_RATIOS.length - 1);
    // Diagnostic only (MINECRAFT-401 review, round 5 ledge-test investigation): whether ANY wheel's
    // suspension force was positive (the exact same `touching` condition that gates box.wakeUp() below)
    // as of the end of the last tick -- lets a caller distinguish "genuinely airborne" from "still grounded
    // but not yet falling fast" when a fall isn't showing up in velocity.
    private boolean lastTouching;
    // Per-wheel telemetry for /dtcar debug (MINECRAFT-401 review: the ticket's own acceptance criteria
    // calls these out by name -- contact speed, slip, applied force/torque -- not just gear/RPM). Written
    // alongside wheelSpin, below the same force<=0 guard, so they freeze with it for an
    // airborne/zero-load/raycast-miss wheel rather than reading as live for a wheel nothing is touching.
    private final double[] wheelContactSpeed;
    private final double[] wheelSlip;
    private final double[] wheelForce;
    // Review, round 8: isolates JUST the recovery reaction's own contribution to wheelForce (0 whenever
    // this sub-step did not recover, i.e. excessForce != 0), so a test can check the reaction's own
    // momentum/Newton's-third-law identity directly against this tick's actual production application,
    // not against a reproduction of the formula that computed it - a unit test reproducing the formula
    // cannot catch a bug where the formula is right but applying it (or applying it twice, or with the
    // wrong sign) at the call site is wrong, since the reproduction and the call site would then disagree
    // with the real world identically. Same "last sub-step wins" snapshot convention as wheelForce itself.
    private final double[] wheelReactionForce;
    // Review, round 9: the friction circle is longitudinal+lateral combined (hypot), not longitudinal
    // alone - a test checking only wheelForce against the circle's own ceiling could pass while the
    // COMBINED force silently exceeds it, if cornering lateral force moved into the budget this tick.
    private final double[] wheelLateralForce;
    // Review, round 10: this tick's own actual mu*normalForce circle (Tire#frictionLimit), so a test can
    // check the delivered force against the REAL per-wheel-per-tick budget instead of a fixed global
    // worst-case ceiling - tighter, and catches a smaller overflow the generous global bound would miss.
    private final double[] wheelFrictionLimit;
    // MINECRAFT-446 review (comment 49019): the REAL aero downforce this tick's production path actually
    // applied, captured at the one real application site below - not re-derived by a test from an assumed
    // speed. The review's own finding is why these exist: a test that infers the wing's contribution from
    // net suspension compression cannot separate it from the acceleration/settling baseline, and so passes
    // even if the wing load is absent or wrong by a factor of the sub-step count. These three make the
    // applied force itself observable instead:
    //  - lastAeroAirflowSpeed: the forward airflow speed (m/s, signed) the downforce formula was evaluated
    //    at, so a test can check the applied force against VehicleSpec.wingDownforceNewtons at the speed
    //    the car ACTUALLY reached rather than needing two runs to reach matching speeds.
    //  - lastAeroDownforceNewtons: total downward aero force (N, front + rear, including forceScale) this
    //    tick. Applied once per tick outside the sub-step loop, so this is sub-step-count invariant BY
    //    CONSTRUCTION - which is exactly the property under test, hence worth observing directly.
    //  - lastAeroApplications: how many aero impulses were handed to applyImpulseAtPoint this tick (one per
    //    axle that has a nonzero coefficient). The crispest "once per tick, not once per sub-step" signal
    //    there is: an application wrongly placed inside the sub-step loop multiplies this by wheelSubSteps.
    // All three hold their last real value on a tick that applied no aero at all (both coefficients 0, or
    // a standstill), same as lastSlipSpeed/lastErosionReport* above; a test must therefore establish a
    // nonzero lastAeroAirflowSpeed before reading the other two, which is also the review's own
    // "establish nonzero measured forward speed" ask.
    private double lastAeroAirflowSpeed;
    private double lastAeroDownforceNewtons;
    private int lastAeroApplications;
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
    // Previous tick's contact point per wheel (block, contacted face, and block-local 0..1 position),
    // null until that wheel has first touched ground. Lets contactPatch() integrate texture evidence
    // over the texels actually crossed between ticks instead of sampling a single frozen point. Reset to
    // null whenever a wheel is airborne or lands on a different block/face, so a gap in contact or a jump
    // across blocks is never read as a texel sweep between two unrelated points.
    private final BlockPos[] lastContactBlockPos;
    private final Direction[] lastContactFace;
    private final Vec3[] lastContactLocal;

    private SableCarBody(VehicleSpec spec, ServerLevel level, BoxPhysicsObject box, RigidBodyHandle body) {
        this.spec = spec;
        this.halfExtents = new Vector3d(spec.halfX(), spec.halfY(), spec.halfZ());
        this.level = level;
        this.box = box;
        this.body = body;
        this.wheelSpin = new double[spec.mounts().length];
        this.wheelContactSpeed = new double[spec.mounts().length];
        this.wheelSlip = new double[spec.mounts().length];
        this.wheelForce = new double[spec.mounts().length];
        this.wheelReactionForce = new double[spec.mounts().length];
        this.wheelLateralForce = new double[spec.mounts().length];
        this.wheelFrictionLimit = new double[spec.mounts().length];
        this.suspensionTravel = new double[spec.mounts().length];
        this.steerAngle = new double[spec.mounts().length];
        this.lastContactBlockPos = new BlockPos[spec.mounts().length];
        this.lastContactFace = new Direction[spec.mounts().length];
        this.lastContactLocal = new Vec3[spec.mounts().length];
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
        VehicleSpec.VehicleTuning tuning = car.vehicleTuning();
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Vector3d position = pose.position();
        Quaterniond orientation = new Quaterniond(pose.orientation());
        Quaterniond inverse = new Quaterniond(orientation).invert();
        Vector3d linear = body.getLinearVelocity(new Vector3d());
        Vector3d angular = body.getAngularVelocity(new Vector3d());
        Vector3d down = orientation.transform(new Vector3d(0, -1, 0));

        Vector3d carForward = orientation.transform(new Vector3d(0, 0, 1));

        // Drivetrain (MINECRAFT-401): resolve the gear ratio actually engaged this tick, once, from the
        // driven wheels' own spin as it stood at the end of the LAST tick (wheelSpin is only updated inside
        // the sub-step loop below) -- see the field's own comment for why that one-tick lag is acceptable.
        // All four wheels are driven (DRIVE_FORCE's own comment: "per wheel, all-wheel drive"), so the
        // average of their magnitudes is the drivetrain's own input speed; averaging rather than picking one
        // wheel keeps a single wheel's momentary wheelspin from single-handedly forcing an automatic shift.
        int wheelCountForDrivetrain = spec.mounts().length;
        double drivenWheelSpin = 0.0;
        for (int w = 0; w < wheelCountForDrivetrain; w++) {
            drivenWheelSpin += Math.abs(wheelSpin[w]);
        }
        drivenWheelSpin /= wheelCountForDrivetrain;
        // There is exactly one engine regardless of gear, so exactly one rawEngineRpm reading feeds the
        // limiter below -- computed from whichever ratio is actually engaged this tick (REVERSE's own
        // ratio, the resolved manual/automatic ratio, or NEUTRAL's decoupled free-rev, which bypasses the
        // engine's rotational inertia entirely since nothing couples it to a wheel to have inertia against).
        //
        // Review, round 5: this used to ALSO smooth this value through DrivetrainMath.smoothedRpm (round
        // 2's fix) before the limiter read it, on top of the round-5 fix below that already bounds how
        // fast wheelSpin itself (and so drivenWheelSpin, and so this rawTarget) can change per step. Doing
        // both compounded into enough extra lag to fail opposingDirectionThrottleDecelerateAndReverseInBothMirroredDirections
        // and starve carRecoversCleanlyAfterDrivingOffALedgeAndLanding of real acceleration within its own
        // window -- the SAME physical inertia counted twice. The wheel-level fix (DrivetrainMath.
        // boundedSpinTowardSnap, applied to wheelSpin[] itself below in the per-wheel loop) is the
        // physically-grounded one the round-5 review asked for; it already makes drivenWheelSpin change
        // gradually at the SOURCE, which is sufficient on its own to keep rawRpm/engineRpm/
        // limiterTorqueFactor gradual too (the gradual-recovery GameTest's own per-wheel assertions confirm
        // this end-to-end) -- so the separate engine-side smoothing step is now redundant and removed.
        double rawEngineRpm;
        if (gear == CarPhysics.Gear.NEUTRAL) {
            gearRatio = 0.0;
            rawEngineRpm = DrivetrainMath.freeRevRpm(throttle);
        } else {
            if (gear == CarPhysics.Gear.REVERSE) {
                gearRatio = DrivetrainMath.REVERSE_RATIO;
            } else if (gear == CarPhysics.Gear.AUTOMATIC) {
                automaticGearIndex = DrivetrainMath.automaticGearIndex(automaticGearIndex, drivenWheelSpin);
                gearRatio = DrivetrainMath.ratioFor(automaticGearIndex);
            } else {
                gearRatio = DrivetrainMath.ratioFor(gear.manualIndex());
            }
            rawEngineRpm = DrivetrainMath.rawRpm(drivenWheelSpin, gearRatio);
        }
        engineRpm = DrivetrainMath.clampRpm(rawEngineRpm);
        // Couples the rev limiter to delivered torque, not just to the displayed RPM number: forceMultiplier
        // scales the engaged ratio's own force, limiterTorqueFactor cuts it toward 0 as RPM nears the
        // limiter -- gradually, because drivenWheelSpin (and so rawEngineRpm) is itself now bounded by the
        // wheel-level fix below, not because this value is separately smoothed. Reverse's own fixed force
        // is cut by the exact same factor; harmless when not actually in REVERSE, since GearDrive.drive
        // only ever reads whichever of the two its own gear branch applies.
        double limiterTorqueFactor = DrivetrainMath.limiterTorqueFactor(rawEngineRpm);
        double driveForceMultiplier = DrivetrainMath.forceMultiplier(gearRatio) * limiterTorqueFactor;
        double effectiveReverseForce = REVERSE_FORCE * limiterTorqueFactor;

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
        // Per-texel texture refinement on top of the block-level grip/rollingResistance (TextureGripMath),
        // from the texels the contact patch actually crossed since last tick (Surfaces.contactPatch()).
        // Computed once per tick here, alongside the single raycast per wheel, not per sub-step: the
        // evidence does not change within a tick any more than the block-level SurfaceProperties does.
        // 1.0 (no effect) until/unless a same-block-and-face previous contact gives real evidence to sample.
        double[] textureGrip = new double[wheelCount];
        double[] textureRolling = new double[wheelCount];
        java.util.Arrays.fill(textureGrip, 1.0);
        java.util.Arrays.fill(textureRolling, 1.0);
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
                lastContactBlockPos[wheel] = null;
                lastContactFace[wheel] = null;
                lastContactLocal[wheel] = null;
                continue;
            }
            double distance = hit.getLocation().distanceTo(new Vec3(from.x, from.y, from.z));
            distances[wheel] = distance;
            compressions[wheel] = WheelMath.compression(spec.restLength(), distance);
            hits.add(new Vector3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z));

            BlockPos blockPos = hit.getBlockPos();
            Direction face = hit.getDirection();
            Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(blockPos));
            if (blockPos.equals(lastContactBlockPos[wheel]) && face == lastContactFace[wheel]) {
                Vec3 last = lastContactLocal[wheel];
                java.util.Optional<TireContactSampler.ContactPatch> patch = Surfaces.contactPatch(
                        level, blockPos, face, last.x, last.y, last.z, local.x, local.y, local.z);
                if (patch.isPresent()) {
                    textureGrip[wheel] = TextureGripMath.gripMultiplier(patch.get().average());
                    textureRolling[wheel] = TextureGripMath.rollingResistanceMultiplier(patch.get().average());
                }
            }
            lastContactBlockPos[wheel] = blockPos;
            lastContactFace[wheel] = face;
            lastContactLocal[wheel] = local;
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
        // Review, round 7: the wheel-spin-recovery reaction (below) is a real contact-patch sliding event
        // (the wheel surface differs from ground speed while it's being despun/respun), so it belongs in
        // the SAME slip/erosion accounting every other sliding event already feeds, not a parallel one.
        // Accumulated as an IMPULSE (summed across sub-steps, exactly like the chassis's own delivered
        // impulse) rather than read per-sub-step, for the same substep-count-invariance reason
        // WheelSubStepper.subStepSlipSpeed evaluates reportedSlip at the tick's own dt: a sum of impulses
        // is exact regardless of how many sub-steps it was split across, where a per-sub-step force/spin
        // read is not. Converted to an equivalent slip SPEED (impulse / effectiveMass, m/s - the same unit
        // tire().slipSpeed()/reportedSlip already use) once, after the sub-step loop, and folded into the
        // existing wearSlipMax/wearSlipForce bookkeeping below - the existing ">0.3"/once-per-tick/
        // SlipReporter guards apply to it exactly as they already do to reportedSlip, unmodified.
        double[] recoveryImpulseAccum = new double[wheelCount];
        double[] recoveryLoadForce = new double[wheelCount];

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
                VehicleSpec.SuspensionTuning suspTuning = spec.suspensionTuning();
                subSuspension[wheel] = WheelMath.suspensionForce(compressions[wheel], rate, spec.springRate(),
                        spec.dampingRate(), suspTuning.fastCompressionDamping(),
                        suspTuning.slowReboundDamping(), suspTuning.fastReboundDamping(), suspTuning.dampingKneeSpeed(),
                        spec.maxSpringForce(), suspTuning.bumpStopTravel(), suspTuning.bumpStopRate());
            }
            double[] forces = WheelSubStepper.afterAntiRoll(subSuspension, compressions, partners, barRate);
            double[] gripForces = new double[wheelCount];
            for (int wheel = 0; wheel < wheelCount; wheel++) {
                if (wheelHits[wheel].getType() == HitResult.Type.MISS || !(forces[wheel] > 0.0)) {
                    continue;
                }
                SurfaceProperties surface = Surfaces.at(level, wheelHits[wheel].getBlockPos());
                // MINECRAFT-445: tire pressure scales grip (under-inflation enlarges the contact patch).
                // Exactly 1.0 at this car's nominal pressure, which every roster car carries, so this
                // multiply is a no-op for them -- the same pattern downforceGripMultiplier already uses.
                double gripMu = WheelMath.BASE_FRICTION * Math.min(1.0, surface.grip() * spec.looseGrip())
                        * spec.downforceGripMultiplier(forwardSpeed) * textureGrip[wheel]
                        * WheelMath.tirePressureGripMultiplier(spec.tirePressureBar());
                gripForces[wheel] = gripMu * forces[wheel];
            }

            // Pass 2 of this sub-step: suspension and tire forces from this sub-step's forces[].
            for (int wheel = 0; wheel < wheelCount; wheel++) {
                // Steer angle is a pure function of front/steer/forwardSpeed/spec - none of which depend
                // on the raycast or the force guard below - so it is published for EVERY wheel, every
                // sub-step, ahead of both guards. See the steerAngle field's own comment for the contract.
                double[] mount = spec.mounts()[wheel];
                boolean front = mount[2] > 0;
                // MINECRAFT-445: the rear axle takes its own configured share of the front angle. The
                // ratio is 0.0 for every vehicle that has not opted in, so this is bit-for-bit the
                // `: 0.0` the rear wheels got before rear steering existed. A nonzero ratio is real
                // physics, not just a rendered pose: this angle rotates the tire's own forward axis
                // below, which is what vLong/vLat -- and therefore the tire's force directions -- are
                // measured against.
                double frontSteerAngle = steer * WheelMath.maxSteerAngle(forwardSpeed, WheelMath.BASE_FRICTION,
                        spec.wheelbase(), CarPhysics.MAX_STEER);
                double wheelSteerAngle = front ? frontSteerAngle
                        : frontSteerAngle * spec.steeringTuning().rearSteerRatio();
                this.steerAngle[wheel] = wheelSteerAngle;

                BlockHitResult hit = wheelHits[wheel];
                if (hit.getType() == HitResult.Type.MISS) {
                    // Review, round 9: unlike wheelForce/wheelSpin (which deliberately FREEZE at their
                    // last grounded value here - see the force<=0 guard's own comment below), a stale
                    // wheelReactionForce reading is actively wrong, not just stale: wheelSpin freezing
                    // means THIS tick's own spin delta reads as exactly 0, and a nonzero reaction left
                    // over from the last tick this wheel was actually grounded would then violate the
                    // very momentum identity (reaction == -(deltaSpin)*inertia/radius/dt) it exists to let
                    // a test check - CI caught exactly this. Clear it explicitly; it is not meant to
                    // persist past the sub-step that actually produced it.
                    wheelReactionForce[wheel] = 0.0;
                    wheelLateralForce[wheel] = 0.0;
                    wheelFrictionLimit[wheel] = 0.0;
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
                    // Same staleness fix as the MISS guard above, for the same reason.
                    wheelReactionForce[wheel] = 0.0;
                    wheelLateralForce[wheel] = 0.0;
                    wheelFrictionLimit[wheel] = 0.0;
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

                // Accelerator direction follows the selected gear, and the brake always just brakes --
                // see GearDrive's own javadoc (MINECRAFT-382).
                double drive = GearDrive.drive(throttle, forwardSpeed, gear, driveForceMultiplier, DRIVE_FORCE, effectiveReverseForce, spec.forceScale(), spec.maxSpeed());
                double brake = GearDrive.brake(throttle, forwardSpeed, BRAKE_FORCE, spec.forceScale());
                double lateralScale = 1.0;
                double brakeGain = 1.0;
                if (throttle == 0 && Math.abs(forwardSpeed) < 1.5) {
                    brake = BRAKE_FORCE * spec.forceScale(); // parked: hold on a slope instead of rolling away
                    brakeGain = 6.0;
                }
                if (handbrake && !front) {
                    brake = WheelMath.handbrakeForce(HANDBRAKE_FORCE, spec.forceScale(), spec.handbrakeScale());
                    drive = 0.0;
                    lateralScale = 0.35;
                }
                int partner = partners[wheel];
                boolean torqueDistributionActive = tuning.frontTorqueSplit() != 0.5
                        || tuning.vectoringGain() != 0.0;
                if (torqueDistributionActive) {
                    drive = TorqueDistribution.distribute(drive, front, mount[0] < 0.0, tuning, steer);
                }
                if (drive != 0.0 && partner >= 0 && !torqueDistributionActive) {
                    // The zero-lock path in distribute returns drive unchanged exactly, preserving the
                    // existing open-differential path for every vehicle that has not opted in.
                    LimitedSlipDifferential.DrivePair axleDrive = LimitedSlipDifferential.distribute(
                            drive, drive, wheelSpin[wheel] * spec.wheelRadius(),
                            wheelSpin[partner] * spec.wheelRadius(), gripForces[wheel], gripForces[partner],
                            tuning.lsdLockPercent());
                    drive = axleDrive.left();
                } else if (drive != 0.0 && partner >= 0) {
                    // Split/vectoring chooses the axle's requested drive first; LSD then makes its one
                    // traction-based transfer across that already-distributed pair, rather than applying
                    // either correction twice.
                    double[] partnerMount = spec.mounts()[partner];
                    boolean left = mount[0] < 0.0;
                    double partnerDrive = TorqueDistribution.distribute(
                            GearDrive.drive(throttle, forwardSpeed, gear, driveForceMultiplier, DRIVE_FORCE, effectiveReverseForce, spec.forceScale(), spec.maxSpeed()),
                            partnerMount[2] > 0.0, partnerMount[0] < 0.0, tuning, steer);
                    LimitedSlipDifferential.DrivePair axleDrive = left
                            ? LimitedSlipDifferential.distribute(drive, partnerDrive,
                                    wheelSpin[wheel] * spec.wheelRadius(), wheelSpin[partner] * spec.wheelRadius(),
                                    gripForces[wheel], gripForces[partner], tuning.lsdLockPercent())
                            : LimitedSlipDifferential.distribute(partnerDrive, drive,
                                    wheelSpin[partner] * spec.wheelRadius(), wheelSpin[wheel] * spec.wheelRadius(),
                                    gripForces[partner], gripForces[wheel], tuning.lsdLockPercent());
                    drive = left ? axleDrive.left() : axleDrive.right();
                }
                // What the tire feels comes from the block it is on (grip, rolling resistance), via Dynamic Terrain.
                SurfaceProperties surface = Surfaces.at(level, hit.getBlockPos());
                // Downforce (MINECRAFT-184): spec.downforceGripMultiplier() is exactly 1.0 for every vehicle
                // whose downforceGripPerSpeed is 0.0 (every existing vehicle), so this multiply is a no-op
                // for them - see VehicleSpec.INDY's own javadoc for the one vehicle that sets it nonzero.
                // MINECRAFT-445: tire pressure scales grip (under-inflation enlarges the contact patch).
                // Exactly 1.0 at this car's nominal pressure, which every roster car carries, so this
                // multiply is a no-op for them -- the same pattern downforceGripMultiplier already uses.
                double gripMu = WheelMath.BASE_FRICTION * Math.min(1.0, surface.grip() * spec.looseGrip())
                        * spec.downforceGripMultiplier(forwardSpeed) * textureGrip[wheel]
                        * WheelMath.tirePressureGripMultiplier(spec.tirePressureBar());
                // MINECRAFT-445: and pressure scales rolling drag the OTHER way (a softer tire deforms
                // more and costs more), so dropping pressure buys grip above and pays for it here.
                double rollingCoefficient = WheelMath.rollingCoefficient(surface.rollingResistance(), surface.deformability(), spec.rollingScale())
                        * textureRolling[wheel]
                        * WheelMath.tirePressureRollingMultiplier(spec.tirePressureBar());
                double effectiveMass = spec.massKg() / 4.0;
                // isIdentity() is checked on the SPEC's own hard-wired tireTuning(), never on config, so
                // CAR/TRUCK/TROPHY (always IDENTITY in code) can never be routed into DriftTireModel no
                // matter what an operator sets in CarConfig's drift tire tuning. Only once a vehicle has
                // already opted in does the actual tuning VALUES come from config (CarConfig.driftTireTuning(),
                // live-reloadable) rather than the spec's own numbers, which exist only to mark the opt-in.
                boolean driftTuned = !spec.tireTuning().isIdentity();
                // MINECRAFT-317: this entity's persisted tuning (LSD lock, front/rear torque split, torque-
                // vectoring gain, camber, toe) is threaded through to the owning physics model. WheelMath
                // applies the static alignment grip modifiers; the differential and torque-distribution
                // models consume their respective values above.
                WheelMath.Tire tire = !driftTuned
                        ? WheelMath.tire(vLong, vLat, force, gripMu, rollingCoefficient, lateralScale,
                                drive, brake, brakeGain, effectiveMass, subDt, tuning)
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
                double priorSpin = wheelSpin[wheel];
                double snappedSpin = WheelMath.spinRate(priorSpin, spec.wheelRadius(), vLong, excessForce,
                        WheelMath.WHEEL_INERTIA, subDt);
                // Review, round 5: bound WheelMath.spinRate's own zero-excess snap to what kinetic friction
                // could actually despin this wheel by this step -- see DrivetrainMath.boundedSpinTowardSnap's
                // own javadoc. Only this one branch changes; excessForce != 0 still reads spinRate's own
                // return value exactly as before, untouched.
                //
                // Review, round 6: two things round 5 missed about WHERE that bounding torque comes from.
                // First, the excessForce != 0 branch needs no chassis reaction because its extra torque is
                // the ENGINE's own (driveForce exceeding what the circle delivers) - entirely internal to
                // the driveline, already fully accounted by handing the chassis exactly tire.longitudinal()
                // and nothing more. This branch is different: the wheel isn't gripping because it's being
                // driven within the circle, it's gripping because excessForce reads exactly 0 - any torque
                // that still changes its spin here can only come from the GROUND (kinetic friction against
                // the leftover spin/ground-speed mismatch), and a ground-sourced torque demands an equal and
                // opposite reaction on the chassis (Newton's third law) - round 5 bounded the torque but
                // never paid that reaction, conjuring momentum. Second, that friction is drawn from the SAME
                // circle this step's longitudinal/lateral tire forces already spent part of, not a second,
                // independent mu*normalForce budget - bound by the circle's remaining headroom instead.
                double reactionImpulse = 0.0;
                if (excessForce == 0.0) {
                    double frictionHeadroom = Math.max(0.0,
                            tire.frictionLimit() - Math.hypot(tire.longitudinal(), tire.lateral()));
                    double recoveredSpin = DrivetrainMath.boundedSpinTowardSnap(priorSpin, snappedSpin,
                            frictionHeadroom, spec.wheelRadius(), WheelMath.WHEEL_INERTIA, subDt);
                    // The same torque balance WheelMath.spinRate itself uses (a force F at the contact
                    // produces Δspin = F*radius/inertia*dt) relates a GROUND force to a WHEEL-torque
                    // reaction of the OPPOSITE sign on this model's own sign convention (a positive, forward
                    // drive force resists being spun up, per spinRate's existing excessForce contract) -
                    // solving that same relation for the force that would have produced THIS step's actual
                    // (bounded) Δspin gives the one-time chassis impulse below: spin decreasing (overspin
                    // bleeding off) pays out as a FORWARD impulse, spin increasing (a locked wheel catching
                    // back up) as a backward one, matching the two cases driving/braking already use the
                    // opposite sign for.
                    reactionImpulse = -(recoveredSpin - priorSpin) * WheelMath.WHEEL_INERTIA / spec.wheelRadius();
                    impulseWorld.fma(reactionImpulse, forward);
                    wheelSpin[wheel] = recoveredSpin;
                    recoveryImpulseAccum[wheel] += Math.abs(reactionImpulse);
                    recoveryLoadForce[wheel] = force;
                } else {
                    wheelSpin[wheel] = snappedSpin;
                }
                double spinSlip = WheelMath.slipFromSpin(wheelSpin[wheel], spec.wheelRadius(), vLong);
                spinSlipThisTick = Math.max(spinSlipThisTick, Math.abs(spinSlip));
                // Reported slip (feeds slipThisTick/lastSlipSpeed/wearSlip/SlipReporter) is evaluated at the
                // TICK's own dt, not subDt, so it reads the same regardless of wheelSubSteps - see
                // WheelSubStepper.subStepSlipSpeed's own comment for why. The force above, which this does
                // NOT touch, still comes from the subDt call: delivered impulse is unaffected.
                double reportedSlip = WheelSubStepper.subStepSlipSpeed(vLong, vLat, force, gripMu, rollingCoefficient,
                        lateralScale, drive, brake, brakeGain, effectiveMass, dt);
                slipThisTick = Math.max(slipThisTick, reportedSlip);
                // MINECRAFT-401 review: debug telemetry naming contact speed, per-wheel slip and applied
                // force explicitly. Last-sub-step-wins, same as every other per-wheel value below this
                // force<=0 guard -- these are a snapshot for /dtcar debug, not integrated state.
                wheelContactSpeed[wheel] = vLong;
                wheelSlip[wheel] = reportedSlip;
                // Review, round 7: this must report the TOTAL longitudinal force actually delivered to the
                // chassis this sub-step, not just tire.longitudinal() -- the recovery reaction above (0 when
                // not recovering) is real, applied force that was previously missing from this telemetry.
                wheelForce[wheel] = tire.longitudinal() + reactionImpulse / subDt;
                wheelReactionForce[wheel] = reactionImpulse / subDt;
                wheelLateralForce[wheel] = tire.lateral();
                wheelFrictionLimit[wheel] = tire.frictionLimit();
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
                double tireY = CarConfig.TIRE_FORCE_AT_CONTACT.get() ? contactLocal.y : BODY_TIRE_FORCE_HEIGHT;
                // MINECRAFT-445 review fix (PR184 #issuecomment-6282623425): anti-dive/anti-squat is
                // documented as a LONGITUDINAL (braking/drive) geometry effect only, but horizontalLocal
                // bundles the lateral tire force too -- applying the blended height to all of it would
                // have let a configured pitch fraction silently change CORNERING roll transfer as well,
                // which this ticket never claimed and never tested. Only the longitudinal component (tire
                // .longitudinal() plus the wheel-spin recovery reaction, both along `forward`) gets the
                // anti-dive/anti-squat height; the lateral component (tire.lateral() along `lateral`)
                // keeps applying at the ORIGINAL, unblended tireY regardless of pitch tuning.
                //
                // Anti-DIVE applies while this wheel's own longitudinal force opposes the car's motion,
                // anti-SQUAT while it drives it, so a braking wheel uses anti-dive even mid-corner. Both
                // fractions are 0.0 for every roster car; at 0.0 the blend returns tireY unchanged and the
                // split below still sums to exactly horizontalLocal applied at exactly tireY, so this
                // stays bit-for-bit the single pre-split call for them.
                boolean opposingMotion = tire.longitudinal() * forwardSpeed < 0;
                double antiFraction = opposingMotion
                        ? spec.pitchTuning().antiDive()
                        : spec.pitchTuning().antiSquat();
                if (antiFraction == 0.0) {
                    body.applyImpulseAtPoint(new Vector3d(local.x, tireY, local.z), horizontalLocal);
                } else {
                    double pitchTireY = WheelMath.pitchResistApplicationHeight(tireY, BODY_TIRE_FORCE_HEIGHT, antiFraction);
                    Vector3d lateralLocal = inverse.transform(new Vector3d(lateral).mul(tire.lateral() * subDt));
                    Vector3d longitudinalLocal = new Vector3d(horizontalLocal).sub(lateralLocal);
                    body.applyImpulseAtPoint(new Vector3d(local.x, pitchTireY, local.z), longitudinalLocal);
                    body.applyImpulseAtPoint(new Vector3d(local.x, tireY, local.z), lateralLocal);
                }
            }
            // Re-read velocity so the next sub-step's forces respond to the impulses just applied above -
            // see the note before this loop on why this, and not pose, is what makes sub-stepping matter.
            subLinear = body.getLinearVelocity(new Vector3d());
            subAngular = body.getAngularVelocity(new Vector3d());
        }
        double recoveryEffectiveMass = spec.massKg() / 4.0;
        // forwardSpeed itself is sub-step-loop-local; this re-derives it the same way (subLinear.dot
        // (carForward)) from the loop's own final velocity, consistent with every other "last sub-step
        // wins" value already read after that loop ends.
        double finalForwardSpeed = subLinear.dot(carForward);
        for (int wheel = 0; wheel < wheelCount; wheel++) {
            // Review, round 7: fold the recovery reaction's own equivalent slip speed (this tick's total
            // accumulated reaction impulse / effectiveMass - see the accumulator's own comment above) into
            // the EXISTING wearSlipMax/wearSlipForce bookkeeping once, exactly like any other slip source
            // already competing for that same "worst this tick" slot - no separate threshold, no separate
            // SlipReporter call.
            if (recoveryImpulseAccum[wheel] > 0.0) {
                double recoverySlipSpeed = recoveryImpulseAccum[wheel] / recoveryEffectiveMass;
                double recoveryWear = CarConfig.WEAR_ENABLED.get()
                        ? CarEffectsMath.wearSlip(throttle, finalForwardSpeed, recoverySlipSpeed, CarConfig.WEAR_STRENGTH.get())
                        : recoverySlipSpeed;
                if (recoveryWear > wearSlipMax[wheel]) {
                    wearSlipMax[wheel] = recoveryWear;
                    wearSlipForce[wheel] = recoveryLoadForce[wheel];
                }
                slipThisTick = Math.max(slipThisTick, recoverySlipSpeed);
                wheelSlip[wheel] = Math.max(wheelSlip[wheel], recoverySlipSpeed);
            }
            if (wearSlipMax[wheel] > 0.3) {
                double loadKg = wearSlipForce[wheel] / GRAVITY;
                Erosion.Result result = SlipReporter.report(level, wheelHits[wheel].getBlockPos(), wearSlipMax[wheel], loadKg);
                lastErosionReportSlipSpeed = wearSlipMax[wheel];
                lastErosionReportLoadKg = loadKg;
                lastErosionResult = result;
            }
        }
        // MINECRAFT-259: water/lava buoyancy, independent of speed -- a car sitting still in a flooded
        // quarry still wants to float (water) or be pushed up harder (lava). getFluidHeight is NOT a 0..1
        // bounding-box fraction (review, PR #159): it is the fluid's surface height in blocks above this
        // entity's own Y, so it must be normalized against the car's own full height before it means
        // "how submerged" -- see EnvironmentMath.submersionFraction's own javadoc for why that matters for
        // a short vehicle vs. a tall one. It is already kept current by super.tick()'s fluid-state
        // bookkeeping in CarEntity.tick, so no extra sampling here.
        double vehicleHeight = 2.0 * spec.halfY();
        double waterFraction = EnvironmentMath.submersionFraction(car.getFluidHeight(FluidTags.WATER), vehicleHeight);
        double lavaFraction = EnvironmentMath.submersionFraction(car.getFluidHeight(FluidTags.LAVA), vehicleHeight);
        double fluidScale = CarConfig.FLUID_EFFECTS.get();
        if (fluidScale > 0.0 && (waterFraction > 0.0 || lavaFraction > 0.0)) {
            double buoyancy = fluidScale * (
                    EnvironmentMath.buoyantForce(spec.massKg(), GRAVITY, waterFraction, EnvironmentMath.WATER_BUOYANCY)
                    + EnvironmentMath.buoyantForce(spec.massKg(), GRAVITY, lavaFraction, EnvironmentMath.LAVA_BUOYANCY));
            Vector3d buoyantImpulse = inverse.transform(new Vector3d(0, buoyancy * dt, 0));
            body.applyImpulseAtPoint(new Vector3d(0, 0, 0), buoyantImpulse);
        }
        // MINECRAFT-259: pass-through foliage resistance -- sampled at each wheel's own horizontal
        // position, at the car's feet height, since that is where a block like tall grass actually sits
        // as a wheel drives over it; the wheel raycast above cannot see these (it casts with
        // ClipContext.Block.COLLIDER, which foliage has none of, so it passes straight through to the
        // real ground beneath). AIR applies none because it never has an empty-but-non-air collision
        // shape; every other pass-through block gets at least EnvironmentMath.FOLIAGE_BASELINE, scaled up
        // by its own hardness (see EnvironmentMath's own javadoc for why hardness was chosen). Water and
        // lava also have an empty collision shape and are not air, so they must be excluded explicitly
        // (review, PR #159) -- otherwise fluidEffects=0 would still add spurious "foliage" drag inside a
        // fluid, and fluid tiles would double up with the buoyancy/drag handling above.
        double foliageDragTotal = 0.0;
        double foliageScale = CarConfig.FOLIAGE_RESISTANCE.get();
        if (foliageScale > 0.0) {
            double feetY = position.y - spec.halfY();
            for (int wheel = 0; wheel < wheelCount; wheel++) {
                BlockPos pos = BlockPos.containing(position.x + wheelOffsets[wheel].x, feetY + 0.1, position.z + wheelOffsets[wheel].z);
                BlockState state = level.getBlockState(pos);
                if (isFoliage(state, level, pos)) {
                    foliageDragTotal += foliageScale * EnvironmentMath.foliageDragCoefficient(state.getDestroySpeed(level, pos));
                }
            }
        }
        // Air drag (plus any fluid/foliage drag above) along the velocity: once per tick in total, at the
        // full dt, not once per sub-step - using the velocity as it stands after every sub-step's impulses.
        // MINECRAFT-446: spec.wingDragCoeff() replaces the old hardcoded AIR_DRAG global constant -- 0.4
        // for every vehicle that has not opted into a different per-car wing config, so this is
        // byte-for-byte the same impulse as before for the whole roster until an operator overrides it.
        //
        // MINECRAFT-446 review (comment 49064): the effective applied force is
        //   dragCoeff * forceScale() * |v| * v        (opposing the full 3D velocity vector)
        // i.e. the configured coefficient is scaled by this vehicle's own forceScale, and NOTHING is
        // applied at or below DRAG_SPEED_DEADBAND. Both are pre-existing behaviour this ticket preserves
        // byte-for-byte, and both are now documented as part of the contract in docs/per-car-config.md
        // rather than left implicit -- see that doc's `wing` section for the exact formulas.
        double speed = subLinear.length();
        if (speed > DRAG_SPEED_DEADBAND) {
            double fluidDrag = fluidScale * (
                    EnvironmentMath.fluidDragCoefficient(waterFraction, EnvironmentMath.WATER_DRAG)
                    + EnvironmentMath.fluidDragCoefficient(lavaFraction, EnvironmentMath.LAVA_DRAG));
            double dragCoeff = spec.wingDragCoeff() + fluidDrag + foliageDragTotal;
            Vector3d drag = inverse.transform(new Vector3d(subLinear).mul(-dragCoeff * spec.forceScale() * speed * dt));
            body.applyImpulseAtPoint(new Vector3d(0, 0, 0), drag);
        }
        // Wing downforce (MINECRAFT-446): a genuine downward impulse, once per tick at the full dt (same
        // cadence as air drag above), applied at each axle's own mount point rather than at the CoG so a
        // front/rear split also produces a genuine pitch moment, not just a vertical force. The airflow
        // speed used is the body's own forward speed (signed, but squared below so direction never
        // matters) read AFTER every sub-step's impulses, exactly like drag above. Purely vertical
        // (world -Y), unlike drag (which opposes the full 3D velocity vector, not just its forward
        // component): on ordinary horizontal travel this does no work on the car's own forward motion,
        // since a vertical force is perpendicular to a horizontal velocity -- but world-down is NOT
        // generally perpendicular to velocity on a slope or while airborne, so on sloped/vertical travel
        // this genuinely can add or remove vertical kinetic energy, exactly the way gravity already does;
        // that is expected, real physics, not an injected-energy bug. Zero at zero speed, and zero for
        // every vehicle on the roster today (both coefficients default to 0.0).
        double airflowSpeed = subLinear.dot(carForward);
        if (spec.frontWingDownforceCoeff() > 0.0 || spec.rearWingDownforceCoeff() > 0.0) {
            Vector3d frontAxlePoint = new Vector3d();
            Vector3d rearAxlePoint = new Vector3d();
            int frontCount = 0;
            int rearCount = 0;
            for (double[] mount : spec.mounts()) {
                if (mount[2] > 0) {
                    frontAxlePoint.add(mount[0], mount[1], mount[2]);
                    frontCount++;
                } else {
                    rearAxlePoint.add(mount[0], mount[1], mount[2]);
                    rearCount++;
                }
            }
            if (frontCount > 0) {
                frontAxlePoint.div(frontCount);
            }
            if (rearCount > 0) {
                rearAxlePoint.div(rearCount);
            }
            double frontDownforce = VehicleSpec.wingDownforceNewtons(spec.frontWingDownforceCoeff(), airflowSpeed);
            double rearDownforce = VehicleSpec.wingDownforceNewtons(spec.rearWingDownforceCoeff(), airflowSpeed);
            // Telemetry (MINECRAFT-446 review, comment 49019): recorded from the SAME locals the impulses
            // below are built from, and the counter incremented at each real applyImpulseAtPoint call, so
            // what a test reads is what the production path actually applied -- see the fields' own comment.
            double appliedNewtons = 0.0;
            int applications = 0;
            if (frontDownforce > 0.0 && frontCount > 0) {
                Vector3d impulse = inverse.transform(new Vector3d(0, -frontDownforce * spec.forceScale() * dt, 0));
                body.applyImpulseAtPoint(frontAxlePoint, impulse);
                appliedNewtons += frontDownforce * spec.forceScale();
                applications++;
            }
            if (rearDownforce > 0.0 && rearCount > 0) {
                Vector3d impulse = inverse.transform(new Vector3d(0, -rearDownforce * spec.forceScale() * dt, 0));
                body.applyImpulseAtPoint(rearAxlePoint, impulse);
                appliedNewtons += rearDownforce * spec.forceScale();
                applications++;
            }
            if (applications > 0) {
                lastAeroAirflowSpeed = airflowSpeed;
                lastAeroDownforceNewtons = appliedNewtons;
                lastAeroApplications = applications;
            }
        }
        lastSlipSpeed = slipThisTick;
        lastSpinSlip = spinSlipThisTick;
        if (CarDebug.enabled) {
            drawDebug(position, orientation, hits);
        }
        if (touching || throttle != 0 || steer != 0 || handbrake) {
            box.wakeUp();
        }
        lastTouching = touching;
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
     * MINECRAFT-259: whether {@code state} is a pass-through foliage block that should add resistance --
     * not air, not a fluid (water/lava also have an empty collision shape, but the buoyancy/drag block
     * above already owns them), and no collision shape of its own. Package-private, not private, so
     * EnvironmentGameTests can exercise this exact production classification directly against real block
     * states (air, ordinary vegetation, water) instead of only against EnvironmentMath's own pre-normalized
     * constants (review, PR #159).
     */
    static boolean isFoliage(BlockState state, ServerLevel level, BlockPos pos) {
        return !state.isAir() && state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
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

    /** The REAL slip speed (m/s), load (kg) and {@link Erosion.Result} the production tick last actually
     * passed to/got back from {@link SlipReporter#report} -- not a value a caller recomputes itself. */
    double lastErosionReportSlipSpeed() {
        return lastErosionReportSlipSpeed;
    }

    double lastErosionReportLoadKg() {
        return lastErosionReportLoadKg;
    }

    Erosion.Result lastErosionResult() {
        return lastErosionResult;
    }

    /** Each wheel's suspension travel as of the last tick's single raycast, metres (a copy; not live state). */
    double[] suspensionTravel() {
        return suspensionTravel.clone();
    }

    /** MINECRAFT-446 review (comment 49019): the forward airflow speed (m/s, signed) the last applied wing
     * downforce was actually evaluated at. {@code 0.0} until some tick has actually applied aero, so a
     * test must establish this is nonzero before trusting the two readings below. */
    double lastAeroAirflowSpeed() {
        return lastAeroAirflowSpeed;
    }

    /** The total downward wing force (N, front + rear, including {@code forceScale}) the last aero-applying
     * tick actually handed to the rigid body &mdash; see {@link #lastAeroAirflowSpeed()}. */
    double lastAeroDownforceNewtons() {
        return lastAeroDownforceNewtons;
    }

    /** How many aero impulses the last aero-applying tick handed to {@code applyImpulseAtPoint}: one per
     * axle with a nonzero coefficient, once per TICK regardless of {@code wheelSubSteps}. An application
     * wrongly placed inside the sub-step loop would multiply this by the sub-step count. */
    int lastAeroApplications() {
        return lastAeroApplications;
    }

    /** Each wheel's steer angle as of the last tick it was grounded, radians (a copy; not live state). */
    double[] steerAngles() {
        return steerAngle.clone();
    }

    /** Each wheel's own spin rate as of the last tick it was grounded, rad/s (a copy; not live state). */
    double[] wheelSpinRates() {
        return wheelSpin.clone();
    }

    /** Each wheel's own contact-patch speed (m/s) and applied longitudinal force (N) as of the last tick
     * it was grounded (MINECRAFT-401 review, round 5: evidence for actual wheel/contact torque-cut
     * recovery, not just smoothed engine audio). Copies; not live state. */
    double[] wheelContactSpeeds() {
        return wheelContactSpeed.clone();
    }

    double[] wheelForces() {
        return wheelForce.clone();
    }

    /** Review, round 8: JUST the recovery reaction's own contribution to {@link #wheelForces()} (0 when
     * not recovering) -- see {@link #wheelReactionForce}'s own comment for why this is tracked
     * separately. */
    double[] wheelReactionForces() {
        return wheelReactionForce.clone();
    }

    /** Review, round 9: each wheel's own lateral tire force this tick -- the friction circle is
     * longitudinal+lateral combined (hypot), so a test checking the circle invariant against
     * {@link #wheelForces()} alone could miss a combined-force overflow a cornering tick introduces. */
    double[] wheelLateralForces() {
        return wheelLateralForce.clone();
    }

    /** Review, round 10: this tick's own actual {@code Tire#frictionLimit()} per wheel (0 when not
     * gripping at all), so a test can check the delivered force against the REAL per-tick circle instead
     * of a fixed global worst-case ceiling. */
    double[] wheelFrictionLimits() {
        return wheelFrictionLimit.clone();
    }

    /** Engine RPM implied by the drivetrain this tick (MINECRAFT-401): idle in NEUTRAL scaled by throttle,
     * otherwise {@link DrivetrainMath#engineRpm} through whichever ratio is actually engaged. */
    double engineRpm() {
        return engineRpm;
    }

    /** 0-based index into {@link DrivetrainMath#GEAR_RATIOS} that automatic mode is currently holding,
     * regardless of whether {@link CarPhysics.Gear#AUTOMATIC} is the gear actually selected right now. */
    int automaticGearIndex() {
        return automaticGearIndex;
    }

    /** The gear ratio actually engaged this tick (the manual gear's own ratio, automatic mode's current
     * pick, or {@link DrivetrainMath#REVERSE_RATIO}); 0.0 in NEUTRAL. */
    double gearRatio() {
        return gearRatio;
    }

    /** The body's speed, m/s. */
    double speed() {
        return body.getLinearVelocity(new Vector3d()).length();
    }

    /** Diagnostic only (MINECRAFT-401 review, round 5): whether any wheel had a positive suspension
     * force as of the end of the last tick -- see {@link #lastTouching}. */
    boolean touching() {
        return lastTouching;
    }

    /** The body's current orientation, for saving. */
    org.joml.Quaternionf orientationF() {
        box.updatePose();
        org.joml.Quaterniondc q = box.getPose().orientation();
        return new org.joml.Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
    }

    /** Debug summary: height of the body centre above the ground below it, pitch and roll in degrees. */
    String describe(CarPhysics.Gear gear, double throttle) {
        box.updatePose();
        Pose3d pose = new Pose3d(box.getPose());
        Quaterniond q = new Quaterniond(pose.orientation());
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        Vector3d fwd = q.transform(new Vector3d(0, 0, 1));
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd.y))));
        double roll = Math.toDegrees(Math.atan2(q.transform(new Vector3d(1, 0, 0)).y, up.y));
        Vector3d v = body.getLinearVelocity(new Vector3d());
        // MINECRAFT-401 (review: complete the telemetry the ticket's own acceptance criteria names):
        // requested mode/gear, the effective gear/ratio it resolves to (AUTOMATIC's own current pick, or
        // the manual gear itself), engine RPM, throttle/brake, and every wheel's own angular (spin) and
        // linear speed, this tick's contact-patch speed, slip and applied force/torque -- see the class
        // javadoc for which values are "fixed for the whole tick" versus live.
        String effective = gear == CarPhysics.Gear.AUTOMATIC
                ? "D" + (automaticGearIndex + 1) + "(auto)"
                : gear.name();
        double brakeInput = Math.max(0.0, -throttle);
        double throttleInput = Math.max(0.0, throttle);
        StringBuilder wheels = new StringBuilder();
        for (int wheel = 0; wheel < wheelSpin.length; wheel++) {
            double wheelLinear = wheelSpin[wheel] * spec.wheelRadius();
            double torque = wheelForce[wheel] * spec.wheelRadius();
            wheels.append(String.format(" w%d(spin=%.2f linear=%.2f contact=%.2f slip=%.2f force=%.0f torque=%.0f)",
                    wheel, wheelSpin[wheel], wheelLinear, wheelContactSpeed[wheel], wheelSlip[wheel],
                    wheelForce[wheel], torque));
        }
        return String.format("body y=%.3f pitch=%.2f roll=%.2f speed=%.2f spinSlip=%.2f slip=%.2f "
                + "requestedGear=%s effectiveGear=%s ratio=%.2f rpm=%.0f throttle=%.2f brake=%.2f%s",
                pose.position().y, pitch, roll, v.length(), lastSpinSlip, lastSlipSpeed,
                gear.name(), effective, gearRatio, engineRpm, throttleInput, brakeInput, wheels);
    }
}
