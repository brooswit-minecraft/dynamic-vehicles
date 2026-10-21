package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

/** Entity-level contract checks for the shared wheel/camera ancestor. Client hardware/rendering is separate. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DriverVehicleGameTests {
    private DriverVehicleGameTests() {}

    @GameTest(template = "settlement_test")
    public static void registeredCarAndBoatShareInputContractWithoutOptingAircraftIn(GameTestHelper helper) {
        DriverVehicleEntity car = DynamicVehiclesMod.CAR.get().create(helper.getLevel());
        DriverVehicleEntity boat = DynamicVehiclesMod.MOTORBOAT.get().create(helper.getLevel());
        DriverVehicleEntity chopper = DynamicVehiclesMod.CHOPPER.get().create(helper.getLevel());
        DriverVehicleEntity plane = DynamicVehiclesMod.BIPLANE.get().create(helper.getLevel());
        helper.assertTrue(car != null && boat != null && chopper != null && plane != null,
                "vehicle registration failed after introducing the shared ancestor");
        helper.assertTrue(DriverVehicleEntity.acceptsWheelMovement(car), "car lost its wheel input eligibility");
        helper.assertTrue(DriverVehicleEntity.acceptsWheelMovement(boat), "boat did not opt into wheel input");
        helper.assertTrue(!DriverVehicleEntity.acceptsWheelMovement(chopper)
                        && !DriverVehicleEntity.acceptsWheelMovement(plane)
                        && !DriverVehicleEntity.acceptsWheelMovement(null),
                "aircraft or dismounted state incorrectly opted into car/boat controls");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void boatRetainsItsSingleDriverAndOrientationThroughSharedAncestor(GameTestHelper helper) {
        MotorboatEntity boat = helper.spawn(DynamicVehiclesMod.MOTORBOAT.get(), new BlockPos(5, 3, 5));
        var driver = helper.makeMockPlayer(GameType.CREATIVE);
        var passenger = helper.makeMockPlayer(GameType.CREATIVE);
        helper.assertTrue(driver.startRiding(boat), "driver could not board boat");
        helper.assertTrue(boat.getControllingPassenger() == driver, "shared ancestor changed boat controller");
        helper.assertTrue(!passenger.startRiding(boat), "shared ancestor added an unintended passenger seat");
        CompoundTag saved = new CompoundTag();
        Quaternionf orientation = new Quaternionf().rotateY(.4f).rotateX(.2f);
        saved.putFloat("OrientationX", orientation.x);
        saved.putFloat("OrientationY", orientation.y);
        saved.putFloat("OrientationZ", orientation.z);
        saved.putFloat("OrientationW", orientation.w);
        boat.readAdditionalSaveData(saved);
        CompoundTag written = new CompoundTag();
        boat.addAdditionalSaveData(written);
        for (String component : new String[] {"X", "Y", "Z", "W"}) {
            helper.assertTrue(Math.abs(written.getFloat("Orientation" + component)
                            - saved.getFloat("Orientation" + component)) < 1e-6,
                    "shared ancestor changed saved boat orientation component " + component);
        }
        driver.stopRiding();
        helper.assertTrue(boat.getControllingPassenger() == null, "dismount retained a stale controller");
        helper.succeed();
    }
}
