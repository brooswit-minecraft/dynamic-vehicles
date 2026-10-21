package io.github.brooswitminecraft.dynamicvehicles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
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
 * <p><b>How the suspension-based tests here measure, and why.</b> Every one of them reads compression
 * LEVELS while the car is COASTING (throttle released, launch transient allowed to decay), compares
 * against either a matched wing-off control car or the same car at another speed, and requires an explicit
 * margin. That shape was arrived at by three measured failures, all worth keeping on the record because
 * each looks reasonable until it is run:
 * <ul>
 * <li>Asserting a front-only winged car's front travel exceeds its own rear travel failed outright
 *     (front=0.124, rear=0.151): accelerating under throttle transfers weight rearward on its own, which
 *     outweighs a wing's front bias. Hence a wing-off control rather than a raw within-car comparison.</li>
 * <li>Comparing a winged car's compression CHANGE across a launch against a stock car's was flaky: the
 *     change is ~-0.43 m dominated by launch weight transfer and settling, the wing's own share ~1% of it,
 *     so the difference of two such deltas read +1.18e-4, +4.24e-5 and then -0.0053 (flipped sign) for
 *     identical code. Hence levels, not changes.</li>
 * <li>The same flaw sank the sub-step comparison built on that quantity; sub-step invariance is now
 *     established exactly from the production path's own applied-force telemetry instead (see {@link
 *     #appliedWingDownforceIsOncePerTickAndMatchesTheFormulaAtEverySubstepCount}), which is a stronger
 *     statement than any compression proxy could make.</li>
 * </ul>
 * Releasing the throttle removes the drive-torque weight transfer that dominated all three, and comparing
 * levels never forms a difference of deltas. Cars compared against each other carry the same stock drag
 * coefficient, so they coast alike and no comparison here is a speed comparison in disguise.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class WingAeroGameTests {
    private WingAeroGameTests() {}

    // ---- Inputs -------------------------------------------------------------------------------------
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
    /** Long enough that a spurious force at rest would show up as real drift, not just jitter. */
    private static final int AT_REST_OBSERVE_TICKS = 100;
    // FWD needs a longer launch to establish the aerodynamic load used by these assertions.
    private static final int COAST_SPIN_UP_TICKS = 90;
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
    /** The two {@code wheelSubSteps} settings the applied-force telemetry is checked at: the minimum, and
     * the maximum {@code SableCarBody.tick} clamps to. A force wrongly applied inside the sub-step loop
     * would differ between these by a factor of {@link #MANY_SUB_STEPS}. */
    private static final int ONE_SUB_STEP = 1;
    private static final int MANY_SUB_STEPS = 4;

    /** One aero impulse per axle per tick: this roster is all four-wheel, two-axle cars, so a correct tick
     * applies exactly this many when both coefficients are nonzero. */
    private static final int AXLES_WITH_A_WING = 2;
    /** The drag dead band docs/per-car-config.md documents, pinned against the production constant by
     * {@link #theDocumentedDragDeadBandMatchesTheProductionConstant}. */
    private static final double DOCUMENTED_DRAG_DEADBAND = 0.5;
    /** The car the applied-force test runs on: {@code muscle_car}, deliberately chosen because its
     * {@code forceScale} is NOT 1.0 (review fix, comment 49064). On {@code car} (forceScale exactly 1.0)
     * the {@code coeff * vf^2 * forceScale()} assertion is insensitive to whether the production path
     * applies {@code forceScale} at all, which is how the documentation drifted from the code unnoticed;
     * the test asserts that premise explicitly so this can never silently regress to a 1.0 car. */
    private static final String FORCE_SCALED_CAR_ID = VehicleIds.MUSCLE_CAR;
    /** How far the applied force may sit from the documented formula evaluated at the recorded airflow
     * speed. Both sides are computed from the same recorded speed via the same {@code
     * VehicleSpec#wingDownforceNewtons} call, so only double rounding separates them; this allows far more
     * than that while staying orders of magnitude tighter than any per-sub-step multiplication error. */
    private static final double APPLIED_FORCE_TOLERANCE_FRACTION = 0.01;
    /** A compression reading must stay below this fraction of the car's own {@code restLength} for an
     * added-load measurement to be meaningful: at full travel the spring has bottomed out and stops
     * responding to extra load at all, which would flatten the very difference under test. */
    private static final double BOTTOM_OUT_FRACTION = 0.9;
    /** How much deeper a winged car's suspension must sit than an identically-driven wing-off control's
     * while coasting at speed, in metres of summed per-axle compression. Set an order of magnitude above
     * the ~0.005 m run-to-run noise the earlier change-based drafts measured (see {@link
     * #wingDownforceMeasurablyLoadsTheSuspensionVersusAWingOffControl}), so a pass means a real load
     * rather than a lucky sample, while staying far below the difference a correct wing produces. */
    private static final double MIN_WING_COMPRESSION_MARGIN = 0.05;
    /** Ticks to let the launch's own weight transfer decay after the throttle is released, before a
     * coasting measurement is taken. */
    private static final int LAUNCH_TRANSIENT_TICKS = 20;
    /** How much more front-loaded (summed front minus rear compression, metres) a front-only winged car
     * must sit than a wing-off control while coasting. Same noise reasoning as {@link
     * #MIN_WING_COMPRESSION_MARGIN}, applied to a front/rear balance rather than total compression. */
    private static final double MIN_BALANCE_SHIFT_MARGIN = 0.02;
    /** A deliberately short spin-up, so the car it drives reaches a much lower coasting speed than one
     * given the full {@link #COAST_SPIN_UP_TICKS} window. */
    private static final int SHORT_SPIN_UP_TICKS = 35;
    /** The faster car must be travelling at least this many times the slower car's speed for a
     * speed-dependence comparison to be comparing two genuinely different speeds. */
    private static final double MIN_SPEED_GAP_RATIO = 1.5;

    // ---- Test world layout --------------------------------------------------------------------------
    /** Half-width of the floor patch under each spawn point: wide enough that every one of a car's four
     * wheel raycasts lands on placed floor rather than the template's own terrain. */
    private static final int FLOOR_PATCH_RADIUS = 2;
    /** Where the at-rest and water tests sit on {@code settlement_test}; neither drives anywhere. */
    private static final int LANE_X = 5;
    private static final int FLOOR_Y = 2;
    /** One block above {@link #FLOOR_Y} -- a car spawns resting on the floor patch, not inside it. */
    private static final int SPAWN_Y = FLOOR_Y + 1;
    private static final int FIRST_LANE_Z = 5;
    /** The driving tests' own lanes on a {@link VehicleLanes} site: two cars driven at once never share one.
     * These used to be two spots 15 blocks apart in a single lane of {@code settlement_test}, with the second
     * car four blocks short of the template's barrier wall -- fine only while GameTest's placement kept the
     * cars from really moving (see {@link VehicleLanes}); where they did move, the second car hit the wall
     * within 14 ticks and the first rear-ended it at about 11 m/s. */
    private static final int FIRST_CAR_LANE_X = VehicleLanes.FIRST_LANE_X;
    private static final int SECOND_CAR_LANE_X = VehicleLanes.THIRD_LANE_X;
    /** Ticks after spawn before the per-tick lane check starts: the body exists and its wheels are down. */
    private static final long SETTLE_TICKS = 2L;
    private static final Block[] STONE_WHEELS = VehicleLanes.allWheels(Blocks.STONE);
    /** These fixtures are about aero loads, not the surface, and every one launches at full throttle, which
     * wears a stone lane where it launches; a lane may show any of that wear short of being worn through. */
    private static final VehicleLanes.Wear LANE_WEAR = VehicleLanes.Wear.ANY_STAGE;

    /** Read submersion as soon as the car has ticked at all: driving first would let buoyancy float it up
     * out of full submersion, which is orthogonal, expected behaviour rather than what this checks. */
    private static final int SUBMERSION_READ_TICKS = 2;
    /** A submerged car fights fluid drag and buoyancy, so it will not reach the speeds the dry-land tests
     * use; this is the much lower bar for "it really is moving, so the aero load really is nonzero". */
    private static final double IN_FLUID_SPEED_FLOOR = 0.05;
    /** How far a car driven inside a small pool may end up from its spawn point and still support a
     * "bounded motion" claim: a couple of blocks of real travel, not an unbounded launch. */
    private static final double FLUID_DRIFT_BOUND = 8.0;

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

    /** A stone lane along a {@link VehicleLanes} site and the spawn point on it, as a matched pair, so the two
     * can never drift apart. */
    private static Vec3 stoneLane(VehicleLanes lanes, int laneX) {
        lanes.layLane(laneX, Blocks.STONE.defaultBlockState());
        return VehicleLanes.spawnPoint(laneX);
    }

    private static double horizontalSpeed(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return Math.hypot(v.x, v.z) * TICKS_PER_SECOND;
    }

    /** {@code null} for a few ticks after spawn, until {@code CarEntity}'s own first server tick creates
     * the real Sable body &mdash; callers must only read this after at least one {@code runAfterDelay},
     * which every test here satisfies by measuring only after a spin-up and coast window. */
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
     * its stock 0.0) must, while coasting at speed, sit relatively MORE front-loaded than an identically
     * driven stock car &mdash; the force this ticket adds is applied at the front axle's own mount point,
     * off the CoG, so it loads the front springs harder. A bug that applied it at the CoG (no axle split
     * at all) or on the wrong axle would show no such shift, or shift it the wrong way.
     *
     * <p>Measured while coasting, against a wing-off control, for the reasons {@link
     * #wingDownforceMeasurablyLoadsTheSuspensionVersusAWingOffControl}'s own javadoc sets out: under
     * throttle, drive-torque weight transfer dominates the reading (a first draft asserted front travel
     * exceeds rear travel outright and failed at front=0.124 rear=0.151 for exactly that reason), and both
     * cars carry the same stock drag coefficient here so they coast identically. An explicit margin is
     * required rather than a bare inequality, so a noise-sized difference cannot pass. */
    @GameTest(template = "settlement_test", timeoutTicks = 400 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void frontOnlyWingDownforceShiftsLoadForwardComparedToStock(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.FRONT_ONLY_WING, lanes -> {
            Vec3 stockSpawn = stoneLane(lanes, FIRST_CAR_LANE_X);
            Vec3 wingedSpawn = stoneLane(lanes, SECOND_CAR_LANE_X);
            CarEntity stock = lanes.spawn(DynamicVehiclesMod.CAR.get(), stockSpawn);
            withLoadedOverride(VehicleIds.CAR, wingJsonWithOnly(FRONT_DOWNFORCE_KEY, STRONG_DOWNFORCE_COEFF), () -> {
                CarEntity winged = lanes.spawn(DynamicVehiclesMod.CAR.get(), wingedSpawn);
                lanes.assertStaysOnLane(stock, FIRST_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                        COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS - 1L, "stock car");
                lanes.assertStaysOnLane(winged, SECOND_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                        COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS - 1L, "winged car");
                stock.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
                winged.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
                helper.runAfterDelay(COAST_SPIN_UP_TICKS, () -> {
                    stock.selectGear(CarPhysics.Gear.NEUTRAL);
                    stock.forceDrive(NO_THROTTLE, NO_STEER, LAUNCH_TRANSIENT_TICKS);
                    winged.selectGear(CarPhysics.Gear.NEUTRAL);
                    winged.forceDrive(NO_THROTTLE, NO_STEER, LAUNCH_TRANSIENT_TICKS);
                    helper.runAfterDelay(LAUNCH_TRANSIENT_TICKS, () -> {
                        double stockSpeed = horizontalSpeed(stock);
                        double wingedSpeed = horizontalSpeed(winged);
                        helper.assertTrue(stockSpeed > REAL_SPEED_FLOOR && wingedSpeed > REAL_SPEED_FLOOR,
                                "test setup never got both cars coasting at real speed: stock=" + stockSpeed
                                        + " winged=" + wingedSpeed);
                        double stockBalance = frontMinusRearTravel(stock);
                        double wingedBalance = frontMinusRearTravel(winged);
                        double shift = wingedBalance - stockBalance;
                        helper.assertTrue(shift > MIN_BALANCE_SHIFT_MARGIN,
                                "a front-only wing downforce config must shift load measurably forward relative "
                                        + "to an identically-coasting stock car: stock(front-rear)=" + stockBalance
                                        + " winged(front-rear)=" + wingedBalance + " shift=" + shift
                                        + " required>" + MIN_BALANCE_SHIFT_MARGIN);
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** AC (speed/airflow dependence): two cars carrying the IDENTICAL wing config, coasting at different
     * speeds, must sit measurably differently compressed &mdash; the faster one more so, because downforce
     * is speed-squared rather than a fixed load.
     *
     * <p>Two cars at different speeds rather than one car at two points in a coast: a 1200 kg car at the
     * stock 0.4 N per (m/s)^2 decelerates at only ~0.1 m/s^2 while coasting, so an earlier version of this
     * test that waited for the SAME car to slow measurably never got there inside a GameTest window --
     * measured 16.87 -> 15.62 m/s over 80 ticks across four consecutive runs, a 7% drop, where a
     * speed-squared comparison needs considerably more. Braking to force the gap would have introduced the
     * forward weight transfer these tests exist to avoid. Different spin-up windows produce a large speed
     * gap with no such confound, and both cars are read in the same quiet coasting state.
     *
     * <p>Both carry the same wing AND the same stock drag coefficient, so the only material difference
     * between the two readings is the speed each is travelling at. */
    @GameTest(template = "settlement_test", timeoutTicks = 400 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void wingDownforceLoadsTheSuspensionMoreAtHigherSpeed(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.WING_AT_TWO_SPEEDS, lanes -> {
            Vec3 fastSpawn = stoneLane(lanes, FIRST_CAR_LANE_X);
            Vec3 slowSpawn = stoneLane(lanes, SECOND_CAR_LANE_X);
            withLoadedOverride(VehicleIds.CAR,
                    wingJsonWithOnlyDownforce(STRONG_DOWNFORCE_COEFF, STRONG_DOWNFORCE_COEFF), () -> {
                        CarEntity fastCar = lanes.spawn(DynamicVehiclesMod.CAR.get(), fastSpawn);
                        CarEntity slowCar = lanes.spawn(DynamicVehiclesMod.CAR.get(), slowSpawn);
                        lanes.assertStaysOnLane(fastCar, FIRST_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                                COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS - 1L, "faster car");
                        lanes.assertStaysOnLane(slowCar, SECOND_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                                COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS - 1L, "slower car");
                        fastCar.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
                        slowCar.forceDrive(FULL_THROTTLE, NO_STEER, SHORT_SPIN_UP_TICKS);
                        helper.runAfterDelay(SHORT_SPIN_UP_TICKS, () -> slowCar.selectGear(CarPhysics.Gear.NEUTRAL));
                        helper.runAfterDelay(COAST_SPIN_UP_TICKS, () -> fastCar.selectGear(CarPhysics.Gear.NEUTRAL));
                        // Both are coasting with their launch transient decayed by this point: the slow car
                        // released throttle earlier, which only widens the speed gap being tested.
                        helper.runAfterDelay(COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS, () -> {
                            double fastSpeed = horizontalSpeed(fastCar);
                            double slowSpeed = horizontalSpeed(slowCar);
                            double fastCompression = totalTravel(fastCar);
                            double slowCompression = totalTravel(slowCar);

                            helper.assertTrue(slowSpeed > MOVING_SPEED_FLOOR,
                                    "test setup left the slower car with no real airflow at all, so it is not a "
                                            + "speed comparison: slow=" + slowSpeed);
                            helper.assertTrue(fastSpeed > slowSpeed * MIN_SPEED_GAP_RATIO,
                                    "test setup never opened a real speed gap between the two cars: fast="
                                            + fastSpeed + " slow=" + slowSpeed + " required fast>slow*"
                                            + MIN_SPEED_GAP_RATIO);
                            double deepestCompression = 0.0;
                            for (double compression : suspensionTravel(fastCar)) {
                                deepestCompression = Math.max(deepestCompression, compression);
                            }
                            helper.assertTrue(deepestCompression < fastCar.spec().restLength() * BOTTOM_OUT_FRACTION,
                                    "the faster car's suspension is too close to bottoming out for a load "
                                            + "comparison to mean anything: deepest compression="
                                            + deepestCompression + " restLength=" + fastCar.spec().restLength());
                            double extraLoad = fastCompression - slowCompression;
                            helper.assertTrue(extraLoad > MIN_WING_COMPRESSION_MARGIN,
                                    "the same wing config must load the suspension measurably more at a higher "
                                            + "speed (downforce is speed-squared, not a fixed load): fast="
                                            + fastCompression + " (" + fastSpeed + " m/s) slow=" + slowCompression
                                            + " (" + slowSpeed + " m/s) extra=" + extraLoad + " required>"
                                            + MIN_WING_COMPRESSION_MARGIN);
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
    @GameTest(template = "settlement_test", timeoutTicks = 400 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void elevatedDragOnlyEverSlowsACoastingCarDown(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.ELEVATED_DRAG, lanes -> {
            Vec3 spawn = stoneLane(lanes, FIRST_CAR_LANE_X);
            withLoadedOverride(VehicleIds.CAR, wingJsonWithOnly(DRAG_KEY, ELEVATED_DRAG_COEFF), () -> {
                CarEntity car = lanes.spawn(DynamicVehiclesMod.CAR.get(), spawn);
                // Spin-up plus the whole coast-down: about 97 m of real travel.
                lanes.assertStaysOnLane(car, FIRST_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                        COAST_SPIN_UP_TICKS + COAST_TICKS - 1L, "coasting car");
                car.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
                helper.runAfterDelay(COAST_SPIN_UP_TICKS, () -> {
                    double speedAtThrottleRelease = horizontalSpeed(car);
                    helper.assertTrue(speedAtThrottleRelease > MOVING_SPEED_FLOOR,
                            "test setup never got the car moving before coast-down: " + speedAtThrottleRelease);
                    car.selectGear(CarPhysics.Gear.NEUTRAL);
                    car.forceDrive(NO_THROTTLE, NO_STEER, COAST_TICKS);
                    double[] lastSpeed = {speedAtThrottleRelease};
                    boolean[] everSpedUp = {false};
                    // Let launch wheel slip and suspension transients settle before attributing
                    // any subsequent speed increase to the coasting force path.
                    helper.runAfterDelay(LAUNCH_TRANSIENT_TICKS, () -> lastSpeed[0] = horizontalSpeed(car));
                    for (int delay = LAUNCH_TRANSIENT_TICKS + COAST_SAMPLE_INTERVAL_TICKS; delay <= COAST_TICKS;
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
                                        + "slow down after settling -- lastSpeed=" + lastSpeed[0]
                                        + " releaseSpeed=" + speedAtThrottleRelease);
                        helper.assertTrue(lastSpeed[0] < speedAtThrottleRelease,
                                "a coasting car with elevated aerodynamic drag must end up slower than when "
                                        + "throttle was released: released=" + speedAtThrottleRelease
                                        + " final=" + lastSpeed[0]);
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** AC (nonzero-speed tick/substep behaviour), review follow-up (comment 49019): the DIRECT evidence
     * that wing downforce is applied once per TICK and at the documented magnitude &mdash; read from the
     * production path's own recorded aero telemetry ({@link SableCarBody#lastAeroApplications()}, {@link
     * SableCarBody#lastAeroDownforceNewtons()}, {@link SableCarBody#lastAeroAirflowSpeed()}), not inferred
     * from suspension movement.
     *
     * <p>This replaces an earlier version of this test that compared NET suspension-compression change at
     * 1 vs 4 sub-steps, which the review correctly rejected as inadequate: that net also contains the
     * acceleration/settling baseline, so (its own counterexample) a baseline change of -0.1 in both runs
     * with a wing contribution of +0.001 vs +0.004 reads as -0.099 vs -0.096, a ratio of ~0.97 that passes
     * while the wing contribution is actually wrong by 4x. Reading the applied force itself has no such
     * blind spot: a 4x error IS the assertion's subject rather than a term hidden inside it.
     *
     * <p>Each sub-step count is checked independently against the car's OWN recorded airflow speed, so the
     * two runs do not need to reach matching speeds for the comparison to mean anything (downforce is
     * speed-squared, which is exactly what made a cross-run comparison of raw effects fragile). Nonzero
     * measured forward speed is established first, as the review asked, because the telemetry fields hold
     * their last real value and a standstill would otherwise read as a vacuous pass.
     *
     * <p>Own isolated batch, restoring {@code CarConfig.WHEEL_SUB_STEPS} even on failure, for the reason
     * {@code CarDrivetrainGameTests#wheelSubStepCountDoesNotMateriallyChangeReachedSpeedAfterAFixedWindow}'s
     * own javadoc explains (it is a global, live value, not something a test can scope to its own car). */
    @GameTest(template = "settlement_test", timeoutTicks = 300 + VehicleLanes.CHUNK_LOAD_ALLOWANCE,
            batch = "wingAeroSubStepIsolated")
    public static void appliedWingDownforceIsOncePerTickAndMatchesTheFormulaAtEverySubstepCount(
            GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.APPLIED_WING, lanes -> {
            Vec3 oneStepSpawn = stoneLane(lanes, FIRST_CAR_LANE_X);
            Vec3 manyStepSpawn = stoneLane(lanes, SECOND_CAR_LANE_X);
            int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
            lanes.restoreSubStepsOnEveryOutcome();
            String wingJson = wingJsonWithOnlyDownforce(MODERATE_DOWNFORCE_COEFF, MODERATE_DOWNFORCE_COEFF);
            try {
                CarConfig.WHEEL_SUB_STEPS.set(ONE_SUB_STEP);
                withLoadedOverride(FORCE_SCALED_CAR_ID, wingJson, () -> {
                    CarEntity oneStepCar = lanes.spawn(DynamicVehiclesMod.MUSCLE.get(), oneStepSpawn);
                    lanes.assertStaysOnLane(oneStepCar, FIRST_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                            DRIVE_TICKS - 1L, "wheelSubSteps=" + ONE_SUB_STEP + " car");
                    oneStepCar.forceDrive(FULL_THROTTLE, NO_STEER, DRIVE_TICKS);
                    helper.runAfterDelay(DRIVE_TICKS, () -> {
                        assertAppliedAeroMatchesFormula(helper, oneStepCar, ONE_SUB_STEP);
                        // Done with: it leaves the leg at ~27 m/s and would otherwise coast on toward the end
                        // of its lane while the other leg is still being measured.
                        oneStepCar.discard();
                        runManySubStepAeroLegAndRestore(helper, lanes, originalSubSteps, wingJson, manyStepSpawn);
                    });
                });
            } catch (RuntimeException e) {
                CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                throw e;
            }
        });
    }

    private static void runManySubStepAeroLegAndRestore(GameTestHelper helper, VehicleLanes lanes,
            int originalSubSteps, String wingJson, Vec3 spawn) {
        try {
            CarConfig.WHEEL_SUB_STEPS.set(MANY_SUB_STEPS);
            withLoadedOverride(FORCE_SCALED_CAR_ID, wingJson, () -> {
                CarEntity manyStepCar = lanes.spawn(DynamicVehiclesMod.MUSCLE.get(), spawn);
                lanes.assertStaysOnLane(manyStepCar, SECOND_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                        DRIVE_TICKS - 1L, "wheelSubSteps=" + MANY_SUB_STEPS + " car");
                manyStepCar.forceDrive(FULL_THROTTLE, NO_STEER, DRIVE_TICKS);
                helper.runAfterDelay(DRIVE_TICKS, () -> {
                    try {
                        assertAppliedAeroMatchesFormula(helper, manyStepCar, MANY_SUB_STEPS);
                        CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                        helper.succeed();
                    } catch (RuntimeException e) {
                        CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                        throw e;
                    }
                });
            });
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    /** The whole assertion set for one sub-step count: a real airflow speed was reached, exactly one aero
     * impulse per axle was applied on that tick (not one per sub-step), and the force applied is the
     * documented {@code coeff * forwardSpeed^2 * forceScale} at that very airflow speed. */
    private static void assertAppliedAeroMatchesFormula(GameTestHelper helper, CarEntity car, int subSteps) {
        SableCarBody body = (SableCarBody) car.sableBody();
        double airflow = body.lastAeroAirflowSpeed();
        helper.assertTrue(Math.abs(airflow) > MOVING_SPEED_FLOOR,
                "test setup never reached a real forward airflow speed at wheelSubSteps=" + subSteps
                        + ", so the aero telemetry below would be vacuous: airflow=" + airflow);
        helper.assertTrue(body.lastAeroApplications() == AXLES_WITH_A_WING,
                "wing downforce must be applied once per TICK per axle regardless of wheelSubSteps "
                        + "(an application inside the sub-step loop would multiply this by the count): "
                        + "wheelSubSteps=" + subSteps + " applications=" + body.lastAeroApplications()
                        + " expected=" + AXLES_WITH_A_WING);
        // Review fix (comment 49064): the applied force is coeff * vf^2 * forceScale(), and this test must
        // be able to FAIL if that forceScale factor were dropped. On CAR it could not -- CAR's forceScale
        // is exactly 1.0, so multiplying by it changes nothing and the earlier version of this test passed
        // either way. Hence the premise assertion below, and MUSCLE as the subject.
        double forceScale = car.spec().forceScale();
        helper.assertTrue(forceScale != 1.0,
                "test premise broken: this test must run on a car whose forceScale is NOT 1.0, or it cannot "
                        + "detect the forceScale multiplication being dropped. forceScale=" + forceScale);
        double unscaled = VehicleSpec.wingDownforceNewtons(MODERATE_DOWNFORCE_COEFF, airflow)
                + VehicleSpec.wingDownforceNewtons(MODERATE_DOWNFORCE_COEFF, airflow);
        double expected = unscaled * forceScale;
        double applied = body.lastAeroDownforceNewtons();
        helper.assertTrue(Math.abs(applied - expected) <= expected * APPLIED_FORCE_TOLERANCE_FRACTION,
                "applied wing downforce must equal coeff * forwardSpeed^2 * forceScale at the airflow speed "
                        + "it was evaluated at (see docs/per-car-config.md's exact formulas): wheelSubSteps="
                        + subSteps + " airflow=" + airflow + " forceScale=" + forceScale + " applied="
                        + applied + " expected=" + expected);
        // The drift pin proper: the applied value must NOT be the unscaled coefficient * vf^2, which is
        // what the documentation used to claim and what dropping the multiplication would produce.
        helper.assertTrue(Math.abs(applied - unscaled) > unscaled * APPLIED_FORCE_TOLERANCE_FRACTION,
                "applied wing downforce equals the UNSCALED coeff * forwardSpeed^2, i.e. forceScale was not "
                        + "applied -- the documented contract in docs/per-car-config.md has drifted from the "
                        + "production path: applied=" + applied + " unscaled=" + unscaled
                        + " forceScale=" + forceScale);
    }

    /** Review fix (comment 49064): pins the 0.5 m/s drag dead band that {@code docs/per-car-config.md}'s
     * "Exact applied formulas" section now documents against the production constant itself, so the doc and
     * the code cannot drift apart silently. The dead band is pre-existing behaviour this ticket preserves;
     * what was missing was it being documented as part of the per-car drag contract's speed dependence. */
    @GameTest(template = "settlement_test")
    public static void theDocumentedDragDeadBandMatchesTheProductionConstant(GameTestHelper helper) {
        helper.assertTrue(SableCarBody.DRAG_SPEED_DEADBAND == DOCUMENTED_DRAG_DEADBAND,
                "docs/per-car-config.md documents a " + DOCUMENTED_DRAG_DEADBAND + " m/s drag dead band but "
                        + "SableCarBody.DRAG_SPEED_DEADBAND is " + SableCarBody.DRAG_SPEED_DEADBAND
                        + " -- update the doc and this constant together");
        helper.succeed();
    }

    /** AC (nonzero-speed behaviour), review follow-up (comment 49019): the END-TO-END half &mdash; that the
     * applied wing force actually reaches the real suspension, isolated from the acceleration/settling
     * baseline by a matched wing-off control car, which is what the review asked for. The telemetry test
     * above proves the right force was computed and handed to the body; this proves the body responds to
     * it. Between them: that one catches a wrong MAGNITUDE (including one wrong by the sub-step count),
     * this one catches a wing that is configured and applied but never reaches the springs.
     *
     * <p><b>Measured while COASTING at speed, comparing compression LEVELS, not changes.</b> Two earlier
     * drafts measured the change in compression across a full-throttle launch instead, and both were
     * defeated by their own signal-to-noise: the net change is ~-0.43 m dominated by launch weight
     * transfer and settling, the wing's own share is ~1% of that, and taking the difference of two such
     * noisy deltas produced run-to-run readings of +1.18e-4, +4.24e-5 and then -0.0053 (a flipped sign) for
     * the same code -- a flaky assertion, not evidence. Releasing the throttle first removes the
     * drive-torque weight transfer entirely, and comparing the two cars' compression LEVELS at that moment
     * (rather than each car's change from rest) never forms a difference of deltas at all.
     *
     * <p>Signal size at these settings: {@link #STRONG_DOWNFORCE_COEFF} per axle at the coast speed this
     * reaches is a large fraction of the car's own weight, so a correct wing shows up as a compression
     * difference far above {@link #MIN_WING_COMPRESSION_MARGIN}, which is itself set well clear of the
     * ~0.005 m noise those earlier drafts measured. Both cars carry the same stock drag coefficient (the
     * config here sets only the downforce keys), so they coast at the same rate and the comparison is not
     * a speed comparison in disguise.
     *
     * <p>Saturation: a bottomed-out spring stops responding to extra load, which would flatten the very
     * difference under test, so the winged car's compression is asserted to stay clear of full travel. That
     * plus the margin assertion is what this offers on saturation; it does not claim to detect a
     * spring-force clamp that leaves compression below that bound. */
    @GameTest(template = "settlement_test", timeoutTicks = 400 + VehicleLanes.CHUNK_LOAD_ALLOWANCE)
    public static void wingDownforceMeasurablyLoadsTheSuspensionVersusAWingOffControl(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.WING_VS_CONTROL, lanes -> {
            Vec3 stockSpawn = stoneLane(lanes, FIRST_CAR_LANE_X);
            Vec3 wingedSpawn = stoneLane(lanes, SECOND_CAR_LANE_X);
            CarEntity stock = lanes.spawn(DynamicVehiclesMod.CAR.get(), stockSpawn);
            withLoadedOverride(VehicleIds.CAR, wingJsonWithOnlyDownforce(STRONG_DOWNFORCE_COEFF, STRONG_DOWNFORCE_COEFF),
                    () -> {
                        CarEntity winged = lanes.spawn(DynamicVehiclesMod.CAR.get(), wingedSpawn);
                        lanes.assertStaysOnLane(stock, FIRST_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                                COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS - 1L, "wing-off control");
                        lanes.assertStaysOnLane(winged, SECOND_CAR_LANE_X, STONE_WHEELS, LANE_WEAR, SETTLE_TICKS,
                                COAST_SPIN_UP_TICKS + LAUNCH_TRANSIENT_TICKS - 1L, "winged car");
                        stock.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
                        winged.forceDrive(FULL_THROTTLE, NO_STEER, COAST_SPIN_UP_TICKS);
                        helper.runAfterDelay(COAST_SPIN_UP_TICKS, () -> {
                            // Throttle released: from here on neither car has drive torque, so neither carries
                            // the launch weight transfer that defeated the earlier change-based drafts.
                            stock.selectGear(CarPhysics.Gear.NEUTRAL);
                    stock.forceDrive(NO_THROTTLE, NO_STEER, LAUNCH_TRANSIENT_TICKS);
                            winged.selectGear(CarPhysics.Gear.NEUTRAL);
                    winged.forceDrive(NO_THROTTLE, NO_STEER, LAUNCH_TRANSIENT_TICKS);
                            helper.runAfterDelay(LAUNCH_TRANSIENT_TICKS, () -> {
                                double stockLevel = totalTravel(stock);
                                double wingedLevel = totalTravel(winged);
                                double difference = wingedLevel - stockLevel;
                                SableCarBody wingedBody = (SableCarBody) winged.sableBody();

                                helper.assertTrue(Math.abs(wingedBody.lastAeroAirflowSpeed()) > REAL_SPEED_FLOOR,
                                        "test setup never reached a real coasting airflow speed, so no meaningful "
                                                + "wing load was under test: airflow="
                                                + wingedBody.lastAeroAirflowSpeed());
                                helper.assertTrue(horizontalSpeed(stock) > REAL_SPEED_FLOOR,
                                        "test setup never got the wing-off control car coasting at real speed: "
                                                + horizontalSpeed(stock));
                                double deepestCompression = 0.0;
                                for (double compression : suspensionTravel(winged)) {
                                    deepestCompression = Math.max(deepestCompression, compression);
                                }
                                helper.assertTrue(deepestCompression < winged.spec().restLength() * BOTTOM_OUT_FRACTION,
                                        "the winged car's suspension is too close to bottoming out for an "
                                                + "added-load measurement to mean anything: deepest compression="
                                                + deepestCompression + " restLength=" + winged.spec().restLength());
                                // Downforce pushes the body DOWN, so at speed the winged car must sit
                                // measurably more compressed than the identically-driven wing-off control.
                                helper.assertTrue(difference > MIN_WING_COMPRESSION_MARGIN,
                                        "a real wing load must leave the car measurably more compressed at speed "
                                                + "than an identically-driven wing-off control: winged="
                                                + wingedLevel + " stock=" + stockLevel + " difference="
                                                + difference + " required>" + MIN_WING_COMPRESSION_MARGIN);
                                helper.succeed();
                            });
                        });
                    });
        });
    }

    /**
     * AC (water/lava/fluid compatibility), review follow-up (comment 49019): the earlier version of this
     * test was named and described as establishing buoyancy and bounded motion while asserting only
     * pre-drive full submersion plus post-drive finiteness, which the review correctly called out. This
     * states and checks exactly three things, no more:
     * <ol>
     * <li><b>Full submersion before driving</b> &mdash; a winged car reads back fully submerged, so the
     *     wing fields have not disturbed the separate submersion/buoyancy inputs. Read before driving
     *     deliberately: driving first lets buoyancy float the car up out of full submersion, an orthogonal
     *     and expected behaviour (a first draft that read it after 100 driven ticks measured 0.916).</li>
     * <li><b>A genuinely simultaneous aero and fluid load</b> while driving &mdash; the production path's
     *     own recorded aero telemetry shows a real nonzero downforce actually applied, AND the car is still
     *     in fluid at that same moment. This is what makes it a coexistence test rather than two
     *     consecutive single-system checks.</li>
     * <li><b>Finite and bounded motion</b> &mdash; position and velocity stay finite, and the car is still
     *     within {@link #FLUID_DRIFT_BOUND} blocks of where it started, so "bounded" is an actual
     *     observation rather than a claim.</li>
     * </ol>
     *
     * <p>It does NOT establish the buoyant response's own magnitude or correctness &mdash; that belongs to
     * the pre-existing, untouched MINECRAFT-259 system and its own {@code EnvironmentGameTests} coverage,
     * which this ticket does not modify. The separate code-level non-duplication audit (this diff reads no
     * fluid state; {@code fluidDrag}/{@code foliageDragTotal} compose with {@code wingDragCoeff()} exactly
     * as they composed with the old {@code AIR_DRAG}) stands on its own and is not what this test proves.
     *
     * <p>Setup follows {@code EnvironmentGameTests#shortVehicleFullySubmergedReachesFullSubmersionFraction}:
     * two full, non-surface water layers over the roster's shortest car.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void aWingedCarInWaterCarriesBothLoadsAndStaysBounded(GameTestHelper helper) {
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
                        Vec3 spawnPos = car.position();
                        car.forceDrive(SUBMERGED_THROTTLE, NO_STEER, DRIVE_TICKS);
                        helper.runAfterDelay(DRIVE_TICKS, () -> {
                            Vec3 motion = car.getDeltaMovement();
                            Vec3 pos = car.position();
                            helper.assertTrue(
                                    Double.isFinite(motion.x) && Double.isFinite(motion.y) && Double.isFinite(motion.z)
                                            && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z),
                                    "a winged car in water produced non-finite position/velocity: pos=" + pos
                                            + " motion=" + motion);
                            // Claim 2: a real aero load WAS applied, and the car is in fluid at the same
                            // time -- the two systems genuinely active together, not one after the other.
                            SableCarBody body = (SableCarBody) car.sableBody();
                            double drivenFraction = EnvironmentMath.submersionFraction(
                                    car.getFluidHeight(FluidTags.WATER), car.spec().height());
                            helper.assertTrue(drivenFraction > 0.0,
                                    "the car left the fluid entirely, so no simultaneous aero+fluid load was "
                                            + "ever observed: submersion=" + drivenFraction);
                            helper.assertTrue(Math.abs(body.lastAeroAirflowSpeed()) > IN_FLUID_SPEED_FLOOR
                                            && body.lastAeroDownforceNewtons() > 0.0,
                                    "no real wing downforce was applied while the car was in fluid, so this "
                                            + "never exercised both loads at once: airflow="
                                            + body.lastAeroAirflowSpeed() + " downforceN="
                                            + body.lastAeroDownforceNewtons());
                            // Claim 3: bounded, as an observation rather than an assertion of faith.
                            helper.assertTrue(pos.distanceTo(spawnPos) < FLUID_DRIFT_BOUND,
                                    "a winged car driven in water drifted further than a bounded-motion claim "
                                            + "can support: moved " + pos.distanceTo(spawnPos) + " blocks from "
                                            + spawnPos + " to " + pos);
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
