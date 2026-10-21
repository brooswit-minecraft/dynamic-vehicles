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

        // At default retention 0.3, 100m/s leaves a probabilistic third break.
        car.hitBlockAhead(1000.0);

        for (int block = 0; block < 3; block++) {
            helper.assertTrue(helper.getLevel().getBlockState(front(car, block)).isAir(),
                    "severe production collision did not break eligible block " + block);
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void severeCrashSharesBudgetAcrossHeights(GameTestHelper helper) {
        CarEntity car = fixture(helper);
        int budget = CarConfig.COLLISION_SEVERE_MAX_BLOCKS.get();
        for (int block = 0; block <= budget; block++) {
            helper.getLevel().setBlock(front(car, block), Blocks.GLASS.defaultBlockState(), 3);
            helper.getLevel().setBlock(front(car, block).above(), Blocks.GLASS.defaultBlockState(), 3);
        }
        double retention = CarConfig.COLLISION_BREAK_SPEED_SCALE.get();
        helper.assertTrue(retention > 0, "fixture requires positive speed retention");
        car.hitBlockAhead(1000 / Math.pow(retention, budget));
        int broken = 0;
        for (int block = 0; block <= budget; block++) {
            for (int height = 0; height < 2; height++) {
                if (helper.getLevel().getBlockState(front(car, block).above(height)).isAir()) broken++;
            }
        }
        helper.assertTrue(broken == budget, "one impact must share its successful-break budget; got " + broken);
        helper.assertTrue(helper.getLevel().getBlockState(front(car, budget)).is(Blocks.GLASS),
                "block beyond the successful-break budget was destroyed");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void fluidStopsSevereTraversal(GameTestHelper helper) {
        CarEntity car = fixture(helper);
        helper.getLevel().setBlock(front(car, 0), Blocks.GLASS.defaultBlockState(), 3);
        helper.getLevel().setBlock(front(car, 1), Blocks.WATER.defaultBlockState(), 3);
        helper.getLevel().setBlock(front(car, 2), Blocks.GLASS.defaultBlockState(), 3);
        car.hitBlockAhead(1000.0);
        helper.assertTrue(helper.getLevel().getBlockState(front(car, 0)).isAir(), "first glass must break");
        helper.assertTrue(helper.getLevel().getBlockState(front(car, 2)).is(Blocks.GLASS),
                "collision must not traverse through fluid");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void ordinaryCrashCannotSpendAnotherBudgetAtUpperHeight(GameTestHelper helper) {
        CarEntity car = fixture(helper);
        BlockPos lower = front(car, 0);
        helper.getLevel().setBlock(lower, Blocks.GLASS.defaultBlockState(), 3);
        helper.getLevel().setBlock(lower.above(), Blocks.GLASS.defaultBlockState(), 3);
        double speed = Math.nextDown(CarConfig.COLLISION_SEVERE_SPEED.get());
        helper.assertTrue(CollisionMath.breakChance(speed, 0.3, CarConfig.COLLISION_MIN_SPEED.get(),
                CarConfig.COLLISION_MAX_HARDNESS.get()) == 1.0, "fixture requires guaranteed ordinary glass break");
        car.hitBlockAhead(speed);
        helper.assertTrue(helper.getLevel().getBlockState(lower).isAir(), "ordinary impact must break first glass");
        helper.assertTrue(helper.getLevel().getBlockState(lower.above()).is(Blocks.GLASS),
                "ordinary impact spent a second budget at the upper probe");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void refusedDestructionStopsBeforeBlocksBehindBarrier(GameTestHelper helper) {
        int[] attempts = {0};
        CarEntity car = new CarEntity(DynamicVehiclesMod.CAR.get(), helper.getLevel()) {
            @Override
            boolean destroyImpactBlock(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
                attempts[0]++;
                return false;
            }
        };
        BlockPos origin = helper.absolutePos(new BlockPos(5, 2, 5));
        car.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0, 0);
        car.setHeadingForTest(new Vec3(0, 0, 1));
        for (int i = 0; i < 3; i++) {
            helper.getLevel().setBlock(front(car, i), Blocks.GLASS.defaultBlockState(), 3);
        }
        car.hitBlockAhead(1000);
        helper.assertTrue(attempts[0] == 1, "a refused destruction must terminate traversal immediately");
        for (int i = 0; i < 3; i++) {
            helper.assertTrue(helper.getLevel().getBlockState(front(car, i)).is(Blocks.GLASS),
                    "refused barrier or block behind it was modified");
        }
        helper.succeed();
    }

    /** The third crossed voxel is missed by sampling at one-metre intervals on an oblique ray. */
    @GameTest(template = "settlement_test")
    public static void obliqueCrashVisitsIntermediateCrossedVoxelWithoutBreakingPastBudget(GameTestHelper helper) {
        int oldBudget = CarConfig.COLLISION_SEVERE_MAX_BLOCKS.get();
        try {
            CarConfig.COLLISION_SEVERE_MAX_BLOCKS.set(3);
            CarEntity car = fixture(helper);
            BlockPos origin = helper.absolutePos(new BlockPos(5, 2, 5));
            car.moveTo(origin.getX() + 0.1, origin.getY(), origin.getZ() + 0.1, 0, 0);
            car.setHeadingForTest(new Vec3(0.8, 0, 0.6));
            helper.assertTrue(Math.abs(car.spec().halfZ() + 0.6 - 2.1) < 1e-9,
                    "oblique fixture assumes the base car's 2.1m collision reach");
            // Start (1.78,1.36), then cross x=2, z=2, x=3 relative to origin.
            BlockPos[] crossed = {origin.offset(1, 0, 1), origin.offset(2, 0, 1),
                    origin.offset(2, 0, 2), origin.offset(3, 0, 2)};
            for (BlockPos pos : crossed) helper.getLevel().setBlock(pos, Blocks.GLASS.defaultBlockState(), 3);
            car.hitBlockAhead(1000);
            for (int i = 0; i < 3; i++) {
                helper.assertTrue(helper.getLevel().getBlockState(crossed[i]).isAir(),
                        "oblique traversal skipped crossed voxel " + crossed[i]);
            }
            helper.assertTrue(helper.getLevel().getBlockState(crossed[3]).is(Blocks.GLASS),
                    "oblique traversal exceeded the shared successful-break budget");
        } finally {
            CarConfig.COLLISION_SEVERE_MAX_BLOCKS.set(oldBudget);
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
