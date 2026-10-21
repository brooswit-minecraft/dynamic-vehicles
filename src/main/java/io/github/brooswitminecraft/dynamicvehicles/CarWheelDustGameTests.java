package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.List;

import io.github.brooswitminecraft.dynamicatmosphere.AtmosphereMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Production-path checks for bounded dust from actual loaded wheel contacts. */
public final class CarWheelDustGameTests {
    private CarWheelDustGameTests() {}

    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void dustUsesOnlyLoadedWheelContactsAndTheAtmosphereDeliveryPath(GameTestHelper helper) {
        BlockPos leftContact = new BlockPos(4, 2, 5);
        BlockPos rightContact = new BlockPos(6, 2, 5);
        helper.setBlock(leftContact, Blocks.DIRT);
        helper.setBlock(rightContact, Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        List<AtmosphereCompat.Emission> emissions = new ArrayList<>();
        AtmosphereCompat.EmissionObserver.install(emissions::add);
        try {
            // The final wheel has a raycast block but carried no suspension load, the lost-contact/airborne
            // case. It must not produce dust just because it has a stale or speculative contact position.
            car.emitDust(helper.getLevel(), CarConfig.DUST_SPEED_START.get(),
                    new BlockPos[] {leftContact, rightContact, new BlockPos(5, 2, 4), null},
                    new int[] {1, 3, 0, 0}, new double[] {0.0, CarConfig.DUST_SLIP_START.get(), 20.0, 20.0});
            helper.assertTrue(emissions.size() == 2,
                    "dust did not stay limited to the two loaded wheel contacts: " + emissions.size());
            for (AtmosphereCompat.Emission emission : emissions) {
                helper.assertTrue(emission.material() == AtmosphereMaterial.DUST,
                        "wheel dust did not use the Dynamic Atmosphere dust material singleton");
                helper.assertTrue(emission.pos().equals(leftContact) || emission.pos().equals(rightContact),
                        "dust was not emitted at an actual loaded wheel contact: " + emission.pos());
                helper.assertTrue(emission.amount() > 0 && emission.amount() <= CarConfig.DUST_MAX_AMOUNT.get(),
                        "wheel dust escaped its configured per-emission bounds: " + emission.amount());
            }
            helper.succeed();
        } finally {
            AtmosphereCompat.EmissionObserver.clear();
        }
    }
}
