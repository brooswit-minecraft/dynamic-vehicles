package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /** Generates with the placeholder placer, which never fails; real callers should pass a terrain-aware one once it exists. */
    public static PathfinderRoute generate(PathfinderRegion origin, PathfinderLevel level) {
        return generate(origin, level, PathfinderCheckpointPlacer.DEFAULT_UNPLACED)
                .orElseThrow(() -> new AssertionError("DEFAULT_UNPLACED must never fail to place a checkpoint"));
    }

    /**
     * @return {@link Optional#empty()} if {@code placer} could not resolve every checkpoint
     *         (review follow-up: fail closed — never build/cache/return a route with an invalid
     *         or unsafe checkpoint).
     */
    public static Optional<PathfinderRoute> generate(PathfinderRegion origin, PathfinderLevel level, PathfinderCheckpointPlacer placer) {
        List<PathfinderCheckpoint> checkpoints = new ArrayList<>(level.checkpointCount());
        if (level.isConfinedToOriginRegion()) {
            int count = level.checkpointCount();
            for (int i = 0; i < count; i++) {
                Optional<PathfinderCheckpoint> checkpoint = placer.place(origin, i, count);
                if (checkpoint.isEmpty()) {
                    return Optional.empty();
                }
                checkpoints.add(checkpoint.get());
            }
        } else {
            for (PathfinderRegion region : PathfinderRingMath.circuitOrder(origin, level.ring())) {
                Optional<PathfinderCheckpoint> checkpoint = placer.place(region, 0, 1);
                if (checkpoint.isEmpty()) {
                    return Optional.empty();
                }
                checkpoints.add(checkpoint.get());
            }
        }
        return Optional.of(new PathfinderRoute(origin, level, checkpoints));
    }
}
