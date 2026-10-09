package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure logic behind {@code CarEntity.readAdditionalSaveData}: whether a saved entity's NBT carries a
 * persisted body orientation, and if so what it is. These four keys (OrientationX/Y/Z/W) are, and always
 * have been, the ONLY thing {@code CarEntity} ever saved about itself before MINECRAFT-170 made vehicles
 * multi-seat (MINECRAFT-183) &mdash; no seat count or layout was ever part of this tag, so a pre-change
 * entity carries either all four keys or none of them, never a partial or seat-related one. Kept free of
 * Minecraft types (CompoundTag etc.) so it can be unit tested directly, the same seam
 * {@link SeatAssignment} already uses.
 */
final class OrientationNbt {
    private OrientationNbt() {}

    record Orientation(float x, float y, float z, float w) {}

    /**
     * Mirrors the real tag's own gate: {@code CarEntity} only trusts the four fields once "OrientationW" is
     * present (its own write side always writes all four together, so that one key standing for the whole
     * group is exact, not a heuristic). Returns {@code null} for old/empty data, exactly as "do nothing,
     * leave savedOrientation null" already behaved before this extraction.
     */
    static Orientation read(boolean hasOrientationW, float x, float y, float z, float w) {
        return hasOrientationW ? new Orientation(x, y, z, w) : null;
    }
}
