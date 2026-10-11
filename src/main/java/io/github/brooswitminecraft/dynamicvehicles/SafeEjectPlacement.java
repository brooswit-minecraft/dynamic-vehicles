package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure candidate-offset search for a safe spot to eject a mob passenger (MINECRAFT-249): never inside a
 * block, never far from the vehicle. Minecraft-type-free so it can be unit tested directly with a fake
 * {@code isFree} predicate; {@code CarEntity} is the only caller, supplying a real world-collision check.
 */
public final class SafeEjectPlacement {

    /** How far, in blocks, {@link #find} will search before giving up -- "never teleported far". */
    public static final int SEARCH_RADIUS_BLOCKS = 3;

    /** Offsets (x, y, z) around the origin, nearest ring first. */
    private static final double[][] OFFSETS = buildOffsets();

    private SafeEjectPlacement() {}

    /**
     * The first of a small ring of candidate points around ({@code originX}, {@code originY}, {@code
     * originZ}) -- same height as the vehicle, out to {@link #SEARCH_RADIUS_BLOCKS} blocks horizontally,
     * nearest first -- that {@code isFree} accepts. If no nearby point is safe, the origin is checked as
     * a last resort; if it is unsafe too, {@code null} is returned and the caller must defer ejection.
     */
    public static double[] find(double originX, double originY, double originZ, Predicate<double[]> isFree) {
        for (double[] offset : OFFSETS) {
            double[] candidate = {originX + offset[0], originY + offset[1], originZ + offset[2]};
            if (isFree.test(candidate)) {
                return candidate;
            }
        }
        double[] origin = {originX, originY, originZ};
        return isFree.test(origin) ? origin : null;
    }

    private static double[][] buildOffsets() {
        List<double[]> offsets = new ArrayList<>();
        for (int radius = 1; radius <= SEARCH_RADIUS_BLOCKS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Only this ring's own perimeter -- a smaller radius already covered every inner point,
                    // and visiting it again would try the same point twice and break "nearest first".
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    offsets.add(new double[] {dx, 0.0, dz});
                }
            }
        }
        return offsets.toArray(new double[0][]);
    }
}
