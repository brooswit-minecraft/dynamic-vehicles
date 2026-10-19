package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-446: production-path regressions for wing downforce/drag, run against a real, ticking {@link
 * CarEntity}/{@link SableCarBody} and a real per-car config override (same {@code withLoadedOverride}
 * pattern as {@link VehicleSpecOverridesGameTests}) &mdash; {@link VehicleSpecWingTest}'s pure tests
 * already cover {@link VehicleSpec#wingDownforceNewtons} and the roster's own stock defaults in isolation;
 * what they cannot catch is a bug in how {@link SableCarBody#tick} actually applies these fields to the
 * real rigid body (wrong axle point, wrong sign, wrong cadence).
 *
 * <p>The front/rear-balance and speed-growth tests below compare a winged car against an otherwise
 * identical STOCK car driven with the exact same throttle profile, rather than asserting on the winged
 * car's raw numbers alone: accelerating under throttle already shifts load toward the rear on its own
 * (weight transfer under drive torque), a real effect with nothing to do with this ticket, confirmed
 * live &mdash; a first draft asserted front-suspension-travel-exceeds-rear directly and failed because that
 * acceleration-driven rear squat outweighed a modest wing coefficient's own front bias. Comparing each
 * winged measurement against a stock car driven through the identical profile cancels that common
 * confound and isolates the wing's own contribution.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class WingAeroGameTests {
    private WingAeroGameTests() {}

    private static final double GENTLE_THROTTLE = 0.3;
    private static final int DRIVE_TICKS = 100;
    /** Strong enough that its signal clearly exceeds the acceleration-driven rear-squat confound described
     * in this class' own javadoc, without being so large the suspension simply bottoms out. */
    private static final double STRONG_DOWNFORCE_COEFF = 8.0;

    private static void fillFloor(GameTestHelper helper, int cx, int y, int cz) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static double horizontalSpeed(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return Math.hypot(v.x, v.z) * 20.0;
    }

    /** {@code null} for a few ticks after spawn, until {@code CarEntity}'s own first server tick creates
     * the real Sable body &mdash; callers must only read this after at least one {@code runAfterDelay}. */
    private static double[] suspensionTravel(CarEntity car) {
        return ((SableCarBody) car.sableBody()).suspensionTravel();
    }

    private static double frontMinusRearTravel(CarEntity car) {
        double[] travel = suspensionTravel(car);
        double front = (travel[0] + travel[1]) / 2.0;
        double rear = (travel[2] + travel[3]) / 2.0;
        return front - rear;
    }

    /** AC (front/rear load balance): a car whose wing config gives it front downforce only (rear left at
     * its stock 0.0), driven through the exact same throttle profile as an otherwise identical stock car,
     * must end up relatively MORE front-loaded than that stock car &mdash; the genuine downward force this
     * ticket adds is applied at the front axle's own mount point, off the CoG, so it loads the front
     * springs harder than a plain stock car's own (acceleration-driven) front/rear balance. A bug that
     * applied it at the CoG (no axle split at all) or on the wrong axle would instead show no such shift,
     * or shift it the wrong way. */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void frontOnlyWingDownforceShiftsLoadForwardComparedToStock(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5);
        fillFloor(helper, 5, 2, 20);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        withLoadedOverride(VehicleIds.CAR, "{\"wing\": {\"frontDownforceCoeff\": " + STRONG_DOWNFORCE_COEFF + "}}", () -> {
            CarEntity winged = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 20));
            stock.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
            winged.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
            helper.runAfterDelay((long) DRIVE_TICKS, () -> {
                double stockSpeed = horizontalSpeed(stock);
                double wingedSpeed = horizontalSpeed(winged);
                helper.assertTrue(stockSpeed > 1.0 && wingedSpeed > 1.0,
                        "test setup never got both cars moving: stock=" + stockSpeed + " winged=" + wingedSpeed);
                double stockBalance = frontMinusRearTravel(stock);
                double wingedBalance = frontMinusRearTravel(winged);
                helper.assertTrue(wingedBalance > stockBalance,
                        "a front-only wing downforce config must shift load forward relative to an "
                                + "identically-driven stock car: stock(front-rear)=" + stockBalance
                                + " winged(front-rear)=" + wingedBalance);
                helper.succeed();
            });
        });
    }

    /** AC (speed/airflow dependence): the SAME wing config, driven through the exact same throttle profile
     * as an otherwise identical stock car, must gain MORE total suspension compression (front and rear
     * combined) between "just after spawn" and "up to real speed" than the stock car does &mdash;
     * downforce scales with the square of airflow speed, so a real wing must add genuine extra compression
     * growth on top of whatever the stock car's own suspension already gains from acceleration alone. */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void wingDownforceAddsExtraCompressionGrowthComparedToStock(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5);
        fillFloor(helper, 5, 2, 20);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        withLoadedOverride(VehicleIds.CAR,
                "{\"wing\": {\"frontDownforceCoeff\": " + STRONG_DOWNFORCE_COEFF + ", \"rearDownforceCoeff\": "
                        + STRONG_DOWNFORCE_COEFF + "}}",
                () -> {
                    CarEntity winged = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 20));
                    // A few ticks to let CarEntity create its real Sable body (null for a moment right
                    // after spawn) before either car has moved meaningfully.
                    helper.runAfterDelay(5L, () -> {
                        double stockRest = totalTravel(stock);
                        double wingedRest = totalTravel(winged);
                        stock.forceDrive(1.0, 0.0, DRIVE_TICKS);
                        winged.forceDrive(1.0, 0.0, DRIVE_TICKS);
                        helper.runAfterDelay((long) DRIVE_TICKS, () -> {
                            double stockSpeed = horizontalSpeed(stock);
                            double wingedSpeed = horizontalSpeed(winged);
                            helper.assertTrue(stockSpeed > 3.0 && wingedSpeed > 3.0,
                                    "test setup never got both cars up to real speed: stock=" + stockSpeed
                                            + " winged=" + wingedSpeed);
                            double stockGrowth = totalTravel(stock) - stockRest;
                            double wingedGrowth = totalTravel(winged) - wingedRest;
                            helper.assertTrue(wingedGrowth > stockGrowth,
                                    "a real wing config must add extra suspension-compression growth as "
                                            + "speed builds, beyond whatever an identically-driven stock "
                                            + "car's own acceleration already causes: stockGrowth="
                                            + stockGrowth + " wingedGrowth=" + wingedGrowth);
                            helper.succeed();
                        });
                    });
                });
    }

    private static double totalTravel(CarEntity car) {
        double[] travel = suspensionTravel(car);
        return travel[0] + travel[1] + travel[2] + travel[3];
    }

    /** AC (stable zero-speed and tick/substep behaviour): a car carrying a real wing config, left entirely
     * untouched (no throttle/steer) at rest, must not spontaneously move or diverge &mdash; downforce is
     * exactly zero at zero airflow speed by construction ({@code coeff * speed^2}), so a bug that instead
     * applied some fixed or discontinuous force at rest would show up here as real horizontal drift. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void aWingedCarAtRestStaysStable(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5);
        withLoadedOverride(VehicleIds.CAR,
                "{\"wing\": {\"frontDownforceCoeff\": 4.0, \"rearDownforceCoeff\": 4.0, \"dragCoeff\": 2.0}}", () -> {
                    CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
                    helper.runAfterDelay(100L, () -> {
                        double speed = horizontalSpeed(car);
                        helper.assertTrue(!Double.isNaN(speed) && speed < 0.2,
                                "a winged car left at rest with no input must stay essentially stationary, not "
                                        + speed);
                        helper.succeed();
                    });
                });
    }

    /** AC (no energy injection from drag): once throttle is released, a car with an elevated per-car drag
     * coefficient must only ever lose speed while coasting, never gain it &mdash; drag is applied strictly
     * opposing the body's own velocity, so it can only remove kinetic energy, not add it. Sampled a few
     * times during the coast-down rather than only at the very end, so a transient speed-up partway through
     * (which a final-speed-only comparison could miss) is caught too. */
    @GameTest(template = "settlement_test", timeoutTicks = 400)
    public static void elevatedDragOnlyEverSlowsACoastingCarDown(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5);
        withLoadedOverride(VehicleIds.CAR, "{\"wing\": {\"dragCoeff\": 3.0}}", () -> {
            CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
            car.forceDrive(1.0, 0.0, 60);
            helper.runAfterDelay(60L, () -> {
                double speedAtThrottleRelease = horizontalSpeed(car);
                helper.assertTrue(speedAtThrottleRelease > 1.0,
                        "test setup never got the car moving before coast-down: " + speedAtThrottleRelease);
                car.forceDrive(0.0, 0.0, 100);
                double[] lastSpeed = {speedAtThrottleRelease};
                boolean[] everSpedUp = {false};
                for (long delay = 10; delay <= 100; delay += 10) {
                    helper.runAfterDelay(delay, () -> {
                        double speed = horizontalSpeed(car);
                        if (speed > lastSpeed[0] + 1e-6) {
                            everSpedUp[0] = true;
                        }
                        lastSpeed[0] = speed;
                    });
                }
                helper.runAfterDelay(100L, () -> {
                    helper.assertTrue(!everSpedUp[0],
                            "a coasting car with elevated aerodynamic drag must never speed back up, only "
                                    + "slow down -- drag injected energy instead of only opposing velocity");
                    helper.assertTrue(lastSpeed[0] < speedAtThrottleRelease,
                            "a coasting car with elevated aerodynamic drag must end up slower than when "
                                    + "throttle was released: released=" + speedAtThrottleRelease
                                    + " final=" + lastSpeed[0]);
                    helper.succeed();
                });
            });
        });
    }

    /** AC (nonzero-speed tick/substep behaviour): review follow-up (comment 49011) &mdash; {@link
     * #aWingedCarAtRestStaysStable} only establishes stability at zero speed, not that wing downforce's
     * own contribution stays well-behaved under a DIFFERENT {@code wheelSubSteps} count once the car is
     * actually moving. Wing downforce is applied once per tick, outside the sub-step loop, exactly like
     * air drag (see {@code SableCarBody.tick}'s own comment there) &mdash; unlike the per-wheel tire/
     * suspension forces inside that loop, which {@code CarDrivetrainGameTests
     * #wheelSubStepCountDoesNotMateriallyChangeReachedSpeedAfterAFixedWindow}'s own javadoc documents as
     * changing measurably (~10-15%) with sub-step count. Same production entry point and tolerance-band
     * approach as that test (a generous ratio band, not exact equality, since the REST of the physics a
     * compression measurement also reflects does have its own real, expected sub-step sensitivity) &mdash;
     * run in its own isolated batch and restoring {@code CarConfig.WHEEL_SUB_STEPS} even on failure, for
     * the same reason that test's own javadoc explains. */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "wingAeroSubStepIsolated")
    public static void wingDownforceGrowthIsSubstepCountInvariant(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5);
        fillFloor(helper, 5, 2, 20);
        int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
        String wingJson = "{\"wing\": {\"frontDownforceCoeff\": " + STRONG_DOWNFORCE_COEFF
                + ", \"rearDownforceCoeff\": " + STRONG_DOWNFORCE_COEFF + "}}";
        try {
            CarConfig.WHEEL_SUB_STEPS.set(1);
            withLoadedOverride(VehicleIds.CAR, wingJson, () -> {
                CarEntity oneStepCar = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
                helper.runAfterDelay(5L, () -> {
                    double oneStepRest = totalTravel(oneStepCar);
                    oneStepCar.forceDrive(1.0, 0.0, DRIVE_TICKS);
                    helper.runAfterDelay((long) DRIVE_TICKS, () -> {
                        double oneStepGrowth = totalTravel(oneStepCar) - oneStepRest;
                        runFourSubStepLegAndRestore(helper, originalSubSteps, wingJson, oneStepGrowth);
                    });
                });
            });
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    private static void runFourSubStepLegAndRestore(GameTestHelper helper, int originalSubSteps, String wingJson,
            double oneStepGrowth) {
        try {
            CarConfig.WHEEL_SUB_STEPS.set(4);
            withLoadedOverride(VehicleIds.CAR, wingJson, () -> {
                CarEntity fourStepCar = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 20));
                helper.runAfterDelay(5L, () -> {
                    double fourStepRest = totalTravel(fourStepCar);
                    fourStepCar.forceDrive(1.0, 0.0, DRIVE_TICKS);
                    helper.runAfterDelay((long) DRIVE_TICKS, () -> {
                        try {
                            double fourStepGrowth = totalTravel(fourStepCar) - fourStepRest;
                            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                            // Sign is not asserted on: a hard full-throttle launch's own acceleration-driven
                            // weight transfer can leave the NET compression change negative even with real
                            // wing downforce added on top (confirmed live) -- not something this test is
                            // about. What matters here is that the MAGNITUDE of whatever net change results
                            // does not swing wildly, and does not flip sign, between substep counts.
                            double oneStepAbs = Math.abs(oneStepGrowth);
                            double fourStepAbs = Math.abs(fourStepGrowth);
                            helper.assertTrue(oneStepAbs > 1e-6 && fourStepAbs > 1e-6,
                                    "test setup invalid -- wing downforce produced no measurable compression "
                                            + "change at either substep count: 1-step=" + oneStepGrowth
                                            + " 4-step=" + fourStepGrowth);
                            helper.assertTrue(Math.signum(oneStepGrowth) == Math.signum(fourStepGrowth),
                                    "wheelSubSteps flipped the sign of the net compression change: 1-step="
                                            + oneStepGrowth + " 4-step=" + fourStepGrowth);
                            double ratio = fourStepAbs / oneStepAbs;
                            helper.assertTrue(ratio > 0.5 && ratio < 2.0,
                                    "wing downforce's own once-per-tick contribution must not swing wildly with "
                                            + "wheelSubSteps: 1-step=" + oneStepGrowth + " 4-step=" + fourStepGrowth
                                            + " ratio=" + ratio);
                            helper.succeed();
                        } catch (RuntimeException e) {
                            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                            throw e;
                        }
                    });
                });
            });
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    /** AC (water/lava/buoyancy compatibility): review follow-up (comment 49011) &mdash; a car carrying a
     * real wing config, fully submerged in water and driven forward, must still report full submersion and
     * keep finite, bounded motion. Wing downforce/drag are computed and applied entirely independently of
     * {@code SableCarBody.tick}'s separate water/lava buoyancy and fluid-drag block (this ticket reads no
     * fluid state and does not touch that code), so this proves they coexist rather than merely asserting
     * it from reading the diff: the same "two full layers of water, read back as fully submerged" setup
     * {@code EnvironmentGameTests#shortVehicleFullySubmergedReachesFullSubmersionFraction} already uses for
     * the pre-existing, untouched buoyancy system (same spawn point and short delay before the submersion
     * check, for the same reason -- driving first would let buoyancy float the car up out of full
     * submersion before the check, which is an orthogonal, expected buoyancy behaviour, not something this
     * assertion is about). Driving happens AFTER that check, to also exercise the wing/fluid-drag code
     * paths running together in the same tick without the earlier submersion check racing against it. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void aWingedCarFullySubmergedInWaterStaysBuoyantAndStable(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        withLoadedOverride("indy_car",
                "{\"wing\": {\"frontDownforceCoeff\": " + STRONG_DOWNFORCE_COEFF + ", \"rearDownforceCoeff\": "
                        + STRONG_DOWNFORCE_COEFF + ", \"dragCoeff\": 3.0}}",
                () -> {
                    CarEntity car = helper.spawn(DynamicVehiclesMod.INDY.get(), new BlockPos(5, 2, 5));
                    helper.runAfterDelay(2L, () -> {
                        double vehicleHeight = 2.0 * car.spec().halfY();
                        double fraction = EnvironmentMath.submersionFraction(
                                car.getFluidHeight(FluidTags.WATER), vehicleHeight);
                        helper.assertTrue(fraction == 1.0,
                                "a winged car fully covered by water did not read back as fully submerged "
                                        + "(wing fields must not interfere with the separate buoyancy system): "
                                        + fraction);
                        car.forceDrive(0.5, 0.0, 100);
                        helper.runAfterDelay(100L, () -> {
                            Vec3 motion = car.getDeltaMovement();
                            Vec3 pos = car.position();
                            helper.assertTrue(
                                    Double.isFinite(motion.x) && Double.isFinite(motion.y) && Double.isFinite(motion.z)
                                            && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z),
                                    "a winged car in water produced non-finite position/velocity: pos=" + pos
                                            + " motion=" + motion);
                            helper.succeed();
                        });
                    });
                });
    }

    private static void withLoadedOverride(String carId, String json, Runnable body) {
        try {
            Path configDir = Files.createTempDirectory("dynamicvehicles-gametest-config");
            Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
            Files.createDirectories(carsDir);
            Files.writeString(carsDir.resolve(carId + ".json"), json);
            VehicleSpecOverrides.load(configDir);
            body.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"), "dynamicvehicles-no-such-config-dir"));
        }
    }
}
