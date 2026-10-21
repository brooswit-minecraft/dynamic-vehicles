package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.List;

import io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-480 production-path checks. These deliberately drive a real entity through Sable's raycast,
 * suspension-load and slip path; they do not inject invented contact arrays into {@link CarEntity#emitDust}.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class CarWheelDustGameTests {
    private CarWheelDustGameTests() {}

    /**
     * A real car, on a real dirt lane, must emit through Dynamic Atmosphere only from a wheel that the same
     * production tick raycast, loaded and found eligible for dust. The observer is called by
     * {@link AtmosphereCompat#dust} after the actual emitter invocation, while this entity's current Sable
     * telemetry still describes that tick, so it proves the world-space delivery position rather than merely
     * echoing a test-supplied relative position.
     */
    @GameTest(template = "settlement_test", timeoutTicks = 150 + VehicleLanes.CHUNK_LOAD_ALLOWANCE,
            batch = "wheelDustIsolated")
    public static void groundedRollingCarDeliversDustAtRealLoadedWheelContacts(GameTestHelper helper) {
        VehicleLanes.build(helper, VehicleLanes.Site.WHEEL_DUST, lanes -> {
            lanes.layLane(VehicleLanes.FIRST_LANE_X, Blocks.DIRT.defaultBlockState());
            CarEntity car = lanes.spawn(DynamicVehiclesMod.CAR.get(), VehicleLanes.spawnPoint(VehicleLanes.FIRST_LANE_X));
            List<Observation> dust = new ArrayList<>();
            AtmosphereCompat.EmissionObserver.install(emission -> {
                if (emission.material() == AtmosphereMaterial.DUST) {
                    // EmissionObserver is intentionally global for the optional-dependency boundary. Scope it
                    // here to this fixture's CURRENT real wheel contacts before recording: a concurrently
                    // ticking vehicle may produce legitimate dust, but is not evidence about this car.
                    Observation observation = new Observation(emission, helper.getLevel().getBlockState(emission.pos()),
                            car.debugSpeed(), car.debugWheelContactBlocks(), car.debugWheelContactSubSteps(),
                            car.debugWheelDustSlipSpeeds());
                    if (loadedWheelAt(observation, emission.pos()) >= 0) {
                        dust.add(observation);
                    }
                }
            });
            helper.testInfo.addListener(new GameTestCleanup(AtmosphereCompat.EmissionObserver::clear));
            lanes.assertStaysOnLane(car, VehicleLanes.FIRST_LANE_X,
                    new net.minecraft.world.level.block.Block[] {Blocks.DIRT, Blocks.DIRT, Blocks.DIRT, Blocks.DIRT},
                    VehicleLanes.Wear.SAME_SURFACE, 4, 125, "dust rolling lane");
            helper.runAfterDelay(8, () -> car.forceDrive(1.0, 0.0, 110));
            helper.runAfterDelay(135, () -> {
                helper.assertTrue(!dust.isEmpty(), "real grounded driving never delivered a dust emission");
                for (Observation observed : dust) {
                    AtmosphereCompat.Emission emission = observed.emission();
                    // The lane starts as DIRT. Sable's one real SlipReporter call may legitimately advance
                    // that exact contacted block through Dynamic Terrain's documented DIRT -> GRAVEL -> AIR
                    // damage transition before emitEffects runs. Capture and report the state at delivery;
                    // accepting only that lineage keeps this a material/transition assertion instead of a
                    // flaky pristine-block assertion.
                    helper.assertTrue(isDirtWearStage(observed.materialAtDelivery()),
                            "dust contact left the dirt wear lineage at delivery: pos=" + emission.pos()
                                    + " material=" + observed.materialAtDelivery().getBlock());
                    int wheel = loadedWheelAt(observed, emission.pos());
                    helper.assertTrue(wheel >= 0, "dust source was not a positive-load current wheel contact: "
                            + emission.pos());
                    helper.assertTrue(Math.abs(observed.speed()) >= CarConfig.DUST_SPEED_START.get()
                                    || observed.slips()[wheel] >= CarConfig.DUST_SLIP_START.get(),
                            "dust ignored both real speed and slip thresholds: speed=" + observed.speed()
                                    + " slip=" + observed.slips()[wheel]);
                    helper.assertTrue(emission.amount() > 0 && emission.amount() <= CarConfig.DUST_MAX_AMOUNT.get(),
                            "dust escaped its configured per-wheel bounds: " + emission.amount());
                }
                helper.succeed();
            });
        });
    }

    @GameTest(template = "settlement_test", timeoutTicks = 170, batch = "wheelDustReverseIsolated")
    public static void reversingEntityEmitsFromActualLoadedWheels(GameTestHelper helper) {
        runSlipScenario(helper, true);
    }

    @GameTest(template = "settlement_test", timeoutTicks = 170, batch = "wheelDustBurnoutIsolated")
    public static void stationaryWallBurnoutEmitsFromActualSpinningWheels(GameTestHelper helper) {
        runSlipScenario(helper, false);
    }

    private static void runSlipScenario(GameTestHelper helper, boolean reverse) {
        VehicleLanes.build(helper, reverse ? VehicleLanes.Site.DUST_REVERSE : VehicleLanes.Site.DUST_BURNOUT, lanes -> {
            lanes.layLane(VehicleLanes.FIRST_LANE_X, Blocks.STONE.defaultBlockState());
            if (!reverse) {
                for (int x=2;x<=6;x++) for (int y=3;y<=5;y++)
                    lanes.setBlock(new BlockPos(x,y,14),Blocks.BEDROCK.defaultBlockState());
            }
            CarEntity car = lanes.spawn(DynamicVehiclesMod.CAR.get(),
                    new net.minecraft.world.phys.Vec3(VehicleLanes.FIRST_LANE_X+.5,VehicleLanes.SPAWN_Y,reverse?90:6));
            var rider = helper.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
            helper.assertTrue(rider.startRiding(car), "scenario driver could not mount car");
            car.selectGear(reverse ? CarPhysics.Gear.REVERSE : CarPhysics.Gear.D1);
            int[] qualifying = {0};
            AtmosphereCompat.EmissionObserver.install(emission -> {
                if (emission.material()!=AtmosphereMaterial.DUST) return;
                Observation observed = new Observation(emission,helper.getLevel().getBlockState(emission.pos()),
                        car.debugSpeed(),car.debugWheelContactBlocks(),car.debugWheelContactSubSteps(),car.debugWheelDustSlipSpeeds());
                int wheel = loadedWheelAt(observed,emission.pos());
                if (wheel<0) return;
                boolean scenario = reverse ? car.getDeltaMovement().z < -.1
                        : observed.speed()<1 && observed.slips()[wheel]>CarConfig.DUST_SLIP_START.get();
                if (scenario) {
                    helper.assertTrue(emission.amount()>0 && emission.amount()<=CarConfig.DUST_MAX_AMOUNT.get(),
                            "real reverse/burnout dust escaped per-wheel cap");
                    qualifying[0]++;
                }
            });
            helper.testInfo.addListener(new GameTestCleanup(AtmosphereCompat.EmissionObserver::clear));
            helper.startSequence().thenIdle(8).thenExecute(() -> car.forceDrive(1,0,100))
                    .thenIdle(105).thenExecute(() -> helper.assertTrue(qualifying[0]>0,
                            "real entity never delivered "+(reverse?"reverse":"stationary burnout")+" contact dust"))
                    .thenSucceed();
        });
    }

    private static int loadedWheelAt(Observation observed, BlockPos position) {
        int count = Math.min(observed.contacts().length,
                Math.min(observed.contactSubSteps().length, observed.slips().length));
        for (int wheel = 0; wheel < count; wheel++) {
            if (position.equals(observed.contacts()[wheel]) && observed.contactSubSteps()[wheel] > 0) {
                return wheel;
            }
        }
        return -1;
    }

    private static boolean isDirtWearStage(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRAVEL) || state.isAir();
    }

    private record Observation(AtmosphereCompat.Emission emission, BlockState materialAtDelivery, double speed,
            BlockPos[] contacts, int[] contactSubSteps, double[] slips) {}

    private static final class GameTestCleanup implements net.minecraft.gametest.framework.GameTestListener {
        private final Runnable cleanup;
        private boolean done;

        private GameTestCleanup(Runnable cleanup) { this.cleanup = cleanup; }
        private void run() { if (!done) { done = true; cleanup.run(); } }
        @Override public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
        @Override public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,
                net.minecraft.gametest.framework.GameTestRunner runner) { run(); }
        @Override public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,
                net.minecraft.gametest.framework.GameTestRunner runner) { run(); }
        @Override public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo info,
                net.minecraft.gametest.framework.GameTestInfo retry,
                net.minecraft.gametest.framework.GameTestRunner runner) { run(); }
    }
}
