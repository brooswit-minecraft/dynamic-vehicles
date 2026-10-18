package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * A Pathfinder region: a fixed 512x512-block (32x32-chunk) grid cell, per
 * MINECRAFT-379's design. This is intentionally a distinct type from
 * {@code delivery.RegionCoord} (the Dispatcher's structure-placement-grid
 * region, a different and smaller cell size driven by
 * {@code RandomSpreadStructurePlacement}) — the two must never be conflated,
 * even though both are simple (x, z) grid coordinates.
 */
public record PathfinderRegion(int x, int z) {
    public static final int SIZE_BLOCKS = 512;

    /** The region containing the given block coordinates, correct for negative inputs. */
    public static PathfinderRegion fromBlockCoords(int blockX, int blockZ) {
        return new PathfinderRegion(Math.floorDiv(blockX, SIZE_BLOCKS), Math.floorDiv(blockZ, SIZE_BLOCKS));
    }
}
