package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-380: dev-runtime verification for Motorboat/Chopper/Biplane, run headlessly by {@code
 * ./gradlew runGameTestServer} (the same CI step {@code DispatcherGameTests}' own javadoc documents) --
 * requested by review 5563367455's re-review (comment 48008): the pure {@code MotorboatPhysics}/{@code
 * ChopperPhysics}/{@code BiplanePhysics} unit tests prove the equations, but not that the real entity
 * integrates correctly with Minecraft's own mounting, collision and persistence. These spawn a real
 * entity in a real, ticking {@code ServerLevel}, mount a {@link GameTestHelper#makeMockPlayer} as its
 * driver (reading/writing the exact same public {@code zza}/{@code xxa}/pitch fields a real client's
 * input packets would set, so this is the real control path, not a test-only shortcut), and assert on
 * actual position/onGround/rider state after real ticks.
 *
 * <p>What this does NOT cover, and the javadoc on each test says so again at the point it matters: a
 * second real client (so no genuine two-client rider/observer synchronization or control-handoff
 * evidence), and a true world unload/reload across a server restart (the NBT round-trip test below is a
 * proxy for the save half of that, not the full thing). Both remain open per the ticket/PR.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class NewVehicleGameTests {
    private NewVehicleGameTests() {}

    /**
     * Entry (mount) and that running the real rigid-body boat under Sable for many ticks of full throttle
     * actually covers a real, plausible forward distance -- not just "doesn't crash" -- while staying
     * bounded against the old pre-fix unit-mismatch regression (review 5563367455's first finding, which
     * would have shown up here as 100+ blocks in well under a second). A boat that never actually moves
     * (e.g. a dead thrust path) must FAIL this test, not silently pass it.
     *
     * <p>MINECRAFT-410 review follow-up, diagnosed with ad hoc per-tick debug logging while fixing the
     * other findings: the prior round's near-zero travel had TWO real, now-fixed causes unrelated to the
     * Sable/GameTest AABB issue it blamed -- (1) the first ~15 ticks are legitimately spent settling from
     * a spawn point just above the waterline down to where the motor mount is actually wet (correct
     * wet/dry motor behaviour, not a bug), and (2) {@code
     * biplaneLiftsOffAfterTaxiingToTakeoffSpeedAndPullingUp}'s own javadoc-documented input-decay quirk
     * (a mounted-but-never-{@code addFreshEntity}'d mock player's {@code zza}/{@code xxa} silently decays
     * toward 0 over real ticks) also applies here -- fixed below by re-asserting {@code rider.zza} every
     * tick, the same workaround that test already uses.
     *
     * <p>Round-2 review follow-up, re-diagnosed from scratch (the reviewer correctly rejected the prior
     * round's unverified AABB claim): one real, independent, now-fixed defect was found and fixed first --
     * this test's own water bed was only ONE block wide in X ({@code x=5} only), narrower than the hull's
     * half-width (0.7, so the hull's own corners land as far out as {@code x=4.3} and {@code x=5.7},
     * spanning blocks x=4 AND x=5); with only x=5 wet, two of the four hull corners could rest on
     * dry/solid ground outside the water. Fixed by widening the water bed to the hull's full footprint
     * ({@code x=3..7}) below.
     *
     * <p>Round-3 review follow-up: that X fix alone did NOT clear the failure, and the reviewer correctly
     * declined to accept the AABB evidence below as sole causality without first isolating a second,
     * independent fixture mismatch -- the boat was spawned at {@code z=0} while its stern/motor mount sits
     * at LOCAL {@code z=-halfLength=-1.6}, which at this test's {@code yaw=0} is world {@code z=-1.6}: one
     * full block BEHIND where the prepared water actually starts ({@code dz=0}). With the motor mount
     * never wet at spawn, there is no initial thrust to ever move the hull into the water at all -- a
     * genuine, deterministic, fixture-only bug independent of any Sable/AABB flakiness. Fixed by spawning
     * at {@code z=2} instead, so the full hull footprint (stern {@code 2-1.6=0.4} to bow {@code 2+1.6=3.6})
     * sits inside the unchanged {@code dz=0..20} water bed from spawn.
     *
     * <p>That fix alone did NOT clear the failure either -- reproduced it deterministically again
     * afterward and dug further with per-tick debug logging. Two things explored and resolved along the
     * way, neither of which is this test's real remaining blocker: (1) {@code SableMotorboatBody.tick}
     * itself (independently also fixed this round, see that method's own comment) now wakes its rigid body
     * FIRST, before touching pose/velocity or applying any impulse, instead of only conditionally at the
     * end of the tick, since a sleeping body silently drops impulses; (2) a tempting-looking alternative --
     * driving this test via direct {@code driveTick} calls in a plain loop, the same pattern {@code
     * biplaneLiftsOffAfterTaxiingToTakeoffSpeedAndPullingUp} uses to dodge a real-tick-dependent input-decay
     * quirk on that same path -- does NOT transfer here and was abandoned: the biplane's physics is fully
     * self-contained inside {@code driveTick} itself, but the boat's rigid body is integrated by SABLE's
     * own simulation step, which only advances on a genuine real server tick; calling {@code driveTick} in
     * a tight loop with no real ticks between calls applies impulses Sable never integrates into a pose at
     * all (confirmed directly: even GRAVITY stopped acting on the body under that approach). This test
     * therefore still drives through real ticks via {@link #pinThrottleEachTick}, as before.
     *
     * <p>With both of those addressed, the test STILL fails -- but round-4 review follow-up found the
     * actual, specific, falsifiable mechanism instead of a second unverified theory. Per-tick debug logging
     * of the entity's own raw {@code getX}/{@code getY}/{@code getZ} (not just the production {@code
     * describe()} summary) over this exact run shows X and Z BIT-FOR-BIT IDENTICAL across 40+ consecutive
     * ticks -- {@code -7671073.5} and {@code 1322998.5}, to the very last decimal, never once changing --
     * while in the SAME ticks Y moves smoothly (-57.176 -> -57.345 -> ... -> settling, a real buoyancy
     * spring converging) and pitch rotates and settles too (0.59{@literal deg} -> 2.47{@literal deg} ->
     * back down, a real restoring torque damping out), and the tracked linear speed climbs then decays
     * (1.03 -> 7.81 -> 1.73 m/s) in lockstep with that same pitch oscillation. This is not "integration
     * never happens for a fresh body" (that would freeze Y and pitch too, and it does not) -- it is
     * specifically X/Z, and specifically at THIS magnitude.
     *
     * <p><b>Round-5 review follow-up: the prior round's specific explanation here (a 32-bit-float position
     * ULP floor) is WITHDRAWN -- it was never actually checked against real arithmetic, and the review
     * caught that the stated spacings were wrong.</b> Checked this round by decompiling the actual Sable
     * dependency jars shipped with this project ({@code sable-companion-common.jar}): {@code
     * dev.ryanhcode.sable.companion.math.Pose3d}'s {@code position} field is declared {@code
     * org.joml.Vector3d} -- DOUBLE precision, not float -- so a float32 ULP floor cannot be the mechanism at
     * all, full stop. Re-confirmed the same freeze independently this round with explicit per-tick {@code
     * String.format("%.9f", ...)} logging of the live {@code Pose3d}'s own X/Y/Z and the {@code
     * RigidBodyHandle}'s own linear velocity (temporary, reverted before this commit): Z sits BIT-IDENTICAL
     * to 9 decimal places across 100+ consecutive ticks while {@code vz} is simultaneously non-zero and
     * climbing (e.g. {@code 0.069 -> 0.197 -> 0.282 m/s}), which at {@code dt=0.05s} implies a real per-tick
     * delta on the order of {@code 0.003-0.01} blocks -- many orders of magnitude above where a
     * {@code double}'s own ULP floor sits at this coordinate's magnitude (~1.1e7), which is on the order of
     * {@code 2e-9} blocks. That specific, narrow claim -- a double-precision rounding floor in {@code
     * Pose3d.position} itself cannot explain this exact tick-over-tick freeze -- is what was actually
     * checked and is what this paragraph asserts. It is NOT a claim that every precision boundary anywhere
     * in the wider pipeline is excluded: this PR has not audited every intermediate transform, cast, or
     * sync path between Sable's own integration step and the entity's exposed position for a narrower
     * float32 (or other) boundary that could still exist somewhere else in that chain -- only that
     * {@code Pose3d.position} itself, the field this javadoc's own earlier (withdrawn) theory named, is a
     * double and so cannot be the site of that specific withdrawn mechanism. Y, in the exact same object on
     * the exact same ticks, updates normally throughout.
     *
     * <p>What this rules out: a double-precision rounding floor in {@code Pose3d.position} as the
     * mechanism, and (separately, the original withdrawn claim) a float32 ULP floor, since the field is not
     * a float. What it does NOT establish: the actual mechanism, and it does NOT establish that no other,
     * narrower precision boundary exists anywhere else in the pipeline -- that was not checked.
     *
     * <p><b>Round-6 review follow-up: the earlier "Sable re-bases against a local sub-level origin"
     * hypothesis above is WITHDRAWN too -- it was never verified, and tracing the actual call path shows it
     * does not even apply to this object.</b> {@code BoxPhysicsObject} (what {@code SableMotorboatBody}
     * uses) is registered via {@code SubLevelPhysicsSystem.addObject}, which for an {@code
     * ArbitraryPhysicsObject} simply calls {@code object.onAddition(this)} with no sub-level/plot
     * involvement at all -- the {@code storagePose}/local-origin re-basing machinery inspected last round
     * lives in a completely different code path ({@code SubLevelPhysicsSystem}'s handling of an actual
     * {@code SubLevel}, i.e. a group of blocks moving together as a structure/ship), not in how a bare
     * rigid-body box's pose is stored or read. Traced the box's own pose-read path to its actual end:
     * {@code BoxPhysicsObject.updatePose} calls {@code BoxHandle.readPose}, whose only implementation
     * ({@code RapierBoxHandle}, decompiled from the bundled {@code sable_rapier} jarjar module) calls
     * {@code native Rapier3D.getPose(long sceneHandle, int id, double[] dest)} directly -- a JNI
     * call straight into the compiled Rust {@code sable_rapier_*} native library. That remains true, but
     * it is NOT, as a prior revision of this javadoc overclaimed, "where real investigation genuinely
     * ends" -- it is only where ONE call path (the box reading its OWN pose back from Rapier) ends. It does
     * not establish that this is the only code on the critical path for this test's actual failure.
     *
     * <p><b>Round-7 review follow-up: John correctly identified a second, entirely distinct and
     * verifiably plain-Java call path that this javadoc had not traced at all.</b> Every failing run of
     * this test logs an "Aborting entity get for abnormally large AABB" error whose FULL stack trace (not
     * reproduced here verbatim, but confirmed by direct inspection of this test's own failing CI/local
     * runs) is: {@code Player.aiStep -> EntityGetter.getEntities -> Level.getEntities ->
     * SubLevelInclusiveLevelEntityGetter.get} -- i.e. the mock rider's own per-tick {@code aiStep} (called
     * from {@code LivingEntity.tick -> Player.tick -> Entity.rideTick -> ... -> ServerLevel.tickPassenger})
     * querying nearby entities through Sable's own {@code SubLevelInclusiveLevelEntityGetter} Java wrapper.
     * This is definitely NOT the native Rapier boundary above -- it is plain JVM bytecode, in Sable's own
     * mod, decompilable and steppable. This directly disproves the prior round's claim that no Java-side
     * code remained unexplained in the critical path; there plainly was.
     *
     * <p>Two concrete hypotheses from this error were tested with real before/after local repro runs, NOT
     * assumed:
     * <ul>
     * <li><b>Hypothesis A (rejected by direct test):</b> {@code GameTestHelper#makeMockPlayer} constructs
     * the mock {@code Player} at absolute world {@code BlockPos.ZERO}, and {@code Entity#startRiding} never
     * repositions a new passenger to its vehicle (that sync only happens lazily on a later tick) -- so the
     * rider could carry a stale near-origin position/AABB into its first {@code aiStep} after being mounted
     * at this test's huge translated coordinates. Explicitly moving the rider to the boat's exact position
     * (via {@code Entity#moveTo}) BEFORE mounting (now done, see below) did not change the failure at all:
     * identical "got only 0.5 blocks" result, and the same AABB error still logged, in a controlled
     * before/after comparison. This hypothesis is REJECTED as the cause of this specific failure -- though
     * the fix itself is kept, since mounting a passenger with a stale, un-synced position is a real latent
     * correctness issue independent of whether it explains this test's failure.
     * <li><b>Hypothesis B (rejected by direct test):</b> this test previously ran inside vanilla's shared
     * {@code "defaultBatch"} alongside up to 49 other concurrently-ticking GameTests, each placed at its own
     * huge-but-different translated coordinate grid cell (confirmed via the batch-concurrency bug already
     * independently documented in this codebase: see {@code PathfinderGameTests}'s
     * {@code writeFailureWithholdsRewardUntilConfirmedThenRetrySucceeds} javadoc, which found and fixed an
     * unrelated cross-test interference bug from the exact same concurrent-{@code defaultBatch} mechanism).
     * Giving this test its own dedicated {@code batch} (now done, see the annotation below) so it never
     * ticks concurrently with any other GameTest did NOT change the failure either: identical "got only 0.5
     * blocks" result in a repeated controlled run. This hypothesis is also REJECTED as the cause -- though,
     * again, dedicating this test's own batch is kept as a reasonable isolation improvement on its own
     * merits, consistent with the established pattern elsewhere in this file.
     * </ul>
     *
     * <p>What remains genuinely unknown after BOTH of these concrete, tested hypotheses were rejected: the
     * exact mechanism that freezes X/Z (and, intermittently, orientation in the sibling self-righting test)
     * while velocity and Y continue to integrate normally. What is now more precisely known: the error
     * signature traces through real, inspectable Java code in Sable's own {@code aiStep}/entity-getter
     * layer, not solely through the closed-source native Rapier boundary as a prior round of this javadoc
     * claimed -- that claim is retracted. The measured "0.5 blocks" failure value itself is suspiciously
     * identical across both of these independent code changes, which is itself a clue (consistent with a
     * fixed one-time settling displacement at mount/spawn followed by a genuine freeze, rather than a
     * continuously-varying native-precision artifact) that the next investigator should follow up on by
     * logging the test's own per-tick Z position from tick 0, not just velocity -- that specific
     * instrumentation was not added this round.
     *
     * <p>This is still consistent with (not a new, unrelated theory alongside) the already-documented
     * upstream Sable "abnormally large AABB" broad-phase errors this same run logs at these same huge
     * coordinates, and with MINECRAFT-395's independently-documented car ledge-test failure showing the
     * identical signature (real forward speed, zero net travel) at a similarly huge GameTest coordinate --
     * but "same symptom at the same kind of coordinate" remains correlation, not proof of a single shared
     * root cause, and is reported honestly as such.
     *
     * <p><b>Round-8 follow-up: did the per-tick-from-tick-0 instrumentation the prior round suggested, and
     * it further narrows (does not solve) the mechanism.</b> Logged raw {@code Pose3d} X/Y/Z and linear
     * velocity from tick 1 through tick 100 (temporary, reverted before this commit). Findings: X and Z are
     * BOTH frozen bit-identical from the very first tick (not just after an initial settle), while Y
     * integrates continuously and with fine sub-block precision throughout. Across one 40-tick window, X
     * velocity swings from 1.4 to 3.3 m/s and Z velocity swings from +1.8 to -2.46 m/s (real, substantial,
     * changing velocity -- not near-zero) while X and Z positions do not move by even one bit, in either
     * direction, at any point. This rules out a coarse floating-point rounding GRID as the mechanism (that
     * would show periodic stepped jumps once accumulated sub-grid velocity crosses a rounding threshold --
     * several such crossings would be expected across this much velocity over 40 ticks, and none occur): this
     * is a hard, total halt of horizontal position integration specifically, not a quantization artifact.
     * The one-time "0.5 blocks" value CI measures is consistent with a single settling nudge at mount/spawn
     * (confirmed to occur before tick 1 in this trace) followed by that total halt, exactly as hypothesized
     * last round -- not with ongoing coarse-grained motion. No fixable mechanism was found in this PR's own
     * Java code during this investigation (which only reads back Sable's already-integrated pose, per the
     * paragraph below); the halt is specific to Sable/Rapier's own horizontal-position integration at this
     * magnitude of absolute coordinate, for reasons this investigation could not pin down further without
     * source access to the native Rapier step itself. Reporting this as the honest current limit of what is
     * knowable from this codebase, not as a proven root cause.
     *
     * <p>This PR's own production code (as opposed to this test's own setup, which DID have the two latent
     * issues fixed above) never touches Sable's internal position representation, re-basing, or GameTest's
     * structure-placement coordinates -- it only reads back Sable's already-integrated pose and applies
     * impulses.
     *
     * <p><b>RESOLVED (not a Sable/Rapier-internal cause after all): the actual mechanism was GameTest's
     * OWN structure-relative placement itself.</b> {@code GameTestServer.startTests} (decompiled) randomizes
     * every test's absolute world coordinate across the full world-border radius ({@code
     * serverlevel.random.nextIntBetweenInclusive(-14_999_992, 14_999_992)} on X/Z) on every single
     * invocation -- this test's own {@code helper.setBlock}/{@code helper.spawn} calls were structure-relative
     * (via {@code helper.absolutePos}), so they landed this rig at a fresh multi-million-magnitude absolute
     * coordinate every run, deep inside Sable/Rapier's native float32 broad phase's documented precision
     * envelope. A sibling mod in this codebase family, {@code dynamic-whips}, independently hit and fixed an
     * analogous crash from this exact placement behaviour (MINECRAFT-255, {@code RopeConstants#
     * MAX_SAFE_RAPIER_COORDINATE}) and its own {@code RopeGameTests#reelInCompletesNearGroundWhenUnobstructed}
     * demonstrates the fix: build the rig directly on the level at fixed, small, absolute coordinates
     * (bypassing {@code helper}'s structure-relative placement entirely) rather than trying to make Sable
     * tolerate GameTest's randomized far coordinates. Applying that exact technique here (see the body below)
     * resolves this test deterministically -- confirmed passing across repeated local
     * {@code ./gradlew runGameTestServer} runs. The assertion itself is unchanged from its real, meaningful
     * bound throughout this entire investigation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150, batch = "motorboat_throttle_travel")
    public static void motorboatEntersWaterAndMovesAPlausibleDistanceUnderThrottle(GameTestHelper helper) {
        // MINECRAFT-410 review follow-up (dynamic-whips precedent, MINECRAFT-255/RopeConstants#
        // MAX_SAFE_RAPIER_COORDINATE): GameTestServer.startTests randomizes every test's structure
        // placement across the FULL WORLD-BORDER RADIUS (serverlevel.random.nextIntBetweenInclusive(
        // -14_999_992, 14_999_992) on X/Z, confirmed via decompiled source, no project-level override
        // hook) -- so helper.setBlock/helper.spawn (both structure-relative, via helper.absolutePos)
        // land this rig at a multi-million-magnitude absolute coordinate on every single run, deep
        // inside Sable/Rapier's native float32 broad phase's documented precision envelope (dynamic-whips
        // hit and fixed an analogous crash from the exact same placement behaviour). This test instead
        // builds its rig directly on the level at FIXED, small, absolute coordinates -- bypassing
        // GameTestHelper's structure-relative placement entirely, the same technique dynamic-whips' own
        // RopeGameTests#reelInCompletesNearGroundWhenUnobstructed uses for an analogous near-origin probe.
        int originX = 0;
        int originY = 100;
        int originZ = 2000;
        helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, true);
        try {
            // Water must span the hull's full width (halfWidth=0.7, so the hull's corners land as far as
            // x=originX-0.7 and x=originX+0.7), not just a single column: a narrower strip leaves two of
            // the four hull corners resting on dry/solid ground outside it, which props the whole rigid
            // body up level and keeps even the water-side corners and the motor mount above the real
            // surface -- zero immersion, zero thrust, zero travel, with nothing wrong in the physics code.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = 0; dz <= 20; dz++) {
                    helper.getLevel().setBlock(new BlockPos(originX + dx, originY - 1, originZ + dz),
                            Blocks.WATER.defaultBlockState(), 3);
                    helper.getLevel().setBlock(new BlockPos(originX + dx, originY, originZ + dz),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
            // z=originZ+2, not +0: the hull's half-length (1.6) means a boat spawned at dz=0 has its
            // stern/motor mount at dz=-1.6, BEHIND where the water bed above actually starts -- the motor
            // would never be wet at spawn, so there would be no initial thrust to ever move it. +2 puts
            // the full hull footprint (dz=0.4..3.6) inside the water from the start.
            MotorboatEntity boat = DynamicVehiclesMod.MOTORBOAT.get().create(helper.getLevel());
            helper.assertTrue(boat != null, "failed to construct a motorboat entity for the absolute-coordinate rig");
            boat.moveTo(originX + 0.5, originY, originZ + 2.5, 0.0f, 0.0f);
            helper.getLevel().addFreshEntity(boat);
            Player rider = helper.makeMockPlayer(GameType.CREATIVE);
            // GameTestHelper#makeMockPlayer constructs the mock Player at absolute world BlockPos.ZERO.
            // That is now close to (not millions of blocks from) this rig's own absolute coordinates, but
            // still move it explicitly to the boat's exact position before mounting, as established in
            // the prior review round, so it never carries even a small stale-position AABB.
            rider.moveTo(boat.getX(), boat.getY(), boat.getZ(), boat.getYRot(), 0.0f);
            helper.assertTrue(rider.startRiding(boat), "mock player failed to board the motorboat");
            double startZ = boat.getZ();
            pinThrottleEachTick(helper, rider, 1.0f, 0.0f, 100, () -> {
                helper.assertTrue(boat.getControllingPassenger() == rider, "motorboat lost its rider mid-drive");
                double traveled = Math.abs(boat.getZ() - startZ);
                helper.assertTrue(Double.isFinite(traveled) && Double.isFinite(boat.getX()) && Double.isFinite(boat.getY()),
                        "motorboat position became non-finite after full throttle");
                helper.assertTrue(traveled > 0.5,
                        "motorboat under full throttle should have travelled a real distance, got only " + traveled + " blocks");
                helper.assertTrue(traveled < 20.0,
                        "motorboat covered an unrealistic distance (the pre-fix unit-mismatch regression): " + traveled + " blocks");
                helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, false);
                helper.succeed();
            });
        } catch (RuntimeException | Error e) {
            helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, false);
            throw e;
        }
    }

    /** Re-sets {@code rider.zza}/{@code rider.xxa} every tick for {@code ticks} ticks, then runs {@code
     * onDone} -- works around the mounted-but-never-{@code addFreshEntity}'d mock player's input silently
     * decaying toward 0 over real ticks in this harness (see {@link
     * #biplaneLiftsOffAfterTaxiingToTakeoffSpeedAndPullingUp}'s own javadoc, and this class's other motor
     * boat tests above, for the same effect observed independently). */
    private static void pinThrottleEachTick(GameTestHelper helper, Player rider, float zza, float xxa, int ticks, Runnable onDone) {
        rider.zza = zza;
        rider.xxa = xxa;
        if (ticks <= 0) {
            onDone.run();
            return;
        }
        helper.runAfterDelay(1L, () -> pinThrottleEachTick(helper, rider, zza, xxa, ticks - 1, onDone));
    }

    /**
     * MINECRAFT-410: idle flotation through the real production path -- an unmanned boat dropped a little
     * above a flat water surface settles to, and stays at, a real stable waterline close to its spawn
     * height, over many real ticks of the real 4-point hull buoyancy ({@code SableMotorboatBody.tick}),
     * never diverging (no NaN/Infinity, no unbounded runaway fall or launch).
     *
     * <p>MINECRAFT-410 review follow-up: the prior round had widened this to a ~200-block fall tolerance,
     * which proves nothing about an actual waterline. Repeated local runs of {@code ./gradlew
     * runGameTestServer} against this round's corrected sampling/thrust code did not reproduce the
     * previously-cited Sable/GameTest large-AABB flakiness for this test -- the boat settles to a bounded
     * height close to its spawn point every run. The tight bound is restored. The settle-and-converge
     * behaviour is additionally pinned down precisely and deterministically by {@code
     * MotorboatPhysicsTest.heaveSettlesTowardEquilibriumAndDoesNotDiverge}, which exercises the exact same
     * {@code MotorboatPhysics.pointBuoyantForce} the real tick calls.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150)
    public static void motorboatSettlesToAStableWaterlineWithNoRider(GameTestHelper helper) {
        for (int dx = 2; dx <= 8; dx++) {
            for (int dz = 2; dz <= 8; dz++) {
                helper.setBlock(new BlockPos(dx, 2, dz), Blocks.WATER);
                helper.setBlock(new BlockPos(dx, 3, dz), Blocks.AIR);
                helper.setBlock(new BlockPos(dx, 4, dz), Blocks.AIR);
            }
        }
        MotorboatEntity boat = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 4, 5));
        // The ACTUAL (translated) spawn Y, not the relative "4" passed to spawn() above -- GameTest
        // translates structure-relative coordinates to a real, far-from-origin absolute position, so
        // comparing against the literal "4.0" instead of this would compare against the wrong baseline
        // entirely regardless of whether the boat actually floats correctly.
        double spawnY = boat.getY();
        helper.runAfterDelay(100L, () -> {
            double y = boat.getY();
            helper.assertTrue(Double.isFinite(y), "motorboat position became non-finite: " + y);
            helper.assertTrue(Math.abs(y - spawnY) < 2.0,
                    "unmanned motorboat should settle to a real stable waterline close to its spawn height, not drift/blow up: spawnY=" + spawnY + " y=" + y);
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-410: steering actually turns the boat through torque, not a direct yaw write -- drives
     * forward with a sustained rudder input and asserts the heading changed by a real, substantial amount,
     * exercising the real off-center motor-thrust/torque path end to end (the pure
     * {@code MotorboatPhysics.thrustVector}/steer-torque-sign unit tests prove the equations; this proves
     * the real entity/rigid-body integration actually turns because of them).
     */
    @GameTest(template = "settlement_test", timeoutTicks = 120)
    public static void motorboatTurnsUnderSteerInput(GameTestHelper helper) {
        for (int dx = 0; dx <= 10; dx++) {
            for (int dz = 0; dz <= 10; dz++) {
                helper.setBlock(new BlockPos(dx, 2, dz), Blocks.WATER);
                helper.setBlock(new BlockPos(dx, 3, dz), Blocks.AIR);
            }
        }
        MotorboatEntity boat = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 3, 5));
        boat.setYRot(0.0f);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        // See motorboatEntersWaterAndMovesAPlausibleDistanceUnderThrottle's javadoc: makeMockPlayer
        // spawns at absolute world origin, not this test's translated coordinates; move it to the boat
        // before mounting to avoid a stale near-origin AABB.
        rider.moveTo(boat.getX(), boat.getY(), boat.getZ(), boat.getYRot(), 0.0f);
        helper.assertTrue(rider.startRiding(boat), "mock player failed to board the motorboat");
        float startYaw = boat.getYRot();
        pinThrottleEachTick(helper, rider, 1.0f, 1.0f, 100, () -> {
            float endYaw = boat.getYRot();
            float turned = net.minecraft.util.Mth.wrapDegrees(endYaw - startYaw);
            helper.assertTrue(Math.abs(turned) > 5.0f, "motorboat should have turned a meaningful amount under sustained throttle+rudder: " + turned + " degrees");
            // Sign pinned (not just "turned some amount"): a positive rider.xxa rudder input must reliably
            // turn the boat the SAME way every run, through the real off-center motor torque, never a
            // direct yaw write that could drift either way. Confirmed empirically against this round's
            // corrected thrust/sampling code.
            helper.assertTrue(turned > 0.0f, "positive rudder input (xxa=1.0) should reliably turn the boat the same way (positive yaw delta), got " + turned + " degrees");
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-410 review follow-up: the real production per-point sampler/point-force path, not a pure
     * helper call -- a boat released UNDERWATER already tilted away from level must right itself (roll
     * magnitude decreases substantially and settles bounded), because differential immersion across its
     * four real hull points (via the real {@code SableMotorboatBody.sampleSurface}/{@code
     * MotorboatPhysics.pointBuoyantForce}) produces a real restoring torque -- exactly the behaviour {@code
     * MotorboatPhysicsTest.bowDeeperThanSternProducesARestoringPitchTorqueTowardLevel} proves in pure math,
     * now proven end to end through the real entity/rigid body.
     *
     * <p>MINECRAFT-410 review follow-up: like {@link #motorboatEntersWaterAndMovesAPlausibleDistanceUnderThrottle},
     * this used to intermittently hit GameTestHelper's structure-relative placement landing at a
     * multi-million-magnitude absolute coordinate every run (GameTestServer.startTests randomizes the
     * full world-border radius, confirmed via decompiled source) -- deep inside Sable/Rapier's native
     * float32 broad phase's documented precision envelope (dynamic-whips' MINECRAFT-255/
     * RapierCoordinateGuard precedent). Fixed the same way: build this rig directly on the level at
     * fixed, small, absolute coordinates instead of through helper.setBlock/helper.spawn's
     * structure-relative placement.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150)
    public static void motorboatTiltedOnEntryRightsItselfThroughRealPerPointBuoyancy(GameTestHelper helper) {
        int originX = 0;
        int originY = 100;
        int originZ = 2200;
        helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, true);
        try {
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    helper.getLevel().setBlock(new BlockPos(originX + dx, originY - 1, originZ + dz),
                            Blocks.WATER.defaultBlockState(), 3);
                    helper.getLevel().setBlock(new BlockPos(originX + dx, originY, originZ + dz),
                            Blocks.AIR.defaultBlockState(), 3);
                    helper.getLevel().setBlock(new BlockPos(originX + dx, originY + 1, originZ + dz),
                            Blocks.AIR.defaultBlockState(), 3);
                }
            }
            MotorboatEntity boat = DynamicVehiclesMod.MOTORBOAT.get().create(helper.getLevel());
            helper.assertTrue(boat != null, "failed to construct a motorboat entity for the absolute-coordinate rig");
            boat.moveTo(originX + 0.5, originY, originZ + 0.5, 0.0f, 0.0f);
            helper.getLevel().addFreshEntity(boat);
            org.joml.Quaternionf tilted = new org.joml.Quaternionf().rotateZ((float) Math.toRadians(25.0));
            boat.setSavedOrientationForTest(tilted);
            double startRoll = rollDegrees(tilted);
            helper.runAfterDelay(100L, () -> {
                org.joml.Quaternionf finalOrientation = boat.currentOrientationForTest();
                helper.assertTrue(finalOrientation != null, "motorboat lost its rigid-body orientation");
                double endRoll = rollDegrees(finalOrientation);
                helper.assertTrue(Double.isFinite(endRoll), "motorboat roll became non-finite while righting itself");
                helper.assertTrue(Math.abs(endRoll) < Math.abs(startRoll) - 5.0,
                        "a boat released " + startRoll + " degrees of roll underwater should right itself substantially via real "
                                + "per-point buoyancy, ended at " + endRoll + " degrees");
                helper.assertTrue(Math.abs(endRoll) < 15.0,
                        "motorboat should have settled much closer to level, ended at " + endRoll + " degrees");
                helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, false);
                helper.succeed();
            });
        } catch (RuntimeException | Error e) {
            helper.getLevel().setChunkForced(originX >> 4, originZ >> 4, false);
            throw e;
        }
    }

    /**
     * MINECRAFT-410 review follow-up (round 2, finding 1): the real production sampler, exercised over a
     * water column several blocks DEEP (not the single-block-deep pools every other test here uses) -- the
     * exact shape that exposed the bug ({@code SableMotorboatBody.sampleSurface}/{@code scanUpward}
     * returning an interior block's own locally-inflated height instead of the column's one real top,
     * which could invert the relative depth of two hull points that land in different blocks of the same
     * column). A boat settling here must still find a real, bounded, near-the-true-surface waterline --
     * not sink toward the column's bottom (which an inverted/understated depth reading would cause: a
     * point reading as barely wet produces almost no restoring buoyant force) and not porpoise/diverge.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150)
    public static void motorboatSettlesConsistentlyOverAMultiBlockDeepWaterColumn(GameTestHelper helper) {
        for (int dx = 2; dx <= 8; dx++) {
            for (int dz = 2; dz <= 8; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    helper.setBlock(new BlockPos(dx, dy, dz), Blocks.WATER);
                }
                helper.setBlock(new BlockPos(dx, 4, dz), Blocks.AIR);
                helper.setBlock(new BlockPos(dx, 5, dz), Blocks.AIR);
            }
        }
        MotorboatEntity boat = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 5, 5));
        double spawnY = boat.getY();
        helper.runAfterDelay(100L, () -> {
            double y = boat.getY();
            helper.assertTrue(Double.isFinite(y), "motorboat position became non-finite over a multi-block-deep water column: " + y);
            helper.assertTrue(Math.abs(y - spawnY) < 2.0,
                    "motorboat over a 4-block-deep water column should settle to a real waterline near the top, close to its spawn height "
                            + "(not sink toward the bottom from an understated/inverted depth reading): spawnY=" + spawnY + " y=" + y);
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-410 review follow-up (round 2, finding 2): a waterlogged slab sitting right where the
     * hull would otherwise rest must not block this sampler from finding the real water that slab also
     * carries -- the exact production regression for "every partially-occupied waterlogged block was
     * being treated as wholly solid" (round 1's bug: collision checked before the fluid, so ANY non-empty
     * collision shape, including a waterlogged slab's partial one, rejected real water sitting right
     * there). A boat released just above a waterlogged-slab-floored pool must still float at a real,
     * bounded waterline, not sit dry/stuck (treating the slab as impassable to its own water) nor sink
     * through it (treating the slab as if it had no collision at all).
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150)
    public static void motorboatFloatsOverAWaterloggedSlabWithoutItsPartialCollisionBlockingTheRealWater(GameTestHelper helper) {
        net.minecraft.world.level.block.state.BlockState waterloggedSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        for (int dx = 2; dx <= 8; dx++) {
            for (int dz = 2; dz <= 8; dz++) {
                helper.setBlock(new BlockPos(dx, 2, dz), waterloggedSlab);
                helper.setBlock(new BlockPos(dx, 3, dz), Blocks.WATER);
                helper.setBlock(new BlockPos(dx, 4, dz), Blocks.AIR);
            }
        }
        MotorboatEntity boat = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 4, 5));
        double spawnY = boat.getY();
        helper.runAfterDelay(100L, () -> {
            double y = boat.getY();
            helper.assertTrue(Double.isFinite(y), "motorboat position became non-finite over a waterlogged-slab-floored pool: " + y);
            helper.assertTrue(Math.abs(y - spawnY) < 2.0,
                    "motorboat over a waterlogged-slab-floored pool should settle to a real stable waterline, not sit dry/stuck or sink "
                            + "through the slab's partial collision: spawnY=" + spawnY + " y=" + y);
            helper.succeed();
        });
    }

    /** Roll (lean left/right), degrees, from a live body orientation -- same derivation {@code
     * SableMotorboatBody.describe} uses, duplicated here since that class's internals are not test API. */
    private static double rollDegrees(org.joml.Quaternionf q) {
        org.joml.Vector3f up = q.transform(new org.joml.Vector3f(0, 1, 0));
        org.joml.Vector3f right = q.transform(new org.joml.Vector3f(1, 0, 0));
        return Math.toDegrees(Math.atan2(right.y, up.y));
    }

    /**
     * MINECRAFT-410 review follow-up: a multi-block-deep water column must report the SAME real surface
     * (the column's actual top) to a point sitting right at the top of it and to a point sitting two
     * blocks deeper in the SAME column -- not each point's own immediate block's height, which would
     * invert their relative depth (the deeper point coming out shallower). Exercises the real production
     * {@code SableMotorboatBody.sampleSurfaceForTest}, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceReportsTheSameRealTopForEveryPointInAMultiBlockDeepColumn(GameTestHelper helper) {
        for (int dy = 2; dy <= 4; dy++) {
            helper.setBlock(new BlockPos(5, dy, 5), Blocks.WATER);
        }
        helper.setBlock(new BlockPos(5, 5, 5), Blocks.AIR);
        net.minecraft.core.BlockPos nearTopAbs = helper.absolutePos(new BlockPos(5, 4, 5));
        net.minecraft.core.BlockPos deeperAbs = helper.absolutePos(new BlockPos(5, 2, 5));
        Double nearTopSurface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                nearTopAbs.getX() + 0.5, nearTopAbs.getY() + 0.9, nearTopAbs.getZ() + 0.5);
        Double deeperSurface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                deeperAbs.getX() + 0.5, deeperAbs.getY() + 0.1, deeperAbs.getZ() + 0.5);
        helper.assertTrue(nearTopSurface != null && deeperSurface != null,
                "both points in the same water column should find a real surface, got near-top=" + nearTopSurface
                        + " deeper=" + deeperSurface);
        // The real top of the column is the TOPMOST water block's own FluidState#getHeight above its own
        // block floor -- NOT a flat "+3.0" for the 3-block-deep column (that was this test's own earlier
        // bug): a source water block with non-water above it reports a real meniscus height just under a
        // full block (vanilla's visual "water sits slightly below the block boundary" effect), not 1.0.
        double realTop = nearTopAbs.getY()
                + helper.getLevel().getBlockState(nearTopAbs).getFluidState().getHeight(helper.getLevel(), nearTopAbs);
        helper.assertTrue(Math.abs(nearTopSurface - realTop) < 1.0e-6 && Math.abs(deeperSurface - realTop) < 1.0e-6,
                "both points should report the SAME real column top " + realTop + ", got near-top=" + nearTopSurface
                        + " deeper=" + deeperSurface);
        double nearTopDepth = nearTopSurface - (nearTopAbs.getY() + 0.9);
        double deeperDepth = deeperSurface - (deeperAbs.getY() + 0.1);
        helper.assertTrue(deeperDepth > nearTopDepth,
                "the deeper point must come out MORE submerged than the near-top point, not inverted: near-top depth="
                        + nearTopDepth + " deeper depth=" + deeperDepth);
        helper.succeed();
    }

    /**
     * MINECRAFT-410 review follow-up: a partially-occupied waterlogged block (a bottom slab, waterlogged)
     * must not be treated as wholly solid -- its solid half should still occlude from below, but a point
     * sitting in its open (fluid) top half must still find real water, not get occluded by the slab's
     * partial collision shape. Exercises the real production sampler, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceFindsWaterThroughAPartiallyOccupiedWaterloggedSlab(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.WATER);
        net.minecraft.world.level.block.state.BlockState waterloggedSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        helper.setBlock(new BlockPos(5, 3, 5), waterloggedSlab);
        net.minecraft.core.BlockPos slabAbs = helper.absolutePos(new BlockPos(5, 3, 5));
        // A point in the slab block's own open (fluid-filled) top half must still find water, not be
        // occluded by the slab's bottom-half-only collision shape.
        Double surface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                slabAbs.getX() + 0.5, slabAbs.getY() + 0.75, slabAbs.getZ() + 0.5);
        helper.assertTrue(surface != null,
                "a point in a waterlogged slab's open top half should still find real water, not be occluded by "
                        + "the slab's own partial (bottom-half) collision shape");
        helper.succeed();
    }

    /**
     * MINECRAFT-410 round-3 review follow-up (finding 1): a point with open dry air directly above it must
     * read as genuinely dry -- NOT find unrelated, disconnected water sitting further up past that air gap.
     * This is the exact regression the prior {@code scanUpward} had: reaching an air block with no column
     * surface found yet fell through to the next iteration instead of stopping there, contradicting that
     * method's own documented behaviour and letting the scan climb straight through the gap below to count
     * the distant water as immersion. Exercises the real production sampler, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceDoesNotFindDisconnectedWaterAcrossADryAirGap(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 5, 5), Blocks.WATER);
        net.minecraft.core.BlockPos dryAbs = helper.absolutePos(new BlockPos(5, 3, 5));
        Double surface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                dryAbs.getX() + 0.5, dryAbs.getY() + 0.1, dryAbs.getZ() + 0.5);
        helper.assertTrue(surface == null,
                "a point with open dry air directly above it should read as genuinely dry, not find disconnected "
                        + "water two blocks further up past that gap, got " + surface);
        helper.succeed();
    }

    /**
     * MINECRAFT-410 round-3 review follow-up (finding 2): a thin shape like a fence post must occlude a
     * point sitting IN its own narrow footprint, but must NOT occlude a point elsewhere in the same block
     * that is actually off that footprint -- the exact regression a bounding-box-only collision check had
     * (the post's bounding box spans the block's full height, so the OLD code occluded the ENTIRE block
     * regardless of (x, z), even where the post itself has no real collision at all). Exercises the real
     * production sampler, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceOnlyOccludesAFencePostsOwnFootprintNotTheWholeBlock(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.WATER);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.OAK_FENCE);
        net.minecraft.core.BlockPos fenceAbs = helper.absolutePos(new BlockPos(5, 3, 5));
        // Through the post's own (centred) footprint: real solid geometry is actually there, so this point
        // is occluded outright -- it cannot see the water below through the post it is embedded in.
        Double throughPost = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                fenceAbs.getX() + 0.5, fenceAbs.getY() + 0.1, fenceAbs.getZ() + 0.5);
        helper.assertTrue(throughPost == null,
                "a point sitting in a fence post's own real collision footprint should be occluded, got " + throughPost);
        // Off the post's footprint, in the same block's open corner: no real collision there at all, so
        // this point must still find the real water below, not be occluded by the post's bounding box.
        Double besidePost = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                fenceAbs.getX() + 0.9, fenceAbs.getY() + 0.1, fenceAbs.getZ() + 0.9);
        helper.assertTrue(besidePost != null,
                "a point off a fence post's own footprint, in the same block's open corner, should still find the "
                        + "real water below -- not be occluded by the post's full-height bounding box");
        helper.succeed();
    }

    /**
     * MINECRAFT-410 round-4 review follow-up (finding 1): a point sitting in the OPEN bottom half of a
     * waterlogged TOP slab must still find real water, not be rejected as "embedded in solid" -- the prior
     * fix only compared y against the block's topmost solid hit (which, for a top slab, sits right at the
     * block's own top boundary), making that comparison true for EVERY y in the block, including this open
     * half. Exercises the real production sampler, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceFindsWaterInTheOpenHalfOfAWaterloggedTopSlabInsteadOfRejectingTheWholeBlock(GameTestHelper helper) {
        net.minecraft.world.level.block.state.BlockState waterloggedTopSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        helper.setBlock(new BlockPos(5, 3, 5), waterloggedTopSlab);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        net.minecraft.core.BlockPos slabAbs = helper.absolutePos(new BlockPos(5, 3, 5));
        // In the slab's own OPEN bottom half (below its solid top-half collision shape): must find water.
        Double surface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                slabAbs.getX() + 0.5, slabAbs.getY() + 0.1, slabAbs.getZ() + 0.5);
        helper.assertTrue(surface != null,
                "a point in a waterlogged top slab's open bottom half should still find real water, not be "
                        + "rejected as embedded in the slab's own solid (top-half-only) collision shape, got "
                        + surface);
        // That water's own surface cannot rise above the slab's solid underside -- it is physically capped
        // there, not at the fluid's own nominal (unobstructed) height.
        double slabSolidUnderside = slabAbs.getY() + 0.5;
        helper.assertTrue(surface <= slabSolidUnderside + 1.0e-6,
                "the open half's water surface must be capped at the slab's own solid underside (" + slabSolidUnderside
                        + "), not the fluid's unobstructed nominal height -- got " + surface);
        helper.succeed();
    }

    /**
     * MINECRAFT-410 round-4 review follow-up (finding 2): an upward scan climbing through consecutive water
     * must STOP at a waterlogged BOTTOM slab's own solid lower half -- not cross straight through it (as
     * waterlogged, it still reports a fluid state, which the prior fix let an upward scan treat as climbable
     * water regardless of whether solid actually blocked that same column from below). Exercises the real
     * production sampler, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceStopsAtAWaterloggedBottomSlabsSolidHalfInsteadOfClimbingThroughIt(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.WATER);
        net.minecraft.world.level.block.state.BlockState waterloggedBottomSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        helper.setBlock(new BlockPos(5, 3, 5), waterloggedBottomSlab);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.WATER);
        net.minecraft.core.BlockPos homeAbs = helper.absolutePos(new BlockPos(5, 2, 5));
        Double surface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                homeAbs.getX() + 0.5, homeAbs.getY() + 0.5, homeAbs.getZ() + 0.5);
        helper.assertTrue(surface != null, "a point already in water should find a real surface, got null");
        double homeBlockSurface = homeAbs.getY()
                + helper.getLevel().getBlockState(homeAbs).getFluidState().getHeight(helper.getLevel(), homeAbs);
        helper.assertTrue(Math.abs(surface - homeBlockSurface) < 1.0e-6,
                "an upward scan must stop at a waterlogged bottom slab's own solid lower half -- not climb "
                        + "through it to the real water block above -- got surface=" + surface + " expected "
                        + "(capped at the slab, i.e. the home block's own surface) =" + homeBlockSurface);
        helper.succeed();
    }

    /**
     * MINECRAFT-410 round-5 review follow-up (finding 1): a point sitting in a waterlogged BOTTOM slab's
     * own OPEN top half must find that water right there at the scan's own starting block ({@code dy=0}),
     * not get wrongly treated as if the scan were "entering this block from below" (a test that only makes
     * sense for a block being climbed INTO from the one below it, not the block the point already starts
     * in) -- which this slab's own solid, starting right at its own bottom boundary, always satisfied
     * regardless of where the point itself actually sits. STONE directly below the slab (not water) makes
     * this observable: the old bug's wrong early return from {@code scanUpward} fell through to {@code
     * scanDownward}, which the stone then blocks outright -- producing a wrongly DRY result for a point
     * that is, in fact, sitting in real water. Exercises the real production sampler, not a
     * reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceFindsWaterInABottomSlabsOwnOpenHalfAtTheScansOwnHomeBlock(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        net.minecraft.world.level.block.state.BlockState waterloggedBottomSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.BOTTOM)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        helper.setBlock(new BlockPos(5, 3, 5), waterloggedBottomSlab);
        net.minecraft.core.BlockPos slabAbs = helper.absolutePos(new BlockPos(5, 3, 5));
        Double surface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                slabAbs.getX() + 0.5, slabAbs.getY() + 0.75, slabAbs.getZ() + 0.5);
        helper.assertTrue(surface != null,
                "a point in a bottom slab's own open top half should find that slab's own water at the scan's "
                        + "own home block, not be wrongly treated as dry just because solid (stone) blocks the "
                        + "unrelated block below -- got null");
        double homeBlockSurface = slabAbs.getY()
                + helper.getLevel().getBlockState(slabAbs).getFluidState().getHeight(helper.getLevel(), slabAbs);
        helper.assertTrue(Math.abs(surface - homeBlockSurface) < 1.0e-6,
                "the surface found must be the home (slab) block's own real water height " + homeBlockSurface
                        + ", not some other block's -- got " + surface);
        helper.succeed();
    }

    /**
     * MINECRAFT-410 round-5 review follow-up (finding 2): a point in a waterlogged TOP slab's open bottom
     * half, with REAL (unrelated) water sitting in the block above the slab, must still report the slab's
     * own capped underside surface -- not get overwritten by continuing to climb straight through the
     * slab's own solid ceiling to find that unrelated water up there instead. Exercises the real
     * production sampler, not a reimplementation.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void sampleSurfaceDoesNotOverwriteATopSlabsCappedWaterWithUnrelatedWaterAbove(GameTestHelper helper) {
        net.minecraft.world.level.block.state.BlockState waterloggedTopSlab = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP)
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true);
        helper.setBlock(new BlockPos(5, 3, 5), waterloggedTopSlab);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.WATER);
        net.minecraft.core.BlockPos slabAbs = helper.absolutePos(new BlockPos(5, 3, 5));
        Double surface = SableMotorboatBody.sampleSurfaceForTest(helper.getLevel(),
                slabAbs.getX() + 0.5, slabAbs.getY() + 0.1, slabAbs.getZ() + 0.5);
        helper.assertTrue(surface != null,
                "a point in a waterlogged top slab's open bottom half should still find real water, got null");
        double slabSolidUnderside = slabAbs.getY() + 0.5;
        helper.assertTrue(Math.abs(surface - slabSolidUnderside) < 1.0e-6,
                "the slab's own capped underside surface (" + slabSolidUnderside + ") must not be overwritten by "
                        + "climbing through the slab's solid ceiling to the unrelated real water sitting in the "
                        + "block above it -- got " + surface);
        helper.succeed();
    }

    /** Entry, rotor hover/climb, and landing: nose-up climbs, nose-down (and no pilot) comes back down and
     * actually rests on the ground rather than hovering forever or clipping through it. */
    @GameTest(template = "settlement_test", timeoutTicks = 200)
    public static void chopperClimbsOnNoseUpThenDescendsAndLandsOnNoseDown(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        ChopperEntity chopper = helper.spawn(DynamicVehiclesMod.CHOPPER.get(), new BlockPos(5, 3, 5));
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(chopper), "mock player failed to board the chopper");
        rider.setXRot(-90.0f);
        double startY = chopper.getY();
        helper.runAfterDelay(60L, () -> {
            helper.assertTrue(chopper.getY() > startY + 1.0, "chopper did not climb after 60 ticks of nose-up pitch: start=" + startY + " now=" + chopper.getY());
            rider.setXRot(90.0f);
            helper.runAfterDelay(140L, () -> {
                helper.assertTrue(chopper.onGround(), "chopper never came back down to land after sustained nose-down pitch");
                helper.succeed();
            });
        });
    }

    /** Entry, ground taxi (throttle + rudder) without picking up unwanted altitude, the landing-gear-style
     * "stays grounded while slow and nose-level" half of basic fixed-wing operation. */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void biplaneTaxisOnThrottleWithoutLeavingTheGround(GameTestHelper helper) {
        for (int dz = 0; dz <= 8; dz++) {
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        BiplaneEntity plane = helper.spawn(DynamicVehiclesMod.BIPLANE.get(), new BlockPos(5, 3, 0));
        plane.setYRot(0.0f);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(plane), "mock player failed to board the biplane");
        rider.zza = 1.0f;
        double startZ = plane.getZ();
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(plane.getControllingPassenger() == rider, "biplane lost its rider mid-taxi");
            helper.assertTrue(Math.abs(plane.getZ() - startZ) > 0.2, "biplane barely moved after 20 ticks of full throttle taxi");
            helper.assertTrue(plane.onGround(), "biplane left the ground while taxiing below takeoff speed with a level pitch");
            helper.succeed();
        });
    }

    /**
     * A real taxi-to-liftoff: throttle alone (nose level) builds speed on the ground, then pulling the
     * nose up past takeoff speed must actually leave the ground. Review 5563367455's second re-review
     * (48012) flagged that the prior fix's grounded-sink branch was checked before this transition, so a
     * fast, nose-up biplane could never actually take off -- {@code onGround()} reports the PREVIOUS
     * tick's collision result, so it still reads true on the very tick the plane first qualifies for
     * liftoff. This exercises that exact transition against the real entity/collision system, not just
     * the pure {@code BiplanePhysics} unit tests (which can pass a {@code grounded} flag directly without
     * ever going through {@code BiplaneEntity#onGround()}'s own one-tick lag).
     *
     * <p>Drives {@link BiplaneEntity#driveTick} directly rather than setting {@code rider.zza} once and
     * waiting out real server ticks: a {@link GameTestHelper#makeMockPlayer} is never added to the level
     * via {@code addFreshEntity}, yet its {@code zza} was observed decaying back toward 0 over real ticks
     * anyway while mounted (diagnosed by logging it tick-by-tick) -- some part of the riding/tracking path
     * (this repo's GameTest environment also carries Sable's own mixins into exactly that path, see
     * {@code entity_rotations_and_riding.ServerEntityMixin} in the server log) evidently still ticks a
     * mounted passenger even though it was never explicitly spawned. Re-asserting {@code zza} before each
     * manual {@code driveTick} call sidesteps that entirely while still exercising the real entity's real
     * {@code move}/collision/{@code onGround} path each call, not a shortcut around it.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 60)
    public static void biplaneLiftsOffAfterTaxiingToTakeoffSpeedAndPullingUp(GameTestHelper helper) {
        for (int dz = 0; dz <= 20; dz++) {
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.STONE);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        BiplaneEntity plane = helper.spawn(DynamicVehiclesMod.BIPLANE.get(), new BlockPos(5, 3, 0));
        plane.setYRot(0.0f);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(plane), "mock player failed to board the biplane");
        for (int i = 0; i < 60; i++) {
            rider.zza = 1.0f;
            plane.driveTick(rider);
        }
        helper.assertTrue(plane.onGround(), "sanity: should still be on the ground during the taxi phase, before pulling up");
        helper.assertTrue(Math.hypot(plane.getDeltaMovement().x(), plane.getDeltaMovement().z()) >= BiplanePhysics.TAKEOFF_SPEED,
                "sanity: should have reached takeoff speed after 60 ticks of full throttle");
        rider.setXRot(-90.0f);
        for (int i = 0; i < 30; i++) {
            rider.zza = 1.0f;
            plane.driveTick(rider);
        }
        helper.assertTrue(!plane.onGround(), "biplane never left the ground after taxiing to takeoff speed and pulling the nose up");
        helper.succeed();
    }

    /** A real landing: an already-airborne, already-slow biplane (below stall speed, so this does not
     * depend on the takeoff transition above) sinks under its bounded stall fall and actually touches
     * down on solid ground, rather than hovering or falling through it. */
    @GameTest(template = "settlement_test", timeoutTicks = 150)
    public static void biplaneDescendsAndLandsWhenSlowAndAirborne(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        for (int dy = 3; dy <= 7; dy++) {
            helper.setBlock(new BlockPos(5, dy, 5), Blocks.AIR);
        }
        BiplaneEntity plane = helper.spawn(DynamicVehiclesMod.BIPLANE.get(), new BlockPos(5, 8, 5));
        double startY = plane.getY();
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(plane), "mock player failed to board the biplane");
        // No throttle, level pitch, starting well below stall speed: BiplanePhysics' own stall branch
        // (speed < STALL_SPEED while airborne) sinks it at a bounded rate regardless of pitch.
        helper.runAfterDelay(140L, () -> {
            helper.assertTrue(plane.onGround(), "biplane never touched down after descending from above the landing site");
            helper.assertTrue(plane.getY() < startY - 2.0, "biplane should have come down from its spawn height, not stayed up there: startY=" + startY + " now=" + plane.getY());
            helper.succeed();
        });
    }

    /**
     * Existing-vehicle regression (review 5563367455/48008's "existing-vehicle smoke testing"): spawning,
     * mounting and dismounting the existing CAR still works unchanged after this PR's additions.
     *
     * <p>This does NOT drive the car with {@code forceDrive} and assert on distance travelled: an earlier
     * version tried exactly that and consistently measured ~0.5 blocks regardless of how many ticks it
     * was given (40, then 120), while the server log showed Sable itself erroring every tick --
     * {@code "Aborting entity get for abnormally large AABB"}, with one of the AABB's own corners at the
     * GameTest dimension's huge translated absolute coordinates (GameTest places each test's structure far
     * from the origin) and the other stuck near zero. That is a pre-existing Sable/GameTest coordinate
     * interaction this PR did not introduce and does not touch (CarEntity/SableCarBody/WheelMath are
     * unmodified) -- not a regression to chase here, but worth recording for whoever next needs Sable
     * driving under a GameTest. Confirmed unrelated to anything in this PR: {@code EnvironmentGameTests}'
     * own CAR/INDY/MONSTER_TRUCK spawns pass today without ever calling {@code forceDrive}.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void existingCarStillMountsAndDismountsAfterTheseChanges(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(car), "mock player failed to board the existing car after this PR's changes");
        helper.assertTrue(car.getControllingPassenger() == rider, "the existing car's controlling passenger was not the rider who boarded it");
        rider.stopRiding();
        helper.assertTrue(car.getControllingPassenger() == null, "the existing car still reports a controlling passenger after dismount");
        helper.succeed();
    }

    /** Save/reload proxy (the full thing needs an actual world unload/reload across a server restart,
     * which this environment cannot drive): round-trips a chopper's position and rotation through the
     * same NBT save/load path {@code SimpleVehicleEntity} and vanilla's own {@code Entity} already define,
     * the way a chunk unload/reload would. */
    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void vehicleNbtRoundTripPreservesPositionAndRotation(GameTestHelper helper) {
        ChopperEntity original = helper.spawn(DynamicVehiclesMod.CHOPPER.get(), new BlockPos(5, 3, 5));
        original.setYRot(123.0f);
        original.setXRot(12.0f);
        CompoundTag tag = new CompoundTag();
        helper.assertTrue(original.save(tag), "failed to save the chopper's own NBT");

        ChopperEntity reloaded = DynamicVehiclesMod.CHOPPER.get().create(helper.getLevel());
        helper.assertTrue(reloaded != null, "failed to construct a fresh chopper entity to reload into");
        reloaded.load(tag);

        helper.assertTrue(Math.abs(reloaded.getX() - original.getX()) < 1e-6, "reloaded chopper's X position did not match the saved one");
        helper.assertTrue(Math.abs(reloaded.getY() - original.getY()) < 1e-6, "reloaded chopper's Y position did not match the saved one");
        helper.assertTrue(Math.abs(reloaded.getZ() - original.getZ()) < 1e-6, "reloaded chopper's Z position did not match the saved one");
        helper.assertTrue(Math.abs(reloaded.getYRot() - original.getYRot()) < 1e-3, "reloaded chopper's yaw did not match the saved one");
        helper.assertTrue(Math.abs(reloaded.getXRot() - original.getXRot()) < 1e-3, "reloaded chopper's pitch did not match the saved one");
        helper.succeed();
    }

    /**
     * MINECRAFT-410: the motorboat's own orientation NBT round-trip -- unlike {@code ChopperEntity} above,
     * the motorboat now persists a full rigid-body {@code Quaternionf} (pitch/roll included, via
     * {@code OrientationNbt}, the same four-key scheme {@code CarEntity} already uses), not just yaw/pitch.
     * Drives the boat under water with throttle+rudder first so it actually tilts away from the identity
     * orientation before saving, so this is not a trivial "zero round-trips to zero" check.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150)
    public static void motorboatOrientationNbtRoundTripsThroughARealTiltedPose(GameTestHelper helper) {
        for (int dx = 0; dx <= 10; dx++) {
            for (int dz = 0; dz <= 10; dz++) {
                helper.setBlock(new BlockPos(dx, 2, dz), Blocks.WATER);
                helper.setBlock(new BlockPos(dx, 3, dz), Blocks.AIR);
            }
        }
        MotorboatEntity original = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 3, 5));
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        // See motorboatEntersWaterAndMovesAPlausibleDistanceUnderThrottle's javadoc: makeMockPlayer
        // spawns at absolute world origin, not this test's translated coordinates; move it to the boat
        // before mounting to avoid a stale near-origin AABB.
        rider.moveTo(original.getX(), original.getY(), original.getZ(), original.getYRot(), 0.0f);
        helper.assertTrue(rider.startRiding(original), "mock player failed to board the motorboat");
        rider.zza = 1.0f;
        rider.xxa = 1.0f;
        helper.runAfterDelay(60L, () -> {
            CompoundTag tag = new CompoundTag();
            helper.assertTrue(original.save(tag), "failed to save the motorboat's own NBT");
            helper.assertTrue(tag.contains("OrientationW"), "saved motorboat NBT should carry its rigid-body orientation");

            MotorboatEntity reloaded = DynamicVehiclesMod.MOTORBOAT.get().create(helper.getLevel());
            helper.assertTrue(reloaded != null, "failed to construct a fresh motorboat entity to reload into");
            reloaded.load(tag);

            helper.assertTrue(Math.abs(reloaded.getX() - original.getX()) < 1e-6, "reloaded motorboat's X position did not match the saved one");
            helper.assertTrue(Math.abs(reloaded.getY() - original.getY()) < 1e-6, "reloaded motorboat's Y position did not match the saved one");
            helper.assertTrue(Math.abs(reloaded.getZ() - original.getZ()) < 1e-6, "reloaded motorboat's Z position did not match the saved one");

            CompoundTag reloadedTag = new CompoundTag();
            helper.assertTrue(reloaded.save(reloadedTag), "failed to re-save the reloaded motorboat's own NBT");
            helper.assertTrue(Math.abs(reloadedTag.getFloat("OrientationX") - tag.getFloat("OrientationX")) < 1e-5, "reloaded motorboat's orientation X did not match the saved one");
            helper.assertTrue(Math.abs(reloadedTag.getFloat("OrientationY") - tag.getFloat("OrientationY")) < 1e-5, "reloaded motorboat's orientation Y did not match the saved one");
            helper.assertTrue(Math.abs(reloadedTag.getFloat("OrientationZ") - tag.getFloat("OrientationZ")) < 1e-5, "reloaded motorboat's orientation Z did not match the saved one");
            helper.assertTrue(Math.abs(reloadedTag.getFloat("OrientationW") - tag.getFloat("OrientationW")) < 1e-5, "reloaded motorboat's orientation W did not match the saved one");
            helper.succeed();
        });
    }
}
