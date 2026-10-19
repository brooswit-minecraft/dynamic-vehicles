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
     * <p>Offset 0.2 rad off exactly &pi;, not exactly &pi;: {@link DiffuserAssistMathTest#exactlyUpsideDownIsAnUnstableEquilibriumWithNoSpuriousKick}
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

    private static VehicleSpec.DiffuserTuning rollOnly(double maxTorque, double gain, double damping) {
        return new VehicleSpec.DiffuserTuning(true, maxTorque, gain, damping,
                false, 0, 0, 0, 0, false, 0, 0, 0, 0, false, 0, 0, 0, 0);
    }

    /** R16, the acceptance criteria's own "upside-down recovery": a car with roll self-righting enabled,
     * started upside down well above the ground (so it has time to right itself before any suspension/
     * contact force could do it instead), must end up close to upright after real ticks of real gravity,
     * real angular drag and this assist's own bounded torque -- not just "less upside down than before",
     * which a large enough constant torque could satisfy while still oscillating or overshooting forever. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void rollSelfRightingRecoversFromUpsideDownBeforeLanding(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 12, 5));
        car.setDiffuserTuningForTest(rollOnly(4000.0, 3000.0, 600.0));
        startUpsideDown(car);
        // sableBody (and so currentOrientationForTest()) does not exist until the first real tick -- wait
        // one tick before reading it at all, rather than asserting on it synchronously right after spawn.
        helper.runAfterDelay(1L, () -> {
            helper.assertTrue(Math.abs(Math.abs(rollAngleRad(car)) - Math.PI) < 0.3,
                    "sanity: car did not actually start upside down, roll=" + rollAngleRad(car));
            helper.runAfterDelay(149L, () -> {
                double roll = rollAngleRad(car);
                helper.assertTrue(Math.abs(roll) < 0.5,
                        "roll self-righting did not bring an inverted car back near upright in time: final roll=" + roll + " rad");
                helper.succeed();
            });
        });
    }

    /** Disabled-is-truly-inactive, on a REAL server (the acceptance criteria's own explicit requirement,
     * not just {@link DiffuserAssistMathTest}'s pure-function coverage of the same claim): the exact same
     * upside-down start, but with {@link VehicleSpec.DiffuserTuning#IDENTITY} (what every existing car
     * actually ships), must NOT right itself the way the enabled case above does -- it should still be
     * substantially inverted after the same real ticks, falling and landing however gravity/collision alone
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
}
