package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;

/**
 * A vanilla Village structure-placement prediction. Unlike a generated
 * structure start, this is available before the destination chunk or region
 * has been generated. Its target is the centre of vanilla's predicted
 * placement chunk, not a claim that a village start has already been saved.
 */
public record PredictedVillage(
        VillageIdentity identity,
        int ring,
        double approxDistance,
        BlockPos targetBlockPos
) {
}
