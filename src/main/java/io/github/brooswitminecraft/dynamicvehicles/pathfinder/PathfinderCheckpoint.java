package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * One reachable checkpoint in a {@link PathfinderRoute}: the region it belongs to (for circuit-
 * ordering purposes) and its own concrete position. Reaching a checkpoint is "entering
 * {@code position}'s own Minecraft chunk, in the same dimension, at any Y" (MINECRAFT-403), never
 * "anywhere in {@code region}" and never gated by terrain, a specific block position, or a reach
 * radius — see {@link PathfinderPosition#sameChunkAs}.
 */
public record PathfinderCheckpoint(PathfinderRegion region, PathfinderPosition position) {

    public boolean isReachedBy(PathfinderPosition playerPosition) {
        return position.sameDimensionAs(playerPosition) && position.sameChunkAs(playerPosition);
    }
}
