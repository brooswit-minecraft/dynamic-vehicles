package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The lightweight, storable identity of a predicted vanilla Village placement for delivery
 * purposes: its structure placement-region coordinates plus dimension. This
 * is deliberately NOT a block position — the region is a stable address that
 * exists independent of which exact chunk inside it vanilla selects. The
 * selected predicted chunk is carried by {@link PredictedVillage} alongside
 * this durable identity.
 */
public record VillageIdentity(RegionCoord region, ResourceKey<Level> dimension) {
}
