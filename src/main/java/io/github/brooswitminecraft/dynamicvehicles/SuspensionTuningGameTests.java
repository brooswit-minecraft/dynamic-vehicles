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

    /** The tick a test's assertions run at when its cars were all spawned at tick 0: comfortably past
     * {@link #SAMPLE_WINDOW_TICKS}, so every per-tick sample it compares has already been taken.
     *
     * <p>A test that spawns a SECOND car partway through (the sub-step one must, since {@code
     * WHEEL_SUB_STEPS} is global and the two cars cannot run concurrently) has to offset both its sampling
     * window and its assertion tick by the tick that car actually appeared at. {@code runAtTickTime} takes
     * an ABSOLUTE test tick, so a window of 1..50 requested at tick 60 simply never fires -- which is
     * exactly how an earlier revision of that test came to read a peak of 0.0 for its second car and
     * report "never touched the ground" for a car that landed perfectly well. */
    private static final long MEASURE_TICK = 60L;

    /** A bump stop engaging well INSIDE the car's own spring-only static compression ({@code massKg * g /
     * wheels / springRate}, 0.15 m for the car), so an ordinary landing drives compression past it and the
     * stop genuinely bears load. A threshold near the end of travel would only engage on a violent impact
     * and would make the comparison depend on how hard the drop happened to be. */
    private static final double LOAD_BEARING_BUMP_STOP_TRAVEL = 0.05;

    /** The configured car's PEAK compression over its drop must come out at most this fraction of the stock
     * control's over an identical drop. The static arithmetic (0.0538 m against 0.15 m, a ratio of 0.36) is
     * the reason to expect a large gap, but it is NOT the predicted value here: a drop is a dynamic impact,
     * not a static equilibrium, so this is deliberately a loose one-sided bound on a MATCHED-PAIR ratio. It
     * still fails outright if the bump-stop term never reached production, which is the claim under test. */
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

    /** An upper bound on any sane speed for a car under {@link #GENTLE_THROTTLE}, m/s -- comfortably above
     * its own cruise speed, well below anything that would indicate the suspension had launched it. Only
     * used to catch a blow-up, never to assert a particular speed. */
    private static final double MAX_SANE_DRIVEN_SPEED = 40.0;

    /** A load-bearing bump stop and nothing else, so a difference against the stock control can only come
     * from the bump-stop term itself (damping is zero at rest either way). */
    private static final String LOAD_BEARING_BUMP_STOP = """
            {"tireKit": {"bumpStopTravel": %s, "bumpStopRate": %s}}
            """.formatted(LOAD_BEARING_BUMP_STOP_TRAVEL, VehicleSpecLimits.MAX_BUMP_STOP_RATE);

    /**
     * The headline production claim: a configured bump stop changes what {@link SableCarBody}'s own
     * suspension call actually computes. A stiff stop engaging at 0.05 m carries load as soon as
     * compression passes that point, so it must limit how far the springs can be driven on a real impact --
     * the configured car's PEAK compression over a drop must come out materially below the matched stock
     * control's. Read through {@code SableCompat.wheelTravel}, which is populated from {@code
     * SableCarBody.tick}'s own {@code compressions[]}: the real per-tick value the force is computed from.
     *
     * <p>Why a drop rather than a resting or rolling measurement -- both of which earlier revisions of this
     * test tried and CI rejected: a car at rest sleeps and reports nothing ({@link #driving}), and a car
     * under throttle pitches so only one axle stays loaded. A drop keeps the body awake and loads all four
     * wheels together, and a peak over the whole window does not depend on which tick is sampled. See
     * {@link #peakCompressionOverDrop}.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningBumpStopIsolated")
    public static void aConfiguredBumpStopLimitsPeakCompressionVersusTheStockControl(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        // Spawned BEFORE any override is loaded, so this is a genuine stock control; dropped from the same
        // height over identical ground for identical ticks, so the two differ only in their tireKit.
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3 + DROP_BLOCKS, 5));
        CarEntity configured = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP,
                new BlockPos(5, 3 + DROP_BLOCKS, 15));

        PeakCompression stockPeak = peakCompressionOverDrop(helper, stock, 0L);
        PeakCompression configuredPeak = peakCompressionOverDrop(helper, configured, 0L);

        helper.runAfterDelay(MEASURE_TICK, () -> {
            helper.assertTrue(configured.spec().suspensionTuning().bumpStopRate() == VehicleSpecLimits.MAX_BUMP_STOP_RATE,
                    "the configured car did not actually receive the tireKit override at construction: "
                            + configured.spec().suspensionTuning());
            helper.assertTrue(stock.spec().suspensionTuning()
                            .equals(VehicleSpec.SuspensionTuning.singleRate(VehicleSpec.CAR.dampingRate())),
                    "the control car was not stock -- an override leaked into it: " + stock.spec().suspensionTuning());
            helper.assertTrue(stockPeak.allFinite && configuredPeak.allFinite,
                    "a wheel reported non-finite compression at some point during the drop");

            // Both cars must actually have landed, or the comparison below would be between two numbers
            // that only mean "never touched anything".
            helper.assertTrue(stockPeak.deepest > LOAD_BEARING_BUMP_STOP_TRAVEL,
                    "the stock control never compressed past the configured car's own bump-stop threshold, so "
                            + "this drop cannot distinguish them at all: peak=" + stockPeak.deepest + " "
                            + where(stock));
            helper.assertTrue(configuredPeak.deepest > 0.0,
                    "the configured car never touched the ground during the drop: peak="
                            + configuredPeak.deepest + " " + where(configured));

            helper.assertTrue(configuredPeak.deepest < stockPeak.deepest * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO,
                    "a load-bearing configured bump stop did not limit peak compression in the production tick: "
                            + "stock=" + stockPeak.deepest + " configured=" + configuredPeak.deepest
                            + " (configured had to be below "
                            + (stockPeak.deepest * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO) + ")");
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
        CarEntity car = driving(spawnWithOverride(helper, EXTREME_TIRE_KIT, new BlockPos(5, 3, 5)));

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
        CarEntity grounded = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP, new BlockPos(5, 3 + DROP_BLOCKS, 5));
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

        // Both sides are judged over a WINDOW, not at one instant. CI caught why that matters: checked at a
        // single tick, the grounded control was mid-bounce after its drop and read a uniform -0.165 on all
        // four wheels, failing a precondition that was only ever meant to establish "this car can see the
        // ground". Sampling instead makes each side's claim the stronger one: the control must find contact
        // at SOME point, and the airborne car must find it at NO point.
        PeakCompression groundedPeak = peakCompressionOverDrop(helper, grounded, 0L);
        PeakCompression airbornePeak = peakCompressionOverDrop(helper, airborne, 0L);

        helper.runAfterDelay(MEASURE_TICK, () -> {
            helper.assertTrue(!airborne.isRemoved(),
                    "the configured car over the open pit was removed while falling with no ground under it");
            helper.assertTrue(groundedPeak.allFinite && airbornePeak.allFinite,
                    "a wheel reported non-finite compression during the window");

            helper.assertTrue(groundedPeak.deepest > 0.0,
                    "the grounded control never detected the ground under it at any point, so the airborne "
                            + "comparison would be vacuous: peak=" + groundedPeak.deepest + " " + where(grounded));

            // The claim under test, at full strength: over the whole window, no wheel of the configured car
            // over an open pit ever reported positive compression. 0.0 is the production code's own
            // fully-extended/ungrounded sentinel, so a peak of exactly 0.0 means no bump stop or rebound
            // rate ever manufactured contact where there was no ground.
            helper.assertTrue(airbornePeak.deepest == 0.0,
                    "a tireKit-configured car over an open pit reported contact at some point during the "
                            + "window (every wheel must stay at the fully-extended 0.0 sentinel): peak="
                            + airbornePeak.deepest + " " + where(airborne));
            helper.succeed();
        });
    }

    /**
     * Sub-step consistency for the new force path: the peak compression a configured bump stop allows on an
     * identical drop must not depend on how many sub-steps {@code SableCarBody.tick} splits its tick into.
     * Compression is the right observable (the spring and bump-stop terms depend on compression, which is
     * fixed for the whole tick, while only the damper term reads the per-sub-step velocity snapshot), so
     * unlike a speed comparison this should agree closely rather than merely "not materially" -- 25% of
     * allowance, since the damper term genuinely does see a different velocity snapshot per sub-step and an
     * impact is where that difference is largest.
     *
     * <p>{@code CarConfig.WHEEL_SUB_STEPS} is a global, live-reloadable setting, so this follows the same
     * two rules {@code CarDrivetrainGameTests}' own sub-step test established: its OWN {@code batch} (one
     * batch runs at a time, so no other test's car is ticking while the global is changed), and a restore
     * that is guaranteed on every path, including a thrown assertion.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "suspensionTuningSubStepIsolated")
    public static void aConfiguredBumpStopAllowsTheSamePeakCompressionAtOneAndFourSubSteps(GameTestHelper helper) {
        int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
        try {
            pad(helper, 5);
            pad(helper, 15);
            CarConfig.WHEEL_SUB_STEPS.set(1);
            CarEntity oneStep = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP,
                    new BlockPos(5, 3 + DROP_BLOCKS, 5));
            PeakCompression oneStepPeak = peakCompressionOverDrop(helper, oneStep, 0L);
            helper.runAfterDelay(MEASURE_TICK,
                    () -> compareFourSubStepsAndRestore(helper, originalSubSteps, oneStepPeak));
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    private static void compareFourSubStepsAndRestore(GameTestHelper helper, int originalSubSteps,
            PeakCompression oneStepPeak) {
        try {
            CarConfig.WHEEL_SUB_STEPS.set(4);
            CarEntity fourStep = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP,
                    new BlockPos(5, 3 + DROP_BLOCKS, 15));
            PeakCompression fourStepPeak = peakCompressionOverDrop(helper, fourStep, MEASURE_TICK);
            // Delay measured from NOW (this callback already runs at MEASURE_TICK), so the second car gets
            // the same full sampling window the first one did before anything is compared.
            helper.runAfterDelay(MEASURE_TICK, () -> {
                try {
                    CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                    helper.assertTrue(oneStepPeak.deepest > 0.0 && fourStepPeak.deepest > 0.0,
                            "a sub-step car never touched the ground during its drop, so there is nothing to "
                                    + "compare: 1-step=" + oneStepPeak.deepest + " 4-step=" + fourStepPeak.deepest);
                    double ratio = fourStepPeak.deepest / oneStepPeak.deepest;
                    helper.assertTrue(ratio > 0.75 && ratio < 1.25,
                            "the configured bump stop's peak compression depended on wheelSubSteps: 1-step="
                                    + oneStepPeak.deepest + " 4-step=" + fourStepPeak.deepest + " ratio=" + ratio);
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
            return helper.spawn(DynamicVehiclesMod.CAR.get(), pos);
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

    /**
     * Samples every wheel's compression on each of the {@link #SAMPLE_WINDOW_TICKS} ticks after {@code fromTick} and returns a
     * holder of the PEAK (deepest) compression seen on any wheel.
     *
     * <p>This is the observable the bump stop and the damper rates actually exist to change, and it is the
     * one regime where the measurement is both well defined and robust. A car in free fall onto its pad is
     * awake (so its telemetry is live, unlike a car at rest -- see {@link #driving}) and loads all four
     * wheels together on impact (unlike a car under throttle, which pitches: CI showed exactly that, one
     * car reading {@code [0.229, 0.103, 0.0, 0.0]} and another {@code [0.0, 0.0, -0.087, -0.086]} while
     * rolling, with real contact on only one axle). Taking a peak across the whole window also removes any
     * dependence on which single tick the comparison lands on.
     */
    private static PeakCompression peakCompressionOverDrop(GameTestHelper helper, CarEntity car, long fromTick) {
        PeakCompression peak = new PeakCompression();
        for (long tick = fromTick + 1; tick <= fromTick + SAMPLE_WINDOW_TICKS; tick++) {
            helper.runAtTickTime(tick, () -> {
                for (double travel : SableCompat.wheelTravel(car.sableBody())) {
                    if (Double.isFinite(travel)) {
                        peak.deepest = Math.max(peak.deepest, travel);
                    }
                    peak.allFinite &= Double.isFinite(travel);
                }
            });
        }
        return peak;
    }

    /** Mutable accumulator for {@link #peakCompressionOverDrop}; a plain holder because GameTest's own
     * per-tick callbacks are the only way to sample a value across ticks. */
    private static final class PeakCompression {
        /** Deepest compression any wheel reached, metres; stays at its initial value if no wheel ever
         * touched, which the asserting test checks rather than silently averaging away. */
        double deepest = Double.NEGATIVE_INFINITY;
        /** Whether every sample on every tick was finite -- the "finite forces" half of this ticket's own
         * acceptance, observed across a real impact rather than at one chosen instant. */
        boolean allFinite = true;
    }

    /** Height above the pad, in blocks, that a car is dropped from: enough to build a real impact speed
     * that pushes compression past {@link #LOAD_BEARING_BUMP_STOP_TRAVEL}, little enough to stay inside the
     * template. */
    private static final int DROP_BLOCKS = 3;

    /** How many ticks {@link #peakCompressionOverDrop} samples for, starting from the tick its car was
     * spawned at; comfortably past the landing and the bounce that follows it. */
    private static final long SAMPLE_WINDOW_TICKS = 50L;

    /** Throttle held across the measurement window: enough to keep the rigid body awake and its wheels
     * loaded, gentle enough (the same value {@code RollingResistanceGameTests} uses for its own cruise
     * comparisons) that a car does not climb to a speed where it could leave its pad. */
    private static final double GENTLE_THROTTLE = 0.3;

    /** Ticks of {@link #GENTLE_THROTTLE}; must outlast {@link #MEASURE_TICK} so every measurement is taken
     * while the car is still awake and loaded rather than after it has coasted to a stop and slept. */
    private static final int DRIVE_TICKS = 120;
}
