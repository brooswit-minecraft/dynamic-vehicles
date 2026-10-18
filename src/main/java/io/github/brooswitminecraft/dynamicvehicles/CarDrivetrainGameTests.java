package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-401 review: production-path regressions for the connected drivetrain/limiter/default-gear/
 * neutral-RPM fixes, driven through a real {@link CarEntity} ticking in a real {@link
 * net.minecraft.server.level.ServerLevel}, not only through the pure {@link DrivetrainMath}/{@link
 * GearDrive} helper tests. Each test's own javadoc names the specific review finding it guards.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class CarDrivetrainGameTests {
    private CarDrivetrainGameTests() {}

    /** Review P1/core ticket ask: D1's own extra torque multiplier must show up as a real, measurably
     * larger speed than top gear over the same short window on the same surface -- not just as a
     * different number in a pure-function test.
     *
     * <p>Two deliberate choices avoid measuring the wrong thing:
     * <ul>
     * <li>A SETTLE period (20 ticks at zero throttle) runs first. A freshly spawned body's suspension
     * has not yet reached equilibrium, and {@code debugSpeed()} is the body's full 3D velocity magnitude
     * -- the vertical settling bounce right after spawn dominates it for the first several ticks,
     * completely masking any horizontal difference between gears. Measuring from a settled standstill
     * means every bit of speed gained afterward is actually from the drivetrain.
     * <li>A PARTIAL throttle (0.3) keeps both gears' commanded force under the tire's grip limit, which
     * isolates the ratio multiplier itself from the separate wheelspin/rev-limiter interaction the next
     * test below covers -- at full throttle, D1's own wheelspin can trip its own limiter within a tick or
     * two (a real low-gear, low-grip launch's own "fighting the limiter" behavior).
     * </ul>
     */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void d1ReachesAHigherSpeedThanD5OverTheSameShortWindow(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 2), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 2, 10), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 10), Blocks.AIR);
        CarEntity carD1 = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 2));
        carD1.setYRot(0.0f);
        carD1.selectGear(CarPhysics.Gear.D1);
        CarEntity carD5 = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 10));
        carD5.setYRot(0.0f);
        carD5.selectGear(CarPhysics.Gear.D5);
        Player riderD1 = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(riderD1.startRiding(carD1), "mock player failed to board the D1 car");
        Player riderD5 = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(riderD5.startRiding(carD5), "mock player failed to board the D5 car");
        helper.runAfterDelay(40L, () -> {
            helper.assertTrue(carD1.debugSpeed() < 0.5 && carD5.debugSpeed() < 0.5,
                    "test setup invalid -- a car had not settled to a near-standstill before throttle was applied: D1="
                            + carD1.debugSpeed() + " D5=" + carD5.debugSpeed());
            riderD1.zza = 0.3f;
            riderD5.zza = 0.3f;
            helper.runAfterDelay(20L, () -> {
                helper.assertTrue(carD1.debugGearRatio() == DrivetrainMath.ratioFor(0),
                        "D1 did not resolve to its own ratio on the production tick path: " + carD1.debugGearRatio());
                helper.assertTrue(carD5.debugGearRatio() == DrivetrainMath.ratioFor(4),
                        "D5 did not resolve to its own ratio on the production tick path: " + carD5.debugGearRatio());
                helper.assertTrue(carD1.debugSpeed() > carD5.debugSpeed() * 1.3,
                        "D1's extra torque multiplier did not produce a measurably higher speed than D5 from a settled standstill: D1="
                                + carD1.debugSpeed() + " D5=" + carD5.debugSpeed());
                helper.succeed();
            });
        });
    }

    /** Review P1: the rev limiter must cap TORQUE, not merely the number displayed. Pinned against a
     * wall in D1 at full throttle, the driven wheels' own spin rate must stay bounded near what the
     * ratio/limiter can carry instead of growing without bound for as long as throttle is held -- before
     * this fix, nothing capped the drive force once the display clamp kicked in. Coupling the limiter to
     * the wheel's own (reset-to-ground-speed-on-grip) spin state means torque now oscillates near the
     * limiter rather than holding it steady -- a real low-gear, low-grip launch's own "fighting the
     * limiter" behavior -- so this samples across the whole window and asserts on the WORST (peak) values
     * seen, not a single end-of-window snapshot that could land mid-cut. This also stands in for
     * "sustained low-grip wheelspin": the wall keeps body speed near zero exactly the way a
     * frictionless/sand surface would, so the same mechanism that would runaway here would runaway there
     * too, and the peak reported slip is the signal the existing erosion/SlipReporter pipeline gates on
     * (MINECRAFT-401 ticket: "wheelspin with near-zero body speed" must still erode eligible ground). */
    @GameTest(template = "settlement_test", timeoutTicks = 140)
    public static void limiterCapsWheelspinInsteadOfLettingItGrowWithoutBoundAgainstAWall(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        for (int dx = 4; dx <= 6; dx++) {
            for (int dy = 2; dy <= 5; dy++) {
                helper.setBlock(new BlockPos(dx, dy, 7), Blocks.STONE);
            }
        }
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.D1);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = 1.0f;
        float[] worstSpinHolder = {0.0f};
        float[] worstSlipHolder = {0.0f};
        long[] sampleTicks = {5, 10, 15, 20, 30, 40, 55, 70, 85, 100, 115};
        for (long sampleTick : sampleTicks) {
            helper.runAfterDelay(sampleTick, () -> {
                for (int wheel = 0; wheel < car.syncedWheelCount(); wheel++) {
                    worstSpinHolder[0] = Math.max(worstSpinHolder[0], Math.abs(car.wheelSpin(wheel, 1.0f)));
                }
                worstSlipHolder[0] = Math.max(worstSlipHolder[0], (float) car.clientSlip());
            });
        }
        helper.runAfterDelay(130L, () -> {
            helper.assertTrue(car.debugSpeed() < 5.0,
                    "car was not actually blocked by the wall -- test setup invalid, speed=" + car.debugSpeed());
            // An unbounded-torque bug injects roughly (excess force * wheelRadius / wheelInertia * dt) of
            // spin EVERY tick indefinitely, which after 115 ticks of full D1 throttle is orders of
            // magnitude past this. A correctly limiter-coupled drivetrain's peak stays near where the
            // engaged ratio's own RPM reaches the limiter -- comfortably under 150 rad/s for D1's ratio.
            helper.assertTrue(worstSpinHolder[0] < 150.0,
                    "a driven wheel's spin rate grew without bound instead of being capped by the rev limiter: " + worstSpinHolder[0] + " rad/s");
            helper.assertTrue(worstSlipHolder[0] > 0.3,
                    "wheelspin against the wall never produced a large reported slip across the whole window: peak=" + worstSlipHolder[0]);
            helper.succeed();
        });
    }

    /** Review P2: keyboard/gamepad (which can only ever select {@link CarPhysics.Gear#DEFAULT_FORWARD} or
     * reverse -- never an individual manual gear) must not be silently pinned in D1 forever now that D1's
     * ratio multiplies drive force. The default policy is {@link CarPhysics.Gear#AUTOMATIC}, so a
     * non-shifter driver who never calls {@code selectGear} must still shift up out of D1 under sustained
     * throttle. */
    @GameTest(template = "settlement_test", timeoutTicks = 140)
    public static void nonShifterDefaultShiftsOutOfFirstGearInsteadOfStayingPinnedThere(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        // Deliberately never call selectGear: this is the exact state a keyboard/gamepad driver who has
        // never touched the reverse key is in.
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = 1.0f;
        helper.runAfterDelay(120L, () -> {
            helper.assertTrue(car.debugAutomaticGearIndex() > 0,
                    "a non-shifter driver's default gear stayed pinned at index 0 (first gear) under sustained throttle instead of shifting up: index="
                            + car.debugAutomaticGearIndex());
            helper.succeed();
        });
    }

    /** Review P3: {@code SableCarBody} passes the car's SIGNED throttle (accelerator minus brake) to
     * {@link DrivetrainMath#freeRevRpm} in NEUTRAL. Holding only the brake (negative throttle, no
     * accelerator) in NEUTRAL must leave the engine at idle, never free-rev it toward the limiter. */
    @GameTest(template = "settlement_test", timeoutTicks = 40)
    public static void neutralWithOnlyTheBrakeHeldNeverRevsPastIdle(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.NEUTRAL);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = -1.0f; // brake only, no accelerator
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(car.clientEngineRpmFraction() < 0.05,
                    "holding only the brake in neutral free-revved the engine instead of leaving it at idle: fraction="
                            + car.clientEngineRpmFraction());
            helper.succeed();
        });
    }

    /** Airborne/contact recovery: driving off a real ledge and landing must not leave the car diverging
     * (NaN/infinite position or speed) -- the wheel-spin "freeze while airborne, resume on landing" path
     * (MINECRAFT-73/118) is exercised by the real raycast miss/hit transition, not simulated. */
    @GameTest(template = "settlement_test", timeoutTicks = 160)
    public static void carRecoversCleanlyAfterDrivingOffALedgeAndLanding(GameTestHelper helper) {
        for (int dz = 0; dz <= 4; dz++) {
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        // A genuine 2-block drop: the wheel raycast only reaches a fraction of a block below the wheel, so
        // this gap is deep enough to actually MISS for at least one tick while falling, rather than just
        // landing on extra suspension travel -- the real condition that freezes/resumes wheelSpin.
        for (int dz = 5; dz <= 9; dz++) {
            helper.setBlock(new BlockPos(5, 0, dz), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 1, dz), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 0));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.D1);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = 1.0f;
        helper.runAfterDelay(150L, () -> {
            double x = car.getX();
            double y = car.getY();
            double z = car.getZ();
            double speed = car.debugSpeed();
            helper.assertTrue(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z),
                    "car position diverged after driving off a ledge: " + x + "," + y + "," + z);
            helper.assertTrue(Double.isFinite(speed) && speed < 100.0,
                    "car speed diverged after driving off a ledge: " + speed);
            helper.succeed();
        });
    }
}
