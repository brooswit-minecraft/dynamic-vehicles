package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure arithmetic for how the car's environment pushes back on it: pass-through foliage resistance,
 * and fluid buoyancy/drag. Free of Minecraft and Sable types so it can be unit tested.
 */
public final class EnvironmentMath {
    /**
     * Baseline per-wheel foliage drag coefficient, N per (m/s), applied to every pass-through block
     * (empty collision shape, not air) regardless of its own hardness -- "AIR applies none" (MINECRAFT-259)
     * is enforced by the caller never reaching this for an air block, not by this being zero.
     */
    public static final double FOLIAGE_BASELINE = 0.5;
    /**
     * How much one point of a block's vanilla destroy speed (hardness) adds to its drag coefficient, on
     * top of {@link #FOLIAGE_BASELINE}. Hardness is the chosen material-specific weighting (MINECRAFT-259
     * decision comment: Brooswit suggested "durability", i.e. hardness; the worker's own check is that it
     * is the one scalar every pass-through block already carries that is not uniformly the same value
     * across foliage -- unlike friction, light opacity or explosion resistance, which vanilla sets to the
     * same default for nearly every plant).
     */
    public static final double FOLIAGE_HARDNESS_WEIGHT = 1.0;

    /** Fraction of a fully submerged car's weight water counteracts: "substantially", not fully. */
    public static final double WATER_BUOYANCY = 0.85;
    /** Fraction of a fully submerged car's weight lava counteracts: more than water (real lava is denser). */
    public static final double LAVA_BUOYANCY = 1.3;
    /** Extra drag coefficient, N per (m/s)^2, a fully submerged car gets in water, on top of air drag. */
    public static final double WATER_DRAG = 2.0;
    /** Extra drag coefficient a fully submerged car gets in lava: more than water. */
    public static final double LAVA_DRAG = 5.0;

    private EnvironmentMath() {}

    /** One pass-through block's foliage drag coefficient (N per m/s), scaled by its own hardness. */
    public static double foliageDragCoefficient(double hardness) {
        return FOLIAGE_BASELINE + Math.max(0.0, hardness) * FOLIAGE_HARDNESS_WEIGHT;
    }

    /**
     * Upward force (N) counteracting gravity for a car of {@code massKg}, {@code fraction} submerged
     * (0..1, vanilla's own fluid height fraction) in a fluid whose full-submersion buoyancy is
     * {@code buoyancy} times the car's own weight (1.0 = fully counteracts gravity; above 1.0 is net lift).
     */
    public static double buoyantForce(double massKg, double gravity, double fraction, double buoyancy) {
        return massKg * gravity * clamp01(fraction) * buoyancy;
    }

    /** Extra drag coefficient (N per (m/s)^2) a fluid adds on top of air drag, scaled by how submerged the car is. */
    public static double fluidDragCoefficient(double fraction, double fullySubmergedDrag) {
        return clamp01(fraction) * fullySubmergedDrag;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
