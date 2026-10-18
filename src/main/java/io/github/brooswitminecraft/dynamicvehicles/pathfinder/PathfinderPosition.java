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
 * actual position (this type) plus a reach radius — see {@link
 * PathfinderCheckpoint}.
 */
public record PathfinderPosition(String dimensionId, double x, double y, double z) {
    public PathfinderPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("position coordinates must be finite: (" + x + ", " + y + ", " + z + ")");
        }
    }

    public double distanceSquaredTo(PathfinderPosition other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean sameDimensionAs(PathfinderPosition other) {
        return dimensionId.equals(other.dimensionId);
    }
}
