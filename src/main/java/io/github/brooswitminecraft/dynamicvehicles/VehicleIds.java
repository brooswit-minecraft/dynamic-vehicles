package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Registry names (entity type and item) for the four vehicles MINECRAFT-170 turned multi-seat while keeping
 * the same ids. Old worlds hold saved entities and spawn items under exactly these ids, so a rename here
 * would silently orphan that data rather than fail loudly &mdash; kept as named constants, rather than
 * string literals inline in {@link DynamicVehiclesMod}'s registration calls, so {@code
 * VehicleIdsPinTest} can catch a rename without needing the Minecraft/NeoForge bootstrap that class
 * requires to load.
 */
final class VehicleIds {
    private VehicleIds() {}

    static final String CAR = "car";
    static final String TRUCK = "truck";
    static final String TROPHY_TRUCK = "trophy_truck";
    static final String DRIFT_CAR = "drift_car";

    /** MINECRAFT-443: named here (rather than left as the literals {@code DynamicVehiclesMod} already
     * registers them under) so {@link VehicleSpecOverrides} and the model/collision dispatch in
     * {@link CarEntity}/{@link CarRenderer} share exactly one id string per car instead of two
     * independently-typed copies drifting apart. */
    static final String MUSCLE_CAR = "muscle_car";
    static final String ROCK_CRAWLER = "rock_crawler";
    static final String MONSTER_TRUCK = "monster_truck";
    static final String INDY_CAR = "indy_car";
    static final String BUS = "bus";
    static final String CARGO_TRUCK = "cargo_truck";

    /** MINECRAFT-380: a watercraft, a rotor-powered aircraft and a fixed-wing aircraft. */
    static final String MOTORBOAT = "motorboat";
    static final String CHOPPER = "chopper";
    static final String BIPLANE = "biplane";
}
