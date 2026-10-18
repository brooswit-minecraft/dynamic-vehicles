package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Minecraft-free math for enumerating the regions in a Pathfinder ring
 * and ordering them into a single closed circuit. Deliberately independent
 * of {@code delivery.RingMath}: that class's ring enumeration is for
 * Dispatcher's random destination search (shuffled order, seeded by world
 * seed + ring index), while a Pathfinder circuit needs a spatially coherent
 * loop so the route is an actual, sensible course to run — not a shuffled
 * visitation order. Keeping the two separate also keeps Pathfinder's region
 * math independent of the Dispatcher package, per the ticket's "keep race
 * mechanics separable" requirement.
 */
public final class PathfinderRingMath {

    private PathfinderRingMath() {
    }

    /** Chebyshev (square-ring) distance between two regions. */
    public static int ringOf(PathfinderRegion origin, PathfinderRegion region) {
        return Math.max(Math.abs(region.x() - origin.x()), Math.abs(region.z() - origin.z()));
    }

    /** All regions at exactly the given ring distance from {@code origin}. Ring 0 is the origin itself. */
    public static List<PathfinderRegion> regionsInRing(PathfinderRegion origin, int ring) {
        if (ring < 0) {
            throw new IllegalArgumentException("ring must be >= 0: " + ring);
        }
        List<PathfinderRegion> regions = new ArrayList<>();
        if (ring == 0) {
            regions.add(origin);
            return regions;
        }
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                    regions.add(new PathfinderRegion(origin.x() + dx, origin.z() + dz));
                }
            }
        }
        return regions;
    }

    /**
     * Orders a ring's regions into a single deterministic loop around
     * {@code origin}, swept by angle starting due east and going clockwise
     * (looking down, +x east / +z south). This is a pure function of the
     * region coordinates — no seed, no villager/player identity — so the
     * same origin region and ring always produce the same circuit, exactly
     * as the ticket requires ("seed route identity with origin-region
     * identity, not individual villager/player identity").
     *
     * <p>Uses integer cross-product comparison (no floating-point angle),
     * so there is no precision edge case to worry about. No two distinct
     * cells on the same Chebyshev ring can be collinear through the origin
     * (that would require one to be a positive scalar multiple of the
     * other, which forces them to be equal), so every region on the ring
     * gets a strictly distinct position in the order.
     */
    public static List<PathfinderRegion> circuitOrder(PathfinderRegion origin, int ring) {
        List<PathfinderRegion> regions = new ArrayList<>(regionsInRing(origin, ring));
        regions.sort((a, b) -> compareAngleClockwiseFromEast(
                a.x() - origin.x(), a.z() - origin.z(),
                b.x() - origin.x(), b.z() - origin.z()));
        return regions;
    }

    private static int halfPlane(int dx, int dz) {
        // Half 0: due east sweeping clockwise through south to due west (exclusive).
        // Half 1: due west (inclusive) sweeping clockwise through north back to east (exclusive).
        if (dz > 0) {
            return 0;
        }
        if (dz < 0) {
            return 1;
        }
        return dx >= 0 ? 0 : 1;
    }

    private static int compareAngleClockwiseFromEast(int dx1, int dz1, int dx2, int dz2) {
        int half1 = halfPlane(dx1, dz1);
        int half2 = halfPlane(dx2, dz2);
        if (half1 != half2) {
            return half1 - half2;
        }
        // cross > 0 means (dx1,dz1) is clockwise-before (dx2,dz2) within this half-plane.
        long cross = (long) dx1 * dz2 - (long) dx2 * dz1;
        return -Long.signum(cross);
    }
}
