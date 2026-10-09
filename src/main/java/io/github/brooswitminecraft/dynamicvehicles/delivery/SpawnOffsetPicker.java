package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.random.RandomGenerator;

/**
 * Pure placement math for one ambush spawn, relative to the traveling
 * entity's heading (MINECRAFT-130 AC2, epic AC2: "near or ahead of the
 * traveling player - ahead matters, since the player is moving"). Takes a
 * forward vector rather than a yaw angle so the thin Minecraft layer can
 * hand it either the player's own look direction or, when the player is
 * riding a car, the car's heading, without this class knowing Minecraft's
 * yaw convention at all. No {@code Vec3}/{@code BlockPos} anywhere, so a
 * seeded RNG test can assert the returned offset's angle against the
 * heading directly (AC7c).
 */
public final class SpawnOffsetPicker {

    private SpawnOffsetPicker() {
    }

    /**
     * @param forwardX heading's X component (need not be normalized; the zero vector falls back to +Z)
     * @param forwardZ heading's Z component
     * @param minDistance nearest the offset may land, in blocks
     * @param maxDistance farthest the offset may land, in blocks; must be &gt; minDistance
     * @param arcRadians total angular spread centered on heading the offset may land within
     * @param random source of randomness; draws exactly two doubles
     */
    public static SpawnOffset pick(double forwardX, double forwardZ, double minDistance, double maxDistance, double arcRadians, RandomGenerator random) {
        double baseAngle = (forwardX == 0.0 && forwardZ == 0.0) ? Math.PI / 2 : Math.atan2(forwardZ, forwardX);
        double jitter = (random.nextDouble() - 0.5) * arcRadians;
        double angle = baseAngle + jitter;
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        return new SpawnOffset(Math.cos(angle) * distance, Math.sin(angle) * distance);
    }

    /** @return the absolute angle (radians, 0..PI) between heading {@code (forwardX, forwardZ)} and offset vector {@code (dx, dz)} */
    public static double angleFromHeading(double forwardX, double forwardZ, double dx, double dz) {
        double headingAngle = Math.atan2(forwardZ, forwardX);
        double offsetAngle = Math.atan2(dz, dx);
        double delta = Math.abs(headingAngle - offsetAngle) % (2 * Math.PI);
        return delta > Math.PI ? 2 * Math.PI - delta : delta;
    }
}
