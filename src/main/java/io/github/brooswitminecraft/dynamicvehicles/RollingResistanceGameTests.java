package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
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
 * <p>Every car here drives on its own full-length lane of a {@link VehicleLanes} site, and {@link
 * VehicleLanes#assertStaysOnLane} checks on every tick of the measured window that each of its wheels' own
 * raycasts is hitting the material that wheel is meant to be on. That is what makes a speed comparison a
 * comparison of materials. These fixtures used to sit nose to tail in one lane of {@code settlement_test} on
 * 5x5 pads, on the assumption -- from a Sable/GameTest {@code forceDrive} "near-zero net position travel"
 * observation, see {@link CarFireDamageGameTests}' {@code burningContinuesAfterLeavingLava} javadoc -- that a
 * car does not really move. That holds only when GameTest happens to place the structure millions of blocks
 * out, where Sable's single-precision position rounds the car back in place every step (see {@link
 * VehicleLanes}). Wherever it lands with fine precision the car really travels: traced, the stone car crossed
 * off its pad onto grass at tick 20, drove over the sand car's pad, and rear-ended the sand car, which had
 * already stopped against the template's barrier wall at z=24 -- which is what PR #180's red CI run measured.
 * Velocity is still read through {@link CarEntity#getDeltaMovement()} and the {@link CarEntity#sableBody()}
 * adapter.
 *
 * <p>A lane is wider than the car's track for the reason the old 5x5 pads were not single blocks: these
 * tests need to know EXACTLY which real block every one of the car's four wheels (mount offsets &plusmn;0.8 m
 * in x, &plusmn;1.2 m in z -- {@code CarGeometry.MOUNTS}) is touching. A first draft of these tests used
 * single-block floors and measured nearly identical (sometimes even reversed) cruise speeds between a
 * "stone" car and a "sand" car, because most of each car's wheels were landing on the template's own
 * terrain -- confirmed with a throwaway diagnostic {@code @GameTest} that logged {@code Surfaces.at}'s real
 * result for each placed block directly (stone: grip 0.95/rollingResistance 0.02; sand: grip
 * 0.45/rollingResistance 0.16, deformability 0.87 -- exactly as expected), proving the surface math itself
 * was never the problem, only floor coverage. The per-tick lane check now enforces that for the whole
 * window rather than only at spawn.
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

    /** Ticks after spawn before a car's own Sable body exists and its wheels have found the lane; the
     * per-tick lane check starts here. */
    private static final long SETTLE_TICKS = 2L;
    /** These fixtures compare surfaces, so a lane may only show wear that leaves it the same surface. */
    private static final VehicleLanes.Wear LANE_WEAR = VehicleLanes.Wear.SAME_SURFACE;

    /** Each car's own lane: never shared, so no two cars here can ever meet. */
    private static final int STONE_LANE_X = VehicleLanes.FIRST_LANE_X;
    private static final int SAND_LANE_X = VehicleLanes.SECOND_LANE_X;
    private static final int THIRD_CAR_LANE_X = VehicleLanes.THIRD_LANE_X;
    private static final int SLAB_LANE_X = VehicleLanes.FIRST_LANE_X;

    /** Horizontal ground speed, m/s -- {@code CarEntity}'s own published {@code getDeltaMovement()}
     * (blocks/tick), the same quantity its private {@code horizontalSpeed()} computes for sound/telemetry,
     * read here rather than reimplemented against any internal state. */
    private static double horizontalSpeed(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return Math.hypot(v.x, v.z) * 20.0;
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
    @GameTest(template = "settlement_test", timeoutTicks = 300 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void sustainedThrottleReachesHigherSpeedOnStoneThanOnSand(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.STONE_VS_SAND, lanes -> {
            lanes.layLane(STONE_LANE_X, Blocks.STONE.defaultBlockState());
            lanes.layLane(SAND_LANE_X, Blocks.SAND.defaultBlockState());

            CarEntity onStone = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(STONE_LANE_X));
            CarEntity onSand = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(SAND_LANE_X));

            onStone.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
            onSand.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
            lanes.assertStaysOnLane(onStone, STONE_LANE_X, VehicleLanes.allWheels(Blocks.STONE), LANE_WEAR,
                    SETTLE_TICKS, DRIVE_TICKS - 1L, "stone car");
            lanes.assertStaysOnLane(onSand, SAND_LANE_X, VehicleLanes.allWheels(Blocks.SAND), LANE_WEAR,
                    SETTLE_TICKS, DRIVE_TICKS - 1L, "sand car");
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
        });
    }

    /**
     * AC2 (multiple grounded wheels on mixed materials, aggregated): three identical cars under the same
     * sustained gentle throttle -- one entirely on {@code Blocks.STONE}, one entirely on {@code
     * Blocks.SAND}, and a third straddling a real material boundary (its two x = -0.8 wheels, {@code
     * CarGeometry.MOUNTS} indices 0/2, over stone; its two x = +0.8 wheels, indices 1/3, over sand). Each
     * wheel's own raycast must land on its own real block and feed {@code Surfaces.at}'s result into that
     * wheel's own {@code WheelMath.tire} call independently (the per-wheel loop in {@code
     * SableCarBody.tick}), so the straddling car's cruise speed must land strictly between the two
     * single-material cars' -- neither matching pure stone (which would mean its sand-side wheels are not
     * contributing their own real resistance) nor pure sand (which would mean its stone-side wheels are
     * not). A bug that collapsed every wheel onto one shared surface (e.g. a stale cache keyed wrong, or
     * every wheel reading wheel 0's hit) would instead make the straddling car match one of the pure cases
     * exactly, not land between them.
     *
     * <p>The boundary runs along the lane, splitting the car's left wheels from its right ones. This rig
     * used to split front from rear instead, which only holds while the car stands still: a car that really
     * drives puts its rear wheels on whatever its front wheels just crossed, so within a couple of metres
     * every wheel was on stone. A left/right split is the one layout that keeps each wheel on one material
     * for the whole drive, so a bug collapsing every wheel onto one surface still shows up as the mixed car
     * matching a pure one. Uneven drag yaws a car on such a lane toward its sand side, so all three cars
     * are driven straight by the same lane-keeping driver, and the per-tick lane check holds each wheel pair
     * on its own material for the whole window.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void mixedMaterialWheelsAggregatePerWheelSurfaces(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.MIXED_MATERIALS, lanes -> {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockState sand = Blocks.SAND.defaultBlockState();
            lanes.layLane(STONE_LANE_X, stone);
            lanes.layLane(SAND_LANE_X, sand);
            lanes.layLane(THIRD_CAR_LANE_X, stone, sand);

            CarEntity onStone = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(STONE_LANE_X));
            CarEntity onSand = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(SAND_LANE_X));
            CarEntity mixed = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(THIRD_CAR_LANE_X));

            // All three get the same lane-keeping driver (see VehicleLanes.driveAlongLane): the mixed car needs
            // one to stay on its seam, and the single-material cars get identical treatment, which asks them
            // for essentially no steering.
            lanes.driveAlongLane(onStone, STONE_LANE_X, GENTLE_THROTTLE, DRIVE_TICKS);
            lanes.driveAlongLane(onSand, SAND_LANE_X, GENTLE_THROTTLE, DRIVE_TICKS);
            lanes.driveAlongLane(mixed, THIRD_CAR_LANE_X, GENTLE_THROTTLE, DRIVE_TICKS);
            lanes.assertStaysOnLane(onStone, STONE_LANE_X, VehicleLanes.allWheels(Blocks.STONE), LANE_WEAR,
                    SETTLE_TICKS, DRIVE_TICKS - 1L, "stone car");
            lanes.assertStaysOnLane(onSand, SAND_LANE_X, VehicleLanes.allWheels(Blocks.SAND), LANE_WEAR,
                    SETTLE_TICKS, DRIVE_TICKS - 1L, "sand car");
            lanes.assertStaysOnLane(mixed, THIRD_CAR_LANE_X, new Block[] {Blocks.STONE, Blocks.SAND, Blocks.STONE, Blocks.SAND},
                    LANE_WEAR, SETTLE_TICKS, DRIVE_TICKS - 1L, "mixed car");
            helper.runAfterDelay((long) DRIVE_TICKS, () -> {
                double stoneSpeed = horizontalSpeed(onStone);
                double sandSpeed = horizontalSpeed(onSand);
                double mixedSpeed = horizontalSpeed(mixed);
                helper.assertTrue(!Double.isNaN(stoneSpeed) && !Double.isNaN(sandSpeed) && !Double.isNaN(mixedSpeed),
                        "sustained throttle produced a NaN speed: stone=" + stoneSpeed + " sand=" + sandSpeed
                                + " mixed=" + mixedSpeed);
                helper.assertTrue(mixedSpeed < stoneSpeed,
                        "a car with its x=+0.8 wheels on real sand must reach a lower cruise speed than a car "
                                + "entirely on stone -- its sand-side wheels may not be contributing their own "
                                + "real resistance: stoneSpeed=" + stoneSpeed + " mixedSpeed=" + mixedSpeed);
                helper.assertTrue(mixedSpeed > sandSpeed,
                        "a car with its x=-0.8 wheels on real stone must reach a higher cruise speed than a car "
                                + "entirely on sand -- its stone-side wheels may not be contributing their own "
                                + "real resistance: sandSpeed=" + sandSpeed + " mixedSpeed=" + mixedSpeed);
                helper.succeed();
            });
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
    @GameTest(template = "settlement_test", timeoutTicks = 300 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void slabOverBuriedSandResolvesTheSlabsOwnMaterialNotTheBuriedBlock(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.SLAB_OVER_SAND, lanes -> {
            BlockState sand = Blocks.SAND.defaultBlockState();
            BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
            lanes.layLane(SLAB_LANE_X, VehicleLanes.FLOOR_Y - 1, sand, sand);
            lanes.layLane(SLAB_LANE_X, slab);
            lanes.layLane(SAND_LANE_X, sand);

            CarEntity onSlab = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(SLAB_LANE_X));
            CarEntity onBareSand = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(SAND_LANE_X));

            onSlab.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
            onBareSand.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
            // Every wheel's raycast must be stopping at the slab itself, never the buried sand: AC3's own claim,
            // now checked on every tick of the window rather than inferred from the cruise speed alone.
            lanes.assertStaysOnLane(onSlab, SLAB_LANE_X, VehicleLanes.allWheels(Blocks.STONE_SLAB), LANE_WEAR,
                    SETTLE_TICKS, DRIVE_TICKS - 1L, "slab car");
            lanes.assertStaysOnLane(onBareSand, SAND_LANE_X, VehicleLanes.allWheels(Blocks.SAND), LANE_WEAR,
                    SETTLE_TICKS, DRIVE_TICKS - 1L, "bare-sand car");
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
        });
    }

    /** Ticks to build real horizontal speed, well above {@code WheelMath.tire}'s own 0.05 m/s rolling
     * dead zone, before either losing ground contact or (the control) staying grounded. */
    private static final int LIFTOFF_ACCEL_TICKS = 60;
    /** Short enough to keep the airborne car's own free-fall vertical speed small relative to its
     * horizontal cruise speed -- {@code SableCarBody.tick}'s air-drag term scales with the FULL 3D speed,
     * so a long fall would eventually inflate drag on the airborne car for a reason that has nothing to do
     * with terrain rolling resistance (an unwanted confound, not the regression this test is for). */
    private static final int AIRBORNE_COAST_TICKS = 10;
    private static final int GROUNDED_LANE_X = VehicleLanes.FIRST_LANE_X;
    private static final int AIRBORNE_LANE_X = VehicleLanes.THIRD_LANE_X;
    /** The pit around the airborne car: 3 blocks either side of its lane's centre line, 3 behind it, and 12
     * ahead -- the ~10 m/s it carries into the window covers about 5 m in those 10 ticks, plus its own
     * 1.5 m half-length, with room to spare. */
    private static final int PIT_HALF_WIDTH = 3;
    private static final int PIT_BEHIND = 3;
    private static final int PIT_AHEAD = 12;

    /**
     * AC4 (raycast miss / lost contact): every wheel of one car is suspended over a cleared-out pit deeper
     * than {@code WheelMath.REST_LENGTH + 0.25} (the wheel raycast's own range), so {@code
     * level.clip(...).getType() == HitResult.Type.MISS} for all four wheels, exactly like {@code
     * SableCarBody.tick}'s own guards -- the missed wheel must publish fully-extended suspension travel
     * (0.0, see {@code suspensionTravel}'s own javadoc), kept as its own direct proof the miss setup is
     * real.
     *
     * <p>Review (PR #181): a first draft spawned this car STATIONARY over the pit and asserted only "no
     * crash, telemetry finite" -- vacuous, because {@code WheelMath.tire}'s own {@code vLong > 0.05} dead
     * zone (around line 114-116) already forces the rolling term to 0 for a car with no horizontal speed
     * at all, regardless of whether {@code SableCarBody.tick}'s airborne/{@code force <= 0} skip does
     * anything. A regression that wrongly applied terrain rolling drag to an ungrounded wheel would have
     * stayed green. This version establishes REAL horizontal speed first (driven on real sand, same as the
     * grounded control below, so both start identically), then removes the ground out from under that same
     * car -- the same "block changes under a never-recreated, never-repositioned body" pattern {@link
     * CarFireDamageGameTests#burningContinuesAfterLeavingLava} already uses -- and compares its speed loss
     * against a matched control that stays grounded on the same real sand at the same starting speed the
     * whole time, rather than against a hand-derived expected number. Both cars still feel the same
     * surface-independent air drag once moving; only the grounded control should ALSO feel real terrain
     * rolling resistance on top of that, so it must lose strictly more speed over the same short window.
     *
     * <p>The pit opens under wherever the airborne car has really got to when the window starts -- about 19
     * m down its lane -- and runs far enough ahead of it to cover the distance it covers during the window.
     * The old fixture dug the pit at the spawn point, so wherever the car really drove it was never over the
     * pit at all, and the grounded control had by then rear-ended it with its own rear wheels over the freshly
     * dug hole.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void airborneWheelsOverAPitGetNoTerrainRollingDrag(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.AIRBORNE_OVER_PIT, lanes -> {
            lanes.layLane(GROUNDED_LANE_X, Blocks.SAND.defaultBlockState());
            lanes.layLane(AIRBORNE_LANE_X, Blocks.SAND.defaultBlockState());

            CarEntity grounded = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(GROUNDED_LANE_X));
            CarEntity airborne = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(AIRBORNE_LANE_X));

            grounded.forceDrive(1.0, 0.0, LIFTOFF_ACCEL_TICKS);
            airborne.forceDrive(1.0, 0.0, LIFTOFF_ACCEL_TICKS);
            // The grounded control stays on its sand lane through the comparison window as well: that is what
            // makes it the matched control. The airborne car only needs its lane until the pit opens.
            lanes.assertStaysOnLane(grounded, GROUNDED_LANE_X, VehicleLanes.allWheels(Blocks.SAND), LANE_WEAR,
                    SETTLE_TICKS, LIFTOFF_ACCEL_TICKS + AIRBORNE_COAST_TICKS - 1L, "grounded control");
            lanes.assertStaysOnLane(airborne, AIRBORNE_LANE_X, VehicleLanes.allWheels(Blocks.SAND), LANE_WEAR,
                    SETTLE_TICKS, LIFTOFF_ACCEL_TICKS - 1L, "car about to go airborne");
            helper.runAfterDelay((long) LIFTOFF_ACCEL_TICKS, () -> {
                helper.assertTrue(grounded.sableBody() != null && airborne.sableBody() != null,
                        "a car's Sable body was never created during the liftoff acceleration phase");
                double groundedSpeedBefore = horizontalSpeed(grounded);
                double airborneSpeedBefore = horizontalSpeed(airborne);
                helper.assertTrue(groundedSpeedBefore > 0.05 && airborneSpeedBefore > 0.05,
                        "test setup never got either car moving above WheelMath.tire's own rolling dead zone "
                                + "before the airborne car lost contact -- a test at vLong == 0 would be vacuous: "
                                + "grounded=" + groundedSpeedBefore + " airborne=" + airborneSpeedBefore);
                for (double travel : SableCompat.wheelTravel(grounded.sableBody())) {
                    helper.assertTrue(travel > 0.0, "the grounded control was not actually touching the ground "
                            + "right before the comparison window: suspensionTravel=" + travel);
                }

                // The airborne car loses its ground the instant the comparison window starts; the grounded
                // control's real sand floor is untouched. Clear a pit well wider than the car's own
                // track/wheelbase (CarGeometry: halfX 0.95, halfZ 1.5), through the floor and the air above it
                // (y 0..7), so every wheel's raycast -- at most restLength + 0.25 = 0.9 m -- finds nothing below
                // it, starting behind the car and running far enough ahead for the whole window.
                int pitCentreZ = (int) Math.floor(lanes.relativeZ(airborne));
                for (int dx = -PIT_HALF_WIDTH; dx <= PIT_HALF_WIDTH; dx++) {
                    for (int dz = -PIT_BEHIND; dz <= PIT_AHEAD; dz++) {
                        for (int y = 0; y <= 7; y++) {
                            lanes.setBlock(new BlockPos(AIRBORNE_LANE_X + dx, y, pitCentreZ + dz), Blocks.AIR.defaultBlockState());
                        }
                    }
                }
                grounded.forceDrive(0.0, 0.0, AIRBORNE_COAST_TICKS);
                airborne.forceDrive(0.0, 0.0, AIRBORNE_COAST_TICKS);
                helper.runAfterDelay((long) AIRBORNE_COAST_TICKS, () -> {
                    helper.assertTrue(!airborne.isRemoved(), "the airborne car crashed/was removed while falling "
                            + "with no ground under it");
                    double[] groundedTravel = SableCompat.wheelTravel(grounded.sableBody());
                    double[] airborneTravel = SableCompat.wheelTravel(airborne.sableBody());
                    for (double travel : groundedTravel) {
                        helper.assertTrue(travel > 0.0,
                                "the grounded control stopped touching the ground during the comparison window "
                                        + "(not a valid matched control any more): suspensionTravel=" + travel);
                    }
                    for (int wheel = 0; wheel < airborneTravel.length; wheel++) {
                        helper.assertTrue(airborneTravel[wheel] == 0.0,
                                "wheel " + wheel + " over the open pit reported nonzero suspension travel (should "
                                        + "read fully extended/ungrounded, 0.0): " + airborneTravel[wheel]);
                    }

                    double groundedSpeedAfter = horizontalSpeed(grounded);
                    double airborneSpeedAfter = horizontalSpeed(airborne);
                    helper.assertTrue(!Double.isNaN(groundedSpeedAfter) && !Double.isNaN(airborneSpeedAfter),
                            "the comparison window produced a NaN speed: grounded=" + groundedSpeedAfter
                                    + " airborne=" + airborneSpeedAfter);
                    double groundedLoss = groundedSpeedBefore - groundedSpeedAfter;
                    double airborneLoss = airborneSpeedBefore - airborneSpeedAfter;
                    helper.assertTrue(groundedLoss > airborneLoss,
                            "a car that stayed grounded on real sand must lose MORE horizontal speed over this "
                                    + "window than a matched car at the same starting speed that lost ground contact "
                                    + "-- the grounded car alone should feel real terrain rolling resistance on top "
                                    + "of the same surface-independent air drag both feel: groundedLoss=" + groundedLoss
                                    + " airborneLoss=" + airborneLoss);
                    helper.succeed();
                });
            });
        });
    }
}
