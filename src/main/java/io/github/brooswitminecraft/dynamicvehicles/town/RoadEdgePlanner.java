package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;

/**
 * Deterministic road connection points on a town region's four edges. MINECRAFT-388 requires
 * adjacent regions to independently compute the SAME shared-edge point, so the hash input here is
 * canonicalized on the unordered pair of regions sharing that edge (never on "my region plus a
 * direction"), which is exactly what makes region A's EAST point equal region B's WEST point when
 * B is A's east neighbour — order of evaluation never matters.
 */
public final class RoadEdgePlanner {
    private RoadEdgePlanner() {}

    /**
     * The connection point's offset along its edge, measured from that edge's own minimum corner,
     * plus the agreed road HEIGHT at that point relative to each region's own town-centre
     * reference height ({@code heightOffset}). Both coordinates are canonicalized on the same
     * unordered region pair, so a region's edge and its neighbour's opposite edge agree on both -
     * a road built out from either side reaches the border at the exact same elevation, never just
     * the same (x, z).
     */
    public record EdgeConnection(int offsetAlongEdge, int heightOffset) {
        public EdgeConnection {
            if (offsetAlongEdge < 0 || offsetAlongEdge >= TownConstants.REGION_SIZE_BLOCKS) {
                throw new IllegalArgumentException("offsetAlongEdge out of range: " + offsetAlongEdge);
            }
        }
    }

    public static EdgeConnection computeEdgeConnection(RegionCoord region, TownEdge edge, long worldSeed) {
        RegionCoord neighbor = TownRegion.neighbor(region, edge);
        RegionCoord first = canonicalFirst(region, neighbor);
        RegionCoord second = first == region ? neighbor : region;
        long hash = TownSeedMath.mix(worldSeed, first.x(), first.z(), second.x(), second.z(), axisSalt(edge));
        long heightHash = TownSeedMath.mix(worldSeed, first.x(), first.z(), second.x(), second.z(), axisSalt(edge), 0x4EADL);
        int heightOffset = TownSeedMath.boundedOffset(heightHash, TownConstants.ROAD_EDGE_HEIGHT_OFFSET_RANGE);
        return new EdgeConnection(TownSeedMath.bounded(hash, TownConstants.REGION_SIZE_BLOCKS), heightOffset);
    }

    /**
     * NORTH/SOUTH edges run along X, EAST/WEST edges run along Z; the axis — not the edge's
     * compass name — is what two neighbours must agree on, so a N/S pair and an E/W pair never
     * accidentally collide onto the same hash input. The opposite edge of the same physical
     * boundary carries the same salt (NORTH and SOUTH use the same axis salt as EACH OTHER only
     * when canonicalization, not the salt, makes them equal — the salt alone is per-axis, shared
     * by both edges that run along it).
     */
    private static long axisSalt(TownEdge edge) {
        return switch (edge) {
            case NORTH, SOUTH -> 0xA5A5A5A5L;
            case EAST, WEST -> 0x5A5A5A5AL;
        };
    }

    /** The absolute block position of an edge connection, resolved against the region it was computed for. */
    public static int[] absolutePosition(RegionCoord region, TownEdge edge, EdgeConnection connection) {
        int minX = TownRegion.minBlockX(region);
        int minZ = TownRegion.minBlockZ(region);
        int size = TownConstants.REGION_SIZE_BLOCKS;
        return switch (edge) {
            case NORTH -> new int[] {minX + connection.offsetAlongEdge(), minZ};
            case SOUTH -> new int[] {minX + connection.offsetAlongEdge(), minZ + size - 1};
            case WEST -> new int[] {minX, minZ + connection.offsetAlongEdge()};
            case EAST -> new int[] {minX + size - 1, minZ + connection.offsetAlongEdge()};
        };
    }

    /** A region's own ordering key so both sides of a boundary agree which region's coordinates seed the hash first. */
    private static RegionCoord canonicalFirst(RegionCoord a, RegionCoord b) {
        if (a.x() != b.x()) {
            return a.x() < b.x() ? a : b;
        }
        return a.z() <= b.z() ? a : b;
    }
}
