package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the one deterministic {@link PathfinderRoute} for a given origin
 * region and level, per MINECRAFT-379: "one deterministic route per origin
 * region per level, shared by all Pathfinder villagers in that region" and
 * "consistent across attempts and multiplayer sessions". Both hold
 * trivially here because this is a pure function of
 * {@code (origin, level)} only — no RNG, no villager/player/world-seed
 * input — so calling it twice, from any villager, on any attempt, in any
 * session, always returns an equal route.
 *
 * <p>Level 1's eight checkpoints all share the origin region (an in-region
 * local loop); levels 2-5 give each of their ring regions exactly one
 * checkpoint.
 */
public final class PathfinderRouteGenerator {

    private PathfinderRouteGenerator() {
    }

    /** Generates with the placeholder placer; real callers should pass a terrain-aware one once it exists. */
    public static PathfinderRoute generate(PathfinderRegion origin, PathfinderLevel level) {
        return generate(origin, level, PathfinderCheckpointPlacer.DEFAULT_UNPLACED);
    }

    public static PathfinderRoute generate(PathfinderRegion origin, PathfinderLevel level, PathfinderCheckpointPlacer placer) {
        List<PathfinderCheckpoint> checkpoints;
        if (level.isConfinedToOriginRegion()) {
            int count = level.checkpointCount();
            checkpoints = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                checkpoints.add(placer.place(origin, i, count));
            }
        } else {
            List<PathfinderRegion> ring = PathfinderRingMath.circuitOrder(origin, level.ring());
            checkpoints = ring.stream().map(region -> placer.place(region, 0, 1)).toList();
        }
        return new PathfinderRoute(origin, level, checkpoints);
    }
}
