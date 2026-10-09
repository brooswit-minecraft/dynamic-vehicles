package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;

/**
 * A village {@link VillagePlacementService} has confirmed actually generated
 * (or was already present) at its candidate placement region — never an
 * unconfirmed candidate. Carries enough information for a caller to recover
 * the real arrival location later, rather than treating the whole region as
 * the destination.
 *
 * @param identity      the region + dimension address of this village
 * @param ring          the distance ring it was found in (0 = none, since the
 *                      origin's own region is never a candidate; 1 = nearest)
 * @param approxDistance straight-line block distance from the search origin
 *                       to {@code startBlockPos}
 * @param startBlockPos the actual generated village structure start position,
 *                      suitable as a tight arrival/destination criterion
 */
public record ConfirmedVillage(
        VillageIdentity identity,
        int ring,
        double approxDistance,
        BlockPos startBlockPos
) {
}
