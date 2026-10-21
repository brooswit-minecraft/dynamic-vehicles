package io.github.brooswitminecraft.dynamicvehicles;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import io.github.brooswitminecraft.dynamicterrain.DynamicTerrainConfig;
import io.github.brooswitminecraft.dynamicterrain.Erosion;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/** Production Sable -> SlipReporter -> Dynamic Terrain acceptance, without a probabilistic success assertion. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class WheelErosionGameTests {
    private WheelErosionGameTests() {}

    @GameTest(template = "settlement_test", batch = "wheelErosionIsolated")
    public static void wearBoostReachesTerrainOncePerLoadedWheelAndDustCannotRepeatIt(GameTestHelper helper) {
        int substeps = CarConfig.WHEEL_SUB_STEPS.get();
        boolean wear = CarConfig.WEAR_ENABLED.get();
        double strength = CarConfig.WEAR_STRENGTH.get();
        boolean vehicleErosion = DynamicTerrainConfig.VEHICLE_EROSION.get();
        var observer = SlipReporter.observer;
        try {
            DynamicTerrainConfig.VEHICLE_EROSION.set(true);
            CarConfig.WEAR_ENABLED.set(true);
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                for (Block surface : new Block[] {Blocks.DIRT, Blocks.BEDROCK}) {
                    double baseline = 0;
                    for (double multiplier : new double[] {0, 2}) {
                        CarConfig.WEAR_STRENGTH.set(multiplier);
                        try (Fixture f = fixture(helper, surface, false)) {
                            List<SlipReporter.Report> reports = new ArrayList<>();
                            SlipReporter.observer = reports::add;
                            f.body.tick(f.car, 1, 0, false, CarPhysics.Gear.NEUTRAL, 0.05);
                            helper.assertTrue(reports.size() == 4,
                                    "expected exactly one terrain report per loaded wheel, not per substep: " + reports.size());
                            HashSet<BlockPos> positions = new HashSet<>();
                            double totalSlip = 0;
                            for (SlipReporter.Report report : reports) {
                                helper.assertTrue(positions.add(report.position()), "one contact was reported more than once");
                                helper.assertTrue(report.slipSpeed() > 0.3 && report.loadKg() > 0,
                                        "terrain report did not carry positive real load/slip");
                                helper.assertTrue(java.util.Arrays.asList(f.body.wheelContactBlocks()).contains(report.position()),
                                        "terrain report used a block outside the real raycast contacts");
                                if (surface == Blocks.BEDROCK) {
                                    helper.assertTrue(report.result() == Erosion.Result.SKIPPED
                                                    || report.result() == Erosion.Result.RESISTED,
                                            "unbreakable terrain was accepted for erosion: " + report.result());
                                    helper.assertTrue(helper.getLevel().getBlockState(report.position()).is(Blocks.BEDROCK),
                                            "production wheel report modified bedrock");
                                } else {
                                    helper.assertTrue(report.result() != Erosion.Result.SKIPPED
                                                    && report.result() != Erosion.Result.DISABLED,
                                            "real loaded dirt slip did not enter terrain's decision path");
                                }
                                totalSlip += report.slipSpeed();
                            }
                            if (multiplier == 0) baseline = totalSlip;
                            else helper.assertTrue(totalSlip > baseline,
                                    "configured wear boost never reached the real terrain reporter");
                            int beforeDust = reports.size();
                            f.car.emitDust(helper.getLevel(), f.body.speed(), f.body.wheelContactBlocks(),
                                    f.body.wheelContactSubSteps(), f.body.wheelSlipSpeeds());
                            helper.assertTrue(reports.size() == beforeDust,
                                    "wheel dust duplicated the physics erosion reports");
                        }
                    }
                }
            }
        } finally {
            SlipReporter.observer = observer;
            CarConfig.WHEEL_SUB_STEPS.set(substeps);
            CarConfig.WEAR_ENABLED.set(wear);
            CarConfig.WEAR_STRENGTH.set(strength);
            DynamicTerrainConfig.VEHICLE_EROSION.set(vehicleErosion);
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "wheelErosionIsolated")
    public static void airborneSlidingCarReportsNoTerrainWear(GameTestHelper helper) {
        var observer = SlipReporter.observer;
        int substeps = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            for (int steps : new int[] {1, 4}) {
                CarConfig.WHEEL_SUB_STEPS.set(steps);
                try (Fixture f = fixture(helper, Blocks.DIRT, true)) {
                    List<SlipReporter.Report> reports = new ArrayList<>();
                    SlipReporter.observer = reports::add;
                    f.body.tick(f.car, 1, 0, false, CarPhysics.Gear.D1, 0.05);
                    helper.assertTrue(reports.isEmpty(), "airborne car reported wear against terrain");
                    for (int count : f.body.wheelContactSubSteps()) {
                        helper.assertTrue(count == 0, "airborne fixture unexpectedly touched terrain");
                    }
                }
            }
        } finally {
            SlipReporter.observer = observer;
            CarConfig.WHEEL_SUB_STEPS.set(substeps);
        }
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, Block surface, boolean airborne) {
        CarEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
        helper.assertTrue(car != null, "could not construct erosion fixture");
        BlockPos centre = helper.absolutePos(new BlockPos(5, 2, 5));
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            helper.getLevel().setBlock(centre.offset(x, 0, z), surface.defaultBlockState(), 3);
            for (int y = 1; y <= 5; y++) {
                helper.getLevel().setBlock(centre.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        car.moveTo(centre.getX() + 0.5,
                centre.getY() + 1 + car.spec().rideHeight() - car.spec().halfY() + (airborne ? 4 : 0),
                centre.getZ() + 0.5, 0, 0);
        SableCarBody body = SableCarBody.create(helper.getLevel(), car);
        helper.assertTrue(body != null, "Sable did not construct erosion fixture");
        try {
            Field field = SableCarBody.class.getDeclaredField("body");
            field.setAccessible(true);
            RigidBodyHandle handle = (RigidBodyHandle) field.get(body);
            // Real lateral chassis slip gives all four loaded tires an erosion demand, independent of gears.
            handle.applyImpulseAtPoint(new Vector3d(), new Vector3d(8 * car.spec().massKg(), 0, 0));
            return new Fixture(car, body);
        } catch (ReflectiveOperationException | RuntimeException e) {
            body.remove();
            throw new IllegalStateException("could not seed erosion fixture", e);
        }
    }

    private record Fixture(CarEntity car, SableCarBody body) implements AutoCloseable {
        @Override public void close() { body.remove(); }
    }
}
