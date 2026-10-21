package io.github.brooswitminecraft.dynamicvehicles;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Production-path checks for one vehicle-local exhaust source, separate from tire dust. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class CarExhaustGameTests {
    private static final int EMIT_INTERVAL = 5;
    private static final int DRIVE_TICKS = 45;

    private CarExhaustGameTests() {}

    @GameTest(template = "settlement_test", timeoutTicks = 20, batch = "carExhaustRear")
    public static void fullThrottleCarEmitsBoundedExhaustFromItsRear(GameTestHelper helper) {
        int originalSteps = CarConfig.WHEEL_SUB_STEPS.get();
        int originalInterval = CarConfig.EXHAUST_INTERVAL_TICKS.get();
        boolean originalEnabled = CarConfig.EXHAUST_ENABLED.get();
        double originalMultiplier = CarConfig.EXHAUST_MULTIPLIER.get();
        installTerminalCleanup(helper, originalSteps, originalInterval, originalEnabled, originalMultiplier);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        AtomicReference<AtmosphereCompat.Emission> observed = new AtomicReference<>();
        AtmosphereCompat.EmissionObserver.install(observed::set);
        try {
            car.emitExhaust(helper.getLevel(), 1.0, CarEffectsMath.MIN_EXHAUST_SPEED);
            AtmosphereCompat.Emission emission = observed.get();
            helper.assertTrue(emission != null, "full-throttle moving car emitted no exhaust through the production compatibility path");
            helper.assertTrue(emission.material() == AtmosphereCompat.exhaustMaterial(),
                    "car exhaust did not use Dynamic Atmosphere's exhaust material singleton");
            helper.assertTrue(emission.amount() > 0 && emission.amount() <= CarConfig.EXHAUST_MAX_AMOUNT.get(),
                    "car exhaust escaped the configured per-emission bounds: " + emission.amount());
            helper.assertTrue(emission.pos().getZ() < car.getZ(),
                    "car exhaust was not emitted behind the yaw-0 vehicle: car=" + car.blockPosition() + " exhaust=" + emission.pos());
            car.setYRot(90.0f);
            car.emitExhaust(helper.getLevel(), 1.0, CarEffectsMath.MIN_EXHAUST_SPEED);
            emission = observed.get();
            helper.assertTrue(emission.pos().getX() > car.getX(),
                    "car exhaust was not emitted behind the yaw-90 vehicle: car=" + car.blockPosition() + " exhaust=" + emission.pos());
            helper.succeed();
        } finally {
            AtmosphereCompat.EmissionObserver.clear();
        }
    }

    /**
     * Drives real entities through {@link CarEntity#tick()} (and therefore the production Sable/tick
     * path) at both supported sub-step extremes. The observer proves that an emission is not multiplied
     * by wheel sub-steps and never exceeds one per configured entity interval.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 2 * DRIVE_TICKS + VehicleLanes.CHUNK_LOAD_ALLOWANCE,
            batch = "carExhaustCadence")
    public static void entityTickExhaustCadenceIsBoundedAtOneAndFourWheelSubSteps(GameTestHelper helper) {
        int originalSteps = CarConfig.WHEEL_SUB_STEPS.get();
        int originalInterval = CarConfig.EXHAUST_INTERVAL_TICKS.get();
        boolean originalEnabled = CarConfig.EXHAUST_ENABLED.get();
        double originalMultiplier = CarConfig.EXHAUST_MULTIPLIER.get();
        installTerminalCleanup(helper, originalSteps, originalInterval, originalEnabled, originalMultiplier);
        AtomicInteger emissions = new AtomicInteger();
        AtomicReference<CarEntity> activeCar = new AtomicReference<>();
        AtmosphereCompat.EmissionObserver.install(emission -> {
            CarEntity car = activeCar.get();
            if (car != null && emission.material() == AtmosphereCompat.exhaustMaterial()
                    && emission.pos().equals(exhaustPosition(car))) {
                emissions.incrementAndGet();
            }
        });
        VehicleLanes.build(helper, VehicleLanes.Site.EXHAUST_CADENCE, lanes -> {
            try {
                CarConfig.EXHAUST_ENABLED.set(true);
                CarConfig.EXHAUST_MULTIPLIER.set(2.5);
                CarConfig.EXHAUST_INTERVAL_TICKS.set(EMIT_INTERVAL);
                // Dust is not due during either 45-tick leg: exhaust must keep its own cadence.
                CarConfig.SMOKE_INTERVAL_TICKS.set(200);
                CarConfig.WHEEL_SUB_STEPS.set(1);
                CarEntity oneStep = spawnDrivenCar(lanes, VehicleLanes.FIRST_LANE_X);
                activeCar.set(oneStep);
                helper.runAfterDelay(DRIVE_TICKS, () -> runFourStepCadenceLeg(helper, lanes, emissions,
                        originalSteps, originalInterval, originalEnabled, originalMultiplier, oneStep, activeCar));
            } catch (RuntimeException e) {
                restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
                AtmosphereCompat.EmissionObserver.clear();
                throw e;
            }
        });
    }

    private static void runFourStepCadenceLeg(GameTestHelper helper, VehicleLanes lanes, AtomicInteger emissions,
            int originalSteps, int originalInterval, boolean originalEnabled, double originalMultiplier, CarEntity oneStep, AtomicReference<CarEntity> activeCar) {
        try {
            int oneStepCount = emissions.getAndSet(0);
            assertBoundedCadence(helper, oneStep, oneStepCount, 1);
            activeCar.set(null);
            oneStep.discard();
            CarConfig.WHEEL_SUB_STEPS.set(4);
            CarEntity fourStep = spawnDrivenCar(lanes, VehicleLanes.THIRD_LANE_X);
            activeCar.set(fourStep);
            helper.runAfterDelay(DRIVE_TICKS, () -> {
                try {
                    int fourStepCount = emissions.get();
                    assertBoundedCadence(helper, fourStep, fourStepCount, 4);
                    restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
                    AtmosphereCompat.EmissionObserver.clear();
                    helper.succeed();
                } catch (RuntimeException e) {
                    restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
                    AtmosphereCompat.EmissionObserver.clear();
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
            AtmosphereCompat.EmissionObserver.clear();
            throw e;
        }
    }

    /** Scope the global observer to the active fixture's rear source, excluding wheel dust and other cars. */
    private static BlockPos exhaustPosition(CarEntity car) {
        double yaw = Math.toRadians(car.getYRot());
        double back = car.spec().halfZ() + 0.3;
        return BlockPos.containing(car.getX() + Math.sin(yaw) * back, car.getY() + 0.5,
                car.getZ() - Math.cos(yaw) * back);
    }

    private static CarEntity spawnDrivenCar(VehicleLanes lanes, int laneX) {
        lanes.layLane(laneX, Blocks.STONE.defaultBlockState());
        CarEntity car = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(laneX));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.D1);
        car.forceDrive(1.0, 0.0, DRIVE_TICKS + 2);
        return car;
    }

    private static void assertBoundedCadence(GameTestHelper helper, CarEntity car, int count, int subSteps) {
        helper.assertTrue(car.debugSpeed() >= CarEffectsMath.MIN_EXHAUST_SPEED,
                "fixture never reached exhaust speed at wheelSubSteps=" + subSteps + ": " + car.debugSpeed());
        helper.assertTrue(count > 0, "moving full-throttle car emitted no exhaust at wheelSubSteps=" + subSteps);
        helper.assertTrue(count <= (DRIVE_TICKS + EMIT_INTERVAL - 1) / EMIT_INTERVAL,
                "exhaust emitted more than once per interval at wheelSubSteps=" + subSteps + ": " + count);
    }

    /** The production exhaust gate must suppress the compatibility call both when disabled and at zero scale. */
    @GameTest(template = "settlement_test", timeoutTicks = 2 * DRIVE_TICKS + VehicleLanes.CHUNK_LOAD_ALLOWANCE,
            batch = "carExhaustDisabled")
    public static void disabledAndZeroScaleExhaustDoNotEmit(GameTestHelper helper) {
        int originalSteps = CarConfig.WHEEL_SUB_STEPS.get();
        int originalInterval = CarConfig.EXHAUST_INTERVAL_TICKS.get();
        boolean originalEnabled = CarConfig.EXHAUST_ENABLED.get();
        double originalMultiplier = CarConfig.EXHAUST_MULTIPLIER.get();
        installTerminalCleanup(helper, originalSteps, originalInterval, originalEnabled, originalMultiplier);
        AtomicInteger emissions = new AtomicInteger();
        AtomicReference<CarEntity> activeCar = new AtomicReference<>();
        AtmosphereCompat.EmissionObserver.install(emission -> {
            CarEntity car = activeCar.get();
            if (car != null && emission.material() == AtmosphereCompat.exhaustMaterial()
                    && emission.pos().equals(exhaustPosition(car))) {
                emissions.incrementAndGet();
            }
        });
        VehicleLanes.build(helper, VehicleLanes.Site.EXHAUST_DISABLED, lanes -> {
            try {
                CarConfig.WHEEL_SUB_STEPS.set(1);
                CarConfig.EXHAUST_INTERVAL_TICKS.set(EMIT_INTERVAL);
                CarConfig.EXHAUST_ENABLED.set(false);
                CarConfig.EXHAUST_MULTIPLIER.set(2.5);
                CarEntity disabled = spawnDrivenCar(lanes, VehicleLanes.FIRST_LANE_X);
                activeCar.set(disabled);
                helper.runAfterDelay(DRIVE_TICKS, () -> runZeroScaleLeg(helper, lanes, emissions,
                        originalSteps, originalInterval, originalEnabled, originalMultiplier, disabled, activeCar));
            } catch (RuntimeException e) {
                restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
                AtmosphereCompat.EmissionObserver.clear();
                throw e;
            }
        });
    }

    private static void runZeroScaleLeg(GameTestHelper helper, VehicleLanes lanes, AtomicInteger emissions,
            int originalSteps, int originalInterval, boolean originalEnabled, double originalMultiplier, CarEntity disabled, AtomicReference<CarEntity> activeCar) {
        try {
            helper.assertTrue(disabled.debugSpeed() >= CarEffectsMath.MIN_EXHAUST_SPEED,
                    "disabled-exhaust fixture never reached exhaust speed: " + disabled.debugSpeed());
            helper.assertTrue(emissions.getAndSet(0) == 0, "disabled exhaust reached the compatibility emitter");
            activeCar.set(null);
            disabled.discard();
            CarConfig.EXHAUST_ENABLED.set(true);
            CarConfig.EXHAUST_MULTIPLIER.set(0.0);
            CarEntity zeroScale = spawnDrivenCar(lanes, VehicleLanes.THIRD_LANE_X);
            activeCar.set(zeroScale);
            helper.runAfterDelay(DRIVE_TICKS, () -> {
                try {
                    helper.assertTrue(zeroScale.debugSpeed() >= CarEffectsMath.MIN_EXHAUST_SPEED,
                            "zero-scale fixture never reached exhaust speed: " + zeroScale.debugSpeed());
                    helper.assertTrue(emissions.get() == 0, "zero-scale exhaust reached the compatibility emitter");
                    restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
                    AtmosphereCompat.EmissionObserver.clear();
                    helper.succeed();
                } catch (RuntimeException e) {
                    restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
                    AtmosphereCompat.EmissionObserver.clear();
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            restore(originalSteps, originalInterval, originalEnabled, originalMultiplier);
            AtmosphereCompat.EmissionObserver.clear();
            throw e;
        }
    }

    private static void restore(int steps, int interval, boolean enabled, double multiplier) {
        CarConfig.WHEEL_SUB_STEPS.set(steps);
        CarConfig.EXHAUST_INTERVAL_TICKS.set(interval);
        CarConfig.EXHAUST_ENABLED.set(enabled);
        CarConfig.EXHAUST_MULTIPLIER.set(multiplier);
    }

    /** Registers cleanup before this fixture touches process-global config or the singleton observer. */
    private static void installTerminalCleanup(GameTestHelper helper, int steps, int interval, boolean enabled,
            double multiplier) {
        int dustInterval = CarConfig.SMOKE_INTERVAL_TICKS.get();
        helper.testInfo.addListener(new GameTestListener() {
            private boolean cleaned;

            private void clean() {
                if (!cleaned) {
                    cleaned = true;
                    restore(steps, interval, enabled, multiplier);
                    CarConfig.SMOKE_INTERVAL_TICKS.set(dustInterval);
                    AtmosphereCompat.EmissionObserver.clear();
                }
            }

            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { clean(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { clean(); }
            @Override public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) { clean(); }
        });
    }
}
