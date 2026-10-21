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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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
    private double[] lastTravelLiftForces = new double[4];
    double[] travelLiftForces() { return lastTravelLiftForces.clone(); }
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
        // Wake the body BEFORE touching its pose/velocity or applying any impulse this tick -- not only
        // afterward. A sleeping rigid body's own physics engine ignores impulses applied to it while
        // asleep, so waking it only at the end of the tick (the prior approach) lets this tick's
        // buoyancy/thrust/drag impulses get silently dropped whenever the body fell asleep on the previous
        // tick, and the body then only wakes in time for the NEXT tick, by which point its velocity has
        // already been zeroed by the engine's own sleep handling -- observed directly as a boat that moves
        // a small one-off amount on its very first tick (before it had ever slept) and then permanently
        // freezes, immersion and speed both pinned at zero forever after, regardless of throttle.
        box.wakeUp();
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

        double[] liftX = new double[hullMounts.length];
        double[] liftZ = new double[hullMounts.length];
        double[] liftDepth = new double[hullMounts.length];
        int wetPoints = 0;
        for (int point = 0; point < hullMounts.length; point++) {
            double[] mount = hullMounts[point];
            Vector3d offset = orientation.transform(new Vector3d(mount[0], mount[1], mount[2]));
            liftX[point] = offset.x;
            liftZ[point] = offset.z;
            Vector3d worldPoint = new Vector3d(position).add(offset);
            Double surfaceY = sampleSurface(worldPoint.x, worldPoint.y, worldPoint.z);
            if (surfaceY == null) {
                continue;
            }
            double depth = surfaceY - worldPoint.y;
            if (!(depth > 0.0)) {
                continue;
            }
            liftDepth[point] = depth;
            wetPoints++;
            Vector3d pointVelocity = new Vector3d(angular).cross(offset).add(linear);
            double force = MotorboatPhysics.pointBuoyantForce(depth, pointVelocity.y, stiffness, damping, maxPointForce);
            if (force <= 0.0) {
                continue;
            }
            Vector3d localImpulse = inverse.transform(new Vector3d(0, force * dt, 0));
            body.applyImpulseAtPoint(new Vector3d(mount[0], mount[1], mount[2]), localImpulse);
        }
        lastTravelLiftForces = MotorboatPhysics.travelSideLift(linear.x, linear.z, liftX, liftZ, liftDepth,
                MotorboatConfig.TRAVEL_LIFT_COEFFICIENT.get(), MotorboatConfig.MAX_TRAVEL_LIFT_FORCE.get());
        for (int point = 0; point < hullMounts.length; point++) {
            double force = lastTravelLiftForces[point];
            if (force > 0) {
                double[] mount = hullMounts[point];
                body.applyImpulseAtPoint(new Vector3d(mount[0], mount[1], mount[2]),
                        inverse.transform(new Vector3d(0, force * dt, 0)));
            }
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
            // Thrust is built and applied ENTIRELY in the boat's own LOCAL frame: steer rotates the local
            // forward axis (0,0,1) around the local Y axis exactly as MotorboatPhysics.thrustVector's own
            // javadoc documents, and the result is applied directly as a local impulse at the local motor
            // mount -- never projected onto world XZ, never forced to world.y=0, and never routed through
            // `orientation`/`inverse` at all. That's deliberate: this is what makes steering rotate WITH the
            // physical motor regardless of the hull's pitch/roll (pitching the boat nose-down still thrusts
            // along the hull's own nose-down axis, not flattened to horizontal; rolling the boat rotates the
            // steering plane along with it; there is no orientation at which thrust vanishes).
            double steerRad = steer * Math.toRadians(MotorboatConfig.MAX_STEER_DEG.get());
            MotorboatPhysics.Vector2 thrust = MotorboatPhysics.thrustVector(0.0, 1.0, steerRad, throttle, MotorboatConfig.MAX_THRUST.get());
            Vector3d localImpulse = new Vector3d(thrust.x() * dt, 0, thrust.z() * dt);
            body.applyImpulseAtPoint(new Vector3d(motorMount[0], motorMount[1], motorMount[2]), localImpulse);
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

        // Angular damping (yaw/pitch/roll), same bounded primitive, same immersion scaling. This directly
        // ADDS the bounded delta to the body's own angular velocity (RigidBodyHandle.addLinearAndAngular
        // Velocity), rather than converting it to an impulse: an applied angular impulse only becomes the
        // angular-velocity change boundedDampingDelta computed after Sable divides it by the body's own
        // inertia tensor, NOT by mass -- for this hull's box shape the two differ by roughly an order of
        // magnitude, so scaling by mass (the prior approach) could overshoot the intended delta badly
        // enough to reverse and amplify roll instead of damping it. Adding directly to angular velocity has
        // no such conversion step to get wrong, and boundedDampingDelta's own bound still guarantees this
        // can only ever remove angular momentum, never add it or overshoot past zero.
        double angularRate = MotorboatConfig.ANGULAR_DAMPING_RATE.get() * immersionFraction;
        double dax = MotorboatPhysics.boundedDampingDelta(angular.x, angularRate, dt);
        double day = MotorboatPhysics.boundedDampingDelta(angular.y, angularRate, dt);
        double daz = MotorboatPhysics.boundedDampingDelta(angular.z, angularRate, dt);
        if (dax != 0.0 || day != 0.0 || daz != 0.0) {
            body.addLinearAndAngularVelocity(new Vector3d(0, 0, 0), new Vector3d(dax, day, daz));
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
     * unbounded search). Scans radiate OUT FROM THE POINT ITSELF in each direction ({@link #scanUpward}, then
     * {@link #scanDownward}), each stopping the instant it hits a solid (collidable) block -- fixing the
     * prior version's bug of scanning the whole window top-down regardless of the point's own position,
     * which could reach a distant water block before ever testing the solid block actually between it and
     * the point (seeing water "through" a roof) and, symmetrically, could hit a solid block above the point
     * before ever reaching water the point itself is already submerged in (a dry bridge over a submerged
     * point reporting dry). Radiating from the point fixes both: a boat under a bridge with water on the
     * bridge's own roof does not feel that water (the bridge's own block, directly between the point and
     * that water, is hit first); a point already submerged beneath a dry bridge further up finds its own
     * water immediately, at {@code dy=0}, before the scan ever reaches the bridge.
     *
     * <p>The water surface used is the block's own {@link FluidState#getHeight}, not a flat {@code
     * blockY+1} -- so a shallow flowing-water block reports its real, lower surface rather than being
     * treated as a full source block. {@link #scanUpward} additionally climbs through consecutive water
     * blocks to find a multi-block-deep column's real top (see its own javadoc), rather than returning the
     * first submerged block's own single-block height, which would otherwise invert the relative depth of
     * two points more than one block apart in the same column.
     *
     * <p>Collision is checked at every probe ({@link #probe}) with a real point/segment CLIP against the
     * block's actual {@link VoxelShape} at the sample's own fractional (x, z) -- not the shape's overall
     * bounding box. Round-3 review follow-up: a bounding-box check (comparing only {@code shape.bounds()}'s
     * minY/maxY) got this wrong two distinct ways, both now fixed: (a) a waterlogged slab/stairs' bounding
     * box spans the full block height (because the box is drawn around solid-plus-liquid together), so a
     * point sitting IN the slab's own solid half was wrongly read as floating in open water instead of
     * being occluded by the solid it's actually embedded in; (b) a thin shape like a fence post has a
     * bounding box spanning the full block height too, so EVERY point anywhere in that block's XZ footprint
     * -- including points well outside the post's own thin column, where there is no collision at all --
     * was wrongly read as fully blocked. Clipping the real shape at the sample's own (x, z) fixes both:
     * {@link #probe} now reports exactly where real solid geometry begins along that one column, compared
     * directly against the sample's own height (fixing (a)), and reports no occlusion at all off the post's
     * actual footprint (fixing (b)) -- while still keeping fractional coordinates throughout, never
     * snapping to block granularity.
     */
    private Double sampleSurface(double x, double y, double z) {
        return sampleSurface(level, x, y, z);
    }

    /** Test-only entry point for {@link #sampleSurface(double, double, double)} against a real {@code
     * ServerLevel} (e.g. a GameTest's), without needing a constructed rigid body -- exercises the actual
     * production sampler, not a reimplementation, for the review-flagged regression cases: a
     * multi-block-deep water column's relative point depth, and a partially-occupied waterlogged/thin-shape
     * block's real-geometry collision clipping. */
    static Double sampleSurfaceForTest(ServerLevel level, double x, double y, double z) {
        return sampleSurface(level, x, y, z);
    }

    private static Double sampleSurface(ServerLevel level, double x, double y, double z) {
        // Round-3 review follow-up: a point embedded in real solid matter at its own exact position (e.g.
        // inside a fence post's own footprint, or the solid half of a waterlogged slab it's resting in)
        // must be occluded outright, in EVERY direction -- checked here, once, before scanning either way.
        // This used to live only inside scanUpward's dy=0 case, which meant a point that was correctly
        // found to be embedded in solid still fell through to scanDownward afterward (since scanUpward
        // returning null is also the ordinary "nothing above, keep checking below" signal) and could find
        // real water sitting BELOW the very solid the point is embedded in -- as if the solid were
        // transparent to anything underneath it.
        //
        // Round-4 review follow-up: this used to compare y only against the block's TOPMOST solid hit
        // (solidTopY), which is wrong for a partial shape whose solid interval doesn't start at the block's
        // own bottom -- a waterlogged TOP slab's solid spans only [blockBottom+0.5, blockTop], so its
        // topmost hit sits right at blockTop, and "y <= topmost hit" was then true for EVERY y inside the
        // block, including the OPEN bottom half (just water, under the slab) -- wrongly rejecting a point
        // that isn't embedded in anything. Embedded now means y actually falls WITHIN the solid's own
        // interval ({@link #embeddedInSolid}), not merely at-or-below its topmost surface.
        BlockPos base = BlockPos.containing(x, y, z);
        Probe home = probe(level, x, z, base);
        if (embeddedInSolid(home, y)) {
            return null;
        }
        Double up = scanUpward(level, x, y, z);
        if (up != null) {
            return up;
        }
        return scanDownward(level, x, y, z);
    }

    /** Scan from the point's own block ({@code dy=0}) upward to {@link #PROBE_UP}. Unlike {@link
     * #scanDownward}, this must keep climbing through CONSECUTIVE water blocks once it finds the first one,
     * rather than returning that first block's own single-block height immediately: a multi-block-deep
     * water column's blocks all individually report a height of "their own top" (e.g. {@code blockY+1} for
     * a full source block), which is NOT the column's real free surface for any point sitting more than one
     * block below it. Returning the first hit's own height instead of the column's true top inverts the
     * relative depth of two points that differ by more than one block within the same column (the deeper
     * point can come out LESS deep than a shallower one, because each is compared against its own
     * immediate block's height rather than the one real surface above both). Climbing through consecutive
     * water and only returning once a non-water block is hit fixes this, while still never searching across
     * a DRY gap for unrelated water: round-3 review follow-up fixed a real bug here where reaching an air
     * block (no solid, no water) with no column surface found yet fell through to the NEXT iteration
     * instead of stopping, contradicting this method's own documented behaviour and letting the scan climb
     * straight through intervening dry air to count unrelated water further up as immersion. It now always
     * stops the instant a block is neither solid-at-this-column nor water, whether or not water was already
     * found below. */
    private static Double scanUpward(ServerLevel level, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        Double columnSurface = null;
        for (int dy = 0; dy <= PROBE_UP; dy++) {
            BlockPos pos = base.offset(0, dy, 0);
            Probe p = probe(level, x, z, pos);
            // Round-5 review follow-up: this entry-from-below test only makes sense for a block the scan
            // is actually ENTERING by climbing up from the one below it (dy > 0). At dy=0 the point already
            // starts inside this exact block -- it isn't entering from anywhere, so whether this block's
            // own solid happens to start right at ITS OWN bottom boundary (true for e.g. a bottom slab,
            // even when the point itself sits safely above that slab's solid, in its open top half) is
            // irrelevant to whether the point can reach water that may be sitting right there in that same
            // open half. Applying this test unconditionally at dy=0 wrongly returned null for a point like
            // that, in turn falling through to scanDownward and reporting (if anything) UNRELATED water
            // from a completely different block below, rather than the point's own home water right here.
            if (dy > 0 && blocksEntryFromBelow(p, pos)) {
                // Round-4 review follow-up: this used to ask "does solid reach this block's TOP" (the wrong
                // side for a scan arriving from BELOW), so a waterlogged BOTTOM slab -- solid spanning only
                // [blockBottom, blockBottom+0.5], its topmost hit well short of the top -- never tripped
                // this check, fell through to the water-height branch below (true, since waterlogged), and
                // the climb crossed straight through the slab's own solid lower half as if it were open
                // water. The side that matters for an upward-moving scan is the one it actually ENTERS
                // from: does solid start right at THIS block's bottom boundary.
                return columnSurface;
            }
            if (p.waterSurfaceY != null) {
                columnSurface = p.waterSurfaceY;
                if (blocksEntryFromAbove(p, pos)) {
                    // Round-5 review follow-up: this block's own solid reaches ITS OWN top boundary (e.g. a
                    // waterlogged TOP slab), so the water just found here is physically capped by that
                    // solid ceiling -- there is no way through it to whatever is in the block above.
                    // Continuing to climb anyway could cross straight through that ceiling and, if real
                    // (unrelated) water happens to sit up there too, silently overwrite this correct,
                    // capped answer with that other water's own surface. Stop right here instead, with
                    // the capped answer already recorded above.
                    return columnSurface;
                }
                continue;
            }
            // Neither water nor solid blocking entry from below at this column (open air, or a non-water
            // obstacle that only partially occupies this block, e.g. off a fence post's own thin footprint):
            // stop here, whether or not columnSurface is already set -- never keep searching across this
            // dry gap for unrelated water further up.
            return columnSurface;
        }
        return columnSurface;
    }

    /** Scan from just below the point's own block ({@code dy=-1}) downward to {@link #PROBE_DOWN},
     * stopping at the first solid (collidable, at this exact column) block and returning the first water
     * surface found strictly before that. */
    private static Double scanDownward(ServerLevel level, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        for (int dy = -1; dy >= -PROBE_DOWN; dy--) {
            BlockPos pos = base.offset(0, dy, 0);
            Probe p = probe(level, x, z, pos);
            if (blocksEntryFromAbove(p, pos)) {
                return null;
            }
            if (p.waterSurfaceY != null) {
                return p.waterSurfaceY;
            }
            if (p.solidTopY != null) {
                // A non-water obstacle occupies this exact (x, z) column (even if only partially, within
                // this one block) -- occludes this probe outright, same as the old OCCLUDED sentinel.
                return null;
            }
        }
        return null;
    }

    /** One probe's findings for a single block, at the sample's own fractional {@code (x, z)}: the real
     * solid INTERVAL along that column within this block ({@code solidBottomY}..{@code solidTopY}, both
     * absolute world Y, or both {@code null} if nothing collides on this exact column), and this block's
     * own real water surface ({@code waterSurfaceY}, or {@code null} if this block is not water, or if a
     * solid ceiling directly above the water caps it lower than its own unobstructed height -- see
     * {@link #probe}). A partial shape like a slab has a single contiguous solid interval that may start at
     * either the block's bottom (a bottom slab) or its top (a top slab) -- round-4 review follow-up: a lone
     * {@code solidTopY} (the old field) cannot distinguish those two cases from each other, which is exactly
     * what let a point in the OPEN remainder be misread as embedded ({@link #embeddedInSolid}) and let an
     * upward scan misread a solid interval starting at the bottom as passable ({@link #blocksEntryFromBelow}). */
    private record Probe(Double solidBottomY, Double solidTopY, Double waterSurfaceY) {}

    /** Whether the given world Y actually falls WITHIN a probe's own solid interval -- not merely
     * at-or-below its topmost surface, which (round-4 review follow-up) is true for every Y in a block
     * whose solid occupies only the TOP half (a waterlogged top slab), wrongly treating the OPEN bottom
     * half -- real water, not solid -- as embedded matter. */
    private static boolean embeddedInSolid(Probe p, double y) {
        return p.solidBottomY != null && p.solidTopY != null && y >= p.solidBottomY && y <= p.solidTopY;
    }

    /** Whether a probe's solid geometry blocks entry into this block from ABOVE -- i.e. starts right at
     * this block's own top boundary, same as a scan arriving from above would actually meet first. {@link
     * VoxelShape#clip}'s own fast path (see its source) always reports a hit 0.001 short of the ray's own
     * start, so a truly full-height-from-the-top shape's own {@code solidTopY} comes back at
     * {@code blockTop - 0.001}, never exactly {@code blockTop} -- a tight ({@code 1.0e-6}) tolerance against
     * the raw block boundary would therefore never actually match it. {@code 0.01} comfortably covers that
     * known 0.001 fast-path offset with real margin, without being loose enough to misclassify a genuinely
     * partial shape (a slab's own half-block gap is two full orders of magnitude bigger than this). */
    private static boolean blocksEntryFromAbove(Probe p, BlockPos pos) {
        return p.solidTopY != null && p.solidTopY >= pos.getY() + 1.0 - 0.01;
    }

    /** Whether a probe's solid geometry blocks entry into this block from BELOW -- i.e. starts right at
     * this block's own bottom boundary, same as a scan arriving from below would actually meet first.
     * Round-4 review follow-up: {@link #scanUpward} used to ask the ABOVE-entry question
     * ({@link #blocksEntryFromAbove}) regardless of its own direction, which never tripped for a waterlogged
     * BOTTOM slab (solid starting at the bottom, not the top) and let the climb cross straight through its
     * solid lower half as if it were open water. Same {@code 0.01} fast-path margin as above, mirrored. */
    private static boolean blocksEntryFromBelow(Probe p, BlockPos pos) {
        return p.solidBottomY != null && p.solidBottomY <= pos.getY() + 0.01;
    }

    /** One probe position, clipped against the block's REAL {@link VoxelShape} at the sample's own (x, z)
     * -- not its bounding box -- fixing two distinct round-3 review findings: a shape that only partially
     * covers this block's height at this column (a waterlogged slab's solid half) no longer gets conflated
     * with "fully blocks," and a shape with a full-height bounding box but a narrow actual footprint (a
     * fence post) no longer occludes points off that footprint. The fluid height used is the block's own
     * real {@link FluidState#getHeight}, never a flat {@code blockY+1} -- except when a solid ceiling sits
     * directly above the water in this same column (a waterlogged TOP slab): {@code getHeight} reports the
     * fluid's own unobstructed height regardless of what's above it, which would otherwise claim a surface
     * HIGHER than the solid ceiling actually pinning it down, so that case is capped at the ceiling's own
     * underside ({@code solidBottomY}) -- round-4 review follow-up, alongside the two solid-interval fixes
     * above, so the open lower half of a waterlogged top slab reports its real (capped), not its nominal,
     * surface. */
    private static Probe probe(ServerLevel level, double x, double z, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos);
        Double solidTopY = null;
        Double solidBottomY = null;
        if (!shape.isEmpty()) {
            BlockHitResult topDown = shape.clip(new Vec3(x, pos.getY() + 1.0, z), new Vec3(x, pos.getY(), z), pos);
            if (topDown != null) {
                solidTopY = topDown.getLocation().y;
            }
            BlockHitResult bottomUp = shape.clip(new Vec3(x, pos.getY(), z), new Vec3(x, pos.getY() + 1.0, z), pos);
            if (bottomUp != null) {
                solidBottomY = bottomUp.getLocation().y;
            }
        }
        FluidState fluid = state.getFluidState();
        Double waterSurfaceY = null;
        if (fluid.is(FluidTags.WATER)) {
            double natural = pos.getY() + (double) fluid.getHeight(level, pos);
            boolean solidCeilingAbove = solidTopY != null && solidBottomY != null
                    && solidTopY >= pos.getY() + 1.0 - 0.01 && solidBottomY > pos.getY() + 0.01;
            waterSurfaceY = solidCeilingAbove ? Math.min(natural, solidBottomY) : natural;
        }
        return new Probe(solidBottomY, solidTopY, waterSurfaceY);
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
