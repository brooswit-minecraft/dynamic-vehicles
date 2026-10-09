package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Pure, Minecraft-free math for enumerating structure placement regions
 * outward from an origin in square distance rings, and for shuffling the
 * regions within a ring deterministically. Nothing here touches a server,
 * a level, or any world-generation API, so it is unit-testable on its own.
 */
public final class RingMath {

    private RingMath() {
    }

    /** Chebyshev (square-ring) distance between two regions. */
    public static int ringOf(RegionCoord origin, RegionCoord region) {
        return Math.max(Math.abs(region.x() - origin.x()), Math.abs(region.z() - origin.z()));
    }

    /**
     * All regions at exactly the given ring distance from {@code origin}, in a
     * fixed deterministic base order (independent of any seed). Ring 0 is the
     * origin's own region. The caller is expected to shuffle this with
     * {@link #shuffleRing} before using it as a search order.
     */
    public static List<RegionCoord> regionsInRing(RegionCoord origin, int ring) {
        if (ring < 0) {
            throw new IllegalArgumentException("ring must be >= 0: " + ring);
        }
        List<RegionCoord> regions = new ArrayList<>();
        if (ring == 0) {
            regions.add(origin);
            return regions;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                    regions.add(new RegionCoord(origin.x() + dx, origin.z() + dz));
                }
            }
        }
        return regions;
    }

    /**
     * Deterministically shuffles {@code regions} (a single ring's worth) given
     * a seed and that ring's index, so repeated calls with the same inputs
     * always produce the same order, and different rings of the same seed get
     * independent orders (no shared bias toward one compass direction).
     */
    public static List<RegionCoord> shuffleRing(List<RegionCoord> regions, long seed, int ring) {
        List<RegionCoord> shuffled = new ArrayList<>(regions);
        Random random = new Random(mixSeed(seed, ring));
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            RegionCoord tmp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, tmp);
        }
        return shuffled;
    }

    /**
     * Combines the world seed and a ring index into a single shuffle seed.
     * Uses the standard 64-bit splitmix finalizer so distinct ring indices
     * decorrelate even though they differ by only a small integer.
     */
    private static long mixSeed(long seed, int ring) {
        long z = seed + (long) ring * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The search order a destination-discovery service should actually use:
     * every region from ring 1 up to {@code maxRing} inclusive, each ring
     * internally shuffled, rings themselves always visited nearest-first. Ring
     * 0 (the origin's own region, i.e. the source village) is always excluded
     * — it is never a valid destination for itself, and excluding it here
     * once removes the need for every caller to special-case or dedup it.
     */
    public static List<RegionCoord> destinationRingsUpTo(RegionCoord origin, int maxRing, long seed) {
        if (maxRing < 0) {
            throw new IllegalArgumentException("maxRing must be >= 0: " + maxRing);
        }
        List<RegionCoord> ordered = new ArrayList<>();
        for (int ring = 1; ring <= maxRing; ring++) {
            ordered.addAll(shuffleRing(regionsInRing(origin, ring), seed, ring));
        }
        return ordered;
    }

    /**
     * The slot-count-stop / range-exhaustion search loop a destination
     * lookup should use (acceptance criterion #6), factored out here —
     * Minecraft-free, so it stays unit-testable without a server even
     * though its caller (confirming a candidate) is not — as an injectable
     * seam: walks {@code regions} in order, calling {@code confirm} on each
     * and keeping only the non-null results, stopping as soon as
     * {@code slots} results have been collected or {@code regions} runs
     * out — whichever comes first.
     */
    public static <T> List<T> collectUpToSlots(List<RegionCoord> regions, int slots, Function<RegionCoord, T> confirm) {
        List<T> found = new ArrayList<>();
        for (RegionCoord region : regions) {
            if (found.size() >= slots) {
                break;
            }
            T result = confirm.apply(region);
            if (result != null) {
                found.add(result);
            }
        }
        return found;
    }
}
