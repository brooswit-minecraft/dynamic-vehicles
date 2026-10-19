package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MINECRAFT-445 review fix (comment 49018): production evidence that an ENABLED, CONFIGURED {@code
 * tireKit} actually reaches and changes {@link SableCarBody}'s own per-sub-step suspension force, plus the
 * contact/lost-contact, finite-force and sub-step-consistency checks this ticket's acceptance asks for.
 *
 * <p>Why this class exists at all: {@code WheelMathSuspensionTuningTest} and {@code
 * VehicleSpecOverridesTest} are both honest about what they cover -- the former exercises the pure
 * {@code suspensionForce} arithmetic, the latter the config loader's parse/clamp/fallback -- but NEITHER
 * executes {@link SableCarBody}. A bug that resolved the config correctly and computed the force correctly
 * while never actually applying it to a car would pass both. These tests spawn real {@link CarEntity}
 * instances and read what the production tick did.
 *
 * <p>How a configured car is obtained without leaving global state mutated during the measurement window:
 * {@link CarEntity}'s CONSTRUCTOR resolves its spec once ({@link VehicleSpecOverrides#resolveForSide}),
 * for the entity's whole lifetime. So each test loads the override, spawns the car, and clears the
 * override immediately -- the spawned entity keeps its configured spec, and nothing global stays changed
 * while the car settles. A stock control car spawned BEFORE the override is loaded is therefore a genuine
 * matched control: same type, same template, same ground, same ticks, differing only in the tireKit the
 * server resolved into it.
 *
 * <p><b>Every test here gets its OWN {@code batch}, and that is load-bearing, not tidiness.</b>
 * {@link VehicleSpecOverrides}' table is one process-global map, and GameTests within a single batch run
 * CONCURRENTLY (each in its own template instance, but sharing the JVM). A test that loads a {@code car}
 * override therefore races every other test that spawns a {@code car} -- including {@code
 * CarDrivetrainGameTests}' own gear tests, which spawn exactly that vehicle. Observed directly while
 * writing these tests: with all four sharing the default batch, an extreme {@code tireKit} meant for one
 * test resolved into another test's car AND into the drivetrain suite's D1/D5 cars, failing three
 * unrelated tests. Batches run one at a time, so a batch per test is what actually removes the race. The
 * pre-existing {@code VehicleSpecOverridesGameTests} never hit this only because it configures {@code
 * truck}/{@code rock_crawler}, which no other test spawns.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class SuspensionTuningGameTests {
    private SuspensionTuningGameTests() {}

    /** Ticks allowed for a spawned car to settle onto its springs before anything is measured. Longer than
     * the 60 {@code CarDrivetrainGameTests} uses for its own "settled to a near-standstill" gate: these
     * tests assert on the resting EQUILIBRIUM itself (every wheel grounded, compression compared between
     * cars), which needs the transient fully gone, not merely slow. The extra margin also keeps them off a
     * knife edge on a busy CI runner, where fewer real ticks elapse per second. */
    private static final long SETTLE_TICKS = 100L;

    /** A bump stop engaging well INSIDE the car's own static compression ({@code massKg * g / wheels /
     * springRate}, 0.15 m for the car), so it is genuinely load-bearing at rest rather than only on a big
     * hit -- that is what makes its effect a static, deterministic equilibrium shift rather than a
     * transient that would have to be sampled tick by tick. */
    private static final double LOAD_BEARING_BUMP_STOP_TRAVEL = 0.05;

    /** The configured car's resting compression must come out at most this fraction of the stock control's.
     * Analytically the two equilibria are 0.0538 m and 0.15 m (a ratio of 0.36), so this leaves generous
     * headroom for in-world settling differences while still failing outright if the bump stop term never
     * reached production. */
    private static final double MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO = 0.7;

    /** Every {@code tireKit} field at the extreme end of its {@link VehicleSpecLimits} range at once: the
     * stiffest damper on both strokes, a knee at zero (so the FAST rate applies at every speed), a bump
     * stop engaged from the very first millimetre of compression, and the stiffest stop rate.
     *
     * <p>This configuration is deliberately DEGENERATE, which is the point of the one test that uses it
     * ({@link #extremeConfiguredTireKitValuesKeepTheProductionTickFiniteAndStable}): a 500,000 N/m stop
     * bearing load from 0 m of travel means equilibrium compression is only {@code massKg * g / wheels /
     * (springRate + bumpStopRate)} -- about 0.006 m for the car, versus its 0.15 m spring-only figure -- so
     * the car rides right at the edge of contact and individual wheels legitimately read a tiny negative
     * (momentarily airborne) compression. That is a valid, finite, non-exploding physical outcome, not a
     * defect, so the test that uses it asserts finiteness and stability rather than firm grounding. Tests
     * that need a properly grounded car use {@link #LOAD_BEARING_BUMP_STOP} instead. */
    private static final String EXTREME_TIRE_KIT = """
            {"tireKit": {
              "compressionDampingFast": %s,
              "reboundDampingSlow": %s,
              "reboundDampingFast": %s,
              "dampingKneeSpeed": %s,
              "bumpStopTravel": %s,
              "bumpStopRate": %s
            }}
            """.formatted(VehicleSpecLimits.MAX_TIRE_KIT_DAMPING, VehicleSpecLimits.MAX_TIRE_KIT_DAMPING,
                    VehicleSpecLimits.MAX_TIRE_KIT_DAMPING, VehicleSpecLimits.MIN_DAMPING_KNEE_SPEED,
                    VehicleSpecLimits.MIN_BUMP_STOP_TRAVEL, VehicleSpecLimits.MAX_BUMP_STOP_RATE);

    /** The widest a wheel's reported compression may stray below zero before it stops being "momentarily
     * out of contact" and starts being a sign the force path has gone wrong. A wheel that is not touching
     * reports a negative compression equal to its gap from the ground, and {@code suspensionForce} returns
     * exactly 0 for any of it; this bound just keeps the assertion meaningful rather than vacuous. */
    private static final double MAX_OUT_OF_CONTACT_GAP = 0.25;

    /** A load-bearing bump stop and nothing else, so a difference against the stock control can only come
     * from the bump-stop term itself (damping is zero at rest either way). */
    private static final String LOAD_BEARING_BUMP_STOP = """
            {"tireKit": {"bumpStopTravel": %s, "bumpStopRate": %s}}
            """.formatted(LOAD_BEARING_BUMP_STOP_TRAVEL, VehicleSpecLimits.MAX_BUMP_STOP_RATE);

    /**
     * The headline production claim: a configured bump stop changes what {@link SableCarBody}'s own
     * suspension call actually computes, in the direction and by roughly the magnitude the arithmetic
     * predicts. A stiff stop engaging at 0.05 m carries part of the car's weight once compression passes
     * it, so equilibrium compression drops from the spring-only 0.15 m to about 0.054 m -- the car rests
     * measurably HIGHER on its springs. Observed through {@code SableCompat.wheelTravel}, which is
     * populated from {@code SableCarBody.tick}'s own {@code compressions[]} (the real per-tick value the
     * force is computed from), against a matched stock control ticking beside it in the same world.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningBumpStopIsolated")
    public static void aConfiguredBumpStopMakesTheCarRestHigherThanTheStockControl(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        // Spawned BEFORE any override is loaded, so this is a genuine stock control -- and driven through
        // the same zero-input settle window as the configured car, so the two differ only in their tireKit.
        CarEntity stock = settling(helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5)));
        CarEntity configured = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP, new BlockPos(5, 3, 15));

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertTrue(configured.spec().suspensionTuning().bumpStopRate() == VehicleSpecLimits.MAX_BUMP_STOP_RATE,
                    "the configured car did not actually receive the tireKit override at construction: "
                            + configured.spec().suspensionTuning());
            helper.assertTrue(stock.spec().suspensionTuning()
                            .equals(VehicleSpec.SuspensionTuning.singleRate(VehicleSpec.CAR.dampingRate())),
                    "the control car was not stock -- an override leaked into it: " + stock.spec().suspensionTuning());

            double stockTravel = meanTravel(helper, stock, "stock control");
            double configuredTravel = meanTravel(helper, configured, "configured car");
            helper.assertTrue(configuredTravel < stockTravel * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO,
                    "a load-bearing configured bump stop did not reduce resting compression in the production "
                            + "tick: stock=" + stockTravel + " configured=" + configuredTravel + " (configured had "
                            + "to be below " + (stockTravel * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO) + ")");
            helper.succeed();
        });
    }

    /**
     * Finite forces under the worst configuration the schema permits (this ticket's "verify ... finite
     * forces" acceptance item, exercised through production rather than asserted about the formula): every
     * {@code tireKit} field pinned to its extreme at once must still leave a real car settled, intact and
     * numerically sane -- no NaN/Infinity in the per-wheel state the force is derived from, no runaway
     * speed, and the entity not destroyed by its own suspension.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningExtremesIsolated")
    public static void extremeConfiguredTireKitValuesKeepTheProductionTickFiniteAndStable(GameTestHelper helper) {
        pad(helper, 5);
        CarEntity car = spawnWithOverride(helper, EXTREME_TIRE_KIT, new BlockPos(5, 3, 5));

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertTrue(!car.isRemoved(), "a car configured with extreme tireKit values was destroyed "
                    + "during settling");
            for (double travel : SableCompat.wheelTravel(car.sableBody())) {
                helper.assertTrue(Double.isFinite(travel),
                        "extreme tireKit values produced a non-finite suspension travel: " + travel);
                // Not "travel >= 0": see EXTREME_TIRE_KIT's own javadoc -- a stop bearing load from 0 m of
                // travel legitimately rides at the edge of contact, so a small negative (out of contact,
                // zero force) reading is correct physics here. What must NOT happen is the car being flung
                // off its springs or driven through the floor, which these bounds do catch.
                helper.assertTrue(travel > -MAX_OUT_OF_CONTACT_GAP && travel <= car.spec().restLength(),
                        "extreme tireKit values produced a suspension travel outside the car's own physical "
                                + "range (" + -MAX_OUT_OF_CONTACT_GAP + ".." + car.spec().restLength() + "): " + travel);
            }
            for (double force : car.debugWheelForces()) {
                helper.assertTrue(Double.isFinite(force),
                        "extreme tireKit values produced a non-finite wheel force: " + force);
            }
            helper.assertTrue(Double.isFinite(car.debugSpeed()) && car.debugSpeed() < 1.5,
                    "a car configured with extreme tireKit values never settled to a near-standstill: "
                            + car.debugSpeed());
            helper.assertTrue(Double.isFinite(car.getY()) && Double.isFinite(car.getX()) && Double.isFinite(car.getZ()),
                    "extreme tireKit values moved a car to a non-finite position: " + car.position());
            helper.succeed();
        });
    }

    /**
     * Contact / lost-contact with the new code path enabled: a configured car whose wheels have nothing
     * under them must report fully-extended (exactly {@code 0.0}) travel, the same signal the production
     * code already uses for a genuine raycast MISS -- a bump stop or rebound rate must never manufacture a
     * contact force, or pull the car down, where there is no ground. Pairs a matched grounded control
     * (same override, same tick window) so "0.0 everywhere" cannot pass vacuously by the override having
     * broken contact handling for every car.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningContactIsolated")
    public static void aConfiguredTireKitReportsNoContactForceWithNoGroundUnderTheWheels(GameTestHelper helper) {
        pad(helper, 5);
        // The grounded control uses the load-bearing stop, not EXTREME_TIRE_KIT: the latter deliberately
        // rides at the edge of contact (see its javadoc), which would make "the control is really touching
        // the ground" flaky for a reason that has nothing to do with the lost-contact claim under test.
        CarEntity grounded = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP, new BlockPos(5, 3, 5));
        // A pit wider than the car's own track/wheelbase (CarGeometry halfX 0.95, halfZ 1.5) and the full
        // template height, so every wheel's raycast -- at most restLength + 0.25 m -- finds nothing below.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = 0; y <= 7; y++) {
                    helper.setBlock(new BlockPos(5 + dx, y, 15 + dz), Blocks.AIR);
                }
            }
        }
        CarEntity airborne = spawnWithOverride(helper, EXTREME_TIRE_KIT, new BlockPos(5, 6, 15));

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            helper.assertTrue(!airborne.isRemoved(),
                    "the configured car over the open pit was removed while falling with no ground under it");
            for (double travel : SableCompat.wheelTravel(grounded.sableBody())) {
                helper.assertTrue(travel > 0.0, "the grounded control was not actually touching the ground, so "
                        + "the airborne comparison would be vacuous: suspensionTravel=" + travel);
            }
            double[] airborneTravel = SableCompat.wheelTravel(airborne.sableBody());
            for (int wheel = 0; wheel < airborneTravel.length; wheel++) {
                helper.assertTrue(airborneTravel[wheel] == 0.0,
                        "wheel " + wheel + " of a tireKit-configured car over an open pit reported nonzero "
                                + "suspension travel (must read fully extended/ungrounded, exactly 0.0): "
                                + airborneTravel[wheel]);
            }
            helper.succeed();
        });
    }

    /**
     * Sub-step consistency for the new force path: the resting equilibrium a configured bump stop produces
     * must not depend on how many sub-steps {@code SableCarBody.tick} splits its tick into. A static
     * equilibrium is the right observable for this (the spring/bump-stop terms depend on compression,
     * which is fixed for the whole tick, while only the damper term depends on the per-sub-step velocity
     * snapshot), so unlike an acceleration comparison this should agree closely rather than merely
     * "not materially" -- 15% of allowance for settling differences.
     *
     * <p>{@code CarConfig.WHEEL_SUB_STEPS} is a global, live-reloadable setting, so this follows the same
     * two rules {@code CarDrivetrainGameTests}' own sub-step test established: its OWN {@code batch} (one
     * batch runs at a time, so no other test's car is ticking while the global is changed), and a restore
     * that is guaranteed on every path, including a thrown assertion.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "suspensionTuningSubStepIsolated")
    public static void aConfiguredBumpStopRestsAtTheSameHeightAtOneAndFourSubSteps(GameTestHelper helper) {
        int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            pad(helper, 5);
            pad(helper, 15);
            CarConfig.WHEEL_SUB_STEPS.set(1);
            CarEntity oneStep = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP, new BlockPos(5, 3, 5));
            helper.runAfterDelay(SETTLE_TICKS, () -> compareFourSubStepsAndRestore(helper, originalSubSteps, oneStep));
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    private static void compareFourSubStepsAndRestore(GameTestHelper helper, int originalSubSteps, CarEntity oneStep) {
        try {
            double travelAtOneStep = meanTravel(helper, oneStep, "1-substep car");
            CarConfig.WHEEL_SUB_STEPS.set(4);
            CarEntity fourStep = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP, new BlockPos(5, 3, 15));
            helper.runAfterDelay(SETTLE_TICKS, () -> {
                try {
                    double travelAtFourSteps = meanTravel(helper, fourStep, "4-substep car");
                    CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                    double ratio = travelAtFourSteps / travelAtOneStep;
                    helper.assertTrue(ratio > 0.85 && ratio < 1.15,
                            "the configured bump stop's resting compression depended on wheelSubSteps: 1-step="
                                    + travelAtOneStep + " 4-step=" + travelAtFourSteps + " ratio=" + ratio);
                    helper.succeed();
                } catch (RuntimeException e) {
                    CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    /** This car's mean per-wheel compression as the production tick last computed it, asserting first that
     * every wheel is actually grounded -- a mean over a car with a wheel in the air would silently measure
     * something other than the resting equilibrium these tests compare. */
    private static double meanTravel(GameTestHelper helper, CarEntity car, String what) {
        helper.assertTrue(car.sableBody() != null, what + "'s Sable body was never created");
        double[] travel = SableCompat.wheelTravel(car.sableBody());
        helper.assertTrue(travel.length > 0, what + " reported no wheels at all");
        double total = 0.0;
        for (double wheel : travel) {
            helper.assertTrue(wheel > 0.0,
                    what + " had a wheel off the ground, so its resting compression is not measurable here: "
                            + wheel);
            total += wheel;
        }
        return total / travel.length;
    }

    /** A flat stone pad under a spawn point, wide enough for the car's own footprint -- the same 5x5 floor
     * at y = 2 under a spawn at y = 3 that {@code RollingResistanceGameTests#fillFloor} uses for the cars
     * it successfully asserts {@code suspensionTravel > 0} on. Deliberately does NOT clear the blocks above
     * the floor: an earlier version of this helper did, and no working test in this suite does. */
    private static void pad(GameTestHelper helper, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(new BlockPos(5 + dx, 2, z + dz), Blocks.STONE);
            }
        }
    }

    /** Spawns a car whose spec was resolved from {@code json}, then clears the override table immediately:
     * {@link CarEntity}'s constructor has already resolved and kept the configured spec, so nothing global
     * stays mutated while the car ticks. See this class's own javadoc. */
    private static CarEntity spawnWithOverride(GameTestHelper helper, String json, BlockPos pos) {
        try {
            Path configDir = Files.createTempDirectory("dynamicvehicles-suspension-gametest");
            Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
            Files.createDirectories(carsDir);
            Files.writeString(carsDir.resolve(VehicleIds.CAR + ".json"), json);
            VehicleSpecOverrides.load(configDir);
            return settling(helper.spawn(DynamicVehiclesMod.CAR.get(), pos));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"),
                    "dynamicvehicles-no-such-config-dir"));
        }
    }

    /**
     * Drives a freshly spawned car with ZERO throttle and steering for the whole settle window.
     *
     * <p>Not a no-op, and not cosmetic: the first version of these tests simply spawned cars and waited,
     * and every wheel reported exactly {@code 0.0} travel -- the ungrounded sentinel -- on CI and locally
     * alike. Every car test in this suite that successfully asserts real per-wheel contact ({@code
     * RollingResistanceGameTests}, {@code CarDrivetrainGameTests}) either boards a mock player or calls
     * {@code forceDrive}; none of them just spawns and waits. Zero inputs keep this a pure
     * settle-under-gravity measurement while still putting the car on the same driven path those working
     * tests use.
     */
    private static CarEntity settling(CarEntity car) {
        car.forceDrive(0.0, 0.0, (int) SETTLE_TICKS);
        return car;
    }
}
