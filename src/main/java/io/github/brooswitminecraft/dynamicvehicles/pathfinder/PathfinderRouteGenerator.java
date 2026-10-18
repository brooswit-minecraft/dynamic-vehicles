package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

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
 */
public final class PathfinderRouteGenerator {

    private PathfinderRouteGenerator() {
    }

    public static PathfinderRoute generate(PathfinderRegion origin, PathfinderLevel level) {
        List<PathfinderRegion> checkpoints = level.isConfinedToOriginRegion()
                ? List.of(origin)
                : PathfinderRingMath.circuitOrder(origin, level.ring());
        return new PathfinderRoute(origin, level, checkpoints);
    }
}
