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
 * <p>What this does NOT cover yet, same open gap already reported on the ticket: pitch/landing (time-to-
 * touchdown), heading alignment and trajectory alignment do not have their own GameTest here. Roll
 * self-righting was chosen first because it is the assist with the clearest, most observable real-server
 * signal (upside-down vs. upright) and the most direct read on {@link DiffuserAssistMathTest}'s own
 * "exactly zero torque at both the stable and unstable equilibrium" claim actually holding once real
 * gravity, real suspension and real collision are in the loop too.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DiffuserAssistGameTests {
    private DiffuserAssistGameTests() {}

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
        Quaternionf upsideDown = new Quaternionf().rotateZ((float) (Math.PI - 0.2));
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
        car.forceDrive(0.001, 0.0, 999);
    }

    /** Disabled-is-truly-inactive, on a REAL server (the acceptance criteria's own explicit requirement,
     * not just {@link DiffuserAssistMathTest}'s pure-function coverage of the same claim): an upside-down
     * car with {@link VehicleSpec.DiffuserTuning#IDENTITY} (what every existing car actually ships) must
     * stay substantially inverted after real ticks, falling and landing however gravity/collision alone
     * take it. This is also the regression guarantee for every existing car: since no car's own stock spec
     * differs from this tuning, this is exactly their own real-server behavior, unchanged. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void identityDiffuserTuningLeavesAnInvertedCarInverted(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 12, 5));
        // No setDiffuserTuningForTest call: this car's diffuserTuning() is exactly spec.diffuserTuning(),
        // VehicleSpec.CAR's own IDENTITY -- the same value every existing car ships today.
        startUpsideDown(car);
        helper.runAfterDelay(150L, () -> {
            double roll = rollAngleRad(car);
            helper.assertTrue(Math.abs(roll) > 1.5,
                    "identity Diffuser tuning should not self-right an inverted car, but roll recovered to " + roll + " rad");
            helper.succeed();
        });
    }

    // R16 (roll self-righting) production-path GameTest: attempted and deliberately NOT included here,
    // same conclusion as R17 below but reached by a different route. A `rollSelfRightingRecoversFrom
    // UpsideDownBeforeLanding` fixture (enabled roll self-righting, start upside down, assert roll is back
    // near upright 150 ticks later) passed repeatedly across several earlier sessions, but across 7 full
    // ./gradlew runGameTestServer runs in this session it failed 3 times, always the same way: final roll
    // landed within ~0.001 rad of exactly +-pi, the roll=pi unstable equilibrium DiffuserAssistMath
    // .rollSelfRightingTorque correctly returns zero restoring torque at (see startUpsideDown's own
    // javadoc on why the start angle is offset off that exact point). Per-tick debug logging showed every
    // run bit-identical through tick 9 (2.8039722326227277 rad, to 17 significant figures) and diverging
    // between tick 9 and 29 -- not slow drift, a hard bifurcation from identical state, which rules out a
    // logic bug in this ticket's own code (same inputs reliably produce the same torque; DiffuserAssistMathTest
    // already pins the zero-at-equilibrium behavior in isolation). Two concrete causes were tested and ruled
    // out directly, not guessed: widening the single-block landing pad to 7x7 (in case drift made the car
    // miss it and freefall far longer than intended) did not change the failure signature at all; deleting
    // run/world for a bit-fresh test dimension before a run did not either. What is left is genuine run-to-
    // run divergence in the physics step itself with nominally identical initial conditions -- consistent
    // with a multithreaded rigid-body solver whose floating-point summation order is not fixed call to
    // call, chaotically amplified by a real 3-axis tumble until it occasionally coasts close enough to the
    // one point this assist cannot (and should not) pull away from. identityDiffuserTuningLeavesAnInverted
    // Car above is unaffected: staying near roll=pi is its PASS condition, not something it needs to avoid.
    // Rather than ship a test that is intermittently red for reasons outside this ticket's own code, or
    // weaken the 150-tick assertion until a chaotic capture near the equilibrium could slip under it
    // undetected, this is reported as a real environment limitation: R16's bounded-torque, zero-at-
    // equilibrium behavior is already covered by DiffuserAssistMathTest's pure-function tests; a reliable
    // production-path check would need either a non-chaotic fixture (e.g. a 2D-only drop with no yaw/pitch
    // coupling, if this engine's wrapper exposes one) or a solver configuration this repo does not currently
    // have a verified single-threaded/deterministic-stepping call site for.

    // R17 (pitch/landing time-to-touchdown) production-path GameTest: attempted and deliberately NOT
    // included here. Over a dozen full ./gradlew runGameTestServer runs with several fixture designs
    // (checking at the airborne/grounded boundary via succeedWhen+isTouchingGroundForTest; checking a
    // fixed tick with progressively stronger tuning and earlier tick numbers), the actual tick at which a
    // pitched car's wheels first contact ground proved NOT deterministic in this environment -- the same
    // fixture landed anywhere from tick ~6 to past tick ~20 run to run (observed directly, not guessed),
    // which this ticket's own acceptance criteria ("finite bounded time-scaled response") cannot be
    // reliably exercised against with a fixed-tick or airborne-boundary check. Rather than land a flaky
    // test (intermittently red for reasons unrelated to the assist) or quietly loosen the assertion until
    // it stopped catching anything meaningful, this is reported as genuine remaining work: R17's own pure
    // -function behaviour (authority ramping, invalid/past-horizon estimates) is already covered by
    // DiffuserAssistMathTest; a reliable production-path check needs either a steadier landing fixture
    // (e.g. a much higher, slower-arriving drop column clear of the "Can't keep up" server-load variance
    // observed during these runs) or a polling design keyed off the body's own predicted time-to-touchdown
    // rather than a fixed tick or the first-contact boundary.
}
