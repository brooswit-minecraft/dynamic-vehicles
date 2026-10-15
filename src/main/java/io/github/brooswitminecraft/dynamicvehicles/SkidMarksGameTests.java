package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class SkidMarksGameTests {
    @GameTest(template = "settlement_test")
    public static void rigidBodyWheelRaysCreateGroundMarksWithoutOnGroundFlag(GameTestHelper helper) {
        for (int x = 5; x <= 15; x++) for (int z = 5; z <= 15; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            for (int y = 2; y < 7; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(10, 2, 10));
        double surfaceY = helper.absolutePos(new BlockPos(10, 2, 10)).getY();
        car.setPos(car.getX(), surfaceY + car.spec().rideHeight() - car.spec().halfY(), car.getZ());
        car.publishOrientation(new Quaternionf());
        car.setOnGround(false);
        try {
            int initial = SkidMarksClient.aliveMarks(System.currentTimeMillis()).size();
            car.publishSoundState(1, 0);
            SkidMarksClient.tick(car);
            helper.assertTrue(SkidMarksClient.aliveMarks(System.currentTimeMillis()).size() == initial,
                    "ordinary rolling produced marks");
            car.publishSoundState(1, 10);
            SkidMarksClient.tick(car);
            var marks = SkidMarksClient.aliveMarks(System.currentTimeMillis());
            helper.assertTrue(marks.size() == initial + car.spec().mounts().length,
                    "supported skidding wheels did not create marks with onGround false");
            for (var mark : marks) helper.assertTrue(Math.abs(mark.y() - surfaceY - SkidMarkMath.SURFACE_OFFSET) < 0.001,
                    "mark floated at chassis height instead of the collision surface");
            car.setPos(car.getX() + 1, car.getY() + 4, car.getZ());
            SkidMarksClient.tick(car);
            helper.assertTrue(SkidMarksClient.aliveMarks(System.currentTimeMillis()).size() == marks.size(),
                    "airborne vehicle produced marks");
            helper.succeed();
        } finally {
            SkidMarksClient.forget(car);
        }
    }
}
