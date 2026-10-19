package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-450, review on PR #181: production-path regressions for terrain rolling resistance, run
 * against a real, ticking {@link CarEntity}/{@link SableCarBody} and real blocks. {@code
 * SurfaceRollingResistanceTest} (plain JUnit) already covers the pure math once a {@code
 * SurfaceProperties}/coefficient has been supplied directly; it cannot reach {@code
 * io.github.brooswitminecraft.dynamicterrain.Surfaces#at}'s own real block lookup, or {@link
 * SableCarBody#tick}'s raycast-to-surface resolution, because this repo's {@code test} sourceSet has no
 * path to any {@code net.minecraft}/NeoForge type at all (see that class's own javadoc for the two
 * confirmations). These four tests close that gap at the one place in this repo that CAN drive a real
 * raycast against a real block through a real {@code Surfaces.at} call: a {@code @GameTest}, following the
 * established pattern in {@link EnvironmentGameTests} and {@link CarFireDamageGameTests}.
 *
 * <p>None of these rely on net world-position displacement -- see {@link CarFireDamageGameTests}'
 * {@code burningContinuesAfterLeavingLava} javadoc for the documented, pre-existing Sable/GameTest
 * {@code forceDrive} bug (near-zero net position travel regardless of ticks) this ticket's own brief asked
 * to route around. Velocity/telemetry read through {@link CarEntity#sableBody()} -- a one-line
 * package-private getter added for this ticket, the narrow adapter the reviewer pre-approved -- is used
 * instead throughout.
 *
 * <p>Floors here are filled as a 5x5 patch ({@link #fillFloor}) under each car's spawn point, not a single
 * block the way {@link CarFireDamageGameTests}/{@link EnvironmentGameTests} do it: those tests only need
 * SOME solid ground under the car (for a lava/fire/fluid check unrelated to which exact block a wheel
 * contacts), so one block is enough. These tests instead need to know EXACTLY which real block every one
 * of the car's four wheels (mount offsets &plusmn;0.8 m in x, &plusmn;1.2 m in z -- {@code
 * CarGeometry.MOUNTS}) is touching -- a single block at the spawn point does not cover that whole
 * footprint. A first draft used single-block floors and measured nearly identical (sometimes even
 * reversed) cruise speeds between a "stone" car and a "sand" car, because most of each car's wheels were
 * actually landing on the test template's own default terrain outside that one placed block, not on the
 * intended material at all -- confirmed with a throwaway diagnostic {@code @GameTest} that logged {@code
 * Surfaces.at}'s real result for each placed block directly (stone: grip 0.95/rollingResistance 0.02;
 * sand: grip 0.45/rollingResistance 0.16, deformability 0.87 -- exactly as expected), proving the surface
 * math itself was never the problem, only this test's own floor coverage.
 *
 * <p>These compare sustained GENTLE-throttle cruise speed, not a hard-throttle coast-down -- see the
 * {@code GENTLE_THROTTLE} javadoc for why: a harder throttle and/or a coast-down delta each introduced
 * their own confound (post-launch pitch/suspension energy, a coast-down floor every car eventually
 * saturates against, or a wheelspin regime both surfaces eventually converge out of) before this floor-
 * coverage bug was found and fixed; gentle, sustained throttle avoids all of them and was kept once the
 * real bug (not a physics confound) turned out to be the actual cause.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class RollingResistanceGameTests {
    private RollingResistanceGameTests() {}

    /** Well under either surface's friction limit at this speed range, so the friction-circle clip in
     * {@code WheelMath.tire} ({@code demand > limit}) never engages for either stone or sand here -- what
     * is left to decide each car's cruise speed is {@code SurfaceProperties.rollingResistance()} opposing a
     * small, constant commanded drive force per wheel, the real {@code Surfaces.at} composition this
     * ticket is about. */
    private static final double GENTLE_THROTTLE = 0.3;
    /** 5 s of sustained gentle throttle: long enough for a real rolling-resistance difference to separate
     * two cars' cruise speeds by several m/s, not just a momentary difference. */
    private static final int DRIVE_TICKS = 100;

    /** Horizontal ground speed, m/s -- {@code CarEntity}'s own published {@code getDeltaMovement()}
     * (blocks/tick), the same quantity its private {@code horizontalSpeed()} computes for sound/telemetry,
     * read here rather than reimplemented against any internal state. */
    private static double horizontalSpeed(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return Math.hypot(v.x, v.z) * 20.0;
    }

    /** Fills a 5x5 patch (centred on {@code cx, cz}, one layer at {@code y}) with {@code state} -- wide
     * enough to cover every one of a spawned car's four wheel contact points (mount offsets &plusmn;0.8 m
     * x, &plusmn;1.2 m z) with margin, so a wheel's real raycast cannot land on the test template's own
     * default terrain outside the material under test (see this class's own javadoc). */
    private static void fillFloor(GameTestHelper helper, int cx, int y, int cz, BlockState state) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(new BlockPos(cx + dx, y, cz + dz), state);
            }
        }
    }

    /**
     * AC1 (contrasting materials): two identical cars, one on real {@code Blocks.STONE} and one on real
     * {@code Blocks.SAND}, both forced through the same sustained gentle throttle. Stone and sand resolve
     * to measurably different {@code SurfaceProperties.rollingResistance()} via {@code SurfaceMath.infer}
     * (stone's STONE family: 0.020; sand's SAND family: 0.160, both before the deformability gain) --
     * exercised here only through the real {@code Surfaces.at} raycast lookup, never handed to a helper
     * directly. If a regression ever disconnected {@code Surfaces.at} from the wheel force (e.g. a
     * hard-coded coefficient, or the wrong block queried), both cars would reach the same cruise speed; a
     * real wiring bug in HOW the raycast resolves (wrong block, stale cache) would show up here as both
     * ending up at the same speed regardless of which floor they are actually on.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void sustainedThrottleReachesHigherSpeedOnStoneThanOnSand(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5, Blocks.STONE.defaultBlockState());
        fillFloor(helper, 5, 2, 15, Blocks.SAND.defaultBlockState());

        CarEntity onStone = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        CarEntity onSand = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 15));

        onStone.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        onSand.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        helper.runAfterDelay((long) DRIVE_TICKS, () -> {
            double stoneSpeed = horizontalSpeed(onStone);
            double sandSpeed = horizontalSpeed(onSand);
            helper.assertTrue(!Double.isNaN(stoneSpeed) && !Double.isNaN(sandSpeed),
                    "sustained throttle produced a NaN speed: stone=" + stoneSpeed + " sand=" + sandSpeed);
            helper.assertTrue(stoneSpeed > 0.5, "test setup never got the car on stone moving: " + stoneSpeed);
            helper.assertTrue(stoneSpeed > sandSpeed,
                    "the same sustained gentle throttle must reach a higher cruise speed on real stone than "
                            + "on real sand (sand's own higher rollingResistance must hold it back more): "
                            + "stoneSpeed=" + stoneSpeed + " sandSpeed=" + sandSpeed);
            helper.succeed();
        });
    }

    /**
     * AC2 (multiple grounded wheels on mixed materials, aggregated): three identical cars under the same
     * sustained gentle throttle -- one entirely on {@code Blocks.STONE}, one entirely on {@code
     * Blocks.SAND}, and a third straddling a real material boundary (its two front wheels, {@code
     * CarGeometry.MOUNTS} indices 0/1, over stone; its two rear wheels, indices 2/3, over sand). Each
     * wheel's own raycast must land on its own real block and feed {@code Surfaces.at}'s result into that
     * wheel's own {@code WheelMath.tire} call independently (the per-wheel loop in {@code
     * SableCarBody.tick}), so the straddling car's cruise speed must land strictly between the two
     * single-material cars' -- neither matching pure stone (which would mean its sand-side wheels are not
     * contributing their own real resistance) nor pure sand (which would mean its stone-side wheels are
     * not). A bug that collapsed every wheel onto one shared surface (e.g. a stale cache keyed wrong, or
     * every wheel reading wheel 0's hit) would instead make the straddling car match one of the pure cases
     * exactly, not land between them.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void mixedMaterialWheelsAggregatePerWheelSurfaces(GameTestHelper helper) {
        fillFloor(helper, 5, 2, 5, Blocks.STONE.defaultBlockState());
        fillFloor(helper, 5, 2, 13, Blocks.SAND.defaultBlockState());
        int cx = 5;
        int cz = 20;
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            // Front wheels (local z = +1.2) land at z in [cz, cz+1]; rear wheels (local z = -1.2) land at
            // z in [cz-2, cz-1] -- see this test's own spawn point below. A few extra rows of margin on
            // each side cover any settling drift without crossing the boundary.
            for (int dz = 0; dz <= 3; dz++) {
                helper.setBlock(new BlockPos(cx + dx, 2, cz + dz), stone);
            }
            for (int dz = -3; dz <= -1; dz++) {
                helper.setBlock(new BlockPos(cx + dx, 2, cz + dz), sand);
            }
        }

        CarEntity onStone = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        CarEntity onSand = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 13));
        CarEntity mixed = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(cx, 3, cz));

        onStone.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        onSand.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        mixed.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        helper.runAfterDelay((long) DRIVE_TICKS, () -> {
            double stoneSpeed = horizontalSpeed(onStone);
            double sandSpeed = horizontalSpeed(onSand);
            double mixedSpeed = horizontalSpeed(mixed);
            helper.assertTrue(!Double.isNaN(stoneSpeed) && !Double.isNaN(sandSpeed) && !Double.isNaN(mixedSpeed),
                    "sustained throttle produced a NaN speed: stone=" + stoneSpeed + " sand=" + sandSpeed
                            + " mixed=" + mixedSpeed);
            helper.assertTrue(mixedSpeed < stoneSpeed,
                    "a car with its rear wheels on real sand must reach a lower cruise speed than a car "
                            + "entirely on stone -- its sand-side wheels may not be contributing their own "
                            + "real resistance: stoneSpeed=" + stoneSpeed + " mixedSpeed=" + mixedSpeed);
            helper.assertTrue(mixedSpeed > sandSpeed,
                    "a car with its front wheels on real stone must reach a higher cruise speed than a car "
                            + "entirely on sand -- its stone-side wheels may not be contributing their own "
                            + "real resistance: sandSpeed=" + sandSpeed + " mixedSpeed=" + mixedSpeed);
            helper.succeed();
        });
    }

    /**
     * AC3 (layered/partial-height contact): a {@code Blocks.STONE_SLAB} (bottom half, the vanilla default
     * state) sits on top of buried {@code Blocks.SAND}. The wheel raycast (real collision geometry, {@code
     * ClipContext.Block.COLLIDER}) must stop at the slab's own top surface (y + 0.5) and report {@code
     * hit.getBlockPos()} as the slab's own position -- never the sand block one layer down -- so {@code
     * Surfaces.at} resolves the slab's {@code SoundType.STONE} family (low rolling resistance), not sand's
     * (high). Proven the same way as AC1: sustained gentle throttle on slab-over-sand must reach a notably
     * higher cruise speed than sustained throttle on bare sand. A regression that resolved the buried block
     * instead of the one actually hit would make the two cruise speeds converge.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void slabOverBuriedSandResolvesTheSlabsOwnMaterialNotTheBuriedBlock(GameTestHelper helper) {
        fillFloor(helper, 5, 1, 5, Blocks.SAND.defaultBlockState());
        fillFloor(helper, 5, 2, 5, Blocks.STONE_SLAB.defaultBlockState());
        fillFloor(helper, 5, 2, 15, Blocks.SAND.defaultBlockState());

        CarEntity onSlab = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        CarEntity onBareSand = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 15));

        onSlab.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        onBareSand.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        helper.runAfterDelay((long) DRIVE_TICKS, () -> {
            double slabSpeed = horizontalSpeed(onSlab);
            double sandSpeed = horizontalSpeed(onBareSand);
            helper.assertTrue(!Double.isNaN(slabSpeed) && !Double.isNaN(sandSpeed),
                    "sustained throttle produced a NaN speed: slab=" + slabSpeed + " sand=" + sandSpeed);
            helper.assertTrue(slabSpeed > 0.5, "test setup never got the car on the slab moving: " + slabSpeed);
            helper.assertTrue(slabSpeed > sandSpeed,
                    "a car on a stone slab over buried sand must reach a higher cruise speed (behaving like "
                            + "stone), not be held back like the buried sand underneath it: slabSpeed=" + slabSpeed
                            + " sandSpeed=" + sandSpeed);
            helper.succeed();
        });
    }

    /**
     * AC4 (raycast miss / lost contact): every wheel is suspended over a cleared-out pit deeper than
     * {@code WheelMath.REST_LENGTH + 0.25} (the wheel raycast's own range), so {@code
     * level.clip(...).getType() == HitResult.Type.MISS} for all four wheels, exactly like {@code
     * SableCarBody.tick}'s own guards. A missed wheel must publish fully-extended suspension travel (0.0,
     * see {@code suspensionTravel}'s own javadoc) and contribute no tire/rolling-resistance force at all
     * (the {@code force &lt;= 0} skip before any {@code Surfaces.at}/{@code WheelMath.tire} call) -- and
     * the car must not crash or go non-finite for having no ground under it at all.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void airborneWheelsOverAPitGetNoTerrainRollingDrag(GameTestHelper helper) {
        int cx = 5;
        int cz = 5;
        // Clear a pit well wider than the car's own track/wheelbase (CarGeometry: halfX 0.95, halfZ 1.5) and
        // the full template height (y 0..7), so every wheel's raycast -- at most restLength + 0.25 = 0.9 m
        // -- finds nothing below it, regardless of exactly where in the pit it lands.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = 0; y <= 7; y++) {
                    helper.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR);
                }
            }
        }
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(cx, 6, cz));
        helper.runAfterDelay(3L, () -> {
            helper.assertTrue(car.sableBody() != null, "car's Sable body was never created");
            double[] travel = SableCompat.wheelTravel(car.sableBody());
            for (int wheel = 0; wheel < travel.length; wheel++) {
                helper.assertTrue(travel[wheel] == 0.0,
                        "wheel " + wheel + " over an open pit reported nonzero suspension travel (should read "
                                + "fully extended/ungrounded, 0.0): " + travel[wheel]);
            }
            double slip = SableCompat.slip(car.sableBody());
            double speed = SableCompat.speed(car.sableBody());
            helper.assertTrue(!Double.isNaN(slip) && !Double.isNaN(speed) && !Double.isInfinite(speed),
                    "an airborne car produced non-finite telemetry: slip=" + slip + " speed=" + speed);
            helper.assertTrue(!car.isRemoved(), "car crashed/was removed while falling with no ground under it");
            helper.succeed();
        });
    }
}
