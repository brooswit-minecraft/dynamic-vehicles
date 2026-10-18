package io.github.brooswitminecraft.dynamicvehicles;

import io.github.brooswitminecraft.dynamicterrain.SlipMath;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-401 review: production-path regressions for the connected drivetrain/limiter/default-gear/
 * neutral-RPM fixes, driven through a real {@link CarEntity} ticking in a real {@link
 * net.minecraft.server.level.ServerLevel}, not only through the pure {@link DrivetrainMath}/{@link
 * GearDrive} helper tests. Each test's own javadoc names the specific review finding it guards.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class CarDrivetrainGameTests {
    private CarDrivetrainGameTests() {}

    /** Review P1/core ticket ask: D1's own extra torque multiplier must show up as a real, measurably
     * larger speed than top gear over the same short window on the same surface -- not just as a
     * different number in a pure-function test.
     *
     * <p>Two deliberate choices avoid measuring the wrong thing:
     * <ul>
     * <li>A SETTLE period (20 ticks at zero throttle) runs first. A freshly spawned body's suspension
     * has not yet reached equilibrium, and {@code debugSpeed()} is the body's full 3D velocity magnitude
     * -- the vertical settling bounce right after spawn dominates it for the first several ticks,
     * completely masking any horizontal difference between gears. Measuring from a settled standstill
     * means every bit of speed gained afterward is actually from the drivetrain.
     * <li>A PARTIAL throttle (0.3) keeps both gears' commanded force under the tire's grip limit, which
     * isolates the ratio multiplier itself from the separate wheelspin/rev-limiter interaction the next
     * test below covers -- at full throttle, D1's own wheelspin can trip its own limiter within a tick or
     * two (a real low-gear, low-grip launch's own "fighting the limiter" behavior).
     * </ul>
     */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void d1ReachesAHigherSpeedThanD5OverTheSameShortWindow(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 2), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 2), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 2, 10), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 10), Blocks.AIR);
        CarEntity carD1 = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 2));
        carD1.setYRot(0.0f);
        carD1.selectGear(CarPhysics.Gear.D1);
        CarEntity carD5 = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 10));
        carD5.setYRot(0.0f);
        carD5.selectGear(CarPhysics.Gear.D5);
        Player riderD1 = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(riderD1.startRiding(carD1), "mock player failed to board the D1 car");
        Player riderD5 = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(riderD5.startRiding(carD5), "mock player failed to board the D5 car");
        helper.runAfterDelay(60L, () -> {
            helper.assertTrue(carD1.debugSpeed() < 1.5 && carD5.debugSpeed() < 1.5,
                    "test setup invalid -- a car had not settled to a near-standstill before throttle was applied: D1="
                            + carD1.debugSpeed() + " D5=" + carD5.debugSpeed());
            riderD1.zza = 0.3f;
            riderD5.zza = 0.3f;
            helper.runAfterDelay(20L, () -> {
                helper.assertTrue(carD1.debugGearRatio() == DrivetrainMath.ratioFor(0),
                        "D1 did not resolve to its own ratio on the production tick path: " + carD1.debugGearRatio());
                helper.assertTrue(carD5.debugGearRatio() == DrivetrainMath.ratioFor(4),
                        "D5 did not resolve to its own ratio on the production tick path: " + carD5.debugGearRatio());
                helper.assertTrue(carD1.debugSpeed() > carD5.debugSpeed() * 1.3,
                        "D1's extra torque multiplier did not produce a measurably higher speed than D5 from a settled standstill: D1="
                                + carD1.debugSpeed() + " D5=" + carD5.debugSpeed());
                helper.succeed();
            });
        });
    }

    /** Review P1 (both rounds): the rev limiter must cap TORQUE, not merely the number displayed, and the
     * limiter's own on/off reset (round 1's bug) must not recur. Pinned against a wall in D1 at full
     * throttle: the driven wheels' own spin rate must stay bounded near what the ratio/limiter can carry
     * instead of growing without bound, AND the chassis speed must stay near zero for the ENTIRE window
     * (sampled throughout, not just checked once at the end) -- round 2's finding was that an immediate
     * spin-to-force-to-spin reset is not evidence of a stable limiter, so this also asserts the body never
     * drifts away from the wall, which an unstable on/off reset could let happen by intermittently
     * delivering a large net impulse. The peak RAW slip speed (m/s, {@code debugSlipSpeed()} -- NOT the
     * normalized 0..1 audio signal {@code clientSlip()}, a unit mismatch round 2 flagged) is compared
     * against {@link SlipReporter}'s own erosion threshold (&gt; 0.3 m/s), which is the actual "wheelspin
     * with near-zero body speed must still erode eligible ground" signal the ticket asks for. Samples
     * across the whole window and asserts on the WORST (peak) spin/slip seen, not a single end-of-window
     * snapshot that could land mid-cut: coupling the limiter to the wheel's own spin state means torque
     * legitimately oscillates near the limiter rather than holding it steady -- a real low-gear, low-grip
     * launch's own "fighting the limiter" behavior, now damped by the engine's own modeled rotational
     * inertia ({@link DrivetrainMath#smoothedRpm}) rather than snapping on/off every tick.
     *
     * <p>Review, round 3: &gt; 0.3 m/s alone is SlipReporter's own GATE, not proof erosion itself would do
     * anything with it -- the real downstream decision is {@code dynamic-terrain}'s own
     * {@link SlipMath#amount}, a pure function of slip speed and wheel load this test can call directly
     * with the peak slip actually observed (and the car's own real quarter-mass load, matching
     * {@code SableCarBody}'s {@code wearSlipForce[wheel] / GRAVITY} that production code passes). A merely
     * gate-clearing slip that still rounds to a negligible erosion amount would not be the evidence the
     * ticket means by "must still erode eligible ground" -- asserting on the actual amount, not just the
     * gate, is. Observing the resulting IN-WORLD block change directly is not attempted here: by design
     * (see {@code Erosion.erode}'s own probability roll against a block's resistance) a single report has
     * well under a 1% chance of actually changing a stone block, so waiting for one in a short, deterministic
     * window would make this test flaky for the wrong reason -- the pure {@code amount} this test does
     * check is the one piece of that pipeline that is NOT probabilistic. */
    @GameTest(template = "settlement_test", timeoutTicks = 140)
    public static void limiterCapsWheelspinInsteadOfLettingItGrowWithoutBoundAgainstAWall(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        for (int dx = 4; dx <= 6; dx++) {
            for (int dy = 2; dy <= 5; dy++) {
                helper.setBlock(new BlockPos(dx, dy, 7), Blocks.STONE);
            }
        }
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.D1);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = 1.0f;
        float[] worstSpinHolder = {0.0f};
        float[] worstSlipHolder = {0.0f};
        float[] worstSpeedHolder = {0.0f};
        long[] sampleTicks = {5, 10, 15, 20, 30, 40, 55, 70, 85, 100, 115, 130};
        for (long sampleTick : sampleTicks) {
            helper.runAfterDelay(sampleTick, () -> {
                for (int wheel = 0; wheel < car.syncedWheelCount(); wheel++) {
                    worstSpinHolder[0] = Math.max(worstSpinHolder[0], Math.abs(car.wheelSpin(wheel, 1.0f)));
                }
                worstSlipHolder[0] = Math.max(worstSlipHolder[0], (float) car.debugSlipSpeed());
                worstSpeedHolder[0] = Math.max(worstSpeedHolder[0], (float) car.debugSpeed());
            });
        }
        helper.runAfterDelay(138L, () -> {
            helper.assertTrue(worstSpeedHolder[0] < 5.0,
                    "the car drifted away from the wall at some point instead of staying blocked throughout -- test setup invalid, peak speed="
                            + worstSpeedHolder[0]);
            // An unbounded-torque bug injects roughly (excess force * wheelRadius / wheelInertia * dt) of
            // spin EVERY tick indefinitely, which after 130 ticks of full D1 throttle is orders of
            // magnitude past this. A correctly limiter-coupled drivetrain's peak stays near where the
            // engaged ratio's own RPM reaches the limiter -- comfortably under 150 rad/s for D1's ratio.
            helper.assertTrue(worstSpinHolder[0] < 150.0,
                    "a driven wheel's spin rate grew without bound instead of being capped by the rev limiter: " + worstSpinHolder[0] + " rad/s");
            helper.assertTrue(worstSlipHolder[0] > 0.3,
                    "wheelspin against the wall never produced a large RAW slip speed across the whole window (SlipReporter's own erosion threshold): peak="
                            + worstSlipHolder[0] + " m/s");
            // The actual downstream erosion decision, not just SlipReporter's own gate: a quarter of the
            // CAR's own mass (1200 kg / 4), matching what SableCarBody really passes as wheelLoadKg.
            double erosionAmount = SlipMath.amount(worstSlipHolder[0], 300.0);
            helper.assertTrue(erosionAmount > 0.0005,
                    "the peak slip speed observed would still round to a negligible erosion amount, not real erosion-worthy wheelspin: slip="
                            + worstSlipHolder[0] + " m/s, amount=" + erosionAmount);
            helper.succeed();
        });
    }

    /** Review P2: keyboard/gamepad (which can only ever select {@link CarPhysics.Gear#DEFAULT_FORWARD} or
     * reverse -- never an individual manual gear) must not be silently pinned in D1 forever now that D1's
     * ratio multiplies drive force. The default policy is {@link CarPhysics.Gear#AUTOMATIC}, so a
     * non-shifter driver who never calls {@code selectGear} must still shift up out of D1 under sustained
     * throttle. */
    @GameTest(template = "settlement_test", timeoutTicks = 140)
    public static void nonShifterDefaultShiftsOutOfFirstGearInsteadOfStayingPinnedThere(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        // Deliberately never call selectGear: this is the exact state a keyboard/gamepad driver who has
        // never touched the reverse key is in.
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = 1.0f;
        helper.runAfterDelay(120L, () -> {
            helper.assertTrue(car.debugAutomaticGearIndex() > 0,
                    "a non-shifter driver's default gear stayed pinned at index 0 (first gear) under sustained throttle instead of shifting up: index="
                            + car.debugAutomaticGearIndex());
            helper.succeed();
        });
    }

    /** Review P3: {@code SableCarBody} passes the car's SIGNED throttle (accelerator minus brake) to
     * {@link DrivetrainMath#freeRevRpm} in NEUTRAL. Holding only the brake (negative throttle, no
     * accelerator) in NEUTRAL must leave the engine at idle, never free-rev it toward the limiter. */
    @GameTest(template = "settlement_test", timeoutTicks = 40)
    public static void neutralWithOnlyTheBrakeHeldNeverRevsPastIdle(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.NEUTRAL);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = -1.0f; // brake only, no accelerator
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(car.clientEngineRpmFraction() < 0.05,
                    "holding only the brake in neutral free-revved the engine instead of leaving it at idle: fraction="
                            + car.clientEngineRpmFraction());
            helper.succeed();
        });
    }

    /** Airborne/contact recovery (review, round 2: the round-1 version of this test could pass for a car
     * that never left its spawn point, since it only checked finite values). Driving off a real ledge
     * must produce an actual grounded -&gt; airborne -&gt; grounded transition. Uses per-wheel
     * {@code wheelTravel()} (exactly 0.0 on a genuine raycast MISS -- the real signal the production code
     * publishes, see SableCarBody's own per-wheel suspensionTravel comment) rather than vanilla
     * {@link CarEntity#onGround()}: CarEntity moves itself by directly calling {@code setPos} from the
     * Sable body's own pose every tick (never vanilla's {@code move()}/collision pipeline), so nothing in
     * this codebase ever actually sets vanilla's onGround flag for a car -- it would misreport "never
     * airborne" regardless of what the body is actually doing. The car must actually have traveled past
     * the ledge (a real peak z displacement, not sitting still), had every wheel report a raycast MISS at
     * some point during the fall (exercising the real freeze/resume wheelSpin path, MINECRAFT-73/118), and
     * be back in solid contact by the end, all without diverging (NaN/infinite position or speed).
     *
     * <p>Known pre-existing flake, unrelated to this test's own logic or to MINECRAFT-401's own code:
     * Sable's own broad-phase entity query ({@code dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter})
     * can log {@code "Aborting entity get for abnormally large AABB"} with bounds spanning this car's own
     * world coordinates all the way to an unrelated, concurrently-running test's structure near the
     * origin -- a malformed cross-structure query, not something either test's own code constructs. When
     * it fires for this body, the body's reported position freezes (this test's own assertions then fail
     * on "peak displacement") while {@code debugSpeed()} keeps climbing, because velocity integration and
     * pose sync apparently diverge once that query aborts. Confirmed present, with the same signature, in
     * a baseline run taken before any MINECRAFT-401 change existed, and reproduced across repeated runs of
     * this exact test at a roughly 3-in-4 rate -- high enough to be a real, known limitation worth this
     * note, not a one-off worth chasing further by reshaping this test's own geometry (already tried:
     * moving the spawn off the structure's dz=0 edge, lengthening the ramp, and switching the airborne
     * signal from the equally-broken vanilla {@code onGround()} to {@code wheelTravel()} -- none of those
     * change this specific failure mode, confirming it sits upstream in Sable itself, not in this test or
     * in MINECRAFT-401's own drivetrain code). Treat a failure here with "peak displacement=0.0" and a
     * matching "Aborting entity get for abnormally large AABB" in the log the same way as the project's
     * other known-flaky test (the Builder corner-post relocation GameTest): a pre-existing engine issue to
     * track separately, not a MINECRAFT-401 regression.
     *
     * <p>Review, round 3: since the malformed AABB observed spans this test's own coordinates together
     * with an unrelated, concurrently-running test's, this now runs in its own {@code batch} -- GameTest
     * batches run one at a time (sequentially), while every test WITHIN a batch runs concurrently with the
     * others in it (see the other tests in this file, which share {@code defaultBatch} and do run at the
     * same time). Isolating this one removes any other test's car/structure from existing at the same
     * moment this body is ticking, which is the condition the cross-structure query needs to misfire in
     * the first place -- expected to make this specific flake materially less likely, though it cannot by
     * itself prove the upstream Sable bug gone, since nothing here can fix Sable's own broad-phase query. */
    @GameTest(template = "settlement_test", timeoutTicks = 160, batch = "carDrivetrainLedgeIsolated")
    public static void carRecoversCleanlyAfterDrivingOffALedgeAndLanding(GameTestHelper helper) {
        // Starts well inside the structure (not at its dz=0 edge, where a test region boundary could
        // interfere with the body) like every other test in this file.
        for (int dz = 5; dz <= 9; dz++) {
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        // A genuine 2-block drop: the wheel raycast only reaches a fraction of a block below the wheel, so
        // this gap is deep enough to actually MISS for at least one tick while falling, rather than just
        // landing on extra suspension travel -- the real condition that freezes/resumes wheelSpin.
        for (int dz = 10; dz <= 14; dz++) {
            helper.setBlock(new BlockPos(5, 0, dz), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 1, dz), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        car.selectGear(CarPhysics.Gear.D1);
        double startZ = car.getZ();
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the car");
        rider.zza = 1.0f;
        boolean[] wasEverAirborne = {false};
        double[] maxZ = {startZ};
        long[] sampleTicks = {5, 10, 15, 20, 25, 30, 35, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140};
        for (long sampleTick : sampleTicks) {
            helper.runAfterDelay(sampleTick, () -> {
                boolean everyWheelAirborne = true;
                for (int wheel = 0; wheel < car.syncedWheelCount(); wheel++) {
                    if (car.wheelTravel(wheel, 1.0f) != 0.0f) {
                        everyWheelAirborne = false;
                        break;
                    }
                }
                if (everyWheelAirborne && car.syncedWheelCount() > 0) {
                    wasEverAirborne[0] = true;
                }
                maxZ[0] = Math.max(maxZ[0], car.getZ());
            });
        }
        helper.runAfterDelay(150L, () -> {
            double x = car.getX();
            double y = car.getY();
            double z = car.getZ();
            double speed = car.debugSpeed();
            helper.assertTrue(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z),
                    "car position diverged after driving off a ledge: " + x + "," + y + "," + z);
            helper.assertTrue(Double.isFinite(speed) && speed < 100.0,
                    "car speed diverged after driving off a ledge: " + speed);
            // The PEAK z reached, not the final z: a hard landing can legitimately roll or bounce the
            // chassis back a little, which must not fail a test that only means to confirm the car
            // actually crossed the ledge under its own drive -- that peak is what proves it did.
            helper.assertTrue(maxZ[0] - startZ > 2.0,
                    "car never actually traveled past the ledge -- test setup invalid, peak displacement=" + (maxZ[0] - startZ));
            helper.assertTrue(wasEverAirborne[0],
                    "every wheel never simultaneously reported a raycast MISS -- the ledge drop never actually left solid contact");
            boolean anyWheelGrounded = false;
            for (int wheel = 0; wheel < car.syncedWheelCount(); wheel++) {
                if (car.wheelTravel(wheel, 1.0f) != 0.0f) {
                    anyWheelGrounded = true;
                    break;
                }
            }
            helper.assertTrue(anyWheelGrounded,
                    "car had not landed and regained ground contact by the end of the window");
            helper.succeed();
        });
    }

    /** Review, round 2: production-path coverage for GearDrive's removed opposing-direction gate
     * (MINECRAFT-401's core ask), mirrored in both directions, through a real tick/substep path -- not
     * only {@code GearDriveTest}'s pure-function checks. Builds real forward speed in D1, then switches to
     * REVERSE under continued full throttle: the signed forward velocity (the car faces world +Z at yaw
     * 0, so {@code getDeltaMovement().z * 20} is exactly that) must NOT flip sign on the very next tick
     * (no instantaneous flip -- the chassis still has to coast down first) but MUST have gone negative by
     * well into the window (the opposing-direction force actually overcomes the roll eventually). The
     * mirror does the same starting in REVERSE and switching to D1. */
    @GameTest(template = "settlement_test", timeoutTicks = 340)
    public static void opposingDirectionThrottleDecelerateAndReverseInBothMirroredDirections(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 2, 15), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 15), Blocks.AIR);
        CarEntity forwardFirst = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        forwardFirst.setYRot(0.0f);
        forwardFirst.selectGear(CarPhysics.Gear.D1);
        CarEntity reverseFirst = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 15));
        reverseFirst.setYRot(0.0f);
        reverseFirst.selectGear(CarPhysics.Gear.REVERSE);
        Player riderForwardFirst = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(riderForwardFirst.startRiding(forwardFirst), "mock player failed to board the forward-first car");
        Player riderReverseFirst = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(riderReverseFirst.startRiding(reverseFirst), "mock player failed to board the reverse-first car");
        helper.runAfterDelay(20L, () -> {
            // Settled; now build real speed in each car's own initial direction for 40 ticks.
            riderForwardFirst.zza = 1.0f;
            riderReverseFirst.zza = 1.0f;
            helper.runAfterDelay(40L, () -> {
                double forwardFirstSpeedBeforeSwitch = forwardVelocity(forwardFirst);
                double reverseFirstSpeedBeforeSwitch = forwardVelocity(reverseFirst);
                helper.assertTrue(forwardFirstSpeedBeforeSwitch > 1.0,
                        "forward-first car never built real forward speed before the switch -- test setup invalid: "
                                + forwardFirstSpeedBeforeSwitch);
                helper.assertTrue(reverseFirstSpeedBeforeSwitch < -1.0,
                        "reverse-first car never built real backward speed before the switch -- test setup invalid: "
                                + reverseFirstSpeedBeforeSwitch);
                // Switch each to the OPPOSING gear, same full throttle held throughout.
                forwardFirst.selectGear(CarPhysics.Gear.REVERSE);
                reverseFirst.selectGear(CarPhysics.Gear.D1);
                helper.runAfterDelay(1L, () -> {
                    helper.assertTrue(forwardVelocity(forwardFirst) > 0.0,
                            "forward-first car's velocity flipped sign on the very first tick after switching to REVERSE -- an instantaneous flip, not a traction-limited one: "
                                    + forwardVelocity(forwardFirst));
                    helper.assertTrue(forwardVelocity(reverseFirst) < 0.0,
                            "reverse-first car's velocity flipped sign on the very first tick after switching to D1 -- an instantaneous flip, not a traction-limited one: "
                                    + forwardVelocity(reverseFirst));
                    // Review, round 3: a car's own forward wheelspin before the switch leaves the engine's
                    // smoothed RPM (DrivetrainMath.smoothedRpm) near the limiter regardless of which
                    // direction it is now driven in -- REVERSE's own force is throttled by the same
                    // limiterTorqueFactor just like a forward gear's is, so the opposing force starts weak
                    // and only ramps up as the smoothed RPM decays (bounded at MAX_RPM_CHANGE_PER_TICK per
                    // tick). That decay plus REVERSE_FORCE's comparatively modest magnitude (never scaled
                    // by a gear ratio multiplier the way forward gears are) genuinely needs more real time
                    // to reverse a multi-m/s roll than the round-2 version of this test allowed for.
                    helper.runAfterDelay(250L, () -> {
                        helper.assertTrue(forwardVelocity(forwardFirst) < 0.0,
                                "forward-first car never actually reversed direction under sustained opposing throttle: "
                                        + forwardVelocity(forwardFirst));
                        helper.assertTrue(forwardVelocity(reverseFirst) > 0.0,
                                "reverse-first car never actually went forward under sustained opposing throttle: "
                                        + forwardVelocity(reverseFirst));
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** The car's own signed forward velocity, m/s: at the yaw this file always spawns at (0 degrees), the
     * car faces world +Z ({@code SableCarBody.orientationOf}'s own identity-at-zero convention), so the
     * world +Z component of its real (not client-smoothed) velocity already IS the forward component. */
    private static double forwardVelocity(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return v.z * 20.0;
    }

    /** Review, round 2 &amp; 3: "tick/substep consistency" through the production path, not only
     * {@code WheelSubStepperTest}'s pure-function coverage of the same formula. Drives the identical
     * scenario at {@code wheelSubSteps = 1} and {@code wheelSubSteps = 4} and asserts the reached speed is
     * comparable -- sub-stepping is documented (see {@code SableCarBody.tick}'s own sub-stepping note) to
     * change the friction circle's lateral behavior measurably (~10-15%) but is not meant to change
     * straight-line longitudinal acceleration by anything like that much.
     *
     * <p>{@code CarConfig.WHEEL_SUB_STEPS} is a global, live-reloadable setting, not something this test
     * can scope to its own car -- review round 3 flagged both halves of what that demands: (1) this now
     * runs in its OWN {@code batch} (see the ledge test's own javadoc above for what GameTest batches
     * guarantee: one batch runs at a time, so nothing else is ticking a car while this one mutates the
     * global value), and (2) restoration is now GUARANTEED, not merely placed before the one assertion
     * that used to be able to throw past it -- every risky section (every {@code assertTrue} on a mock
     * player boarding, which this test cannot control) is wrapped so a thrown assertion still restores
     * {@code originalSubSteps} before propagating, rather than leaving the global mutated into whatever
     * batch happens to run next. */
    @GameTest(template = "settlement_test", timeoutTicks = 100, batch = "carDrivetrainSubStepIsolated")
    public static void wheelSubStepCountDoesNotMateriallyChangeReachedSpeedAfterAFixedWindow(GameTestHelper helper) {
        int originalSubSteps = CarConfig.WHEEL_SUB_STEPS.get();
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 2, 15), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 15), Blocks.AIR);
        try {
            CarConfig.WHEEL_SUB_STEPS.set(1);
            CarEntity carOneStep = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
            carOneStep.setYRot(0.0f);
            carOneStep.selectGear(CarPhysics.Gear.D1);
            Player riderOneStep = helper.makeMockPlayer(GameType.CREATIVE);
            helper.assertTrue(riderOneStep.startRiding(carOneStep), "mock player failed to board the 1-substep car");
            riderOneStep.zza = 1.0f;
            helper.runAfterDelay(30L, () -> runSecondLegAndRestoreOnAnyFailure(helper, originalSubSteps, carOneStep));
        } catch (RuntimeException e) {
            CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
            throw e;
        }
    }

    private static void runSecondLegAndRestoreOnAnyFailure(GameTestHelper helper, int originalSubSteps, CarEntity carOneStep) {
        try {
            double speedAtOneStep = carOneStep.debugSpeed();
            CarConfig.WHEEL_SUB_STEPS.set(4);
            CarEntity carFourStep = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 15));
            carFourStep.setYRot(0.0f);
            carFourStep.selectGear(CarPhysics.Gear.D1);
            Player riderFourStep = helper.makeMockPlayer(GameType.CREATIVE);
            helper.assertTrue(riderFourStep.startRiding(carFourStep), "mock player failed to board the 4-substep car");
            riderFourStep.zza = 1.0f;
            helper.runAfterDelay(30L, () -> {
                try {
                    double speedAtFourStep = carFourStep.debugSpeed();
                    CarConfig.WHEEL_SUB_STEPS.set(originalSubSteps);
                    helper.assertTrue(speedAtOneStep > 0.5 && speedAtFourStep > 0.5,
                            "test setup invalid -- a car never built real speed: 1-step=" + speedAtOneStep + " 4-step=" + speedAtFourStep);
                    double ratio = speedAtFourStep / speedAtOneStep;
                    helper.assertTrue(ratio > 0.6 && ratio < 1.6,
                            "wheelSubSteps materially changed the reached speed in straight-line acceleration: 1-step="
                                    + speedAtOneStep + " 4-step=" + speedAtFourStep + " ratio=" + ratio);
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
}
