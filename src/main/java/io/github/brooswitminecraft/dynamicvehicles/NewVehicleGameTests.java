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

    /** Entry (mount), basic throttle-driven operation on water, and that the unit-mismatch regression
     * (the boat covering ~100+ blocks in well under a second, review 5563367455's first finding) stays
     * fixed: real displacement over a fixed number of real ticks must land in a plausible range, not an
     * 18x-inflated one. */
    @GameTest(template = "settlement_test", timeoutTicks = 100)
    public static void motorboatEntersWaterAndMovesAPlausibleDistanceUnderThrottle(GameTestHelper helper) {
        for (int dz = 0; dz <= 8; dz++) {
            helper.setBlock(new BlockPos(5, 2, dz), Blocks.WATER);
            helper.setBlock(new BlockPos(5, 3, dz), Blocks.AIR);
        }
        MotorboatEntity boat = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 3, 0));
        boat.setYRot(0.0f);
        Player rider = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(rider.startRiding(boat), "mock player failed to board the motorboat");
        rider.zza = 1.0f;
        double startZ = boat.getZ();
        helper.runAfterDelay(20L, () -> {
            helper.assertTrue(boat.getControllingPassenger() == rider, "motorboat lost its rider mid-drive");
            double traveled = Math.abs(boat.getZ() - startZ);
            helper.assertTrue(traveled > 0.5, "motorboat barely moved after 20 ticks of full throttle: " + traveled + " blocks");
            helper.assertTrue(traveled < 15.0,
                    "motorboat covered an unrealistic distance in 20 ticks (the pre-fix unit-mismatch regression): " + traveled + " blocks");
            helper.succeed();
        });
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
}
