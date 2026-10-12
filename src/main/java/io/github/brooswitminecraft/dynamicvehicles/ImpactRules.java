package io.github.brooswitminecraft.dynamicvehicles;

/** Pure eligibility rules for material and entity impacts. */
public final class ImpactRules {
    /** The existing collision response removes 70% of forward speed per solid impact. */
    static final double SOLID_IMPACT_SPEED_SCALE = 0.3;

    private ImpactRules() {}

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

    static double afterSolidImpact(double speed) {
        return speed * SOLID_IMPACT_SPEED_SCALE;
    }
}
