package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-446: production-path regressions for wing downforce/drag, run against a real, ticking {@link
 * CarEntity}/{@link SableCarBody} and a real per-car config override (same {@code withLoadedOverride}
 * pattern as {@link VehicleSpecOverridesGameTests}) &mdash; {@link VehicleSpecWingTest}'s pure tests
 * already cover {@link VehicleSpec#wingDownforceNewtons} and the roster's own stock defaults in isolation;
 * what they cannot catch is a bug in how {@link SableCarBody#tick} actually applies these fields to the
 * real rigid body (wrong axle point, wrong sign, wrong cadence).
 *
 * <p>The front/rear-balance and speed-growth tests below compare a winged car against an otherwise
 * identical STOCK car driven with the exact same throttle profile, rather than asserting on the winged
 * car's raw numbers alone: accelerating under throttle already shifts load toward the rear on its own
 * (weight transfer under drive torque), a real effect with nothing to do with this ticket, confirmed
 * live &mdash; a first draft asserted front-suspension-travel-exceeds-rear directly and failed because that
 * acceleration-driven rear squat outweighed a modest wing coefficient's own front bias. Comparing each
 * winged measurement against a stock car driven through the identical profile cancels that common
 * confound and isolates the wing's own contribution.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class WingAeroGameTests {
    private WingAeroGameTests() {}

    // ---- Inputs -------------------------------------------------------------------------------------
    /** Well under the tire's grip limit, so a comparison between two cars reflects the forces under test
     * rather than which car's wheels broke traction first (same rationale as {@code
     * RollingResistanceGameTests.GENTLE_THROTTLE}). */
    private static final double GENTLE_THROTTLE = 0.3;
    private static final double FULL_THROTTLE = 1.0;
    private static final double NO_THROTTLE = 0.0;
    /** Enough to move a fully submerged car without fighting buoyancy hard enough to launch it out of the
     * water column before the check that follows. */
    private static final double SUBMERGED_THROTTLE = 0.5;
    private static final double NO_STEER = 0.0;

    // ---- Windows (ticks) ----------------------------------------------------------------------------
    /** 5 s of sustained throttle: long enough for a real aerodynamic difference to separate two
     * identically-driven cars, short enough to stay inside each test's own {@code timeoutTicks}. */
    private static final int DRIVE_TICKS = 100;
    /** {@code CarEntity} creates its real Sable body on its own first server tick, so
     * {@link #suspensionTravel} reads {@code null} until at least one tick has passed; this is the delay
     * every "measure at rest, before driving" leg waits out first. */
    private static final int BODY_SETTLE_TICKS = 5;
    /** Long enough that a spurious force at rest would show up as real drift, not just jitter. */
    private static final int AT_REST_OBSERVE_TICKS = 100;
    private static final int COAST_SPIN_UP_TICKS = 60;
    private static final int COAST_TICKS = 100;
    /** Sampling cadence across the coast-down, so a transient speed-up partway through is caught rather
     * than averaged away by a single end-of-window reading. */
    private static final int COAST_SAMPLE_INTERVAL_TICKS = 10;

    // ---- Wing coefficients under test (N per (m/s)^2) -----------------------------------------------
    /** Strong enough that its signal clearly exceeds the acceleration-driven rear-squat confound described
     * in this class' own javadoc, without being so large the suspension simply bottoms out. */
    private static final double STRONG_DOWNFORCE_COEFF = 8.0;
    /** A plausible, middle-of-the-range wing for the tests that only need SOME real downforce present. */
    private static final double MODERATE_DOWNFORCE_COEFF = 4.0;
    /** Both well above the roster's own stock 0.4, so drag's effect is unmistakable within a short window;
     * the elevated one is used where the test turns on drag actually dominating the coast-down. */
    private static final double MODERATE_DRAG_COEFF = 2.0;
    private static final double ELEVATED_DRAG_COEFF = 3.0;

    // ---- Thresholds ---------------------------------------------------------------------------------
    /** Test-setup floor: below this a car never really got going, so the measurement that follows would be
     * reading settling noise rather than the forces under test. */
    private static final double MOVING_SPEED_FLOOR = 1.0;
    /** As {@link #MOVING_SPEED_FLOOR}, for the full-throttle legs that should reach a genuinely high speed
     * (where downforce, being speed-squared, is actually significant). */
    private static final double REAL_SPEED_FLOOR = 3.0;
    /** A car left untouched at rest should read essentially zero; this allows for the suspension's own
     * settling without allowing real drift. */
    private static final double AT_REST_SPEED_CEILING = 0.2;
    /** Floating-point noise floor for "this speed actually went up" / "this change is actually measurable"
     * comparisons, so an exact-equality reading cannot register as a real change. */
    private static final double NEGLIGIBLE = 1e-6;
    /** Full submersion, as {@code EnvironmentMath.submersionFraction} reports it. */
    private static final double FULL_SUBMERSION = 1.0;
    /** How far the net compression change may differ between {@link #ONE_SUB_STEP} and
     * {@link #MANY_SUB_STEPS}. Generous on purpose: wing downforce itself is applied once per tick
     * regardless of sub-step count, but the suspension/tire forces a compression reading ALSO reflects do
     * have their own real, documented sub-step sensitivity (see {@code CarDrivetrainGameTests
     * #wheelSubStepCountDoesNotMateriallyChangeReachedSpeedAfterAFixedWindow}, which uses a band for the
     * same reason) -- this catches a wing force wrongly applied per sub-step (which would scale with the
     * count), not those expected few-percent differences. */
    private static final double SUB_STEP_RATIO_MIN = 0.5;
    private static final double SUB_STEP_RATIO_MAX = 2.0;
    private static final int ONE_SUB_STEP = 1;
    private static final int MANY_SUB_STEPS = 4;

    // ---- Test world layout --------------------------------------------------------------------------
    /** Half-width of the floor patch under each spawn point: wide enough that every one of a car's four
     * wheel raycasts lands on placed floor rather than the template's own terrain. */
    private static final int FLOOR_PATCH_RADIUS = 2;
    private static final int LANE_X = 5;
    private static final int FLOOR_Y = 2;
    /** One block above {@link #FLOOR_Y} -- a car spawns resting on the floor patch, not inside it. */
    private static final int SPAWN_Y = FLOOR_Y + 1;
    /** Two lanes far enough apart that two cars driven at once never interact. */
    private static final int FIRST_LANE_Z = 5;
    private static final int SECOND_LANE_Z = 20;

    /** Read submersion as soon as the car has ticked at all: driving first would let buoyancy float it up
     * out of full submersion, which is orthogonal, expected behaviour rather than what this checks. */
    private static final int SUBMERSION_READ_TICKS = 2;

    /** Game ticks per second, for converting {@code getDeltaMovement()} (blocks/tick) to m/s. */
    private static final double TICKS_PER_SECOND = 20.0;

    /** Wheel indices of each axle in {@code CarGeometry.MOUNTS} order: mounts 0/1 are the front pair
     * (local z = +1.2), 2/3 the rear (z = -1.2) -- the same {@code mount[2] > 0} front test {@code
     * SableCarBody.tick} itself applies. Named here so no test hard-codes a bare index. */
    private static final int[] FRONT_WHEELS = {0, 1};
    private static final int[] REAR_WHEELS = {2, 3};

    // ---- Per-car config bodies ----------------------------------------------------------------------
    /** The {@code wing} section's own field names, exactly as {@code docs/per-car-config.md} documents
     * them -- declared once here rather than re-spelled in every test's JSON literal. */
    private static final String FRONT_DOWNFORCE_KEY = "frontDownforceCoeff";
    private static final String REAR_DOWNFORCE_KEY = "rearDownforceCoeff";
    private static final String DRAG_KEY = "dragCoeff";

    /** A per-car config file body setting all three wing fields. */
    private static String wingJson(double frontCoeff, double rearCoeff, double dragCoeff) {
        return String.format("{\"wing\": {\"%s\": %s, \"%s\": %s, \"%s\": %s}}",
                FRONT_DOWNFORCE_KEY, frontCoeff, REAR_DOWNFORCE_KEY, rearCoeff, DRAG_KEY, dragCoeff);
    }

    /** A per-car config file body naming ONE wing field only &mdash; the shape the tests that turn on
     * "every field this file did not mention stays at its stock value" need. */
    private static String wingJsonWithOnly(String key, double value) {
        return String.format("{\"wing\": {\"%s\": %s}}", key, value);
    }

    /** A per-car config file body setting both downforce coefficients and deliberately NOT mentioning
     * {@link #DRAG_KEY}, so drag stays at this car's stock value while downforce is under test. */
    private static String wingJsonWithOnlyDownforce(double frontCoeff, double rearCoeff) {
        return String.format("{\"wing\": {\"%s\": %s, \"%s\": %s}}",
                FRONT_DOWNFORCE_KEY, frontCoeff, REAR_DOWNFORCE_KEY, rearCoeff);
    }

    private static void fillFloor(GameTestHelper helper, int cx, int y, int cz) {
        for (int dx = -FLOOR_PATCH_RADIUS; dx <= FLOOR_PATCH_RADIUS; dx++) {
            for (int dz = -FLOOR_PATCH_RADIUS; dz <= FLOOR_PATCH_RADIUS; dz++) {
                helper.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /** The floor patch and spawn point for one lane, as a matched pair, so the two can never drift apart. */
    private static BlockPos prepareLane(GameTestHelper helper, int laneZ) {
        fillFloor(helper, LANE_X, FLOOR_Y, laneZ);
        return new BlockPos(LANE_X, SPAWN_Y, laneZ);
    }

    private static double horizontalSpeed(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return Math.hypot(v.x, v.z) * TICKS_PER_SECOND;
    }

    /** {@code null} for a few ticks after spawn, until {@code CarEntity}'s own first server tick creates
     * the real Sable body &mdash; callers must only read this after at least one {@code runAfterDelay}
     * (see {@link #BODY_SETTLE_TICKS}). */
    private static double[] suspensionTravel(CarEntity car) {
        return ((SableCarBody) car.sableBody()).suspensionTravel();
    }

    /** Mean suspension compression across one axle's own wheels. */
    private static double averageTravel(double[] travel, int[] wheels) {
        double sum = 0.0;
        for (int wheel : wheels) {
            sum += travel[wheel];
        }
        return sum / wheels.length;
    }

    private static double frontMinusRearTravel(CarEntity car) {
        double[] travel = suspensionTravel(car);
        return averageTravel(travel, FRONT_WHEELS) - averageTravel(travel, REAR_WHEELS);
    }

    /** AC (front/rear load balance): a car whose wing config gives it front downforce only (rear left at
     * its stock 0.0), driven through the exact same throttle profile as an otherwise identical stock car,
     * must end up relatively MORE front-loaded than that stock car &mdash; the genuine downward force this
     * ticket adds is applied at the front axle's own mount point, off the CoG, so it loads the front
     * springs harder than a plain stock car's own (acceleration-driven) front/rear balance. A bug that
     * applied it at the CoG (no axle split at all) or on the wrong axle would instead show no such shift,
     * or shift it the wrong way. */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void frontOnlyWingDownforceShiftsLoadForwardComparedToStock(GameTestHelper helper) {
        BlockPos stockSpawn = prepareLane(helper, FIRST_LANE_Z);
        BlockPos wingedSpawn = prepareLane(helper, SECOND_LANE_Z);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), stockSpawn);
        withLoadedOverride(VehicleIds.CAR, wingJsonWithOnly(FRONT_DOWNFORCE_KEY, STRONG_DOWNFORCE_COEFF), () -> {
            CarEntity winged = helper.spawn(DynamicVehiclesMod.CAR.get(), wingedSpawn);
            stock.forceDrive(GENTLE_THROTTLE, NO_STEER, DRIVE_TICKS);
            winged.forceDrive(GENTLE_THROTTLE, NO_STEER, DRIVE_TICKS);
            helper.runAfterDelay(DRIVE_TICKS, () -> {
                double stockSpeed = horizontalSpeed(stock);
                double wingedSpeed = horizontalSpeed(winged);
                helper.assertTrue(stockSpeed > MOVING_SPEED_FLOOR && wingedSpeed > MOVING_SPEED_FLOOR,
                        "test setup never got both cars moving: stock=" + stockSpeed + " winged=" + wingedSpeed);
                double stockBalance = frontMinusRearTravel(stock);
                double wingedBalance = frontMinusRearTravel(winged);
                helper.assertTrue(wingedBalance > stockBalance,
                        "a front-only wing downforce config must shift load forward relative to an "
                                + "identically-driven stock car: stock(front-rear)=" + stockBalance
                                + " winged(front-rear)=" + wingedBalance);
                helper.succeed();
            });
        });
    }

    /** AC (speed/airflow dependence): the SAME wing config, driven through the exact same throttle profile
     * as an otherwise identical stock car, must gain MORE total suspension compression (front and rear
     * combined) between "just after spawn" and "up to real speed" than the stock car does &mdash;
     * downforce scales with the square of airflow speed, so a real wing must add genuine extra compression
     * growth on top of whatever the stock car's own suspension already gains from acceleration alone. */
    @GameTest(template = "settlement_test", timeoutTicks = 300)
    public static void wingDownforceAddsExtraCompressionGrowthComparedToStock(GameTestHelper helper) {
        BlockPos stockSpawn = prepareLane(helper, FIRST_LANE_Z);
        BlockPos wingedSpawn = prepareLane(helper, SECOND_LANE_Z);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), stockSpawn);
        withLoadedOverride(VehicleIds.CAR,
                wingJsonWithOnlyDownforce(STRONG_DOWNFORCE_COEFF, STRONG_DOWNFORCE_COEFF),
                () -> {
                    CarEntity winged = helper.spawn(DynamicVehiclesMod.CAR.get(), wingedSpawn);
                    helper.runAfterDelay(BODY_SETTLE_TICKS, () -> {
                        double stockRest = totalTravel(stock);
                        double wingedRest = totalTravel(winged);
                        stock.forceDrive(FULL_THROTTLE, NO_STEER, DRIVE_TICKS);
                        winged.forceDrive(FULL_THROTTLE, NO_STEER, DRIVE_TICKS);
                        helper.runAfterDelay(DRIVE_TICKS, () -> {
                            double stockSpeed = horizontalSpeed(stock);
                            double wingedSpeed = horizontalSpeed(winged);
                            helper.assertTrue(stockSpeed > REAL_SPEED_FLOOR && wingedSpeed > REAL_SPEED_FLOOR,
                                    "test setup never got both cars up to real speed: stock=" + stockSpeed
                                            + " winged=" + wingedSpeed);
                            double stockGrowth = totalTravel(stock) - stockRest;
                            double wingedGrowth = totalTravel(winged) - wingedRest;
                            helper.assertTrue(wingedGrowth > stockGrowth,
                                    "a real wing config must add extra suspension-compression growth as "
                                            + "speed builds, beyond whatever an identically-driven stock "
                                            + "car's own acceleration already causes: stockGrowth="
                                            + stockGrowth + " wingedGrowth=" + wingedGrowth);
                            helper.succeed();
                        });
                    });
                });
    }

    /** Mean suspension compression across every wheel &mdash; front and rear axles combined, the quantity
     * a vertical force applied anywhere on the body moves in one direction. */
    private static double totalTravel(CarEntity car) {
        double[] travel = suspensionTravel(car);
        return averageTravel(travel, FRONT_WHEELS) + averageTravel(travel, REAR_WHEELS);
    }

    /** AC (stable zero-speed and tick/substep behaviour): a car carrying a real wing config, left entirely
     * untouched (no throttle/steer) at rest, must not spontaneously move or diverge &mdash; downforce is
     * exactly zero at zero airflow speed by construction ({@code coeff * speed^2}), so a bug that instead
     * applied some fixed or discontinuous force at rest would show up here as real horizontal drift. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void aWingedCarAtRestStaysStable(GameTestHelper helper) {
        BlockPos spawn = prepareLane(helper, FIRST_LANE_Z);
        withLoadedOverride(VehicleIds.CAR,
                wingJson(MODERATE_DOWNFORCE_COEFF, MODERATE_DOWNFORCE_COEFF, MODERATE_DRAG_COEFF), () -> {
                    CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), spawn);
                    helper.runAfterDelay(AT_REST_OBSERVE_TICKS, () -> {
                        double speed = horizontalSpeed(car);
                        helper.assertTrue(!Double.isNaN(speed) && speed < AT_REST_SPEED_CEILING,
                                "a winged car left at rest with no input must stay essentially stationary, not "
                                        + speed);
                        helper.succeed();
                    });
                });
    }

    /** AC (no energy injection from drag): once throttle is released, a car with an elevated per-car drag
     * coefficient must only ever lose speed while coasting, never gain it &mdash; drag is applied strictly
     * opposing the body's own velocity, so it can only remove kinetic energy, not add it. Sampled a few
     * times during the coast-down rather than only at the very end, so a transient speed-up partway through
     * (which a final-speed-only comparison could miss) is caught too. */
    @GameTest(template = "settlement_test", timeoutTicks = 400)
    public static void elevatedDragOnlyEverSlowsACoastingCarDown(GameTestHelper helper) {
        BlockPos spawn = prepareLane(helper, FIRST_LANE_Z);
        withLoadedOverride(VehicleIds.CAR, wingJsonWithOnly(DRAG_KEY, ELEVATED_DRAG_COEFF), () -> {
            CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), spawn);
            car.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
            helper.runAfterDelay(COAST_SPIN_UP_TICKS, () -> {
                double speedAtThrottleRelease = horizontalSpeed(car);
                helper.assertTrue(speedAtThrottleRelease > MOVING_SPEED_FLOOR,
                        "test setup never got the car moving before coast-down: " + speedAtThrottleRelease);
                car.forceDrive(NO_THROTTLE, NO_STEER, COAST_TICKS);
                double[] lastSpeed = {speedAtThrottleRelease};
                boolean[] everSpedUp = {false};
                for (int delay = COAST_SAMPLE_INTERVAL_TICKS; delay <= COAST_TICKS;
                        delay += COAST_SAMPLE_INTERVAL_TICKS) {
                    helper.runAfterDelay(delay, () -> {
                        double speed = horizontalSpeed(car);
                        if (speed > lastSpeed[0] + NEGLIGIBLE) {
                            everSpedUp[0] = true;
                        }
                        lastSpeed[0] = speed;
                    });
                }
                helper.runAfterDelay(COAST_TICKS, () -> {
                    helper.assertTrue(!everSpedUp[0],
                            "a coasting car with elevated aerodynamic drag must never speed back up, only "
                                    + "slow down -- drag injected energy instead of only opposing velocity");
                    helper.assertTrue(lastSpeed[0] < speedAtThrottleRelease,
                            "a coasting car with elevated aerodynamic drag must end up slower than when "
                                    + "throttle was released: released=" + speedAtThrottleRelease
                                    + " final=" + lastSpeed[0]);
                    helper.succeed();
                });
            });
        });
    }

    /** AC (nonzero-speed tick/substep behaviour): review follow-up (comment 49011) &mdash; {@link
     * #aWingedCarAtRestStaysStable} only establishes stability at zero speed, not that wing downforce's
     * own contribution stays well-behaved under a DIFFERENT {@code wheelSubSteps} count once the car is
     * actually moving. Wing downforce is applied once per tick, outside the sub-step loop, exactly like
     * air drag (see {@code SableCarBody.tick}'s own comment there) &mdash; unlike the per-wheel tire/
     * suspension forces inside that loop, which {@code CarDrivetrainGameTests
     * #wheelSubStepCountDoesNotMateriallyChangeReachedSpeedAfterAFixedWindow}'s own javadoc documents as
     * changing measurably (~10-15%) with sub-step count. Same production entry point and tolerance-band
     * approach as that test (a generous ratio band, not exact equality, since the REST of the physics a
     * compression measurement also reflects does have its own real, expected sub-step sensitivity) &mdash;
     * run in its own isolated batch and restoring {@code CarConfig.WHEEL_SUB_STEPS} even on failure, for
     * the same reason that test's own javadoc explains. */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "wingAeroSubStepIsolated")
    public static void wingDownforceGrowthIsSubstepCountInvariant(GameTestHelper helper) {
        BlockPos oneStepSpawn = prepareLane(helper, FIRST_LANE_Z);
        BlockPos manyStepSpawn = prepareLane(helper, SECOND_LANE_Z);
        int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
        String wingJson = wingJsonWithOnlyDownforce(STRONG_DOWNFORCE_COEFF, STRONG_DOWNFORCE_COEFF);
        try {
            CarConfig.WHEEL_SUB_STEPS.set(ONE_SUB_STEP);
            withLoadedOverride(VehicleIds.CAR, wingJson, () -> {
                CarEntity oneStepCar = helper.spawn(DynamicVehiclesMod.CAR.get(), oneStepSpawn);
                helper.runAfterDelay(BODY_SETTLE_TICKS, () -> {
                    double oneStepRest = totalTravel(oneStepCar);
                    oneStepCar.forceDrive(FULL_THROTTLE, NO_STEER, DRIVE_TICKS);
                    helper.runAfterDelay(DRIVE_TICKS, () -> {
                        double oneStepGrowth = totalTravel(oneStepCar) - oneStepRest;
                        runManySubStepLegAndRestore(helper, originalSubSteps, wingJson, manyStepSpawn, oneStepGrowth);
                    });
                });
            });
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    private static void runManySubStepLegAndRestore(GameTestHelper helper, int originalSubSteps, String wingJson,
            BlockPos spawn, double oneStepGrowth) {
        try {
            CarConfig.WHEEL_SUB_STEPS.set(MANY_SUB_STEPS);
            withLoadedOverride(VehicleIds.CAR, wingJson, () -> {
                CarEntity fourStepCar = helper.spawn(DynamicVehiclesMod.CAR.get(), spawn);
                helper.runAfterDelay(BODY_SETTLE_TICKS, () -> {
                    double fourStepRest = totalTravel(fourStepCar);
                    fourStepCar.forceDrive(FULL_THROTTLE, NO_STEER, DRIVE_TICKS);
                    helper.runAfterDelay(DRIVE_TICKS, () -> {
                        try {
                            double fourStepGrowth = totalTravel(fourStepCar) - fourStepRest;
                            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                            // Sign is not asserted on: a hard full-throttle launch's own acceleration-driven
                            // weight transfer can leave the NET compression change negative even with real
                            // wing downforce added on top (confirmed live) -- not something this test is
                            // about. What matters here is that the MAGNITUDE of whatever net change results
                            // does not swing wildly, and does not flip sign, between substep counts.
                            double oneStepAbs = Math.abs(oneStepGrowth);
                            double fourStepAbs = Math.abs(fourStepGrowth);
                            helper.assertTrue(oneStepAbs > NEGLIGIBLE && fourStepAbs > NEGLIGIBLE,
                                    "test setup invalid -- wing downforce produced no measurable compression "
                                            + "change at either substep count: 1-step=" + oneStepGrowth
                                            + " 4-step=" + fourStepGrowth);
                            helper.assertTrue(Math.signum(oneStepGrowth) == Math.signum(fourStepGrowth),
                                    "wheelSubSteps flipped the sign of the net compression change: 1-step="
                                            + oneStepGrowth + " 4-step=" + fourStepGrowth);
                            double ratio = fourStepAbs / oneStepAbs;
                            helper.assertTrue(ratio > SUB_STEP_RATIO_MIN && ratio < SUB_STEP_RATIO_MAX,
                                    "wing downforce's own once-per-tick contribution must not swing wildly with "
                                            + "wheelSubSteps: 1-step=" + oneStepGrowth + " 4-step=" + fourStepGrowth
                                            + " ratio=" + ratio);
                            helper.succeed();
                        } catch (RuntimeException e) {
                            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                            throw e;
                        }
                    });
                });
            });
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    /** AC (water/lava/buoyancy compatibility): review follow-up (comment 49011) &mdash; a car carrying a
     * real wing config, fully submerged in water and driven forward, must still report full submersion and
     * keep finite, bounded motion. Wing downforce/drag are computed and applied entirely independently of
     * {@code SableCarBody.tick}'s separate water/lava buoyancy and fluid-drag block (this ticket reads no
     * fluid state and does not touch that code), so this proves they coexist rather than merely asserting
     * it from reading the diff: the same "two full layers of water, read back as fully submerged" setup
     * {@code EnvironmentGameTests#shortVehicleFullySubmergedReachesFullSubmersionFraction} already uses for
     * the pre-existing, untouched buoyancy system (same spawn point and short delay before the submersion
     * check, for the same reason -- driving first would let buoyancy float the car up out of full
     * submersion before the check, which is an orthogonal, expected buoyancy behaviour, not something this
     * assertion is about). Driving happens AFTER that check, to also exercise the wing/fluid-drag code
     * paths running together in the same tick without the earlier submersion check racing against it. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void aWingedCarFullySubmergedInWaterStaysBuoyantAndStable(GameTestHelper helper) {
        // Two full, non-surface water layers, so the one layer the car's own box overlaps is reported at
        // full height rather than the top surface's partial one; the air layer above bounds the pool
        // explicitly (same setup as EnvironmentGameTests' own submersion test).
        BlockPos pool = new BlockPos(LANE_X, FLOOR_Y, FIRST_LANE_Z);
        helper.setBlock(pool, Blocks.WATER);
        helper.setBlock(pool.above(), Blocks.WATER);
        helper.setBlock(pool.above(2), Blocks.AIR);
        // INDY is the roster's shortest car (0.7 m tall), so two full water layers cover it completely --
        // the same vehicle EnvironmentGameTests' own full-submersion test uses, and for the same reason.
        withLoadedOverride(VehicleIds.INDY_CAR,
                wingJson(STRONG_DOWNFORCE_COEFF, STRONG_DOWNFORCE_COEFF, ELEVATED_DRAG_COEFF),
                () -> {
                    CarEntity car = helper.spawn(DynamicVehiclesMod.INDY.get(), pool);
                    helper.runAfterDelay(SUBMERSION_READ_TICKS, () -> {
                        double vehicleHeight = car.spec().height();
                        double fraction = EnvironmentMath.submersionFraction(
                                car.getFluidHeight(FluidTags.WATER), vehicleHeight);
                        helper.assertTrue(fraction == FULL_SUBMERSION,
                                "a winged car fully covered by water did not read back as fully submerged "
                                        + "(wing fields must not interfere with the separate buoyancy system): "
                                        + fraction);
                        car.forceDrive(SUBMERGED_THROTTLE, NO_STEER, DRIVE_TICKS);
                        helper.runAfterDelay(DRIVE_TICKS, () -> {
                            Vec3 motion = car.getDeltaMovement();
                            Vec3 pos = car.position();
                            helper.assertTrue(
                                    Double.isFinite(motion.x) && Double.isFinite(motion.y) && Double.isFinite(motion.z)
                                            && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z),
                                    "a winged car in water produced non-finite position/velocity: pos=" + pos
                                            + " motion=" + motion);
                            helper.succeed();
                        });
                    });
                });
    }

    private static void withLoadedOverride(String carId, String json, Runnable body) {
        try {
            Path configDir = Files.createTempDirectory("dynamicvehicles-gametest-config");
            Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
            Files.createDirectories(carsDir);
            Files.writeString(carsDir.resolve(carId + ".json"), json);
            VehicleSpecOverrides.load(configDir);
            body.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"), "dynamicvehicles-no-such-config-dir"));
        }
    }
}
