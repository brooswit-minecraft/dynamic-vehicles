package io.github.brooswitminecraft.dynamicvehicles;

/** Pure eligibility rules for material and entity impacts. */
public final class ImpactRules {
    /** The existing collision response removes 70% of forward speed per solid impact. */
    static final double SOLID_IMPACT_SPEED_SCALE = 0.3;

    private ImpactRules() {}

    enum BlockImpactRoute {
        TERRAIN_EROSION,
        TRUCK_BAMBOO,
        DEFAULT
    }

    /** A creeper contact is meaningful only at the existing hard-impact threshold and outside the cooldown. */
    public static boolean creeperExplosionEligible(double speed, double minImpactSpeed, boolean coolingDown) {
        return !coolingDown && speed >= minImpactSpeed;
    }

    /** Only the ordinary truck is allowed to clear bamboo; all other block choices stay on their own paths. */
    public static boolean truckMayBreakBamboo(boolean truck, boolean bamboo, boolean breakable) {
        return truck && bamboo && breakable;
    }

    /** Panes and other passable glass must not be smashed merely by passing through their space. */
    public static boolean glassMayBreak(boolean glass, boolean solidImpact) {
        return !glass || solidImpact;
    }

    /** Dirt and sand are terrain material: collision reports abuse to Dynamic Terrain rather than deleting it. */
    public static boolean usesTerrainErosion(boolean dirtOrSand) {
        return dirtOrSand;
    }

    /**
     * Select the special block-impact path before collision code considers a truck-only return.
     * Loose terrain is deliberately first: it is handled by Dynamic Terrain for every vehicle,
     * including a truck.
     */
    static BlockImpactRoute blockImpactRoute(boolean truck, boolean dirtOrSand, boolean bamboo, boolean breakable) {
        if (usesTerrainErosion(dirtOrSand)) {
            return BlockImpactRoute.TERRAIN_EROSION;
        }
        if (truckMayBreakBamboo(truck, bamboo, breakable)) {
            return BlockImpactRoute.TRUCK_BAMBOO;
        }
        return BlockImpactRoute.DEFAULT;
    }

    static double afterSolidImpact(double speed) {
        return speed * SOLID_IMPACT_SPEED_SCALE;
    }
}
