package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The lightweight, storable identity of a generated village for delivery
 * purposes: its structure placement-region coordinates plus dimension. This
 * is deliberately NOT a block position — the region is a stable address that
 * exists independent of which exact chunk inside it a village start lands
 * in; recovering the exact start location requires re-deriving it via
 * {@link VillagePlacementService}, which is why {@link ConfirmedVillage}
 * carries that location alongside the identity rather than this record.
 */
public record VillageIdentity(RegionCoord region, ResourceKey<Level> dimension) {
}
