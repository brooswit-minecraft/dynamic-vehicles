package io.github.brooswitminecraft.dynamicvehicles.town;

/**
 * Pure, Minecraft-free deterministic hashing shared by every Dynamic Towns planning class. Uses
 * the same 64-bit splitmix finalizer {@code delivery.RingMath} already uses, so two independent
 * callers that mix the same inputs always land on the same output — which is exactly the property
 * road-edge agreement between neighbouring regions depends on.
 */
public final class TownSeedMath {
    private TownSeedMath() {}

    public static long mix(long seed, long... parts) {
        long z = seed;
        for (long part : parts) {
            z = splitmix(z ^ part);
        }
        return z;
    }

    private static long splitmix(long z) {
        z = (z + 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A hashed value folded into [0, bound). Mirrors {@code Math.floorMod} semantics for negative hashes. */
    public static int bounded(long hash, int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be > 0: " + bound);
        }
        return Math.floorMod(hash, bound);
    }

    /** A hashed value folded into a signed offset range [-range, range] inclusive. */
    public static int boundedOffset(long hash, int range) {
        if (range < 0) {
            throw new IllegalArgumentException("range must be >= 0: " + range);
        }
        return bounded(hash, 2 * range + 1) - range;
    }
}
