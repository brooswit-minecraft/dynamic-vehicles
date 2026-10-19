package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * MINECRAFT-447: production-path verification for the Diffuser KIT's assists, run headlessly by
 * {@code ./gradlew runGameTestServer} (the same CI step {@link NewVehicleGameTests}' own javadoc
 * documents). {@link DiffuserAssistMathTest} proves the bounded PD-controller equations in isolation;
 * these spawn a real {@code CarEntity} in a real, ticking {@code ServerLevel} with its real Sable rigid
 * body and assert on the actual orientation after real ticks -- the thing the pure-function tests cannot
 * reach, since {@link SableCarBody} is entirely Minecraft/Sable-coupled.
 *
 * <p>Every existing car's stock {@link VehicleSpec} carries {@link VehicleSpec.DiffuserTuning#IDENTITY},
 * so these tests cannot spawn a stock car and see any assist activate: {@link CarEntity#setDiffuserTuningForTest}
 * is the GameTest-only hook that lets a real CAR entity opt in without touching any car's own stock spec --
 * see that method's own javadoc for why this is not a second config mechanism.
 *
 * <p>All four assists (R16 roll, R17 pitch/landing, R18 heading, R19 trajectory) have a direction-only
 * production regression here, each run for BOTH signs of its own error: a clean single-axis fixture (car
 * dropped from height, zero initial angular velocity, no ground contact in the window) asserting that the
 * real Sable body's real angle moves TOWARD the assist's target rather than away from it. PR #180 review
 * comment 6278664198 found a real coordinate-convention bug in three of the four assists' proportional
 * feedback (fixed in {@code SableCarBody.applyDiffuserAssists}, see its own comments) that this ticket's
 * own earlier sessions had misdiagnosed as physics-solver nondeterminism; these fixtures are what pins the
 * direction now.
 *
 * <p>Still NOT covered here, and deliberately not claimed: R16's own full "inverted car recovers AND lands
 * upright" scenario (as opposed to the bank-angle direction check), which runs into a separate, genuine
 * chaotic-equilibrium-capture problem -- see the comment block at the end of this class for the full
 * account of that and of what each earlier attempt actually ran into.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DiffuserAssistGameTests {
    private DiffuserAssistGameTests() {}

    /** High enough above the template's own floor that every drop-based fixture here stays airborne for its
     * whole observation window (no landing, so no dependence on the landing tick -- see the comment block
     * at the end of this class for why that matters), and the same height the pre-existing inverted-car
     * fixture already used. */
    private static final BlockPos DROP_SPAWN = new BlockPos(5, 12, 5);
    /** Where a fixture that needs a real floor puts one, and the spawn just above it. */
    private static final BlockPos FLOOR_BLOCK = new BlockPos(5, 2, 5);
    private static final BlockPos ON_FLOOR_SPAWN = new BlockPos(5, 3, 5);

    /** Shared tuning for every direction-only fixture: the same moderate bound/gain/damping triple across
     * all four assists, so one assist's test is not quietly stronger than another's. Chosen around what the
     * roll fixture needed when it was first built (4,000 N*m was enough to recover a fully inverted car);
     * well inside {@link DiffuserTuningLimits}' own range, and these fixtures only ever assert on the
     * DIRECTION of the response, so none of them is sensitive to the exact magnitude. */
    private static final double ASSIST_MAX_TORQUE = 8000.0;
    private static final double ASSIST_GAIN = 6000.0;
    private static final double ASSIST_DAMPING = 1200.0;
    /** Deliberately far longer than any fall in these fixtures, so R17's authority ramp is never the thing
     * under test here -- {@link DiffuserAssistMathTest} already covers the ramp itself. */
    private static final double PITCH_LANDING_HORIZON_SECONDS = 30.0;
    /** Below the speed these fixtures seed, so the speed gate is satisfied rather than exercised. */
    private static final double TRAJECTORY_MIN_SPEED = 1.0;

    /** Starting tilt for the pitch/roll direction fixtures: far enough off level to be unambiguous, far
     * enough from &plusmn;&pi;/2 (gimbal) and &plusmn;&pi; (the roll equilibrium) to avoid either. */
    private static final float START_TILT_RAD = 0.3f;
    /** How far off exactly &pi; the inverted fixture starts -- see {@link #startUpsideDown}. */
    private static final double INVERTED_START_OFFSET_RAD = 0.2;
    /** Heading alignment's seeded target, degrees off the car's own spawn yaw. */
    private static final double HEADING_TARGET_OFFSET_DEG = 10.0;
    /** Horizontal speed (m/s) seeded into the body for the trajectory fixtures. */
    private static final double SEEDED_SPEED = 5.0;

    /** The template every fixture here uses -- a largely open test structure, so a dropped car falls freely
     * unless the fixture itself places a floor under it. */
    private static final String TEMPLATE = "settlement_test";
    /** GameTest timeouts: comfortably above each fixture's own scheduled work, never load-bearing for any
     * assertion (nothing here is keyed to a deadline, only to its own observation window). */
    private static final int DIRECTION_TIMEOUT_TICKS = 100;
    private static final int INVERTED_TIMEOUT_TICKS = 200;
    private static final int TELEMETRY_TIMEOUT_TICKS = 20;

    /** One tick, the point at which a spawned car's Sable body exists and can be read or seeded. */
    private static final long BODY_EXISTS_TICK = 1L;
    /** Observation window for a direction-only check: long enough for a real bounded torque to move the
     * angle measurably, short enough that a drop from {@link #DROP_SPAWN} is still airborne throughout. */
    private static final long OBSERVE_TICKS = 15L;
    /** Window for the inverted no-op fixture, which deliberately runs long enough to land. */
    private static final long INVERTED_OBSERVE_TICKS = 150L;
    /** Long enough for the telemetry line to have been produced for a real, ticking body. */
    private static final long TELEMETRY_TICKS = 2L;

    /** Minimum starting tilt a fixture must actually have before its assertion means anything. */
    private static final double MIN_START_TILT_RAD = 0.2;
    /** Minimum tilt reduction (radians) that counts as "moved toward level", comfortably above per-tick
     * numeric noise while far below the full recovery these assists eventually produce. */
    private static final double MIN_TILT_RECOVERY_RAD = 0.02;
    /** Minimum yaw-error reduction (degrees) that counts as "turned toward the target". */
    private static final double MIN_YAW_RECOVERY_DEG = 0.5;
    /** Minimum initial yaw/travel gap for a trajectory fixture to be a meaningful test at all. */
    private static final double MIN_MEANINGFUL_GAP_DEG = 30.0;
    /** How inverted a car with the assist DISABLED must still be, to prove it did not self-right. */
    private static final double STILL_INVERTED_ROLL_RAD = 1.5;

    /** Negligible forced throttle, held for the fixture's whole duration, purely to keep the body awake --
     * see {@link #startUpsideDown} for why this is necessary. */
    private static final double KEEP_AWAKE_THROTTLE = 0.001;
    private static final int KEEP_AWAKE_TICKS = 999;

    /** Roll angle (radians, 0 = upright, &plusmn;&pi; = inverted) of a car's current real Sable body
     * orientation, using exactly the same {@code atan2(right.y, up.y)} formula
     * {@code SableCarBody.applyDiffuserAssists} itself uses -- so this is checking the real production
     * formula's real output, not a parallel reimplementation that could independently agree with a bug. */
    private static double rollAngleRad(CarEntity car) {
        Quaternionf orientation = car.currentOrientationForTest();
        Vector3f right = orientation.transform(new Vector3f(1, 0, 0));
        Vector3f up = orientation.transform(new Vector3f(0, 1, 0));
        return Math.atan2(right.y(), up.y());
    }

    /** Spawns {@code car}, forces its real Sable body to start upside down (roll &asymp; &pi;, deliberately
     * NOT exactly &pi;: see below) via the same save/load NBT orientation path
     * {@link NewVehicleGameTests#vehicleNbtRoundTripPreservesPositionAndRotation} exercises, applied to
     * this SAME entity before its first tick (while {@code sableBody} is still null, so
     * {@code SableCarBody.create} reads this as the body's actual starting pose) rather than a fresh one.
     *
     * <p>Offset 0.5 rad off exactly &pi;, not exactly &pi;: {@link DiffuserAssistMathTest#exactlyUpsideDownIsAnUnstableEquilibriumWithNoSpuriousKick}
     * already proves {@code rollSelfRightingTorque} returns EXACTLY zero at the true inverted equilibrium
     * (by design -- a real restoring force must vanish there too, or it is not actually an equilibrium).
     * A first version of this fixture used exactly {@code Math.PI} with zero initial angular velocity and
     * a perfectly symmetric drop straight onto flat ground with no yaw: that landed precisely on the
     * degenerate equilibrium and (correctly, per the math) never recovered -- not a bug in the assist, a
     * bug in a test fixture unrealistic enough to hit a measure-zero point exactly. Offsetting the start
     * angle is the fix, not loosening the recovery assertion below. */
    private static void startUpsideDown(CarEntity car) {
        CompoundTag tag = new CompoundTag();
        car.save(tag);
        Quaternionf upsideDown = new Quaternionf().rotateZ((float) (Math.PI - INVERTED_START_OFFSET_RAD));
        tag.putFloat("OrientationX", upsideDown.x);
        tag.putFloat("OrientationY", upsideDown.y);
        tag.putFloat("OrientationZ", upsideDown.z);
        tag.putFloat("OrientationW", upsideDown.w);
        car.load(tag);
        // A car with no rider and no forced input never calls box.wakeUp() (SableCarBody.tick only does
        // so when touching/throttle/steer/handbrake is nonzero) while airborne over open ground -- observed
        // directly: without this, the body's own Y, speed and roll stay frozen bit-for-bit tick after tick,
        // meaning Sable's own gravity/impulse integration never actually runs for it at all, independent of
        // anything this ticket's own math does. A single forced tick was NOT enough (the body appears to
        // need the wakeUp condition to keep being true, not just a one-time nudge); keeping a negligible
        // forced throttle (2.3 N per wheel at this magnitude, versus thousands of N of suspension/gravity
        // force -- not enough to meaningfully drive the car even once grounded) asserted for the test's
        // entire duration guarantees a real, continuously-awake simulation instead.
        car.forceDrive(KEEP_AWAKE_THROTTLE, 0.0, KEEP_AWAKE_TICKS);
    }

    /** Disabled-is-truly-inactive, on a REAL server (the acceptance criteria's own explicit requirement,
     * not just {@link DiffuserAssistMathTest}'s pure-function coverage of the same claim): an upside-down
     * car with {@link VehicleSpec.DiffuserTuning#IDENTITY} (what every existing car actually ships) must
     * stay substantially inverted after real ticks, falling and landing however gravity/collision alone
     * take it. This is also the regression guarantee for every existing car: since no car's own stock spec
     * differs from this tuning, this is exactly their own real-server behavior, unchanged. */
    @GameTest(template = TEMPLATE, timeoutTicks = INVERTED_TIMEOUT_TICKS)
    public static void identityDiffuserTuningLeavesAnInvertedCarInverted(GameTestHelper helper) {
        helper.setBlock(FLOOR_BLOCK, Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), DROP_SPAWN);
        // No setDiffuserTuningForTest call: this car's diffuserTuning() is exactly spec.diffuserTuning(),
        // VehicleSpec.CAR's own IDENTITY -- the same value every existing car ships today.
        startUpsideDown(car);
        helper.runAfterDelay(INVERTED_OBSERVE_TICKS, () -> {
            double roll = rollAngleRad(car);
            helper.assertTrue(Math.abs(roll) > STILL_INVERTED_ROLL_RAD,
                    "identity Diffuser tuning should not self-right an inverted car, but roll recovered to " + roll + " rad");
            helper.succeed();
        });
    }

    /** Wraps a degree difference to {@code (-180, 180]}. */
    private static double wrapDeg(double deg) {
        double wrapped = deg % 360.0;
        if (wrapped <= -180.0) {
            wrapped += 360.0;
        } else if (wrapped > 180.0) {
            wrapped -= 360.0;
        }
        return wrapped;
    }

    /** Direction-only regression for PR #180 review comment 6278664198's heading/trajectory sign defect:
     * {@code SableCarBody.applyDiffuserAssists} built its "error" entirely out of Minecraft yaw degrees but
     * applied the resulting torque about the PHYSICAL world +Y axis -- and {@code yawOf}/{@code
     * orientationOf}'s own documented convention (physical rotation = {@code -yaw}) means a naive
     * target-minus-current error fed straight into a {@code +gain*error} term pushes the PHYSICAL angle,
     * and so the actual Minecraft yaw, AWAY from the target, not toward it. This is exactly the review's own
     * worked example: current yaw 0, target +10 degrees, zero angular velocity, zero steer.
     *
     * <p>Deliberately a CLEAN single-axis case, unlike the matched-pair "launched mid-turn" fixture this
     * ticket's own R18/R19 GameTests attempted and had to defer (see the comment block at the end of this
     * class): a car dropped from height with zero initial angular velocity and no ground contact for the
     * whole test window has nothing ELSE perturbing its orientation, so there is no 3-axis-tumble chaos
     * risk here -- only a direction check, not a magnitude or exact-tick assertion, so it does not need one
     * either. {@link CarEntity#setLastGroundedYawDegForTest} seeds the target directly (this assist
     * normally only acquires one from a prior grounded tick), avoiding the need to first land and relaunch
     * the car just to set up the test.
     *
     * <p>Run for BOTH signs of the target offset (the review's own explicit ask): a single-sign direction
     * test cannot distinguish a correctly-signed controller from one that happens to push the right way for
     * one sign only -- e.g. a term that ignores the error's sign entirely, or a wrap-around defect that only
     * bites on one side of the convention. */
    private static void assertHeadingTurnsTowardTarget(GameTestHelper helper, double targetOffsetDeg) {
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), DROP_SPAWN);
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            double startYaw = car.getYRot();
            double targetYaw = startYaw + targetOffsetDeg;
            car.setDiffuserTuningForTest(headingOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING));
            car.setLastGroundedYawDegForTest(targetYaw);
            double errorBefore = Math.abs(wrapDeg(targetYaw - startYaw));
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double errorAfter = Math.abs(wrapDeg(targetYaw - car.getYRot()));
                helper.assertTrue(errorAfter < errorBefore - MIN_YAW_RECOVERY_DEG,
                        "heading alignment should have turned the car toward its " + targetOffsetDeg
                                + " degree target, not held still or turned away from it: errorBefore="
                                + errorBefore + " errorAfter=" + errorAfter);
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void headingAlignmentTurnsTowardATargetAheadOfCurrentYawNotAwayFromIt(GameTestHelper helper) {
        assertHeadingTurnsTowardTarget(helper, HEADING_TARGET_OFFSET_DEG);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void headingAlignmentTurnsTowardATargetBehindCurrentYawNotAwayFromIt(GameTestHelper helper) {
        assertHeadingTurnsTowardTarget(helper, -HEADING_TARGET_OFFSET_DEG);
    }

    private static VehicleSpec.DiffuserTuning headingOnly(double maxTorque, double gain, double damping) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0,
                true, maxTorque, gain, damping, 1.0, false, 0, 0, 0, 0);
    }

    private static VehicleSpec.DiffuserTuning trajectoryOnly(double maxTorque, double gain, double damping, double minSpeed) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0,
                false, 0, 0, 0, 0, true, maxTorque, gain, damping, minSpeed);
    }

    /** Direction-only regression for PR #180 review comment 6278664198's heading/trajectory sign defect,
     * R19's own half: same reasoning as {@link #headingAlignmentTurnsTowardATargetAheadOfCurrentYawNotAwayFromIt}
     * above, but for trajectory alignment's own target (the car's real horizontal velocity direction, not a
     * frozen heading). Earlier attempts at an R19 GameTest used a "launched mid-turn" fixture (grounded
     * driving, then the floor pulled out) that turned out to carry real 3-axis-tumble risk unrelated to the
     * sign bug itself (see the retired comment this replaces, kept in git history); this version sidesteps
     * that risk entirely by using {@link CarEntity#addLinearVelocityForTest} to give the body real, known
     * horizontal velocity directly -- no grounded phase, no tire slip, no turning, so nothing perturbs roll
     * or pitch at all. The car's spawn yaw and the velocity's own direction are essentially guaranteed to
     * differ (an arbitrary horizontal vector has no reason to line up with the entity's default facing), so
     * there is a real, known gap for the assist to close from the very first active tick. Run for velocity
     * on BOTH sides of the car's own facing, for the same reason the heading pair above does. */
    private static void assertTrajectoryTurnsTowardVelocity(GameTestHelper helper, double vx) {
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), DROP_SPAWN);
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            car.addLinearVelocityForTest(vx, 0.0, 0.0);
            car.setDiffuserTuningForTest(trajectoryOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, TRAJECTORY_MIN_SPEED));
            double travelYawDeg = Math.toDegrees(Math.atan2(-vx, 0.0));
            double errorBefore = Math.abs(wrapDeg(travelYawDeg - car.getYRot()));
            helper.assertTrue(errorBefore > MIN_MEANINGFUL_GAP_DEG,
                    "test setup's spawn yaw happened to already nearly match the seeded velocity direction -- "
                            + "not a meaningful gap for this assist to close: errorBefore=" + errorBefore);
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double errorAfter = Math.abs(wrapDeg(travelYawDeg - car.getYRot()));
                helper.assertTrue(errorAfter < errorBefore - MIN_YAW_RECOVERY_DEG,
                        "trajectory alignment should have turned the car's nose toward its own real velocity "
                                + "direction (vx=" + vx + "), not held still or turned away from it: errorBefore="
                                + errorBefore + " errorAfter=" + errorAfter);
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void trajectoryAlignmentTurnsNoseTowardRealVelocityDirectionNotAwayFromIt(GameTestHelper helper) {
        assertTrajectoryTurnsTowardVelocity(helper, SEEDED_SPEED);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void trajectoryAlignmentTurnsNoseTowardVelocityOnTheOtherSideToo(GameTestHelper helper) {
        assertTrajectoryTurnsTowardVelocity(helper, -SEEDED_SPEED);
    }

    /** Pitch angle (radians, 0 = level, positive = nose up) of a car's current real Sable body orientation,
     * exactly the same {@code asin(forward.y)} formula {@code SableCarBody.applyDiffuserAssists} itself
     * uses. */
    private static double pitchAngleRad(CarEntity car) {
        Quaternionf orientation = car.currentOrientationForTest();
        Vector3f forward = orientation.transform(new Vector3f(0, 0, 1));
        return Math.asin(Math.max(-1f, Math.min(1f, forward.y())));
    }

    /** Forces {@code car}'s real Sable body to start at {@code tilted} (callers pass a modest single-axis
     * rotation -- {@code rotateX} for pitch, {@code rotateZ} for roll -- well clear of both level and any
     * &plusmn;&pi;/2 gimbal or &plusmn;&pi; equilibrium extreme) via the same before-first-tick NBT
     * orientation path {@link #startUpsideDown} uses, and keeps it continuously awake the same way (see
     * that method's own javadoc for why the forced throttle is necessary). */
    private static void startTilted(CarEntity car, Quaternionf tilted) {
        CompoundTag tag = new CompoundTag();
        car.save(tag);
        tag.putFloat("OrientationX", tilted.x);
        tag.putFloat("OrientationY", tilted.y);
        tag.putFloat("OrientationZ", tilted.z);
        tag.putFloat("OrientationW", tilted.w);
        car.load(tag);
        car.forceDrive(KEEP_AWAKE_THROTTLE, 0.0, KEEP_AWAKE_TICKS);
    }

    private static VehicleSpec.DiffuserTuning pitchOnly(double maxTorque, double horizonSeconds, double gain, double damping) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, true, maxTorque, horizonSeconds, gain, damping,
                false, 0, 0, 0, 0, false, 0, 0, 0, 0);
    }

    /** Direction-only regression for PR #180 review comment 6278664198's pitch sign defect: same reasoning
     * as {@link #headingAlignmentTurnsTowardATargetAheadOfCurrentYawNotAwayFromIt} above, but this
     * ticket's own earlier R17 GameTest attempts (see the comment block at the end of this class) landed on
     * a DIFFERENT problem entirely -- a car dropped from height genuinely does not touch down at a
     * deterministic tick in this environment, so any assertion keyed to the moment of landing (or a fixed
     * tick meant to approximate it) was never reliable, sign bug or not. This version never asserts on
     * landing at all: a car falling from height, with a generous {@code pitchLandingHorizonSeconds} (so the
     * authority ramp is not itself gated on being close to the ground), only needs to check that its pitch
     * magnitude is trending back toward level over a short window -- true in the air long before any
     * landing-tick question comes up. Run nose-up AND nose-down, for the same reason the heading/trajectory
     * pairs above are run on both sides. */
    private static void assertPitchReturnsTowardLevel(GameTestHelper helper, float startPitchRad) {
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), DROP_SPAWN);
        car.setDiffuserTuningForTest(pitchOnly(ASSIST_MAX_TORQUE, PITCH_LANDING_HORIZON_SECONDS, ASSIST_GAIN, ASSIST_DAMPING));
        startTilted(car, new Quaternionf().rotateX(startPitchRad));
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            double pitchBefore = Math.abs(pitchAngleRad(car));
            helper.assertTrue(pitchBefore > MIN_START_TILT_RAD,
                    "sanity: car did not actually start pitched (" + startPitchRad + " rad), pitch=" + pitchAngleRad(car));
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double pitchAfter = Math.abs(pitchAngleRad(car));
                helper.assertTrue(pitchAfter < pitchBefore - MIN_TILT_RECOVERY_RAD,
                        "pitch landing help should have turned the car back toward level, not held still or "
                                + "tipped further: pitchBefore=" + pitchBefore + " pitchAfter=" + pitchAfter);
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void pitchLandingTurnsTowardLevelNotAwayFromIt(GameTestHelper helper) {
        assertPitchReturnsTowardLevel(helper, START_TILT_RAD);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void pitchLandingTurnsTowardLevelFromNoseDownToo(GameTestHelper helper) {
        assertPitchReturnsTowardLevel(helper, -START_TILT_RAD);
    }

    private static VehicleSpec.DiffuserTuning rollOnly(double maxTorque, double gain, double damping) {
        return new VehicleSpec.DiffuserTuning(true, maxTorque, gain, damping, false, 0, 0, 0, 0,
                false, 0, 0, 0, 0, false, 0, 0, 0, 0);
    }

    /** R16 (roll self-righting), the assist this ticket's acceptance criteria leads with -- covered here as
     * a direction-only check for the same reason {@link #pitchLandingTurnsTowardLevelNotAwayFromIt} is.
     *
     * <p>This is NOT the full "upside-down car recovers and lands upright" scenario: that fixture
     * (documented in the comment block at the end of this class) really did fail 3 times in 7 runs by
     * getting captured near the roll=&pi; unstable equilibrium during a genuine 3-axis tumble, and nothing
     * here claims to have solved that. What this does cover is the part that failure mode obscured and that
     * no production-path test covered at all: a car banked a modest amount off upright, in clean single-axis
     * freefall with zero initial angular velocity, must have its real Sable body rolled back TOWARD upright
     * by the real assist -- a bank angle nowhere near the degenerate point, so there is no capture risk, and
     * no landing in the window, so there is no landing-tick question either. Both roll directions, same
     * reasoning as the heading/pitch/trajectory pairs above. */
    private static void assertRollReturnsTowardUpright(GameTestHelper helper, float startRollRad) {
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), DROP_SPAWN);
        car.setDiffuserTuningForTest(rollOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING));
        startTilted(car, new Quaternionf().rotateZ(startRollRad));
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            double rollBefore = Math.abs(rollAngleRad(car));
            helper.assertTrue(rollBefore > MIN_START_TILT_RAD,
                    "sanity: car did not actually start banked (" + startRollRad + " rad), roll=" + rollAngleRad(car));
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double rollAfter = Math.abs(rollAngleRad(car));
                helper.assertTrue(rollAfter < rollBefore - MIN_TILT_RECOVERY_RAD,
                        "roll self-righting should have rolled the car back toward upright, not held still or "
                                + "banked further: rollBefore=" + rollBefore + " rollAfter=" + rollAfter);
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void rollSelfRightingRollsBackTowardUprightNotFurtherOver(GameTestHelper helper) {
        assertRollReturnsTowardUpright(helper, START_TILT_RAD);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void rollSelfRightingRollsBackTowardUprightFromTheOtherSideToo(GameTestHelper helper) {
        assertRollReturnsTowardUpright(helper, -START_TILT_RAD);
    }

    /** Telemetry (open item from the ticket's own acceptance criteria): {@code SableCarBody.describe()}'s
     * debug line (logged every 20 ticks by {@code CarEntity.tickSable}, the same line every other car's
     * debug logging already goes through) must report each Diffuser KIT assist's own last-applied torque
     * under its own clearly-labelled key, so an observed moment can be attributed to the system that
     * produced it rather than read off one combined number -- see {@code describe()}'s own javadoc for the
     * three distinct quantities in play (these artificial diffuser corrections, MINECRAFT-446's genuine
     * aerodynamic forces and moments, and MINECRAFT-184's separate tire-grip multiplier). This only checks the
     * format is actually wired end-to-end (the real keys appear in the real production string for a real
     * car), not specific numeric values -- deliberately, after this ticket's other GameTests here found
     * that asserting on an EXACT post-launch/post-tumble number in this environment is not reliable; a
     * stationary car on flat ground (no drop, no launch, no pit) has no such risk. */
    @GameTest(template = TEMPLATE, timeoutTicks = TELEMETRY_TIMEOUT_TICKS)
    public static void describeReportsEachDiffuserAssistsOwnTorqueUnderItsOwnKey(GameTestHelper helper) {
        helper.setBlock(FLOOR_BLOCK, Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), ON_FLOOR_SPAWN);
        car.setDiffuserTuningForTest(VehicleSpec.DiffuserTuning.IDENTITY);
        helper.runAfterDelay(TELEMETRY_TICKS, () -> {
            String description = SableCompat.describe(car.sableBody(), CarPhysics.Gear.AUTOMATIC, 0.0);
            for (String key : new String[] {"diffuserRoll=", "diffuserPitch=", "diffuserHeading=", "diffuserTrajectory="}) {
                helper.assertTrue(description.contains(key),
                        "describe() telemetry is missing its own \"" + key + "\" key, saw: " + description);
            }
            helper.succeed();
        });
    }

    // CORRECTED DIAGNOSIS (PR #180 review comments 6278664198/6278957190): every "run-to-run physics
    // divergence"/"solver nondeterminism" conclusion this class used to draw for R17/R18/R19 was wrong, or
    // at least unproven -- a real coordinate-convention bug (SableCarBody.applyDiffuserAssists built its
    // heading/trajectory error and pitch angle out of MEASURED Minecraft yaw/pitch, but applied the
    // resulting torque about the PHYSICAL axis the engine rotates around, which is the NEGATION of the
    // measured quantity per yawOf/orientationOf's own documented convention) was sitting underneath all
    // three. Fixed in applyDiffuserAssists itself (see its own review-fix comments); R17/R18/R19 each now
    // have a direction-only regression above built around that fix. R16 (roll) was re-examined given this
    // and is NOT affected: it reads and applies torque through the same live orientation object directly,
    // with no yaw-convention round-trip for the bug to hide in.
    //
    // What each earlier attempt actually ran into, now that the sign bug is fixed and no longer a candidate
    // explanation:
    //
    // R16 (roll self-righting): a `rollSelfRightingRecoversFromUpsideDownBeforeLanding` fixture (full
    // upside-down recovery, landing included) failed 3 times in 7 full ./gradlew runGameTestServer runs,
    // every time with the final roll landing within ~0.001 rad of exactly +-pi -- the roll=pi unstable
    // equilibrium DiffuserAssistMath.rollSelfRightingTorque correctly returns zero restoring torque at.
    // Since roll's own code path has no sign defect (see above), this still stands as what it looked like
    // at the time: genuine chaotic sensitivity in a real 3-axis tumble occasionally coasting into the one
    // point a correctly-signed restoring force cannot (and should not) pull away from, not a bug in this
    // ticket's own code. Ruled out directly, not guessed: widening the landing pad 1x1 to 7x7, and a fully
    // fresh run/world before a run -- neither changed the failure signature. No GameTest for the full
    // upside-down-to-landed recovery is included here for that reason. What IS covered, by
    // rollSelfRightingRollsBackTowardUpright* above, is the part that failure mode was obscuring: a modest
    // bank angle (nowhere near the degenerate point) in clean single-axis freefall really is rolled back
    // toward upright by the real assist, both directions. A reliable FULL-recovery production check would
    // still need either a non-chaotic tumble fixture (a 2D-only drop with no yaw/pitch coupling, if this
    // engine's wrapper exposes one) or a verified deterministic-stepping solver configuration this repo
    // does not currently have a call site for.
    //
    // R17 (pitch/landing): over a dozen runs with several fixture designs, the actual TICK at which a
    // pitched car's wheels first contacted ground varied run to run (observed anywhere from tick ~6 to past
    // tick ~20), which an assertion keyed to the landing moment (or a fixed tick meant to approximate it)
    // could not reliably exercise. Whether the earlier (wrongly-signed) pitch controller's own erratic
    // behavior was contributing to that tick variance, independent of genuine landing-timing variance that
    // would exist regardless, was not and is not disentangled here -- this class now sidesteps the question
    // entirely rather than resolve it: pitchLandingTurnsTowardLevelNotAwayFromIt above asserts only a
    // direction trend in the air, never on contact or a specific tick.
    //
    // R18 (heading alignment) and R19 (trajectory alignment): an earlier "launched mid-turn" fixture (two
    // cars driven with the same sustained throttle+steer, then the ground pulled out from under both at
    // once so they go airborne carrying real yaw rate built up from an actual turn) found R19 coming out
    // WORSE than its disabled control, more so at higher gain -- which is now understood to be exactly the
    // sign defect, not solver noise (a flipped P-term gets worse, not better, as gain increases). R18 in
    // that same fixture was inconsistent run to run in a way not yet explained (worth re-attempting now
    // that the sign is fixed, if the matched-pair fixture itself is ever needed again); this class instead
    // moved both R18 and R19 to the much lower-risk direction-only single-axis regressions above
    // (CarEntity#setLastGroundedYawDegForTest / #addLinearVelocityForTest seed the target/velocity directly,
    // with zero initial angular velocity and no grounded driving phase, so nothing perturbs roll or pitch).
    // A real GameTestHelper API bug was found and fixed while building that earlier fixture, worth keeping
    // in mind for anyone touching block placement in a GameTest: setBlock takes template-RELATIVE
    // coordinates and translates internally via absolutePos/relativePos (which also account for the
    // structure's own per-run rotation); passing an ABSOLUTE position (e.g. CarEntity#blockPosition)
    // straight to setBlock compiles and runs with no error but silently clears the wrong patch of the world.
}
