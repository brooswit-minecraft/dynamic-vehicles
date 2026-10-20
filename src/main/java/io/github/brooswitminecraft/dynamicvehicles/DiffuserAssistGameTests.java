package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
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
    /** Beyond this the car is tumbling rather than merely coupling a little off-axis: 45 degrees is far
     * past any cross-axis bleed a bounded yaw torque can produce, and far short of a flip. */
    private static final double NOT_TUMBLING_RAD = Math.toRadians(45.0);
    /** How inverted a car with the assist DISABLED must still be, to prove it did not self-right. */
    private static final double STILL_INVERTED_ROLL_RAD = 1.5;

    /** Negligible forced throttle, held for the fixture's whole duration, purely to keep the body awake --
     * see {@link #startUpsideDown} for why this is necessary. Sized to cover the LONGEST fixture here
     * ({@link #INVERTED_OBSERVE_TICKS}) with margin and no more: an over-long window used to leave cars
     * force-driving for ~50s, long after their own test had finished, inside a GameTest level shared with
     * every other test in the run. */
    private static final double KEEP_AWAKE_THROTTLE = 0.001;
    private static final int KEEP_AWAKE_TICKS = (int) INVERTED_OBSERVE_TICKS + 10;

    /** Spawns a car for a fixture AND registers its cleanup in the same call, so no fixture here can own a
     * car without also owning its disposal.
     *
     * <p>Not optional hygiene. These fixtures deliberately drop cars with no floor beneath them, so the car
     * is still falling when the test ends and has long since left its own structure's bounding box -- which
     * is exactly the region GameTest's own post-test cleanup covers, so nothing reclaims it. Left alone it
     * keeps ticking real physics in the level EVERY other test in the run shares, for as long as the run
     * lasts.
     *
     * <p>Cleanup is registered as a {@link GameTestListener} rather than run inline at the end of each
     * fixture (review, PR #180): an inline call is only reached once every assertion has already passed, so
     * a FAILING assertion or a timeout would skip it and leak the car precisely in the case where a polluted
     * shared level does the most damage -- a failing run going on to disturb later tests. {@code testFailed}
     * covers both of those (the framework reports a timeout through it), and discarding an entity cannot
     * mask the failure: the result is already recorded by the time a listener runs, and this only removes
     * the car. Discarding routes through {@code CarEntity#remove}, which releases the Sable body too. */
    private static CarEntity spawnCarWithCleanup(GameTestHelper helper, BlockPos at) {
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), at);
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {
            }

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                car.discard();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                car.discard();
            }

            @Override
            public void testAddedForRerun(GameTestInfo previous, GameTestInfo next, GameTestRunner runner) {
            }
        });
        return car;
    }

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
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
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
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
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
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
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

    /** Full player steering input, for the suppression case below. */
    private static final double FULL_STEER = 1.0;
    /** Suppression of 1.0 means full steer hands the assist's whole authority back to the player. */
    private static final double FULL_SUPPRESSION = 1.0;
    /** With the assist fully suppressed the yaw error must not close by even this much -- the same
     * threshold the un-suppressed fixtures must EXCEED, so the two assertions cannot both hold. */
    private static final double SUPPRESSED_TOLERANCE_DEG = MIN_YAW_RECOVERY_DEG;

    private static VehicleSpec.DiffuserTuning headingOnlyWithSuppression(double suppression) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0,
                true, ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, suppression, false, 0, 0, 0, 0);
    }

    private static VehicleSpec.DiffuserTuning headingAndTrajectory() {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0,
                true, ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, FULL_SUPPRESSION,
                true, ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, TRAJECTORY_MIN_SPEED);
    }

    /** R18's own player-authority rule, on the production path: steering gives the player their authority
     * back. Same clean single-axis fixture as the heading direction pair, with the ONLY change being full
     * steer held against a tuning whose {@code headingAlignmentSteerSuppression} is 1.0.
     *
     * <p>This fixture is unusually clean for exactly the reason that makes it worth having: while airborne,
     * a steering input has no physical effect whatsoever (steer turns a car through tire forces, and no
     * wheel is touching anything here), so the ONLY route by which steer can change the outcome is
     * {@code DiffuserAssistMath.headingAlignmentTorque}'s own suppression term. Any difference observed
     * against {@link #headingAlignmentTurnsTowardATargetAheadOfCurrentYawNotAwayFromIt}, which is identical
     * but for the steer, is attributable to suppression and nothing else. */
    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void fullSteerSuppressesHeadingAlignmentEntirely(GameTestHelper helper) {
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            double startYaw = car.getYRot();
            double targetYaw = startYaw + HEADING_TARGET_OFFSET_DEG;
            car.setDiffuserTuningForTest(headingOnlyWithSuppression(FULL_SUPPRESSION));
            car.setLastGroundedYawDegForTest(targetYaw);
            car.forceDrive(0.0, FULL_STEER, (int) OBSERVE_TICKS + 2);
            double errorBefore = Math.abs(wrapDeg(targetYaw - startYaw));
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double errorAfter = Math.abs(wrapDeg(targetYaw - car.getYRot()));
                helper.assertTrue(errorAfter > errorBefore - SUPPRESSED_TOLERANCE_DEG,
                        "full steer against suppression 1.0 should have handed the player the assist's whole "
                                + "authority, leaving the heading error essentially untouched, but it closed from "
                                + errorBefore + " to " + errorAfter);
                helper.succeed();
            });
        });
    }

    /** MINECRAFT-395's explicit "do not collapse heading and trajectory into one behaviour" requirement,
     * verified on the production path rather than argued from the source.
     *
     * <p>The two assists are given targets on OPPOSITE sides of the car's own current yaw: heading is
     * seeded {@code +HEADING_TARGET_OFFSET_DEG} ahead, while the seeded velocity puts the travel direction
     * far to the other side. If the two had been collapsed into a single behaviour -- reading one target,
     * or summing into one error -- a car with only ONE of them enabled could not turn in opposite
     * directions depending on which one that is. Each is enabled alone, and the resulting signed yaw
     * changes are asserted to have opposite signs, which no single shared signal can produce. */
    private static void assertSingleAssistTurnsInItsOwnDirection(GameTestHelper helper, boolean heading) {
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            double startYaw = car.getYRot();
            car.addLinearVelocityForTest(SEEDED_SPEED, 0.0, 0.0);
            double travelYawDeg = Math.toDegrees(Math.atan2(-SEEDED_SPEED, 0.0));
            helper.assertTrue(wrapDeg(travelYawDeg - startYaw) < -MIN_MEANINGFUL_GAP_DEG,
                    "fixture assumes the seeded travel direction sits well to the NEGATIVE side of spawn yaw, "
                            + "opposite the +" + HEADING_TARGET_OFFSET_DEG + " heading target: travelYaw="
                            + travelYawDeg + " startYaw=" + startYaw);
            if (heading) {
                car.setDiffuserTuningForTest(headingOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING));
                car.setLastGroundedYawDegForTest(startYaw + HEADING_TARGET_OFFSET_DEG);
            } else {
                car.setDiffuserTuningForTest(
                        trajectoryOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, TRAJECTORY_MIN_SPEED));
            }
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double moved = wrapDeg(car.getYRot() - startYaw);
                if (heading) {
                    helper.assertTrue(moved > 0.0,
                            "heading alignment, alone, must turn toward its OWN +" + HEADING_TARGET_OFFSET_DEG
                                    + " target, not toward the velocity direction on the other side: moved=" + moved);
                } else {
                    helper.assertTrue(moved < 0.0,
                            "trajectory alignment, alone, must turn toward the velocity direction, not toward "
                                    + "heading's own target on the other side: moved=" + moved);
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void headingAloneTurnsTowardItsOwnTargetNotTheVelocityDirection(GameTestHelper helper) {
        assertSingleAssistTurnsInItsOwnDirection(helper, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void trajectoryAloneTurnsTowardVelocityNotHeadingsOwnTarget(GameTestHelper helper) {
        assertSingleAssistTurnsInItsOwnDirection(helper, false);
    }

    /** Two assists enabled at once stay bounded and keep producing finite, sane orientation -- the
     * combined-assist case. With heading and trajectory pulling toward targets on opposite sides, the
     * interesting property is not which one wins (that is just the larger bounded torque) but that the
     * result stays finite and inside the span the two targets bracket, rather than diverging or going NaN
     * the way an unbounded or double-counted sum would. */
    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void bothYawAssistsTogetherStayFiniteAndBounded(GameTestHelper helper) {
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            double startYaw = car.getYRot();
            car.addLinearVelocityForTest(SEEDED_SPEED, 0.0, 0.0);
            car.setDiffuserTuningForTest(headingAndTrajectory());
            car.setLastGroundedYawDegForTest(startYaw + HEADING_TARGET_OFFSET_DEG);
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double yaw = car.getYRot();
                double roll = rollAngleRad(car);
                double pitch = pitchAngleRad(car);
                helper.assertTrue(Double.isFinite(yaw) && Double.isFinite(roll) && Double.isFinite(pitch),
                        "two assists at once produced a non-finite orientation: yaw=" + yaw + " roll=" + roll
                                + " pitch=" + pitch);
                double moved = Math.abs(wrapDeg(yaw - startYaw));
                helper.assertTrue(moved <= 180.0,
                        "two assists at once drove the car past a half turn inside " + OBSERVE_TICKS
                                + " ticks, which no bounded pair of torques should: moved=" + moved);
                // Deliberately NOT asserting that roll and pitch are untouched. An earlier version did,
                // on the reasoning that a yaw-axis torque should produce yaw-axis motion only, and it
                // failed at roll=-0.216 rad. That assertion was wrong, not the implementation: a rigid
                // body with an asymmetric inertia tensor, already spinning about one axis, genuinely
                // couples into the others (Euler's equations), so some roll/pitch bleed is real physics
                // rather than the assists acting off-axis. What IS claimed, and what a regression here
                // would break, is that two bounded torques cannot tumble the car.
                helper.assertTrue(Math.abs(roll) < NOT_TUMBLING_RAD && Math.abs(pitch) < NOT_TUMBLING_RAD,
                        "two yaw assists together tumbled the car, which no pair of bounded torques should: "
                                + "roll=" + roll + " pitch=" + pitch);
                helper.succeed();
            });
        });
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
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
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
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
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

    /** Downward speed (m/s) seeded to make time-to-touchdown SHORT and deterministic, rather than waiting
     * for gravity to produce some particular fall speed at some particular tick -- the exact dependence on
     * landing timing that sank this ticket's earlier R17 attempts. */
    private static final double FAST_FALL_SPEED = 40.0;
    /** Horizon comfortably longer than the fast-fall case's own time-to-touchdown, so that case gets real
     * authority. The drop here is only about 7 m (measured, not assumed: these fixtures spawn near Y=-49.5
     * and the ground under them sits near Y=-56.6), so at {@link #FAST_FALL_SPEED} the estimate is roughly
     * 0.18 s -- well inside this. */
    private static final double LONG_HORIZON_SECONDS = 1.0;
    /** Horizon short enough that NO fall this fixture can produce reaches it: gravity alone needs about
     * 140 m/s over a 7 m drop to bring the estimate under this, which 15 ticks cannot approach. Picked this
     * way deliberately -- an earlier attempt used 1.0 s and the car simply fell close enough to the ground
     * for the ramp to engage late in the window, which is the assist working correctly, not a gate. */
    private static final double SHORT_HORIZON_SECONDS = 0.05;
    /** Short enough that the strong-authority case is sampled before it can overshoot through level: at
     * full authority this assist is meant to be decisive, so "did it move toward level" has to be read
     * before it arrives, not after. */
    private static final long PITCH_RAMP_OBSERVE_TICKS = 3L;
    /** Window for the gated case: long enough that an un-gated assist would visibly move the pitch, short
     * enough that the car is unambiguously still falling. The fixture also asserts it is still airborne at
     * the moment of measurement, because an earlier version ran 15 ticks, the car reached the ground, and
     * its SUSPENSION levelled the pitch -- which looked exactly like the gate failing when it was not. */
    private static final long GATED_OBSERVE_TICKS = 8L;

    /** R17's authority ramp on the production path: the assist is scaled by how close touchdown actually
     * is, so a car far from the ground (in time) must be left alone while a car about to land is corrected.
     *
     * <p>Time-to-touchdown is {@code height / |verticalSpeed|}, so seeding the vertical speed makes it
     * deterministic instead of a function of which tick the fixture happens to sample -- the exact
     * dependence on landing timing that made the earlier R17 attempts unreliable. Both cases drop from the
     * same height with the same tilt; only the seeded fall speed and the configured horizon differ, so
     * whether the assist acts is decided purely by the ramp this case exists to exercise. */
    private static void assertPitchAuthorityMatchesTimeToTouchdown(GameTestHelper helper,
            boolean expectCorrection) {
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
        car.setDiffuserTuningForTest(pitchOnly(ASSIST_MAX_TORQUE,
                expectCorrection ? LONG_HORIZON_SECONDS : SHORT_HORIZON_SECONDS, ASSIST_GAIN, ASSIST_DAMPING));
        // rotateX(-x) for a measured NOSE-UP pitch of +x: this engine's pitch rotation is the negation of
        // the measured asin(forward.y) angle -- the same convention the proportional-sign fix in
        // SableCarBody.applyDiffuserAssists turns on, applied here so the signed assertions below read the
        // way they say they do.
        startTilted(car, new Quaternionf().rotateX(-START_TILT_RAD));
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            if (expectCorrection) {
                car.addLinearVelocityForTest(0.0, -FAST_FALL_SPEED, 0.0);
            }
            // SIGNED, not magnitude: at full authority this assist can drive a +0.3 rad nose-up tilt down
            // through level and out the other side inside the window, and |pitch| cannot tell that apart
            // from the tilt growing. The signed value falling is "moved toward level" in both cases.
            double pitchBefore = pitchAngleRad(car);
            helper.assertTrue(pitchBefore > MIN_START_TILT_RAD,
                    "sanity: car did not actually start pitched nose-up, pitch=" + pitchBefore);
            helper.runAfterDelay(expectCorrection ? PITCH_RAMP_OBSERVE_TICKS : GATED_OBSERVE_TICKS, () -> {
                double pitchAfter = pitchAngleRad(car);
                if (expectCorrection) {
                    helper.assertTrue(pitchAfter < pitchBefore - MIN_TILT_RECOVERY_RAD,
                            "a short time-to-touchdown should give the assist authority and pitch the car "
                                    + "back toward level: pitchBefore=" + pitchBefore + " pitchAfter=" + pitchAfter);
                } else {
                    helper.assertTrue(!car.isTouchingGroundForTest(),
                            "fixture must still be airborne when it measures the gated case -- once the car "
                                    + "lands, its own suspension levels the pitch and that is not the gate");
                    helper.assertTrue(Math.abs(pitchAfter - pitchBefore) < MIN_TILT_RECOVERY_RAD,
                            "a time-to-touchdown far beyond the horizon must leave pitch alone, but it moved "
                                    + "from " + pitchBefore + " to " + pitchAfter);
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void shortTimeToTouchdownGivesPitchTheAuthorityToLevelTheCar(GameTestHelper helper) {
        assertPitchAuthorityMatchesTimeToTouchdown(helper, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void timeToTouchdownBeyondTheHorizonLeavesPitchAlone(GameTestHelper helper) {
        assertPitchAuthorityMatchesTimeToTouchdown(helper, false);
    }

    /** Half-width of the floor pad the contact-transition fixture stands its car on. A single 1x1 block
     * cannot support (or, removed, unsupport) a car whose four wheels span more than a block -- an earlier
     * version of this fixture placed one block, removed it, and found the car still grounded. */
    private static final int FLOOR_PAD_RADIUS = 3;
    /** The contact fixture's pad sits well ABOVE the template's own ground, and the car on top of it, so
     * that removing the pad actually leaves the car unsupported with room to fall. An earlier version put
     * the pad at the template's own floor level and removing it changed nothing: the car simply rested on
     * the ground that was always underneath. */
    private static final BlockPos CONTACT_PAD = new BlockPos(5, 9, 5);
    private static final BlockPos CONTACT_SPAWN = new BlockPos(5, 10, 5);

    private static void setFloorPad(GameTestHelper helper, net.minecraft.world.level.block.Block block) {
        for (int dx = -FLOOR_PAD_RADIUS; dx <= FLOOR_PAD_RADIUS; dx++) {
            for (int dz = -FLOOR_PAD_RADIUS; dz <= FLOOR_PAD_RADIUS; dz++) {
                helper.setBlock(CONTACT_PAD.offset(dx, 0, dz), block);
            }
        }
    }

    /** How far off its seeded target the contact-transition fixture puts the heading, big enough that a
     * working assist moves it unmistakably and a gated one cannot be mistaken for noise. */
    private static final double CONTACT_TARGET_OFFSET_DEG = 40.0;

    /** Contact transition, on the production path: R18's target is overwritten with the car's current yaw
     * on every grounded tick and frozen the moment the car leaves the ground, so the SAME seeded target
     * must do nothing while grounded and take effect once airborne.
     *
     * <p>That self-gating is why a parked car never fights its driver: while wheels are down the error is
     * zero by construction, so there is no torque to fight with. This exercises both sides of the
     * transition in one fixture, and deliberately uses a FIXED relative coordinate for the floor block
     * rather than converting the car's absolute position back to relative -- see this class's closing
     * comment on why {@code relativePos} cannot be trusted to invert {@code absolutePos}. */
    @GameTest(template = TEMPLATE, timeoutTicks = INVERTED_TIMEOUT_TICKS)
    public static void headingTargetIsGatedWhileGroundedAndLiveOnceAirborne(GameTestHelper helper) {
        setFloorPad(helper, Blocks.STONE);
        CarEntity car = spawnCarWithCleanup(helper, CONTACT_SPAWN);
        car.setDiffuserTuningForTest(headingOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING));
        helper.runAfterDelay(OBSERVE_TICKS, () -> {
            helper.assertTrue(car.isTouchingGroundForTest(),
                    "fixture needs the car actually resting on its floor block before the grounded phase");
            double groundedYaw = car.getYRot();
            car.setLastGroundedYawDegForTest(groundedYaw + CONTACT_TARGET_OFFSET_DEG);
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double yawAfterGrounded = car.getYRot();
                helper.assertTrue(Math.abs(wrapDeg(yawAfterGrounded - groundedYaw)) < MIN_YAW_RECOVERY_DEG,
                        "while grounded the target is overwritten with the car's own yaw every tick, so a "
                                + "seeded target must produce no turn at all, but yaw moved "
                                + wrapDeg(yawAfterGrounded - groundedYaw) + " degrees");
                // Same fixed relative coordinates that placed it; no absolute/relative round trip involved.
                setFloorPad(helper, Blocks.AIR);
                helper.runAfterDelay(OBSERVE_TICKS, () -> {
                    helper.assertTrue(!car.isTouchingGroundForTest(),
                            "fixture needs the car actually airborne after its floor was removed");
                    double airborneYaw = car.getYRot();
                    car.setLastGroundedYawDegForTest(airborneYaw + CONTACT_TARGET_OFFSET_DEG);
                    helper.runAfterDelay(OBSERVE_TICKS, () -> {
                        double moved = wrapDeg(car.getYRot() - airborneYaw);
                        helper.assertTrue(moved > MIN_YAW_RECOVERY_DEG,
                                "once airborne the frozen target must actually steer the car toward it, but "
                                        + "yaw moved only " + moved + " degrees");
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** How far off the pure reverse line the reverse fixtures slew the car: a real misalignment the assist
     * should straighten when the reverse is intentional, and must leave alone when it is a rollback --
     * small enough that it could never be confused with the ~180 a naive forward-only target would report
     * for the same pose. */
    private static final double REVERSE_SLEW_DEG = 20.0;

    /** Seeds real velocity along a given Minecraft yaw, using the same forward convention
     * {@code SableCarBody.yawOf} defines ({@code forward = (-sin(yaw), 0, cos(yaw))}). */
    private static void seedVelocityAlongYaw(CarEntity car, double yawDeg, double speed) {
        double radians = Math.toRadians(yawDeg);
        car.addLinearVelocityForTest(-Math.sin(radians) * speed, 0.0, Math.cos(radians) * speed);
    }

    /** R19 reverse coverage (MINECRAFT-447, review-scoped): a car genuinely travelling backwards must have
     * its reverse PRESERVED, not corrected away by a forced half-turn -- while a car rolling backwards
     * without having asked to is left alone entirely.
     *
     * <p>Both cases use the identical pose: velocity pointing behind the car and slewed
     * {@value #REVERSE_SLEW_DEG} degrees off the pure reverse line, so there is a real, small misalignment
     * present. Only the selected gear differs, which is exactly the distinction under test:
     * <ul>
     * <li>reverse selected -- an intentional reverse: the assist straightens that slew, turning the car
     *     toward its reverse line rather than spinning it around;</li>
     * <li>a forward gear -- a rollback: suppressed entirely, so the car keeps whatever heading it had.</li>
     * </ul>
     * A naive forward-only target would report a ~180 degree error for this pose and swing the car round in
     * BOTH cases, so either assertion failing that way is the regression this exists to catch.
     *
     * <p>These fixtures seat a real mock driver, which is not incidental: {@code CarEntity.tick} resets a
     * driverless car's gear to {@link CarPhysics.Gear#DEFAULT_FORWARD} on EVERY tick, so reverse can only
     * exist while someone is actually driving. An earlier version of this fixture selected reverse on an
     * empty car, had it silently reset before the next physics tick, and so ran the "intentional reverse"
     * case as a rollback -- which is also a fair statement of the product behaviour: a car rolling
     * backwards with nobody in it is a rollback, never an intentional reverse. */
    private static void assertReverseTravelIsHandled(GameTestHelper helper, boolean reverseSelected) {
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
        Player driver = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(driver.startRiding(car), "mock driver failed to board the car");
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            double startYaw = car.getYRot();
            // Must be re-selected with the driver aboard: see this method's javadoc on the driverless reset.
            car.selectGear(reverseSelected ? CarPhysics.Gear.REVERSE : CarPhysics.Gear.D1);
            seedVelocityAlongYaw(car, startYaw + 180.0 + REVERSE_SLEW_DEG, SEEDED_SPEED);
            car.setDiffuserTuningForTest(
                    trajectoryOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, TRAJECTORY_MIN_SPEED));
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double moved = wrapDeg(car.getYRot() - startYaw);
                // Either way the car must NOT be swung toward its velocity: that is the forced half-turn
                // this coverage exists to rule out.
                helper.assertTrue(Math.abs(moved) < 90.0,
                        "the assist swung a reversing car toward its own velocity instead of preserving the "
                                + "reverse: yaw moved " + moved + " degrees");
                if (reverseSelected) {
                    helper.assertTrue(moved > MIN_YAW_RECOVERY_DEG,
                            "an INTENTIONAL reverse should still have its " + REVERSE_SLEW_DEG
                                    + " degree slew straightened toward the reverse line, but yaw moved only "
                                    + moved + " degrees");
                } else {
                    helper.assertTrue(Math.abs(moved) < MIN_YAW_RECOVERY_DEG,
                            "a backward ROLLBACK in a forward gear must leave the car's heading alone, but "
                                    + "yaw moved " + moved + " degrees");
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void intentionalReverseIsStraightenedNotSpunAround(GameTestHelper helper) {
        assertReverseTravelIsHandled(helper, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void backwardRollbackWithoutReverseSelectedLeavesHeadingAlone(GameTestHelper helper) {
        assertReverseTravelIsHandled(helper, false);
    }

    /** A slow straight reverse: below any threshold a classifier might use, which is exactly the case
     * review round 2 found being handed the forward target and so a ~180 degree error. */
    private static final double SLOW_REVERSE_SPEED = 1.2;

    /** R19 reverse coverage, review round 2: a SLOW straight reverse must still be treated as a reverse.
     *
     * <p>This is the production-path half of the boundary problem. A stateless magnitude threshold put a
     * slowly-reversing car on the "forward" side, handed it the travel direction -- which is behind the
     * car -- and so asked for a half turn at full torque. The car here reverses in a straight line slewed
     * {@value #REVERSE_SLEW_DEG} degrees off its reverse line, slowly enough to sit below any such
     * threshold, and must be straightened toward that line rather than spun toward its velocity. */
    @GameTest(template = TEMPLATE, timeoutTicks = DIRECTION_TIMEOUT_TICKS)
    public static void aSlowIntentionalReverseIsStillStraightenedNotSpun(GameTestHelper helper) {
        CarEntity car = spawnCarWithCleanup(helper, DROP_SPAWN);
        Player driver = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(driver.startRiding(car), "mock driver failed to board the car");
        helper.runAfterDelay(BODY_EXISTS_TICK, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created on its first real tick");
            double startYaw = car.getYRot();
            car.selectGear(CarPhysics.Gear.REVERSE);
            seedVelocityAlongYaw(car, startYaw + 180.0 + REVERSE_SLEW_DEG, SLOW_REVERSE_SPEED);
            car.setDiffuserTuningForTest(
                    trajectoryOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING, TRAJECTORY_MIN_SPEED));
            helper.runAfterDelay(OBSERVE_TICKS, () -> {
                double moved = wrapDeg(car.getYRot() - startYaw);
                helper.assertTrue(Math.abs(moved) < 90.0,
                        "a slow reverse was swung toward its own velocity instead of being straightened: "
                                + "yaw moved " + moved + " degrees");
                helper.assertTrue(moved > MIN_YAW_RECOVERY_DEG,
                        "a slow intentional reverse should still be straightened toward its reverse line, "
                                + "but yaw moved only " + moved + " degrees");
                helper.succeed();
            });
        });
    }

    /** A one-block step under half the car's own track width (wheel mounts at x=+-0.8, see
     * {@code CarGeometry}), spanning the whole wheelbase in z: low on one side, high on the other. The
     * resulting static bank angle, atan(1 block / ~1.6 m track) ~= 32 degrees, is steep and permanent --
     * exactly the "steep surface" case, not a transient tilt suspension alone would absorb. */
    /** The X coordinate of the step itself: everything left of this is low, everything at or right of it
     * is high. The car spawns straddling it. */
    private static final int STEEP_SEAM_X = 5;
    private static final int STEEP_LOW_Y = 2;
    private static final int STEEP_HIGH_Y = 3;
    /** Wide margin on each side of the seam, not just enough to cover the stationary wheel footprint: a
     * narrower first version occasionally missed both platforms during landing (observed directly --
     * "not touching" at the end of the settle window run to run, inconsistent with the ~13-tick landing
     * time a 2-block drop implies, which points at a bounce/skid off a narrow platform's edge rather than
     * a timing margin). */
    private static final int STEEP_PLATFORM_HALF_SPAN = 4;
    private static final int STEEP_SPAWN_Z = 4;
    private static final BlockPos STEEP_SPAWN = new BlockPos(STEEP_SEAM_X, 6, STEEP_SPAWN_Z);
    /** Long enough for suspension to settle the car onto the step before anything is measured, with real
     * margin beyond the ~15-25 ticks a clean landing actually takes. */
    private static final long STEEP_SETTLE_TICKS = 80L;

    /** A single clean seam at {@link #STEEP_SEAM_X}: one block per (x, z), never two at the same x, so
     * there is no risk of one platform's own placement silently overwriting or stacking on the other's. */
    private static void buildSteepStep(GameTestHelper helper) {
        for (int x = STEEP_SEAM_X - STEEP_PLATFORM_HALF_SPAN; x < STEEP_SEAM_X + STEEP_PLATFORM_HALF_SPAN; x++) {
            int y = x < STEEP_SEAM_X ? STEEP_LOW_Y : STEEP_HIGH_Y;
            for (int dz = -STEEP_PLATFORM_HALF_SPAN; dz <= STEEP_PLATFORM_HALF_SPAN; dz++) {
                helper.setBlock(new BlockPos(x, y, STEEP_SPAWN_Z + dz), Blocks.STONE);
            }
        }
    }

    /** Steep-surface acceptance case (MINECRAFT-447 review fix): roll self-righting must NOT fight a car
     * correctly resting on a steep natural slope. {@code rollSelfRightingTorque}'s own javadoc already
     * documents this ("an assist that only makes sense while airborne... gated by the caller checking
     * contact state"), but {@code SableCarBody.applyDiffuserAssists} never actually gated its call on
     * {@code touching} -- found while covering this exact acceptance case, not asserted from the source.
     *
     * <p>A car settled on the step built by {@link #buildSteepStep} carries a real, large, PERMANENT bank
     * angle (confirmed non-trivial by the sanity check below) that is correct for that slope, not a
     * rollover to recover from. With the gate, the real {@code diffuserRoll} telemetry this ticket's own
     * acceptance criteria calls for must read exactly zero while the car is resting there, regardless of
     * how large that bank angle is -- the direct, production-path proof that the assist is inactive,
     * stronger than inferring it from the car simply not moving further. */
    @GameTest(template = TEMPLATE, timeoutTicks = INVERTED_TIMEOUT_TICKS)
    public static void rollSelfRightingDoesNotFightACarRestingOnASteepSlope(GameTestHelper helper) {
        buildSteepStep(helper);
        CarEntity car = spawnCarWithCleanup(helper, STEEP_SPAWN);
        // The step is built along world/relative X, so the car's own local left/right (its wheel mounts'
        // x-axis) must actually be aligned with that -- a car's default spawn yaw is not guaranteed to be
        // 0 (CarDrivetrainGameTests' own D1/D5 fixture sets this explicitly for the same reason). Without
        // it an earlier version of this fixture measured roll=0.0 for the entire window: the car's real
        // bank was there, just not about the axis this formula reads, because its local x did not line up
        // with the step's.
        car.setYRot(0.0f);
        car.setDiffuserTuningForTest(rollOnly(ASSIST_MAX_TORQUE, ASSIST_GAIN, ASSIST_DAMPING));
        helper.runAfterDelay(STEEP_SETTLE_TICKS, () -> {
            helper.assertTrue(car.isTouchingGroundForTest(),
                    "fixture needs the car actually settled on the steep step before asserting anything");
            double roll = rollAngleRad(car);
            helper.assertTrue(Math.abs(roll) > MIN_START_TILT_RAD,
                    "sanity: the step did not actually produce a real static bank angle, roll=" + roll);
            helper.assertTrue(car.debugDiffuserRollTorque() == 0.0,
                    "roll self-righting must be inactive for a car resting on a steep slope, but it applied "
                            + car.debugDiffuserRollTorque() + " N*m against a " + roll + " rad bank angle");
            helper.succeed();
        });
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
        CarEntity car = spawnCarWithCleanup(helper, ON_FLOOR_SPAWN);
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
    // A GameTestHelper coordinate trap was found while building that earlier fixture. The first half of
    // what this comment used to say was right: setBlock/getBlockState take template-RELATIVE coordinates,
    // so handing them an ABSOLUTE position (e.g. CarEntity#blockPosition) compiles, runs without error, and
    // silently acts on the wrong patch of the world.
    //
    // The second half was WRONG, and is corrected here rather than left to mislead: "convert back with
    // helper.relativePos" does NOT reliably fix it, because relativePos is not the inverse of absolutePos.
    // In vanilla (1.21.1):
    //     absolutePos(rel) = origin + R(rel)
    //     relativePos(abs) = R'(abs - origin),  where R' = R.getRotated(CLOCKWISE_180)
    // R' equals R-inverse only for the 90 and 270 degree cases. At rotation NONE -- the default these tests
    // actually run under -- R' is a 180 degree rotation, so relativePos NEGATES x and z instead of
    // inverting. Measured directly with a probe fixture rather than inferred from the source:
    //     rotation=NONE rel=(5,3,5) -> abs -> relativePos(abs)=(-5,3,-5), identity=false
    // That is why the earlier pit fixture appeared to be "fixed" by relativePos: it was clearing a region
    // mirrored about the structure origin, and only a 41x41 pit was wide enough to still overlap the car by
    // accident -- which is also why a 9x9 pit did not work. No live code in this class depends on it now
    // (that fixture is gone), but anything converting an absolute entity position back to relative should
    // keep its own positions in relative space, or invert absolutePos itself, rather than trust relativePos.
}
