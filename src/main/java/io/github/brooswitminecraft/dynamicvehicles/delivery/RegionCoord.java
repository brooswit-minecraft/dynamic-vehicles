package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * A village structure placement-region coordinate pair (not a chunk or block
 * position). Regions are the grid cells a {@code RandomSpreadStructurePlacement}
 * divides the world into; each region holds at most one candidate chunk for a
 * given structure set.
 */
public record RegionCoord(int x, int z) {
}
