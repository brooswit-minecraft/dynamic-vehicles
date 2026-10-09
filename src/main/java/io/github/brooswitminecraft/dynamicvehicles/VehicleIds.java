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
}
