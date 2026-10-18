package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;

import java.util.EnumMap;
import java.util.Map;

/**
 * The internal, invisible planning map MINECRAFT-388 asks for: what belongs at each location in a
 * town region, decided once, ahead of any construction. Entirely Minecraft-free and immutable, so
 * it is cheap to regenerate from {@link TownPlanner#generate} on every load rather than persisted —
 * determinism (same region + same world seed always yields the identical plan) is what makes
 * regeneration safe across a reload, not a saved copy.
 */
public record TownPlan(
        RegionCoord region,
        long worldSeed,
        int townCenterX,
        int townCenterZ,
        Map<TownEdge, RoadEdgePlanner.EdgeConnection> edgeConnections,
        int storehouseVariant
) {
    public TownPlan {
        edgeConnections = Map.copyOf(edgeConnections);
    }

    public int[] edgeConnectionAbsolute(TownEdge edge) {
        return RoadEdgePlanner.absolutePosition(region, edge, edgeConnections.get(edge));
    }

    /** The zone a given absolute block column is planned for; see {@link TownPlanner} for the formula. */
    public TownZone zoneAt(int blockX, int blockZ) {
        return TownPlanner.zoneAt(this, blockX, blockZ);
    }

    static Map<TownEdge, RoadEdgePlanner.EdgeConnection> allEdges(RegionCoord region, long worldSeed) {
        Map<TownEdge, RoadEdgePlanner.EdgeConnection> map = new EnumMap<>(TownEdge.class);
        for (TownEdge edge : TownEdge.values()) {
            map.put(edge, RoadEdgePlanner.computeEdgeConnection(region, edge, worldSeed));
        }
        return map;
    }
}
