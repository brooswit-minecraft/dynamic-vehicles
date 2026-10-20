package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Expected failures run in a child test; only the enclosing regression counts toward the CI gate. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class VehicleLaneCleanupGameTests {
    @GameTest(template = "settlement_test", timeoutTicks = 180, batch = "laneCleanupFailureIsolated")
    public static void scheduledLaneFailureRestoresGlobalSubsteps(GameTestHelper helper) {
        checkCleanup(helper, false);
    }

    @GameTest(template = "settlement_test", timeoutTicks = 180, batch = "laneCleanupTimeoutIsolated")
    public static void timeoutRestoresGlobalSubsteps(GameTestHelper helper) {
        checkCleanup(helper, true);
    }

    private static void checkCleanup(GameTestHelper helper, boolean timeout) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        boolean[] mutated = {false};
        TestFunction function = new TestFunction("cleanupProbe", "cleanupProbe",
                helper.testInfo.getStructureName(), 100, 0L, true, child ->
                VehicleLanes.build(child, VehicleLanes.Site.GEAR_RATIO, lanes -> {
                    lanes.restoreSubStepsOnEveryOutcome();
                    CarConfig.WHEEL_SUB_STEPS.set(original == 1 ? 4 : 1);
                    mutated[0] = true;
                    if (!timeout) {
                        CarEntity car = lanes.spawn(DynamicVehiclesMod.CAR.get(),
                                VehicleLanes.spawnPoint(VehicleLanes.FIRST_LANE_X));
                        // Actual scheduled callback, deliberately demanding sand on the grass lane.
                        lanes.assertStaysOnLane(car, VehicleLanes.FIRST_LANE_X,
                                VehicleLanes.allWheels(Blocks.SAND), VehicleLanes.Wear.SAME_SURFACE,
                                10L, 10L, "deliberate lane mismatch");
                    }
                }));
        GameTestInfo child = new GameTestInfo(function, Rotation.NONE, helper.getLevel(),
                helper.testInfo.retryOptions());
        child.setNorthWestCorner(helper.absolutePos(BlockPos.ZERO));
        child.prepareTestStructure().placeStructure().startExecution(0);
        helper.onEachTick(() -> {
            child.tick(null);
            if (child.isDone()) {
                helper.assertTrue(mutated[0], "probe never changed substeps");
                helper.assertTrue(child.hasFailed(), "probe must fail before testing cleanup");
                helper.assertTrue(timeout ? child.getError() instanceof GameTestTimeoutException
                                : child.getError().getMessage().contains("deliberate lane mismatch"),
                        "unexpected probe failure: " + child.getError());
                helper.assertTrue(CarConfig.WHEEL_SUB_STEPS.get() == original,
                        "terminal outcome leaked the global substep setting");
                // A rerun notification after failure must be harmless, and never overwrite a later value.
                child.getListeners().forEach(listener -> listener.testAddedForRerun(child, child.copyReset(), null));
                helper.assertTrue(CarConfig.WHEEL_SUB_STEPS.get() == original, "rerun changed restored substeps");
                helper.succeed();
            }
        });
    }
}
