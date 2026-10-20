package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class CombinedPhysicsGameTests {
    @GameTest(template = "settlement_test", batch = "combinedPhysicsOne", timeoutTicks = 180)
    public static void configuredTiresAndRearSteerPreserveClutchEnergyAtOneStep(GameTestHelper helper) {
        exercise(helper, 1);
    }

    @GameTest(template = "settlement_test", batch = "combinedPhysicsFour", timeoutTicks = 180)
    public static void configuredTiresAndRearSteerPreserveClutchEnergyAtFourSteps(GameTestHelper helper) {
        exercise(helper, 4);
    }

    private static void exercise(GameTestHelper helper, int steps) {
        VehicleLanes.restoreSubStepsOnEveryOutcome(helper);
        CarConfig.WHEEL_SUB_STEPS.set(steps);
        VehicleLanes.build(helper, VehicleLanes.Site.INTEGRATION_CONTACT, lanes -> {
            lanes.layLane(VehicleLanes.FIRST_LANE_X, Blocks.STONE.defaultBlockState());
            CarEntity car;
            try {
                Path config = Files.createTempDirectory("vehicles-combined-test");
                Path cars = config.resolve("dynamicvehicles/cars");
                Files.createDirectories(cars);
                Files.writeString(cars.resolve("car.json"),
                        "{\"tireKit\":{\"rearSteerRatio\":-0.3,\"tirePressureBar\":1.5}}");
                VehicleSpecOverrides.load(config);
                car = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(VehicleLanes.FIRST_LANE_X));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"), "dynamicvehicles-no-such-config-dir"));
            }
            helper.assertTrue(car.spec().tirePressureBar() == 1.5
                    && car.spec().steeringTuning().rearSteerRatio() == -0.3, "combined fixture lost its tire settings");
            car.selectGear(CarPhysics.Gear.D2);
            car.forceDrive(0.7, 0.12, 60);
            CarDrivetrainGameTests.ExchangeBooks books = new CarDrivetrainGameTests.ExchangeBooks(car, "configured tires and rear steer");
            int[] contacts = {0};
            for (int tick = 2; tick < 60; tick++) {
                helper.runAfterDelay(tick, () -> {
                    books.sample();
                    if (car.debugTouching()) contacts[0]++;
                });
            }
            helper.runAfterDelay(60, () -> {
                helper.assertTrue(contacts[0] > 30 && car.debugSpeed() > 1.0,
                        "fixture must actually drive on loaded tires: contacts=" + contacts[0] + " speed=" + car.debugSpeed());
                books.assertBalanced(helper);
                helper.succeed();
            });
        });
    }
}
