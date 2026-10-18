package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * A concrete world position: a dimension id plus block-space coordinates.
 * Minecraft-free (no {@code ResourceKey<Level>}/{@code BlockPos}) so the
 * checkpoint-reach math in this package stays unit-testable without a
 * server; the Minecraft-facing integration layer converts to/from this at
 * its boundary.
 *
 * <p>Review follow-up (MINECRAFT-379, PR #163 review): a {@link
 * PathfinderRegion} identifies which 512x512-block cell a checkpoint is
 * in, but is not itself a reachable point and cannot distinguish two
 * checkpoints that happen to sit in the same region. A checkpoint needs an
 * actual position (this type) — see {@link PathfinderCheckpoint}.
 *
 * <p>MINECRAFT-403: arrival is "enters the checkpoint's own Minecraft chunk
 * (16x16 blocks), in the correct dimension, at any Y" — not a reach-radius
 * proximity check against a specific block position. {@code y} is kept only
 * because this type doubles as a representative point for presentation
 * (map/HUD marker) and as the real teleport target GameTests use; {@link
 * #sameChunkAs} and arrival checks never read it.
 */
public record PathfinderPosition(String dimensionId, double x, double y, double z) {
    public PathfinderPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("position coordinates must be finite: (" + x + ", " + y + ", " + z + ")");
        }
    }

    /** Same Minecraft chunk (16x16-block cell) on X/Z, correct for negative coordinates. Ignores Y. */
    public boolean sameChunkAs(PathfinderPosition other) {
        return chunkCoord(x) == chunkCoord(other.x) && chunkCoord(z) == chunkCoord(other.z);
    }

    private static int chunkCoord(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 16);
    }

    public boolean sameDimensionAs(PathfinderPosition other) {
        return dimensionId.equals(other.dimensionId);
    }
}
