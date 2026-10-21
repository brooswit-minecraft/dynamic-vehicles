package io.github.brooswitminecraft.dynamicvehicles;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class BoatTravelLiftGameTests {
    @GameTest(template = "settlement_test", batch = "boatTravelLiftIsolated")
    public static void actualBoatImpulseFollowsTravelAndRequiresWater(GameTestHelper helper) {
        ModConfigSpec.DoubleValue[] settings = {MotorboatConfig.HULL_BUOYANCY_STIFFNESS,
                MotorboatConfig.HULL_BUOYANCY_DAMPING, MotorboatConfig.LONGITUDINAL_DRAG_RATE,
                MotorboatConfig.LATERAL_DRAG_RATE, MotorboatConfig.VERTICAL_DAMPING_RATE,
                MotorboatConfig.ANGULAR_DAMPING_RATE, MotorboatConfig.TRAVEL_LIFT_COEFFICIENT,
                MotorboatConfig.MAX_TRAVEL_LIFT_FORCE};
        double[] saved = new double[settings.length];
        for (int i = 0; i < settings.length; i++) saved[i] = settings[i].get();
        try {
            for (var setting : settings) setting.set(0.0);
            MotorboatConfig.TRAVEL_LIFT_COEFFICIENT.set(8.0);
            MotorboatConfig.MAX_TRAVEL_LIFT_FORCE.set(1000.0);
            BlockPos origin = new BlockPos(0, 100, 50_000);
            for (boolean wet : new boolean[] {true, false}) {
                for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                    for (int y = -2; y <= 3; y++) helper.getLevel().setBlock(origin.offset(x, y, z),
                            (wet ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 3);
                }
                for (float yaw : new float[] {0, 90}) {
                    for (double[] travel : new double[][] {{0,0}, {0,8}, {0,-8}, {6,0}, {-6,0}, {6,8}, {60,80}}) {
                        for (double dt : new double[] {.05, .0125}) {
                            MotorboatEntity boat = DynamicVehiclesMod.MOTORBOAT.get().create(helper.getLevel());
                            helper.assertTrue(boat != null, "boat factory failed");
                            boat.moveTo(.5, 100.5, 50_000.5, yaw, 0);
                            SableMotorboatBody body = SableMotorboatBody.create(helper.getLevel(), boat);
                            helper.assertTrue(body != null, "boat body unavailable");
                            try {
                                Field field = SableMotorboatBody.class.getDeclaredField("body");
                                field.setAccessible(true);
                                RigidBodyHandle handle = (RigidBodyHandle) field.get(body);
                                double mass = MotorboatConfig.MASS_KG.get();
                                handle.applyImpulseAtPoint(new Vector3d(), SableMotorboatBody.orientationOf(yaw).invert()
                                        .transform(new Vector3d(travel[0] * mass, 0, travel[1] * mass)));
                                Vector3d before = handle.getLinearVelocity(new Vector3d());
                                body.tick(boat, 0, 0, dt);
                                double[] lift = body.travelLiftForces();
                                double total = java.util.Arrays.stream(lift).sum();
                                double expected = wet ? Math.min(1000, 8 * (travel[0]*travel[0] + travel[1]*travel[1])) : 0;
                                helper.assertTrue(Math.abs(total - expected) < 1e-5, "wet/dry/stationary/capped lift mismatch");
                                Vector3d after = handle.getLinearVelocity(new Vector3d());
                                helper.assertTrue(Math.abs((after.y - before.y) * mass - total * dt) < 0.02,
                                        "actual body vertical momentum did not receive exactly the bounded lift impulse");
                                helper.assertTrue(Math.abs(after.x-before.x) < 1e-5 && Math.abs(after.z-before.z) < 1e-5,
                                        "world-up lift changed horizontal momentum");
                                if (wet && yaw == 0 && travel[0] == 0 && travel[1] != 0) {
                                    boolean forward = travel[1] > 0;
                                    helper.assertTrue((forward ? lift[0]+lift[1] : lift[2]+lift[3]) > 0,
                                            "travel-facing edge was not lifted");
                                    helper.assertTrue((forward ? lift[2]+lift[3] : lift[0]+lift[1]) == 0,
                                            "trailing edge received lift");
                                    helper.assertTrue(handle.getAngularVelocity(new Vector3d()).x * travel[1] < 0,
                                            "actual body pitched away from the expected travel-facing lift torque");
                                }
                            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                            finally { body.remove(); }
                        }
                    }
                }
            }
        } finally {
            for (int i = 0; i < settings.length; i++) settings[i].set(saved[i]);
        }
        helper.succeed();
    }
}
