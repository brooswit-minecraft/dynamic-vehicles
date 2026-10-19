package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
