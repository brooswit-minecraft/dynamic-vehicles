package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** The persistence/storage key for a town region, in the same {@code dimension:regionX:regionZ}
 *  shape {@code delivery.SettlementFounder#settlementKey} already uses. */
public final class TownKeys {
    private TownKeys() {}

    public static String keyFor(ResourceKey<Level> dimension, RegionCoord region) {
        return dimension.location() + ":" + region.x() + ":" + region.z();
    }
}
