package io.github.brooswitminecraft.dynamicvehicles;

/**
 * One skid decal: where it sits, which way it's oriented, and when it was spawned. Plain data, no
 * Minecraft types, so {@link SkidMarkRingBuffer} and {@link SkidMarkMath} can be unit tested.
 *
 * @param x world x of the decal's centre
 * @param y world y of the decal's centre (just above the ground, see {@link SkidMarkMath#SURFACE_OFFSET})
 * @param z world z of the decal's centre
 * @param yawRadians the car's heading when this mark was laid, so the quad can be drawn long-ways
 *        along the direction of travel instead of always facing the same way
 * @param spawnTimeMs {@code System.currentTimeMillis()} when this mark was laid
 */
public record SkidMark(double x, double y, double z, float yawRadians, long spawnTimeMs) {
}
