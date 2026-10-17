package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Production-adapter regressions for MINECRAFT-259 (review, PR #159): the pure {@link EnvironmentMath}
 * unit tests only ever feed it already-normalized constants, which cannot catch a bug in HOW the real
 * adapter -- {@link SableCarBody#isFoliage} and the fraction it feeds {@link EnvironmentMath#submersionFraction}
 * -- turns real block states and vanilla's own {@code Entity#getFluidHeight} into those inputs. These run
 * against real {@link BlockState}s and a real, ticking {@link CarEntity} instead.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class EnvironmentGameTests {
    private EnvironmentGameTests() {}

    @GameTest(template = "settlement_test")
    public static void isFoliageClassifiesAirVegetationAndFluidCorrectly(GameTestHelper helper) {
        BlockPos air = new BlockPos(5, 2, 5);
        BlockPos grass = new BlockPos(6, 2, 5);
        BlockPos water = new BlockPos(7, 2, 5);
        BlockPos stone = new BlockPos(8, 2, 5);
        helper.setBlock(air, Blocks.AIR);
        helper.setBlock(grass, Blocks.SHORT_GRASS);
        helper.setBlock(water, Blocks.WATER);
        helper.setBlock(stone, Blocks.STONE);
        ServerLevel level = helper.getLevel();

        helper.assertTrue(!isFoliageAt(helper, level, air), "air was classified as pass-through foliage");
        helper.assertTrue(isFoliageAt(helper, level, grass), "short grass was not classified as pass-through foliage");
        helper.assertTrue(!isFoliageAt(helper, level, water), "water was classified as pass-through foliage (it must stay the buoyancy/drag block's alone)");
        helper.assertTrue(!isFoliageAt(helper, level, stone), "a solid, collidable block (stone) was classified as pass-through foliage");
        helper.succeed();
    }

    private static boolean isFoliageAt(GameTestHelper helper, ServerLevel level, BlockPos relativePos) {
        BlockPos pos = helper.absolutePos(relativePos);
        return SableCarBody.isFoliage(level.getBlockState(pos), level, pos);
    }

    /**
     * A short vehicle (INDY, 0.7 m tall) sitting in two full, non-surface layers of water -- so the one
     * layer its own box overlaps is reported at full height, not the top surface's partial one -- must
     * read back as fully (1.0) submerged, not clamped short of it by its own small height (the bug: raw
     * {@code getFluidHeight} clamped directly to 0..1 without dividing by the vehicle's own height).
     */
    @GameTest(template = "settlement_test")
    public static void shortVehicleFullySubmergedReachesFullSubmersionFraction(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR); // bounds the pool explicitly; not relied on by the assertion.
        CarEntity car = helper.spawn(DynamicVehiclesMod.INDY.get(), new BlockPos(5, 2, 5));
        helper.runAfterDelay(2L, () -> {
            double vehicleHeight = 2.0 * car.spec().halfY();
            double fraction = EnvironmentMath.submersionFraction(car.getFluidHeight(FluidTags.WATER), vehicleHeight);
            helper.assertTrue(fraction == 1.0, "a short vehicle fully covered by water did not read back as fully submerged: " + fraction);
            helper.succeed();
        });
    }

    /**
     * A tall vehicle (MONSTER_TRUCK, 1.5 m tall) sitting in exactly one layer of water must read back as
     * only partially submerged, not clamped up to "fully submerged" the instant any water is detected
     * (the bug this review caught: raw depth clamped directly to 0..1 reaches ~1.0 once the water is
     * deeper than about a block, long before a tall vehicle's own height is actually covered).
     */
    @GameTest(template = "settlement_test")
    public static void tallVehiclePartiallySubmergedUnderOneWaterLayerIsNotReadAsFullySubmerged(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.MONSTER_TRUCK.get(), new BlockPos(5, 2, 5));
        helper.runAfterDelay(2L, () -> {
            double vehicleHeight = 2.0 * car.spec().halfY();
            double fraction = EnvironmentMath.submersionFraction(car.getFluidHeight(FluidTags.WATER), vehicleHeight);
            helper.assertTrue(fraction > 0.0, "a tall vehicle sitting in water read back as completely dry: " + fraction);
            helper.assertTrue(fraction < 1.0, "a tall vehicle only one shallow water layer deep read back as fully submerged: " + fraction);
            helper.succeed();
        });
    }

    /** A vehicle on dry land gets no water submersion at all -- the trivial floor this all sits on. */
    @GameTest(template = "settlement_test")
    public static void dryVehicleHasNoWaterSubmersion(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 2, 5));
        helper.runAfterDelay(2L, () -> {
            double vehicleHeight = 2.0 * car.spec().halfY();
            double fraction = EnvironmentMath.submersionFraction(car.getFluidHeight(FluidTags.WATER), vehicleHeight);
            helper.assertTrue(fraction == 0.0, "a dry vehicle read back as submerged: " + fraction);
            helper.succeed();
        });
    }
}
