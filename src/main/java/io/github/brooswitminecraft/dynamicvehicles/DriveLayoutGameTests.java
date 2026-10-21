package io.github.brooswitminecraft.dynamicvehicles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Registered entity construction and saved-layout precedence, beyond pure VehicleSpec constants. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DriveLayoutGameTests {
    private DriveLayoutGameTests() {}

    @GameTest(template = "settlement_test", batch = "driveLayoutConfigIsolated")
    public static void registeredLayoutsAndSavedTuningSurviveNewDefaults(GameTestHelper helper) {
        List<EntityType<CarEntity>> types = List.of(DynamicVehiclesMod.CAR.get(), DynamicVehiclesMod.TRUCK.get(),
                DynamicVehiclesMod.DRIFT.get(), DynamicVehiclesMod.TROPHY.get(), DynamicVehiclesMod.RALLY.get());
        String[] ids = {VehicleIds.CAR, VehicleIds.TRUCK, VehicleIds.DRIFT_CAR,
                VehicleIds.TROPHY_TRUCK, VehicleIds.RALLY_CAR};
        double[] expected = {1.0, .5, .4, .4, .2};
        try {
            Path config = Files.createTempDirectory("drive-layout-gametest");
            Path cars = config.resolve("dynamicvehicles/cars");
            Files.createDirectories(cars);
            VehicleSpecOverrides.load(config);
            CompoundTag[] saved = new CompoundTag[types.size()];
            for (int i = 0; i < types.size(); i++) {
                CarEntity car = types.get(i).create(helper.getLevel());
                helper.assertTrue(car != null, "registered vehicle could not be constructed: " + ids[i]);
                helper.assertTrue(ids[i].equals(car.vehicleId()), "registered factory resolved wrong vehicle id");
                helper.assertTrue(car.vehicleTuning().frontTorqueSplit() == expected[i],
                        "registered " + ids[i] + " did not receive its required default drive layout");
                saved[i] = new CompoundTag();
                car.addAdditionalSaveData(saved[i]);
                Files.writeString(cars.resolve(ids[i] + ".json"), "{\"frontTorqueSplit\":0.65}");
            }
            VehicleSpecOverrides.load(config);
            for (int i = 0; i < types.size(); i++) {
                CarEntity fresh = types.get(i).create(helper.getLevel());
                helper.assertTrue(fresh != null && fresh.vehicleTuning().frontTorqueSplit() == .65,
                        "new " + ids[i] + " ignored its per-car layout override");
                fresh.readAdditionalSaveData(saved[i]);
                helper.assertTrue(fresh.vehicleTuning().frontTorqueSplit() == expected[i],
                        "saved " + ids[i] + " layout was overwritten by newer defaults");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not prepare drive-layout fixture", e);
        } finally {
            VehicleSpecOverrides.load(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get());
        }
        helper.succeed();
    }
}
