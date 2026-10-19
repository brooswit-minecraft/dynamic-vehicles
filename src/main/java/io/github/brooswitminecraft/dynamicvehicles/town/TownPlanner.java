package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;

/**
 * Generates the {@link TownPlan} for a region: a pure function of (region, world seed), so calling
 * it again after a reload always reproduces the identical plan (MINECRAFT-388's reload-stability
 * requirement) without persisting anything.
 */
public final class TownPlanner {
    private TownPlanner() {}

    /** A safety margin keeping the town centre off the region's own edges, where road edge points live. */
    private static final int CENTER_MARGIN_BLOCKS = TownConstants.REGION_SIZE_BLOCKS / 4;

    public static TownPlan generate(RegionCoord region, long worldSeed) {
        int span = TownConstants.REGION_SIZE_BLOCKS - 2 * CENTER_MARGIN_BLOCKS;
        long centerHash = TownSeedMath.mix(worldSeed, region.x(), region.z(), 0xC0FFEEL);
        long centerHashX = TownSeedMath.mix(centerHash, 1L);
        long centerHashZ = TownSeedMath.mix(centerHash, 2L);
        int townCenterX = TownRegion.minBlockX(region) + CENTER_MARGIN_BLOCKS + TownSeedMath.bounded(centerHashX, span);
        int townCenterZ = TownRegion.minBlockZ(region) + CENTER_MARGIN_BLOCKS + TownSeedMath.bounded(centerHashZ, span);

        long storehouseHash = TownSeedMath.mix(worldSeed, region.x(), region.z(), 0x57011EL);
        int storehouseVariant = TownSeedMath.bounded(storehouseHash, TownConstants.STOREHOUSE_VARIATIONS);

        return new TownPlan(region, worldSeed, townCenterX, townCenterZ,
                TownPlan.allEdges(region, worldSeed), storehouseVariant);
    }

    /**
     * The zone an absolute block column is planned for, computed relative to the town's ACTUAL
     * planned road routes (per review round 2 finding #5 - {@link TownRoadPlanner#distanceToNearestRoute},
     * the real L-shaped edge-to-centre paths {@link TownRoadPlanner} builds, never the old
     * infinite centre cross those routes superseded): distance to the nearest route point is
     * banded into {@link TownConstants#PARCEL_SIZE_BLOCKS}-wide rings, cycling commercial
     * (closest), then industrial, then residential (farthest) — repeating outward so the long-run
     * area ratio matches {@link TownConstants#COMMERCIAL_ZONE_SHARE} etc. A small per-parcel
     * hashed jitter keeps the ring boundaries from reading as perfectly concentric squares.
     */
    static TownZone zoneAt(TownPlan plan, int blockX, int blockZ) {
        int parcelSize = TownConstants.PARCEL_SIZE_BLOCKS;
        int distanceToRoad = TownRoadPlanner.distanceToNearestRoute(plan, blockX, blockZ);

        long hash = TownSeedMath.mix(plan.worldSeed(), plan.region().x(), plan.region().z(),
                Math.floorDiv(blockX, parcelSize), Math.floorDiv(blockZ, parcelSize));
        int jitter = TownSeedMath.bounded(hash, parcelSize) - parcelSize / 2;

        int band = Math.floorDiv(distanceToRoad + jitter, parcelSize);
        int totalShare = TownConstants.RESIDENTIAL_ZONE_SHARE + TownConstants.COMMERCIAL_ZONE_SHARE
                + TownConstants.INDUSTRIAL_ZONE_SHARE;
        int cyclePos = Math.floorMod(band, totalShare);
        if (cyclePos < TownConstants.COMMERCIAL_ZONE_SHARE) {
            return TownZone.COMMERCIAL;
        }
        if (cyclePos < TownConstants.COMMERCIAL_ZONE_SHARE + TownConstants.INDUSTRIAL_ZONE_SHARE) {
            return TownZone.INDUSTRIAL;
        }
        return TownZone.RESIDENTIAL;
    }

    /** True when the column lies within half the major road's width of the nearest point on any
     *  of the town's ACTUAL planned road routes (see {@link #zoneAt}'s javadoc for why this is no
     *  longer the old infinite centre cross). */
    public static boolean isOnThoroughfare(TownPlan plan, int blockX, int blockZ) {
        int halfWidth = TownConstants.MAJOR_ROAD_WIDTH / 2;
        return TownRoadPlanner.distanceToNearestRoute(plan, blockX, blockZ) <= halfWidth;
    }
}
