package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * A plain 2D world-space offset (blocks, X/Z plane) picked by
 * {@link SpawnOffsetPicker} for one ambush spawn (MINECRAFT-130 AC2). Y is
 * deliberately absent - the thin Minecraft layer resolves ground height at
 * {@code (ownerX + dx, ownerZ + dz)} itself.
 */
public record SpawnOffset(double dx, double dz) {

    /** @return the straight-line distance this offset places a spawn from the origin */
    public double distance() {
        return Math.sqrt(dx * dx + dz * dz);
    }
}
