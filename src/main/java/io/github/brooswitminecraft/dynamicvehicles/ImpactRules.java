package io.github.brooswitminecraft.dynamicvehicles;

/** Pure eligibility rules for material and entity impacts. */
public final class ImpactRules {
    /** The existing collision response removes 70% of forward speed per solid impact. */
    static final double SOLID_IMPACT_SPEED_SCALE = 0.3;

    /** A collision report never claims more sliding than this (m/s), so one crash cannot outweigh a sustained burnout. */
    static final double MAX_IMPACT_SLIP = 10.0;

    private ImpactRules() {}

    /** The slip speed a collision reports to terrain: the speed the car hit at, capped at {@link #MAX_IMPACT_SLIP}. */
    static double impactSlip(double speedBefore) {
        return Math.max(0.0, Math.min(speedBefore, MAX_IMPACT_SLIP));
    }

    enum BlockImpactRoute {
        TERRAIN_EROSION,
        TRUCK_BAMBOO,
        DEFAULT
    }

    /** A moving car detonates a creeper on contact; the floor only keeps a parked car from being a trigger. */
    static final double CREEPER_TRIGGER_MIN_SPEED = 0.5;

    public static boolean creeperExplosionEligible(double speed) {
        return Math.abs(speed) >= CREEPER_TRIGGER_MIN_SPEED;
    }

    /** A creeper contact is meaningful only at the existing hard-impact threshold and outside the cooldown. */
    public static boolean creeperExplosionEligible(double speed, double minImpactSpeed, boolean coolingDown) {
        return !coolingDown && speed >= minImpactSpeed;
    }

    /** Below this speed (m/s) a hostile mob is merely nudged by the car, not hurt or flung. */
    static final double MOB_HIT_MIN_SPEED = 3.0;
    static final double MOB_HIT_DAMAGE_PER_SPEED = 0.75;
    static final double MOB_HIT_MAX_DAMAGE = 20.0;
    static final double MOB_HIT_KNOCKBACK_BASE = 0.4;
    static final double MOB_HIT_KNOCKBACK_PER_SPEED = 0.03;

    /** A hostile mob is hurt only by a moving car, and only outside the shared impact cooldown's own rule. */
    public static boolean hostileMobHitEligible(double speed) {
        return Math.abs(speed) >= MOB_HIT_MIN_SPEED;
    }

    public static float hostileMobHitDamage(double speed) {
        return (float) Math.min(MOB_HIT_MAX_DAMAGE, Math.abs(speed) * MOB_HIT_DAMAGE_PER_SPEED);
    }

    /** Horizontal knockback strength: heavier hits at higher speed. */
    public static double hostileMobKnockback(double speed) {
        return MOB_HIT_KNOCKBACK_BASE + Math.abs(speed) * MOB_HIT_KNOCKBACK_PER_SPEED;
    }

    /**
     * Which side of the car a mob is on: +1 along the car's perpendicular {@code (-fz, fx)}, -1 opposite.
     * Positive means the mob lies on the {@code (-fz, fx)} side of the forward axis. A mob dead ahead
     * (zero lateral offset) is sent to the positive side so it is never knocked straight back into the car.
     */
    public static int lateralSide(double forwardX, double forwardZ, double offsetX, double offsetZ) {
        double lateral = -forwardZ * offsetX + forwardX * offsetZ;
        return lateral < 0.0 ? -1 : 1;
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
