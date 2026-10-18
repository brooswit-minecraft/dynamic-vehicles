package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;

/**
 * Dynamic Towns' own fixed-size grid over {@code delivery.RegionCoord} cells: one {@code RegionCoord}
 * is one town, per MINECRAFT-388. See {@link TownConstants#REGION_SIZE_BLOCKS} for why a new fixed
 * size was needed here rather than reusing a size already defined on {@code RegionCoord} (there
 * isn't one — flagged as an open decision in the PR description).
 */
public final class TownRegion {
    private TownRegion() {}

    /** The region containing the given block coordinates, correct for negative inputs. */
    public static RegionCoord fromBlockCoords(int blockX, int blockZ) {
        return new RegionCoord(Math.floorDiv(blockX, TownConstants.REGION_SIZE_BLOCKS),
                Math.floorDiv(blockZ, TownConstants.REGION_SIZE_BLOCKS));
    }

    /** The minimum (north-west) block corner of a region. */
    public static int minBlockX(RegionCoord region) {
        return region.x() * TownConstants.REGION_SIZE_BLOCKS;
    }

    public static int minBlockZ(RegionCoord region) {
        return region.z() * TownConstants.REGION_SIZE_BLOCKS;
    }

    /** The region immediately adjacent to {@code region} in {@code edge}'s direction. */
    public static RegionCoord neighbor(RegionCoord region, TownEdge edge) {
        return switch (edge) {
            case NORTH -> new RegionCoord(region.x(), region.z() - 1);
            case SOUTH -> new RegionCoord(region.x(), region.z() + 1);
            case EAST -> new RegionCoord(region.x() + 1, region.z());
            case WEST -> new RegionCoord(region.x() - 1, region.z());
        };
    }
}
