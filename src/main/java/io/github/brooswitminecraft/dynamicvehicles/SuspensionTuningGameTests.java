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
 * while the car is measured. A stock control car spawned BEFORE the override is loaded is therefore a genuine
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

    /** The tick every measurement is taken at: late enough that the spawn transient is over and the car is
     * rolling under steady load, early enough to be comfortably inside {@link #DRIVE_TICKS} so the rigid
     * body is still awake and its suspension telemetry still live. See {@link #driving} for why measuring a
     * car at rest does not work at all. */
    private static final long MEASURE_TICK = 60L;

    /** A bump stop engaging well INSIDE the car's own spring-only static compression ({@code massKg * g /
     * wheels / springRate}, 0.15 m for the car), so it is genuinely load-bearing under ordinary load rather
     * than only on a big hit. That is what makes its effect visible as a steady difference in how far the
     * springs compress while rolling, rather than only during a transient that would have to be sampled
     * tick by tick. */
    private static final double LOAD_BEARING_BUMP_STOP_TRAVEL = 0.05;

    /** The configured car's mean compression must come out at most this fraction of the stock control's,
     * both measured on the same surface under the same throttle at the same tick. The static arithmetic
     * (0.0538 m against 0.15 m, a ratio of 0.36) is the reason to expect a large gap, but it is NOT the
     * predicted value here: these cars are rolling under load, not sitting at their static equilibrium, so
     * this is deliberately a loose one-sided bound on a MATCHED-PAIR ratio. It still fails outright if the
     * bump-stop term never reached production, which is the claim under test. */
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
     * predicts. A stiff stop engaging at 0.05 m carries part of the car's load once compression passes it,
     * so the springs compress measurably LESS than they do without it -- the car rides higher on them.
     * Observed through {@code SableCompat.wheelTravel}, which is populated from {@code SableCarBody.tick}'s
     * own {@code compressions[]} (the real per-tick value the force is computed from), against a matched
     * stock control driving beside it on identical ground for identical ticks.
     *
     * <p>Measured while both cars are rolling under a gentle throttle, not at rest: see {@link #driving}
     * for why a car at rest reports nothing at all through this telemetry.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningBumpStopIsolated")
    public static void aConfiguredBumpStopMakesTheCarRestHigherThanTheStockControl(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        // Spawned BEFORE any override is loaded, so this is a genuine stock control -- and driven through
        // the same gentle-throttle window as the configured car, so the two differ only in their tireKit.
        CarEntity stock = driving(helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5)));
        CarEntity configured = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP, new BlockPos(5, 3, 15));

        // Both cars must hold contact for the whole run-up to the comparison, not just at the instant it
        // is taken (review 49040) -- otherwise a bouncing car could be grounded on exactly the right tick.
        assertGroundedThroughout(helper, stock, "stock control", CONTACT_SAMPLE_START, MEASURE_TICK);
        assertGroundedThroughout(helper, configured, "configured car", CONTACT_SAMPLE_START, MEASURE_TICK);

        helper.runAfterDelay(MEASURE_TICK, () -> {
            helper.assertTrue(configured.spec().suspensionTuning().bumpStopRate() == VehicleSpecLimits.MAX_BUMP_STOP_RATE,
                    "the configured car did not actually receive the tireKit override at construction: "
                            + configured.spec().suspensionTuning());
            helper.assertTrue(stock.spec().suspensionTuning()
                            .equals(VehicleSpec.SuspensionTuning.singleRate(VehicleSpec.CAR.dampingRate())),
                    "the control car was not stock -- an override leaked into it: " + stock.spec().suspensionTuning());

            double stockTravel = meanTravel(helper, stock, "stock control");
            double configuredTravel = meanTravel(helper, configured, "configured car");
            helper.assertTrue(configuredTravel < stockTravel * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO,
                    "a load-bearing configured bump stop did not reduce spring compression under load in the production "
                            + "tick: stock=" + stockTravel + " configured=" + configuredTravel + " (configured had "
                            + "to be below " + (stockTravel * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO) + ")");
            helper.succeed();
        });
    }

    /**
     * Finite forces under the worst configuration the schema permits (this ticket's "verify ... finite
     * forces" acceptance item, exercised through production rather than asserted about the formula): every
     * {@code tireKit} field pinned to its extreme at once must still leave a real car driveable, intact and
     * numerically sane -- no NaN/Infinity in the per-wheel state the force is derived from, no runaway
     * speed, and the entity not destroyed by its own suspension.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningExtremesIsolated")
    public static void extremeConfiguredTireKitValuesKeepTheProductionTickFiniteAndStable(GameTestHelper helper) {
        pad(helper, 5);
        CarEntity car = spawnWithOverride(helper, EXTREME_TIRE_KIT, new BlockPos(5, 3, 5));

        helper.runAfterDelay(MEASURE_TICK, () -> {
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
            // Not "settled to a standstill": this car is deliberately under throttle for the whole window
            // (see driving()). What matters is that the extreme configuration did not make its speed blow
            // up or go non-finite -- a stiff bump stop firing every tick is exactly the shape of bug that
            // would launch a car, and MAX_SANE_DRIVEN_SPEED is far above this throttle's own cruise speed.
            helper.assertTrue(Double.isFinite(car.debugSpeed()) && Math.abs(car.debugSpeed()) < MAX_SANE_DRIVEN_SPEED,
                    "a car configured with extreme tireKit values reached an implausible speed: "
                            + car.debugSpeed() + " " + where(car));
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

        helper.runAfterDelay(MEASURE_TICK, () -> {
            helper.assertTrue(!airborne.isRemoved(),
                    "the configured car over the open pit was removed while falling with no ground under it");
            for (double travel : SableCompat.wheelTravel(grounded.sableBody())) {
                helper.assertTrue(travel > 0.0, "the grounded control was not actually touching the ground, so "
                        + "the airborne comparison would be vacuous: suspensionTravel=" + travel
                        + " " + where(grounded));
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
     * Sub-step consistency for the new force path: the compression a configured bump stop produces under a
     * fixed load must not depend on how many sub-steps {@code SableCarBody.tick} splits its tick into. A static
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
            helper.runAfterDelay(MEASURE_TICK, () -> compareFourSubStepsAndRestore(helper, originalSubSteps, oneStep));
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
            helper.runAfterDelay(MEASURE_TICK, () -> {
                try {
                    double travelAtFourSteps = meanTravel(helper, fourStep, "4-substep car");
                    CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                    double ratio = travelAtFourSteps / travelAtOneStep;
                    helper.assertTrue(ratio > 0.85 && ratio < 1.15,
                            "the configured bump stop's compression under load depended on wheelSubSteps: 1-step="
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
     * something other than the loaded compression these tests compare. */
    private static double meanTravel(GameTestHelper helper, CarEntity car, String what) {
        helper.assertTrue(car.sableBody() != null, what + "'s Sable body was never created");
        double[] travel = SableCompat.wheelTravel(car.sableBody());
        helper.assertTrue(travel.length > 0, what + " reported no wheels at all");
        double total = 0.0;
        for (double wheel : travel) {
            helper.assertTrue(wheel > 0.0,
                    what + " had a wheel off the ground, so its spring compression is not measurable here: "
                            + wheel + " " + where(car));
            total += wheel;
        }
        return total / travel.length;
    }

    /**
     * MINECRAFT-445 review follow-up (comment 49040, "verify support/contact throughout measurement"):
     * asserts every wheel is grounded at several points ACROSS the measurement window, not only at the
     * instant the comparison is taken. A car that is bouncing, drifting off its pad, or momentarily
     * airborne mid-window can still happen to be grounded on the one tick a single end-of-window check
     * looks at -- which would make the equilibrium comparison that follows quietly meaningless. This
     * strengthens the precondition rather than relaxing it: the final comparison's own assertions are
     * unchanged.
     *
     * @param firstSampleTick the first tick to sample at; sampling continues every {@link #CONTACT_SAMPLE_STRIDE}
     *        ticks up to (but not including) {@code lastSampleTick}, which the caller measures at itself
     */
    private static void assertGroundedThroughout(GameTestHelper helper, CarEntity car, String what,
            long firstSampleTick, long lastSampleTick) {
        for (long tick = firstSampleTick; tick < lastSampleTick; tick += CONTACT_SAMPLE_STRIDE) {
            helper.runAtTickTime(tick, () -> {
                for (double travel : SableCompat.wheelTravel(car.sableBody())) {
                    helper.assertTrue(travel > 0.0, what + " lost contact mid-measurement, so its spring "
                            + "compression would not be a meaningful loaded reading: " + travel + " " + where(car));
                }
            });
        }
    }

    /** An upper bound on any sane speed for a car under {@link #GENTLE_THROTTLE}, m/s -- comfortably above
     * its own cruise speed, well below anything that would indicate the suspension had launched it. Only
     * used to catch a blow-up, never to assert a particular speed. */
    private static final double MAX_SANE_DRIVEN_SPEED = 40.0;

    /** How often {@link #assertGroundedThroughout} samples contact, in ticks. Frequent enough to catch a
     * car bouncing or rolling off its pad, sparse enough not to turn one test into hundreds of assertions. */
    private static final long CONTACT_SAMPLE_STRIDE = 10L;

    /** The tick from which contact is required to hold continuously: late enough that the spawn transient
     * is over (so a car still dropping onto its springs is not treated as a failure), and strictly less
     * than {@link #MEASURE_TICK} so the sampling window is non-empty -- an equal value would silently
     * sample nothing at all and make this check vacuous. */
    private static final long CONTACT_SAMPLE_START = 30L;

    /** Enough context in a failure message to tell a car that never settled from one that settled somewhere
     * unexpected: where it actually ended up, how fast it is still moving, every wheel's own travel, and
     * what is under it. Added because two CI rounds of "a wheel is off the ground" said nothing about WHY. */
    private static String where(CarEntity car) {
        BlockPos below = car.blockPosition().below();
        return "[pos=" + car.position() + " speed=" + car.debugSpeed()
                + " travel=" + java.util.Arrays.toString(SableCompat.wheelTravel(car.sableBody()))
                + " restLength=" + car.spec().restLength()
                + " blockBelow=" + below + " " + car.level().getBlockState(below) + "]";
    }

    /** A flat stone pad under a spawn point at y = 2, under a spawn at y = 3 -- the same arrangement
     * {@code RollingResistanceGameTests#fillFloor} uses for the cars it successfully asserts {@code
     * suspensionTravel > 0} on, widened from its 5x5 to 4 blocks in every direction so a car that shifts
     * slightly while being planted cannot end up with a wheel over the pad's edge. Deliberately does NOT
     * clear the blocks above the floor: an earlier version of this helper did, and no working test in this
     * suite does. */
    private static void pad(GameTestHelper helper, int z) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
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
            return driving(helper.spawn(DynamicVehiclesMod.CAR.get(), pos));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"),
                    "dynamicvehicles-no-such-config-dir"));
        }
    }

    /**
     * Holds a car under a gentle, constant throttle across the whole measurement window, which is the
     * arrangement every test in this suite that successfully reads real per-wheel contact uses.
     *
     * <p>Four CI rounds went into this one line, and the first three attempts were all wrong in an
     * instructive way:
     * <ul>
     * <li>Spawn and wait: every wheel read exactly {@code 0.0} travel, the ungrounded sentinel.
     * <li>{@code forceDrive} at zero throttle: travel became {@code -0.178} -- a real raycast value, so
     * the tick was running, but the car was not planted.
     * <li>{@code forceDrive} at FULL throttle to plant it: {@code speed=8.7}, {@code blockBelow=air} -- it
     * drove off the pad into the void.
     * <li>A boarded mock rider holding no inputs: {@code speed=1.8e-8}, {@code pos.y} exactly level with
     * the pad's surface, {@code travel=[0,0,0,0]}.
     * </ul>
     * That last result is the one that explains the rest. A car left at rest comes to a complete stop and
     * its Rapier rigid body goes to SLEEP; a sleeping body's tick no longer refreshes {@code
     * suspensionTravel}, so the array keeps the zeros it was allocated with, and the chassis ends up
     * supported by its own collision box rather than by its springs. A resting equilibrium is therefore not
     * an observable state through this telemetry at all -- which is exactly why no existing test in this
     * suite measures one, and why the single test that does assert {@code suspensionTravel > 0} ({@code
     * RollingResistanceGameTests#airborneWheelsOverAPitGetNoTerrainRollingDrag}) reads it off a car that is
     * actively driving.
     *
     * <p>So these tests measure a LOADED, AWAKE car instead: both the configured car and its stock control
     * are held at the same gentle throttle on the same surface for the same ticks, and compared at the same
     * tick while still driving. A load-bearing bump stop still carries part of the load while rolling, so
     * the comparison it is there to make survives the change of regime intact -- only the absolute numbers
     * differ from the static equilibrium the arithmetic predicts, which is why this test asserts a RATIO
     * between two matched cars rather than either car's absolute compression.
     */
    private static CarEntity driving(CarEntity car) {
        car.forceDrive(GENTLE_THROTTLE, 0.0, DRIVE_TICKS);
        return car;
    }

    /** Throttle held across the measurement window: enough to keep the rigid body awake and its wheels
     * loaded, gentle enough (the same value {@code RollingResistanceGameTests} uses for its own cruise
     * comparisons) that a car does not climb to a speed where it could leave its pad. */
    private static final double GENTLE_THROTTLE = 0.3;

    /** Ticks of {@link #GENTLE_THROTTLE}; must outlast {@link #MEASURE_TICK} so every measurement is taken
     * while the car is still awake and loaded rather than after it has coasted to a stop and slept. */
    private static final int DRIVE_TICKS = 120;
}
