package io.github.brooswitminecraft.dynamicvehicles;

import io.github.brooswitminecraft.dynamicterrain.Erosion;
import io.github.brooswitminecraft.dynamicterrain.TireSlip;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The only place vehicles touch the terrain: a wheel reports how hard it is
 * sliding, and Dynamic Terrain alone decides whether the surface changes.
 * Vehicles never modify terrain directly.
 */
public final class SlipReporter {
    private SlipReporter() {}

    public static Erosion.Result report(Level level, BlockPos contactBlock, double slipSpeed, double wheelLoadKg) {
        return TireSlip.report(level, contactBlock, slipSpeed, wheelLoadKg);
    }
}
