package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

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
 *
 * <p><b>Every delayed callback here is registered from the test body itself, never from inside another
 * callback.</b> This is the crash class {@code CarDrivetrainGameTests}' ledge rig already documents
 * (MINECRAFT-442 round 14): vanilla's {@code GameTestInfo#tickInternal} runs a test's body before it starts
 * iterating its {@code runAtTickTimeMap}, but runs every scheduled callback from INSIDE that iteration, and
 * fastutil's open-hash map has no fail-fast check -- a callback that registers dozens of further samplers
 * rehashes the map under the live iterator. The two anti-dive fixtures below did exactly that, and
 * reproduced the same {@code "this.wrapped" is null} server crash locally; short of crashing, the same
 * corruption can drop a scheduled sampler or assertion, or run one twice. A car that only exists from some
 * later tick is therefore sampled through a holder its own spawn callback fills, rather than by scheduling
 * its sampler late.
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

    /** MINECRAFT-445 review fix (PR184 #issuecomment-6282714154): the configured car's SETTLED MEAN
     * compression (see {@link #aConfiguredBumpStopLimitsPeakCompressionVersusTheStockControl}'s own
     * javadoc for the switch from a drop's peak to a steady-state mean) must come out at most this
     * fraction of the stock control's. The analytical equilibrium ratio at this fixture's own moderate
     * {@link #MAX_BUMP_STOP_RATE_FOR_THIS_TEST} is ~0.52 (0.0782 m against 0.15 m: {@code (m*g/4 +
     * bumpStopRate * travel) / (springRate + bumpStopRate)}); the ~0.36 an earlier revision quoted was the
     * schema-ceiling stop's figure, which this fixture no longer uses. This is tighter than the 0.7 the old
     * drop-based version used (and which still failed CI at an observed 0.775, the first failure in five
     * prior runs) because a settled mean has far less run-to-run variance than a drop's impact peak; it
     * leaves the analytical value about 0.08 of headroom while still failing a stop that carries no load,
     * whose ratio would be 1.0. */
    private static final double MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO = 0.6;

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

    /** The rear-steer ratio the fixture below configures and then verifies in the production tick. */
    private static final double REAR_STEER_RATIO = -0.25;

    /** Opposite-phase rear steering and nothing else: the rear wheels turn against the fronts at a quarter
     * of their angle. Chosen negative (opposite phase) because that is the configuration with a clear,
     * checkable sign relationship against the front angle. */
    private static final String OPPOSITE_PHASE_REAR_STEER = """
            {"tireKit": {"rearSteerRatio": %s}}
            """.formatted(REAR_STEER_RATIO);

    /** Steering input held while the rear-steer angles are read: full lock, so the front angle is as large
     * as this car's own speed-dependent limit allows and the rear share of it is unambiguous. */
    private static final double FULL_STEER = 1.0;

    /** Tolerance for comparing a published steer angle against the ratio times the front angle, radians.
     * Tight: this is one multiplication in production, not an integrated physical quantity. */
    private static final double STEER_ANGLE_TOLERANCE = 1e-9;

    /** A soft tire and nothing else: well under nominal, so it buys grip and pays in rolling drag. The
     * rolling side is what this fixture measures, because cruise speed under a fixed throttle is the
     * observable RollingResistanceGameTests has already proven this suite can compare reliably. */
    private static final String SOFT_TIRES = """
            {"tireKit": {"tirePressureBar": %s}}
            """.formatted(VehicleSpecLimits.MIN_TIRE_PRESSURE_BAR);

    /** A load-bearing bump stop and nothing else, so a difference against the stock control can only come
     * from the bump-stop term itself (damping is zero at rest either way). */
    private static final String LOAD_BEARING_BUMP_STOP = """
            {"tireKit": {"bumpStopTravel": %s, "bumpStopRate": %s}}
            """.formatted(LOAD_BEARING_BUMP_STOP_TRAVEL, VehicleSpecLimits.MAX_BUMP_STOP_RATE);

    /**
     * The headline production claim: a configured bump stop changes what {@link SableCarBody}'s own
     * suspension call actually computes. A stop carrying load must limit how far the springs settle under
     * a real impact -- the configured car's LATE-WINDOW mean compression after a drop must come out
     * materially below the matched stock control's. Read through {@code SableCompat.wheelTravel}, which is
     * populated from {@code SableCarBody.tick}'s own {@code compressions[]}: the real per-tick value the
     * force is computed from.
     *
     * <p>Three measurement techniques were tried for this one fixture, each corrected by CI evidence:
     * <ol>
     * <li>Drop + PEAK compression: failed once in six runs (stock=0.4065, configured=0.3151, a 0.775 ratio
     * against a 0.7 requirement) with no change to its own code path. A drop's peak depends on impact
     * timing -- exactly when first contact lands relative to a physics sub-step -- which is not
     * deterministic the way a settled value is.
     * <li>Driving (throttle) + MEAN compression: failed differently (configured mean -0.008, i.e. the
     * configured car was OFF THE GROUND on average). Diagnosis: {@link #MAX_BUMP_STOP_RATE_FOR_THIS_TEST}
     * at the schema's full 500,000 N/m, engaging only 0.05 m into travel, makes the EFFECTIVE stiffness
     * near equilibrium (springRate + bumpStopRate = 519,620 N/m) nearly 27x the car's own spring rate,
     * while the damping rate stays the one tuned for the SOFT spring alone -- the effective damping ratio
     * collapses from ~0.70 (critically damped, by the stock springRate/dampingRate's own design) to ~0.14
     * (sharply underdamped). Combined with throttle-induced pitch, the configured car oscillates around
     * its ~0.0538 m analytical equilibrium with enough amplitude to swing below zero (off the ground)
     * repeatedly -- averaging those negative/no-contact samples in with the real ones does not measure
     * settled compression at all, it partly measures "how often is this car airborne", which can trivially
     * look smaller than the control's for reasons that have nothing to do with the bump stop limiting
     * anything (review comment 49284).
     * </ol>
     * This version removes BOTH problems at their source rather than filtering around them: a DROP (no
     * throttle, so no pitch) using a MODERATE bump-stop rate ({@link #MAX_BUMP_STOP_RATE_FOR_THIS_TEST},
     * a tenth of the schema's ceiling) chosen specifically so the effective damping ratio near equilibrium
     * stays well-behaved (~0.37, still underdamped but nowhere near the 500,000 N/m case's ~0.14), sampled
     * in a LATE window well after both the fall and the post-impact settling transient are over. Every
     * sampled tick asserts EVERY wheel of BOTH cars is actually in contact (positive travel) before it
     * contributes to the mean -- a car that is not stably supported fails loudly, with the tick and the
     * offending reading named, rather than being silently averaged into a number that looks fine.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningBumpStopIsolated")
    public static void aConfiguredBumpStopLimitsPeakCompressionVersusTheStockControl(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3 + DROP_BLOCKS, 5));
        CarEntity configured = spawnWithOverride(helper, MODERATE_LOAD_BEARING_BUMP_STOP,
                new BlockPos(5, 3 + DROP_BLOCKS, 15));

        SupportedMean stockMean = supportedMeanCompressionOverWindow(helper, stock, LATE_WINDOW_START, LATE_WINDOW_TICKS);
        SupportedMean configuredMean = supportedMeanCompressionOverWindow(helper, configured, LATE_WINDOW_START, LATE_WINDOW_TICKS);

        // One tick after the last sample: callbacks due on the same tick run in hash order, so an assertion
        // on the window's own last tick could run before that tick's sampler and see one tick short.
        helper.runAtTickTime(LATE_WINDOW_START + LATE_WINDOW_TICKS + 1, () -> {
            helper.assertTrue(configured.spec().suspensionTuning().bumpStopRate() == MAX_BUMP_STOP_RATE_FOR_THIS_TEST,
                    "the configured car did not actually receive the tireKit override at construction: "
                            + configured.spec().suspensionTuning());
            helper.assertTrue(stock.spec().suspensionTuning()
                            .equals(VehicleSpec.SuspensionTuning.singleRate(VehicleSpec.CAR.dampingRate())),
                    "the control car was not stock -- an override leaked into it: " + stock.spec().suspensionTuning());
            helper.assertTrue(stockMean.allFinite && configuredMean.allFinite,
                    "a wheel reported non-finite compression at some point during the sampling window");
            // The explicit diagnosis review 49284 asked for: a car that was not STABLY SUPPORTED for the
            // whole window fails here, by name, with the exact tick and reading -- not folded into an
            // average that could look fine for the wrong reason.
            helper.assertTrue(stockMean.unsupportedTick < 0,
                    "the stock control lost contact on at least one wheel during the late sampling window, "
                            + "so it was not stably supported: " + stockMean.unsupportedDetail);
            helper.assertTrue(configuredMean.unsupportedTick < 0,
                    "the configured car lost contact on at least one wheel during the late sampling window, "
                            + "so it was not stably supported: " + configuredMean.unsupportedDetail);
            // The FULL window, not merely "some" samples (PR184 review 5580581955): a mean over a handful of
            // ticks is not the settled value this fixture claims to compare.
            helper.assertTrue(stockMean.ticks == LATE_WINDOW_TICKS && configuredMean.ticks == LATE_WINDOW_TICKS,
                    "a car was not sampled on every tick of the late window: stock ticks=" + stockMean.ticks
                            + " configured ticks=" + configuredMean.ticks + " of " + LATE_WINDOW_TICKS);

            double stockMeanTravel = stockMean.sum / stockMean.samples;
            double configuredMeanTravel = configuredMean.sum / configuredMean.samples;
            helper.assertTrue(stockMeanTravel > LOAD_BEARING_BUMP_STOP_TRAVEL,
                    "the stock control's settled compression never exceeded the configured car's own "
                            + "bump-stop threshold, so this comparison cannot distinguish them at all: mean="
                            + stockMeanTravel + " " + where(stock));

            helper.assertTrue(configuredMeanTravel < stockMeanTravel * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO,
                    "a load-bearing configured bump stop did not limit settled compression in the production "
                            + "tick: stock=" + stockMeanTravel + " configured=" + configuredMeanTravel
                            + " (configured had to be below "
                            + (stockMeanTravel * MAX_CONFIGURED_TO_STOCK_TRAVEL_RATIO) + ")");
            helper.succeed();
        });
    }

    /** A moderate bump-stop rate, deliberately a TENTH of the schema's own ceiling
     * ({@code VehicleSpecLimits.MAX_BUMP_STOP_RATE}), chosen specifically for this fixture's stability --
     * see this fixture's own javadoc for the underdamped-oscillation failure the full ceiling produced.
     * {@link #extremeConfiguredTireKitValuesKeepTheProductionTickFiniteAndStable} already exercises the
     * actual schema ceiling deliberately, with its own documented edge-of-contact caveat; this fixture's
     * job is only to prove a load-bearing stop reaches production, which a moderate rate does just as
     * conclusively while staying well-behaved enough to measure reliably. */
    private static final double MAX_BUMP_STOP_RATE_FOR_THIS_TEST = VehicleSpecLimits.MAX_BUMP_STOP_RATE / 10.0;

    /** A load-bearing bump stop at the moderate rate above, used only by this fixture. */
    private static final String MODERATE_LOAD_BEARING_BUMP_STOP = """
            {"tireKit": {"bumpStopTravel": %s, "bumpStopRate": %s}}
            """.formatted(LOAD_BEARING_BUMP_STOP_TRAVEL, MAX_BUMP_STOP_RATE_FOR_THIS_TEST);

    /** Tick the late sampling window starts at: past both the fall ({@code DROP_BLOCKS} takes roughly 16
     * ticks to land) and the post-impact settling transient (a handful of oscillation periods at this
     * fixture's own moderate effective stiffness/damping -- see its javadoc). */
    private static final long LATE_WINDOW_START = 34L;

    /** Ticks the late window samples for. {@code LATE_WINDOW_START + LATE_WINDOW_TICKS} (34 + 16 = 50)
     * deliberately matches {@link #SAMPLE_WINDOW_TICKS}, so this stays inside the same drop window every
     * other drop-based fixture in this class already uses -- not expressed as that constant minus the
     * start tick because {@code SAMPLE_WINDOW_TICKS} is declared later in this file and a static final
     * field cannot forward-reference one that is not yet initialised. */
    private static final long LATE_WINDOW_TICKS = 16L;

    /** Samples every wheel's compression on each tick of the window and keeps the running MEAN across all
     * four wheels and all sampled ticks, but -- unlike a plain mean -- records the FIRST tick (and wheel
     * reading) where any wheel was not in contact, so a car that was not stably supported for the whole
     * window is diagnosable by name rather than silently folded into an average. A tick on which the car has
     * no Sable body at all counts as unsupported too: nothing was holding it up that this could observe. */
    private static SupportedMean supportedMeanCompressionOverWindow(GameTestHelper helper, CarEntity car,
            long fromTick, long windowTicks) {
        SupportedMean mean = new SupportedMean();
        for (long tick = fromTick + 1; tick <= fromTick + windowTicks; tick++) {
            long sampleTick = tick;
            helper.runAtTickTime(sampleTick, () -> {
                if (car.sableBody() == null) {
                    if (mean.unsupportedTick < 0) {
                        mean.unsupportedTick = sampleTick;
                        mean.unsupportedDetail = "tick=" + sampleTick + " the car had no Sable body";
                    }
                    return;
                }
                mean.ticks++;
                double[] travel = SableCompat.wheelTravel(car.sableBody());
                for (int wheel = 0; wheel < travel.length; wheel++) {
                    double t = travel[wheel];
                    if (!Double.isFinite(t)) {
                        mean.allFinite = false;
                        continue;
                    }
                    if (t <= 0.0 && mean.unsupportedTick < 0) {
                        mean.unsupportedTick = sampleTick;
                        mean.unsupportedDetail = "tick=" + sampleTick + " wheel=" + wheel + " travel=" + t
                                + " " + where(car);
                    }
                    mean.sum += t;
                    mean.samples++;
                }
            });
        }
        return mean;
    }

    /** Mutable accumulator for {@link #supportedMeanCompressionOverWindow}. */
    private static final class SupportedMean {
        double sum = 0.0;
        int samples = 0;
        /** Ticks on which the car had a body to sample. */
        long ticks = 0;
        boolean allFinite = true;
        /** The first sampled tick any wheel was not in contact, or -1 if every sampled wheel on every
         * sampled tick was. */
        long unsupportedTick = -1;
        String unsupportedDetail = "";
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
                for (int y = PIT_FLOOR_Y; y <= 7; y++) {
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
        PeakCompression groundedPeak = peakCompressionOverDrop(helper, () -> grounded, 0L);
        // The airborne car is sampled over a SHORTER window than the grounded control, and that asymmetry
        // is the fix for the last CI failure here rather than an oversight: with the full 50-tick window
        // the car fell clean through the cleared column and landed on the template floor underneath it
        // (peak=0.284, blockBelow=stone), so the test was measuring a landing it had itself arranged. The
        // window now ends well before the car can cross even the deepened pit -- see AIRBORNE_SAMPLE_TICKS.
        PeakCompression airbornePeak = peakCompressionOverWindow(helper, () -> airborne, 0L, AIRBORNE_SAMPLE_TICKS);

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
     * that is guaranteed on every path, including a thrown assertion AND a timeout -- the latter only a
     * terminal {@link GameTestListener} can see (PR184 review, butchr at 42bf198), since by the time the
     * framework times a test out every stack frame below, and its {@code catch}, is long gone.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "suspensionTuningSubStepIsolated")
    public static void aConfiguredBumpStopAllowsTheSamePeakCompressionAtOneAndFourSubSteps(GameTestHelper helper) {
        int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
        Runnable restore = () -> CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
        // Registered BEFORE the first mutation; idempotent, so the earlier catch-based restores stay harmless.
        runOnEveryTerminalOutcome(helper, restore);
        try {
            pad(helper, 5);
            pad(helper, 15);
            CarConfig.WHEEL_SUB_STEPS.set(1);
            CarEntity oneStep = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP,
                    new BlockPos(5, 3 + DROP_BLOCKS, 5));
            PeakCompression oneStepPeak = peakCompressionOverDrop(helper, () -> oneStep, 0L);
            // The four-step car cannot exist until MEASURE_TICK (the setting is global, so the two cars
            // cannot tick at once), but its sampler is still registered here -- see this class's javadoc --
            // and reads it through this holder, which the spawn callback fills.
            CarEntity[] fourStep = new CarEntity[1];
            PeakCompression fourStepPeak = peakCompressionOverDrop(helper, () -> fourStep[0], MEASURE_TICK);

            helper.runAtTickTime(MEASURE_TICK, () -> {
                try {
                    CarConfig.WHEEL_SUB_STEPS.set(4);
                    fourStep[0] = spawnWithOverride(helper, LOAD_BEARING_BUMP_STOP,
                            new BlockPos(5, 3 + DROP_BLOCKS, 15));
                } catch (RuntimeException e) {
                    restore.run();
                    throw e;
                }
            });

            // MEASURE_TICK after the second car appeared, so it gets the same full sampling window the
            // first one did before anything is compared.
            helper.runAtTickTime(2 * MEASURE_TICK, () -> {
                try {
                    restore.run();
                    helper.assertTrue(oneStepPeak.deepest > 0.0 && fourStepPeak.deepest > 0.0,
                            "a sub-step car never touched the ground during its drop, so there is nothing to "
                                    + "compare: 1-step=" + oneStepPeak.deepest + " 4-step=" + fourStepPeak.deepest);
                    double ratio = fourStepPeak.deepest / oneStepPeak.deepest;
                    helper.assertTrue(ratio > 0.75 && ratio < 1.25,
                            "the configured bump stop's peak compression depended on wheelSubSteps: 1-step="
                                    + oneStepPeak.deepest + " 4-step=" + fourStepPeak.deepest + " ratio=" + ratio);
                    helper.succeed();
                } catch (RuntimeException e) {
                    restore.run();
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            restore.run();
            throw e;
        }
    }

    /** Runs {@code action} on whichever terminal outcome this test reaches -- pass, fail (a timeout
     * included) or rerun -- the same listener {@code CarDrivetrainGameTests}' ledge rig uses for its own
     * cleanup. {@code action} must be idempotent: a test that also restores early will run it twice. */
    private static void runOnEveryTerminalOutcome(GameTestHelper helper, Runnable action) {
        helper.testInfo.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                action.run();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                action.run();
            }

            @Override
            public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) {
                action.run();
            }
        });
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

    /**
     * Rear steering, through the production tick: a configured car's REAR wheels must be steered by exactly
     * the configured fraction of its front wheels' angle, with the opposite sign for a negative ratio,
     * while a matched stock control keeps its rear wheels at exactly 0.0.
     *
     * <p>Observable chosen deliberately after the earlier fixtures in this class cost several CI rounds to
     * get right: {@code steerAngle} is the one per-wheel value {@code SableCarBody.tick} publishes for
     * EVERY wheel every sub-step, above its own force and raycast guards, because (its own comment)
     * "steer angle is a pure function of front/steer/forwardSpeed/spec, with no dependence on ground
     * contact". So this reads a value that does not depend on contact, wheel load, bounce, or whether the
     * rigid body is awake -- none of the things that made the suspension fixtures hard. It is still real
     * production physics rather than telemetry: that same angle rotates the tire's forward axis, which is
     * what {@code vLong}/{@code vLat} and therefore the tire's force directions are measured against.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 200, batch = "suspensionTuningRearSteerIsolated")
    public static void aConfiguredRearSteerRatioSteersTheRearWheelsInProduction(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        CarEntity stock = driving(helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5)));
        CarEntity configured = driving(spawnWithOverride(helper, OPPOSITE_PHASE_REAR_STEER,
                new BlockPos(5, 3, 15)));
        stock.forceDrive(GENTLE_THROTTLE, FULL_STEER, DRIVE_TICKS);
        configured.forceDrive(GENTLE_THROTTLE, FULL_STEER, DRIVE_TICKS);

        helper.runAfterDelay(MEASURE_TICK, () -> {
            helper.assertTrue(configured.spec().steeringTuning().rearSteerRatio() == REAR_STEER_RATIO,
                    "the configured car did not receive the rearSteerRatio override at construction: "
                            + configured.spec().steeringTuning());
            helper.assertTrue(stock.spec().steeringTuning().equals(VehicleSpec.SteeringTuning.IDENTITY),
                    "the control car was not stock -- an override leaked into it: "
                            + stock.spec().steeringTuning());

            assertRearSteerMatchesRatio(helper, configured, REAR_STEER_RATIO, "configured car");
            // The control proves the effect is the override's, not something every car now does.
            assertRearSteerMatchesRatio(helper, stock, 0.0, "stock control");
            helper.succeed();
        });
    }

    /** Checks every wheel's published steer angle against this car's own front angle: front wheels carry
     * the front angle itself, rear wheels exactly {@code ratio} times it. Front/rear is decided the same
     * way production decides it -- {@code mounts()[wheel][2] > 0} -- rather than by assuming an index
     * order. Requires a nonzero front angle first, so a car that happened not to be steering at all
     * cannot pass this vacuously with zeros on both axles. */
    private static void assertRearSteerMatchesRatio(GameTestHelper helper, CarEntity car, double ratio,
            String what) {
        double[] angles = SableCompat.wheelSteerAngles(car.sableBody());
        double[][] mounts = car.spec().mounts();
        double frontAngle = 0.0;
        for (int wheel = 0; wheel < angles.length; wheel++) {
            if (mounts[wheel][2] > 0) {
                frontAngle = angles[wheel];
            }
        }
        helper.assertTrue(Math.abs(frontAngle) > STEER_ANGLE_TOLERANCE,
                what + " was not steering at all, so its rear-steer ratio is not observable here: front="
                        + frontAngle + " angles=" + java.util.Arrays.toString(angles));
        for (int wheel = 0; wheel < angles.length; wheel++) {
            double expected = mounts[wheel][2] > 0 ? frontAngle : frontAngle * ratio;
            helper.assertTrue(Math.abs(angles[wheel] - expected) < STEER_ANGLE_TOLERANCE,
                    what + " wheel " + wheel + (mounts[wheel][2] > 0 ? " (front)" : " (rear)")
                            + " steer angle did not match the configured ratio " + ratio + ": expected "
                            + expected + " got " + angles[wheel] + " front=" + frontAngle);
        }
    }

    /**
     * Tire pressure, through the production tick: a car on deliberately soft tires must cruise SLOWER
     * under the same sustained throttle on the same ground than a matched stock control, because the extra
     * rolling drag its pressure multiplier adds is real force in {@code WheelMath.tire}, not a label.
     *
     * <p>Measured while COASTING, not under throttle, and that distinction is the fix for this fixture's
     * first CI failure. Under power the two effects of pressure compete: a soft tire has more rolling drag
     * (slower) but also more grip (better acceleration), and in a throttled window the grip side can win,
     * which is exactly what CI showed. With the throttle released there is no drive force for extra grip
     * to deliver, so rolling drag is the only term left that differs between the two cars -- the clean
     * isolation this claim needs.
     *
     * <p>Direction matters and is asserted, not assumed: pressure moves grip and rolling drag in OPPOSITE
     * directions (see {@code WheelMath#tirePressureRollingMultiplier}), so a soft tire is not simply
     * "worse" -- it is slower in a straight line while having more grip available. This fixture pins the
     * straight-line cost; the grip side is pinned by {@code WheelMathTirePressureTest}, which can compare
     * the multipliers directly instead of trying to isolate cornering grip in a world test.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "suspensionTuningTirePressureIsolated")
    public static void softTiresCruiseSlowerThanStockUnderTheSameThrottle(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        CarEntity soft = spawnWithOverride(helper, SOFT_TIRES, new BlockPos(5, 3, 15));
        // Spin both up under identical throttle, then release it: the comparison happens while coasting.
        stock.forceDrive(GENTLE_THROTTLE, 0.0, (int) SPIN_UP_TICKS);
        soft.forceDrive(GENTLE_THROTTLE, 0.0, (int) SPIN_UP_TICKS);
        // Entry speeds, captured by the first callback for the second; both registered here, at the top
        // level -- see this class's javadoc.
        double[] entry = new double[2];

        helper.runAtTickTime(SPIN_UP_TICKS, () -> {
            helper.assertTrue(soft.spec().tirePressureBar() == VehicleSpecLimits.MIN_TIRE_PRESSURE_BAR,
                    "the soft-tire car did not receive the tirePressureBar override at construction: "
                            + soft.spec().tirePressureBar());
            helper.assertTrue(stock.spec().tirePressureBar() == WheelMath.NOMINAL_TIRE_PRESSURE_BAR,
                    "the control car was not at nominal pressure -- an override leaked into it: "
                            + stock.spec().tirePressureBar());

            entry[0] = horizontalSpeed(stock);
            entry[1] = horizontalSpeed(soft);
            // Setup gate: a comparison between two cars that never moved would be vacuous, the same
            // failure mode this suite's own rolling-resistance fixtures guard against.
            helper.assertTrue(entry[0] > MIN_MEANINGFUL_CRUISE_SPEED && entry[1] > MIN_MEANINGFUL_CRUISE_SPEED,
                    "test setup never got both cars rolling before the coast, so there would be no speed "
                            + "loss to compare: stock=" + entry[0] + " soft=" + entry[1]);
        });

        helper.runAtTickTime(SPIN_UP_TICKS + COAST_TICKS, () -> {
            double stockEntry = entry[0];
            double softEntry = entry[1];
            // Speed LOST while coasting, each car measured against its own entry speed, so a small
            // difference in how fast they got up to speed cannot be mistaken for the drag difference.
            double stockLoss = stockEntry - horizontalSpeed(stock);
            double softLoss = softEntry - horizontalSpeed(soft);
            helper.assertTrue(stockLoss > 0.0,
                    "the stock control did not slow down at all while coasting, so this comparison "
                            + "would be vacuous: entry=" + stockEntry + " now=" + horizontalSpeed(stock));
            helper.assertTrue(softLoss > stockLoss,
                    "a car on soft tires must lose MORE speed while coasting than a matched stock "
                            + "control -- its pressure rolling multiplier may not be reaching the real "
                            + "tire force: stockLoss=" + stockLoss + " softLoss=" + softLoss
                            + " (entry speeds stock=" + stockEntry + " soft=" + softEntry + ")");
            helper.succeed();
        });
    }

    /** Horizontal ground speed, m/s, from {@code CarEntity}'s own published {@code getDeltaMovement()} --
     * the same quantity and conversion {@code RollingResistanceGameTests} reads for its cruise comparisons. */
    private static double horizontalSpeed(CarEntity car) {
        Vec3 velocity = car.getDeltaMovement();
        return Math.hypot(velocity.x, velocity.z) * 20.0;
    }

    /** Ticks of sustained throttle used to get both cars rolling before the throttle is released. */
    private static final long SPIN_UP_TICKS = 60L;

    /** Ticks of coasting, throttle released, over which the two cars' speed LOSS is compared. Long enough
     * for a 4.4x rolling-drag difference to separate them, short enough that neither has stopped. */
    private static final long COAST_TICKS = 40L;

    /** The stock control must exceed this (m/s) or the comparison is measuring two stationary cars. */
    private static final double MIN_MEANINGFUL_CRUISE_SPEED = 0.5;

    /**
     * Anti-dive, through the production tick: a car with full anti-dive geometry must DIVE LESS under the
     * same braking than a matched stock control, measured as the front-to-rear spring-compression
     * difference that dive physically is.
     *
     * <p>Both cars are driven up to speed and then braked hard, and the front-minus-rear compression gap
     * is sampled every tick with the PEAK taken -- the technique that made the earlier fixtures in this
     * class reliable, since it removes any dependence on which single tick the comparison lands on. Under
     * braking a car is pressed firmly onto its springs, so this is the one pitch regime where every wheel
     * is solidly loaded and the compression reading is meaningful.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "suspensionTuningAntiDiveIsolated")
    public static void fullAntiDiveDivesLessUnderBrakingThanTheStockControl(GameTestHelper helper) {
        pad(helper, 5);
        pad(helper, 15);
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        CarEntity configured = spawnWithOverride(helper, FULL_ANTI_DIVE, new BlockPos(5, 3, 15));
        stock.forceDrive(GENTLE_THROTTLE, 0.0, (int) ACCELERATE_TICKS);
        configured.forceDrive(GENTLE_THROTTLE, 0.0, (int) ACCELERATE_TICKS);
        // Registered here, not inside the tick-ACCELERATE_TICKS callback -- see this class's javadoc.
        PeakDive stockDive = peakDiveOverBraking(helper, stock, ACCELERATE_TICKS);
        PeakDive configuredDive = peakDiveOverBraking(helper, configured, ACCELERATE_TICKS);

        helper.runAtTickTime(ACCELERATE_TICKS, () -> {
            helper.assertTrue(configured.spec().pitchTuning().antiDive() == VehicleSpecLimits.MAX_PITCH_RESIST,
                    "the configured car did not receive the antiDive override at construction: "
                            + configured.spec().pitchTuning());
            helper.assertTrue(stock.spec().pitchTuning().equals(VehicleSpec.PitchTuning.IDENTITY),
                    "the control car was not stock -- an override leaked into it: " + stock.spec().pitchTuning());
            helper.assertTrue(horizontalSpeed(stock) > MIN_MEANINGFUL_CRUISE_SPEED,
                    "test setup never got the stock control moving before braking, so there would be no "
                            + "dive to measure: " + horizontalSpeed(stock) + " " + where(stock));

            // Brake hard: negative throttle is brake-only, the same input CarDrivetrainGameTests uses.
            stock.forceDrive(-1.0, 0.0, (int) BRAKE_TICKS);
            configured.forceDrive(-1.0, 0.0, (int) BRAKE_TICKS);
        });

        // One tick after the last sample, so the comparison cannot run ahead of that tick's own sampler.
        helper.runAtTickTime(ACCELERATE_TICKS + BRAKE_TICKS + 1, () -> {
            helper.assertTrue(stockDive.peak > Double.NEGATIVE_INFINITY
                            && configuredDive.peak > Double.NEGATIVE_INFINITY,
                    "a car was never sampled during the braking window -- its Sable body did not exist "
                            + "on any sampled tick: stock=" + stockDive.peak + " configured="
                            + configuredDive.peak);
            helper.assertTrue(stockDive.peak > 0.0,
                    "the stock control never dived at all under braking, so this comparison would be "
                            + "vacuous: peak=" + stockDive.peak + " " + where(stock));
            helper.assertTrue(configuredDive.peak < stockDive.peak,
                    "full anti-dive geometry must reduce the front-to-rear compression gap under braking "
                            + "-- its application-height blend may not be reaching the real tire impulse: "
                            + "stock=" + stockDive.peak + " configured=" + configuredDive.peak);
            helper.succeed();
        });
    }

    /** Samples front-minus-rear mean spring compression every tick of the braking window and keeps the
     * peak: dive IS that gap, so a geometry that resists dive must shrink it. Front/rear is decided the
     * way production decides it ({@code mounts()[wheel][2] > 0}). */
    private static PeakDive peakDiveOverBraking(GameTestHelper helper, CarEntity car, long fromTick) {
        PeakDive dive = new PeakDive();
        for (long tick = fromTick + 1; tick <= fromTick + BRAKE_TICKS; tick++) {
            helper.runAtTickTime(tick, () -> {
                // A car whose Sable body does not exist on this tick cannot be sampled. CI hit exactly
                // this as an NPE from SableCompat.wheelTravel(null) -- skip the tick and let the
                // assertion below report "never sampled", which says what happened instead of throwing.
                if (car.sableBody() == null) {
                    return;
                }
                double[] travel = SableCompat.wheelTravel(car.sableBody());
                double[][] mounts = car.spec().mounts();
                double front = 0.0;
                double rear = 0.0;
                int frontCount = 0;
                int rearCount = 0;
                for (int wheel = 0; wheel < travel.length; wheel++) {
                    if (mounts[wheel][2] > 0) {
                        front += travel[wheel];
                        frontCount++;
                    } else {
                        rear += travel[wheel];
                        rearCount++;
                    }
                }
                if (frontCount > 0 && rearCount > 0) {
                    dive.peak = Math.max(dive.peak, front / frontCount - rear / rearCount);
                }
            });
        }
        return dive;
    }

    /** Mutable accumulator for {@link #peakDiveOverBraking}; GameTest's per-tick callbacks are the only
     * way to sample across ticks. */
    private static final class PeakDive {
        double peak = Double.NEGATIVE_INFINITY;
    }

    /**
     * MINECRAFT-445 review fix (PR184 #issuecomment-6282623425): anti-dive/anti-squat is documented as a
     * LONGITUDINAL geometry effect only -- it must not also change how much the car rolls while cornering.
     * Before this fix, {@code SableCarBody.tick} raised the application height of a wheel's ENTIRE
     * horizontal impulse (longitudinal AND lateral bundled together) by the anti-dive/anti-squat fraction,
     * which would have shortened the lateral moment arm too and silently reduced roll transfer under
     * cornering for any car that configured pitch resistance -- a claim this ticket never made and never
     * tested. This fixture brakes AND steers at once (so both the longitudinal and lateral tire forces this
     * fixture is supposed to tell apart are actually present simultaneously) and asserts the LEFT-RIGHT
     * (roll) compression gap is materially UNCHANGED by full anti-dive, while the FRONT-REAR (dive) gap in
     * the same manoeuvre is materially reduced -- the second half is what keeps the first from passing
     * vacuously on a car whose anti-dive never engaged.
     *
     * <p>PR184 review 5580581955 -- the manoeuvre's own premise, corrected. The first version of this
     * fixture braked at FULL brake (3000 N a wheel, about 1 g) at FULL lock, which puts every tire on its
     * friction limit, so each axle's lateral force is capped by its normal load -- and moving normal load
     * between the axles under braking is exactly what anti-dive does. Per-tick telemetry from a local
     * reproduction (stock 0.258, configured 0.104, ratio 0.405): as the stock car dived its rear friction
     * limit fell from 3339 N to 1014 N within four ticks, its rear wheels locked and it oversteered into
     * about 11.4 kN of total lateral force, while the anti-dive car kept its rear loaded and rolling and
     * cornered on about 6.2 kN. The roll gap followed that force, not where it was applied, and whether
     * the stock car locked up varied from run to run: the same code read 0.405 in one local run and 1.11
     * in the next. That fixture could not tell the defect it was written for from the fix.
     *
     * <p>So this manoeuvre stays inside the friction circle: a light brake at moderate lock from a modest
     * speed, where a tire's lateral force is set by its slip velocity rather than capped by its load, so
     * both cars corner on the same lateral force and a roll difference can only come from where that
     * force acts. Like-for-like is CHECKED rather than assumed -- the two cars' mean total lateral force
     * must agree, or this fails saying so instead of misreporting a roll leak. Roll is compared as the
     * window's MEAN left-right gap rather than one tick's peak, which is the steadier of the two across
     * placements. Checked against the defect itself: with the blend applied to the lateral impulse as well
     * (the pre-fix behaviour), this manoeuvre measured a roll ratio of 0.09; with the shipped split,
     * 1.01..1.10 across every placement tried.
     *
     * <p>The two cars run side by side on a stone floor covering the whole template, and the manoeuvre is
     * short enough (about 15 m forward and 3 m sideways) to finish on it, so it is the same flat surface
     * wherever the harness places the test. That matters: the harness picks a random spot up to 15 million
     * blocks out, and at most of those a car's world position barely changes even at speed (float32
     * precision), while near the origin the cars genuinely drive that far. Both were tried locally, the
     * near-origin case on a temporary rig.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 300, batch = "suspensionTuningAntiDiveRollIsolated")
    public static void fullAntiDiveDoesNotChangeRollTransferWhileCorneringUnderBraking(GameTestHelper helper) {
        for (int x = 0; x <= TEMPLATE_MAX; x++) {
            for (int z = 0; z <= TEMPLATE_MAX; z++) {
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
            }
        }
        CarEntity stock = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 3));
        CarEntity configured = spawnWithOverride(helper, FULL_ANTI_DIVE, new BlockPos(16, 3, 3));
        stock.forceDrive(GENTLE_THROTTLE, 0.0, (int) CORNER_SPIN_UP_TICKS);
        configured.forceDrive(GENTLE_THROTTLE, 0.0, (int) CORNER_SPIN_UP_TICKS);
        // Registered here, not inside the tick-CORNER_SPIN_UP_TICKS callback -- see this class's javadoc.
        CorneringResponse stockResponse = sampleCornering(helper, stock, CORNER_SPIN_UP_TICKS);
        CorneringResponse configuredResponse = sampleCornering(helper, configured, CORNER_SPIN_UP_TICKS);

        helper.runAtTickTime(CORNER_SPIN_UP_TICKS, () -> {
            helper.assertTrue(configured.spec().pitchTuning().antiDive() == VehicleSpecLimits.MAX_PITCH_RESIST,
                    "the configured car did not receive the antiDive override at construction: "
                            + configured.spec().pitchTuning());
            helper.assertTrue(stock.spec().pitchTuning().equals(VehicleSpec.PitchTuning.IDENTITY),
                    "the control car was not stock -- an override leaked into it: " + stock.spec().pitchTuning());
            helper.assertTrue(horizontalSpeed(stock) > MIN_MEANINGFUL_CRUISE_SPEED,
                    "test setup never got the stock control moving before the brake+steer window: "
                            + horizontalSpeed(stock) + " " + where(stock));
            stock.forceDrive(-CORNER_BRAKE, CORNER_STEER, (int) CORNER_TICKS);
            configured.forceDrive(-CORNER_BRAKE, CORNER_STEER, (int) CORNER_TICKS);
        });

        // One tick after the last sample, so the comparison cannot run ahead of that tick's own sampler.
        helper.runAtTickTime(CORNER_SPIN_UP_TICKS + CORNER_TICKS + 1, () -> {
            helper.assertTrue(stockResponse.ticks == CORNER_TICKS && configuredResponse.ticks == CORNER_TICKS,
                    "a car was not sampled on every tick of the brake+steer window: stock ticks="
                            + stockResponse.ticks + " configured ticks=" + configuredResponse.ticks + " of "
                            + CORNER_TICKS);
            helper.assertTrue(Math.abs(stockResponse.meanRoll()) > MIN_MEANINGFUL_ROLL_GAP,
                    "the stock control never showed a meaningful left-right compression gap while "
                            + "cornering, so this comparison would be vacuous: mean=" + stockResponse.meanRoll()
                            + " " + where(stock));
            helper.assertTrue(configuredResponse.peakDive < stockResponse.peakDive * MAX_ANTI_DIVE_TO_STOCK_DIVE_RATIO,
                    "full anti-dive did not materially reduce dive in this manoeuvre, so its roll comparison "
                            + "would say nothing about anti-dive: stock dive=" + stockResponse.peakDive
                            + " configured dive=" + configuredResponse.peakDive);
            double rollRatio = configuredResponse.meanRoll() / stockResponse.meanRoll();
            double lateralRatio = configuredResponse.meanLateralForce() / stockResponse.meanLateralForce();
            helper.assertTrue(rollRatio > MIN_UNCHANGED_ROLL_RATIO && rollRatio < MAX_UNCHANGED_ROLL_RATIO,
                    "full anti-dive geometry changed the left-right (roll) compression gap while "
                            + "cornering under braking -- its application-height blend may be reaching "
                            + "the LATERAL tire force as well as the longitudinal one it is documented "
                            + "to affect: stock=" + stockResponse.meanRoll() + " configured="
                            + configuredResponse.meanRoll() + " ratio=" + rollRatio + " (mean lateral force "
                            + "stock=" + stockResponse.meanLateralForce() + " configured="
                            + configuredResponse.meanLateralForce() + ")");
            helper.assertTrue(lateralRatio > MIN_MATCHED_LATERAL_RATIO && lateralRatio < MAX_MATCHED_LATERAL_RATIO,
                    "the two cars did not corner on the same lateral force, so their roll gaps are not "
                            + "like-for-like and this manoeuvre has left the friction circle it relies on: "
                            + "mean lateral force stock=" + stockResponse.meanLateralForce() + " configured="
                            + configuredResponse.meanLateralForce() + " ratio=" + lateralRatio);
            helper.succeed();
        });
    }

    /** Samples, every tick of the brake+steer window, the front-minus-rear (dive) and left-minus-right
     * (roll) mean spring compression and the car's total lateral tire force. Front/rear and left/right are
     * decided the way production decides them ({@code mounts()[wheel][2] > 0}, {@code mounts()[wheel][0] <
     * 0.0}). */
    private static CorneringResponse sampleCornering(GameTestHelper helper, CarEntity car, long fromTick) {
        CorneringResponse response = new CorneringResponse();
        for (long tick = fromTick + 1; tick <= fromTick + CORNER_TICKS; tick++) {
            helper.runAtTickTime(tick, () -> {
                if (car.sableBody() == null) {
                    return;
                }
                double[] travel = SableCompat.wheelTravel(car.sableBody());
                double[] lateralForces = SableCompat.wheelLateralForces(car.sableBody());
                double[][] mounts = car.spec().mounts();
                double front = 0.0;
                double rear = 0.0;
                double left = 0.0;
                double right = 0.0;
                int frontCount = 0;
                int leftCount = 0;
                double totalLateral = 0.0;
                for (int wheel = 0; wheel < travel.length; wheel++) {
                    if (mounts[wheel][2] > 0) {
                        front += travel[wheel];
                        frontCount++;
                    } else {
                        rear += travel[wheel];
                    }
                    if (mounts[wheel][0] < 0.0) {
                        left += travel[wheel];
                        leftCount++;
                    } else {
                        right += travel[wheel];
                    }
                    totalLateral += lateralForces[wheel];
                }
                int rearCount = travel.length - frontCount;
                int rightCount = travel.length - leftCount;
                if (frontCount == 0 || rearCount == 0 || leftCount == 0 || rightCount == 0) {
                    return;
                }
                response.ticks++;
                response.peakDive = Math.max(response.peakDive, front / frontCount - rear / rearCount);
                response.rollSum += left / leftCount - right / rightCount;
                response.lateralForceSum += Math.abs(totalLateral);
            });
        }
        return response;
    }

    /** Mutable accumulator for {@link #sampleCornering}. The roll gap is summed SIGNED, so the
     * stock-vs-configured ratio of the means compares like with like. */
    private static final class CorneringResponse {
        double peakDive = Double.NEGATIVE_INFINITY;
        double rollSum = 0.0;
        double lateralForceSum = 0.0;
        long ticks = 0;

        /** Mean left-minus-right compression gap over the sampled ticks, m. */
        double meanRoll() {
            return ticks == 0 ? 0.0 : rollSum / ticks;
        }

        /** Mean magnitude of the car's summed per-wheel lateral tire force over the sampled ticks, N. */
        double meanLateralForce() {
            return ticks == 0 ? 0.0 : lateralForceSum / ticks;
        }
    }

    /** The stock control's own mean left-right gap must exceed this (metres) for the ratio comparison to
     * be meaningful rather than dividing two near-zero numbers. It measures about 0.03. */
    private static final double MIN_MEANINGFUL_ROLL_GAP = 0.01;

    /** The configured-to-stock mean roll-gap ratio must fall inside this band for anti-dive to count as
     * having left cornering roll transfer alone. Tighter than the 0.5..1.5 the full-brake version of this
     * fixture used, because inside the friction circle the two cars corner on the same lateral force: the
     * shipped split measured 1.01..1.10 across every placement tried, the pre-fix code (blend applied to
     * the lateral impulse too) 0.09. */
    private static final double MIN_UNCHANGED_ROLL_RATIO = 0.8;
    private static final double MAX_UNCHANGED_ROLL_RATIO = 1.25;

    /** Full anti-dive must take at least a quarter off the stock control's peak dive gap in the cornering
     * manoeuvre, or anti-dive was not active enough there for "roll unchanged" to mean anything; a car
     * whose anti-dive never engaged would read 1.0. Measured 0.01..0.53: the steered front tires' cornering
     * force has a rearward component that still pitches the car, and anti-dive deliberately leaves the
     * lateral force alone. */
    private static final double MAX_ANTI_DIVE_TO_STOCK_DIVE_RATIO = 0.75;

    /** The configured-to-stock ratio of mean total lateral force the cornering manoeuvre must stay inside
     * for its two cars to count as like-for-like. It measures 0.99..1.02; the full-brake version that
     * saturated every tire had the anti-dive car cornering on about 6.2 kN against the stock car's
     * 11.4 kN. */
    private static final double MIN_MATCHED_LATERAL_RATIO = 0.85;
    private static final double MAX_MATCHED_LATERAL_RATIO = 1.15;

    /** Ticks of {@link #GENTLE_THROTTLE} before the cornering manoeuvre: about 8 m/s, reached in about
     * 6 m, so the manoeuvre starts well inside the floor. */
    private static final long CORNER_SPIN_UP_TICKS = 30L;

    /** Ticks of the brake+steer window. */
    private static final long CORNER_TICKS = 30L;

    /** A fifth of full brake (600 N a wheel, about 0.2 g): enough to dive measurably, far from the 1 g
     * that locked the stock car's unloaded rear in this fixture's first version. */
    private static final double CORNER_BRAKE = 0.2;

    /** About a third of this car's speed-limited lock at the manoeuvre's speed: a real cornering load that
     * leaves every tire inside its friction circle alongside {@link #CORNER_BRAKE}. */
    private static final double CORNER_STEER = 0.35;

    /** Highest block coordinate inside {@code settlement_test}'s 24x24 footprint. */
    private static final int TEMPLATE_MAX = 23;

    /** Full anti-dive and nothing else, so any difference against the control is that geometry alone. */
    private static final String FULL_ANTI_DIVE = """
            {"tireKit": {"antiDive": %s}}
            """.formatted(VehicleSpecLimits.MAX_PITCH_RESIST);

    /** Ticks of throttle before braking starts; enough to build real speed so there is dive to resist. */
    private static final long ACCELERATE_TICKS = 60L;

    /** Ticks of braking over which the dive peak is sampled. */
    private static final long BRAKE_TICKS = 30L;

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
     *
     * <p>{@code car} is read on every sampled tick rather than once, so a car that only spawns at {@code
     * fromTick} can still have its sampler registered from the test body -- see this class's javadoc.
     */
    private static PeakCompression peakCompressionOverDrop(GameTestHelper helper, Supplier<CarEntity> car,
            long fromTick) {
        return peakCompressionOverWindow(helper, car, fromTick, SAMPLE_WINDOW_TICKS);
    }

    /** As {@link #peakCompressionOverDrop}, for a caller that needs its own window length -- the airborne
     * case, whose window must end before its car can fall far enough to find anything to land on. */
    private static PeakCompression peakCompressionOverWindow(GameTestHelper helper, Supplier<CarEntity> carRef,
            long fromTick, long windowTicks) {
        PeakCompression peak = new PeakCompression();
        for (long tick = fromTick + 1; tick <= fromTick + windowTicks; tick++) {
            helper.runAtTickTime(tick, () -> {
                CarEntity car = carRef.get();
                if (car == null || car.sableBody() == null) {
                    return;
                }
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

    /** Lowest level the lost-contact pit is cleared to, in template coordinates. Deeper than the template's
     * own floor so a car suspended over it has a long way to fall before finding anything, which is what
     * makes {@link #AIRBORNE_SAMPLE_TICKS} able to cover a genuinely contact-free window. */
    private static final int PIT_FLOOR_Y = -6;

    /** How long the airborne car is sampled for, in ticks. Must END BEFORE the car can reach the bottom of
     * the pit: free fall covers about 5 blocks in these 20 ticks, against the 12 blocks of cleared air
     * below its spawn ({@code y = 6} down to {@link #PIT_FLOOR_Y}), so every sampled tick is one where the
     * car genuinely has nothing under it. A longer window is what made this fixture measure its own
     * arranged landing instead of the lost-contact behaviour it is about. */
    private static final long AIRBORNE_SAMPLE_TICKS = 20L;

    /** Throttle held across the measurement window: enough to keep the rigid body awake and its wheels
     * loaded, gentle enough (the same value {@code RollingResistanceGameTests} uses for its own cruise
     * comparisons) that a car does not climb to a speed where it could leave its pad. */
    private static final double GENTLE_THROTTLE = 0.3;

    /** Ticks of {@link #GENTLE_THROTTLE}; must outlast {@link #MEASURE_TICK} so every measurement is taken
     * while the car is still awake and loaded rather than after it has coasted to a stop and slept. */
    private static final int DRIVE_TICKS = 120;
}
