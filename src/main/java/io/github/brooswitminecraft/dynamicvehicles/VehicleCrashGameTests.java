package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Production-path block-impact regressions for MINECRAFT-481. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class VehicleCrashGameTests {
    private VehicleCrashGameTests() {}

    private static CarEntity fixture(GameTestHelper helper) {
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 2, 5));
        car.setHeadingForTest(new Vec3(0.0, 0.0, 1.0));
        return car;
    }

    private static BlockPos front(CarEntity car, int block) {
        return BlockPos.containing(car.getX(), car.getY() + 0.4, car.getZ() + car.spec().halfZ() + 0.6 + block);
    }

    /** A high-speed crash enters the real destruction route and can traverse the configured three soft blocks. */
    @GameTest(template = "settlement_test")
    public static void severeCrashBreaksBoundedSuccessiveEligibleBlocks(GameTestHelper helper) {
        CarEntity car = fixture(helper);
        for (int block = 0; block < 3; block++) {
            helper.getLevel().setBlock(front(car, block), Blocks.GLASS.defaultBlockState(), 3);
        }

        car.hitBlockAhead(100.0);

        for (int block = 0; block < 3; block++) {
            helper.assertTrue(helper.getLevel().getBlockState(front(car, block)).isAir(),
                    "severe production collision did not break eligible block " + block);
        }
        helper.succeed();
    }

    /** The same real route must leave an eligible block intact below collisionMinSpeed. */
    @GameTest(template = "settlement_test")
    public static void belowThresholdCrashDoesNotBreakBlock(GameTestHelper helper) {
        CarEntity car = fixture(helper);
        BlockPos target = front(car, 0);
        helper.getLevel().setBlock(target, Blocks.GLASS.defaultBlockState(), 3);

        car.hitBlockAhead(Math.max(0.0, CarConfig.COLLISION_MIN_SPEED.get() - 0.01));

        helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.GLASS),
                "below-threshold production collision broke an eligible block");
        helper.succeed();
    }
}
