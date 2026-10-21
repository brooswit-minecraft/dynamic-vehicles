package io.github.brooswitminecraft.dynamicvehicles;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Production-path checks for one vehicle-local exhaust source, separate from tire dust. */
public final class CarExhaustGameTests {
    private CarExhaustGameTests() {}

    @GameTest(template = "settlement_test", timeoutTicks = 20)
    public static void fullThrottleCarEmitsBoundedExhaustFromItsRear(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
        car.setYRot(0.0f);
        AtomicReference<AtmosphereCompat.Emission> observed = new AtomicReference<>();
        AtmosphereCompat.EmissionObserver.install(observed::set);
        try {
            car.emitExhaust(helper.getLevel(), 1.0, CarEffectsMath.MIN_EXHAUST_SPEED);
            AtmosphereCompat.Emission emission = observed.get();
            helper.assertTrue(emission != null, "full-throttle moving car emitted no exhaust through the production compatibility path");
            helper.assertTrue(emission.material() == AtmosphereCompat.exhaustMaterial(),
                    "car exhaust did not use Dynamic Atmosphere's exhaust material singleton");
            helper.assertTrue(emission.amount() > 0 && emission.amount() <= CarConfig.EXHAUST_MAX_AMOUNT.get(),
                    "car exhaust escaped the configured per-emission bounds: " + emission.amount());
            helper.assertTrue(emission.pos().z < car.getZ(),
                    "car exhaust was not emitted behind the yaw-0 vehicle: car=" + car.blockPosition() + " exhaust=" + emission.pos());
            helper.succeed();
        } finally {
            AtmosphereCompat.EmissionObserver.clear();
        }
    }
}
