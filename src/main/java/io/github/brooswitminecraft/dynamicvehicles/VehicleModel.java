package io.github.brooswitminecraft.dynamicvehicles;

/**
 * MINECRAFT-443 review fix (comment 48829): the production selection {@link CarRenderer#render} and
 * {@code CarEntity#hitBlockAhead} actually use, extracted into one pure, headlessly-testable function
 * ({@link #forId}) so the mapping from a car's stable registry id (see {@link VehicleIds}, as returned by
 * {@code CarEntity#vehicleId()}) to "which model/collision branch applies" can be verified directly,
 * without needing to execute either (client-only) rendering or (Sable-coupled) collision code.
 *
 * <p>Deliberately keyed on {@link String} id, never on {@link VehicleSpec} identity or equality: that is
 * the exact bug this extraction guards against reintroducing (an override file changes which {@code
 * VehicleSpec} instance a car carries, but never its id).
 */
enum VehicleModel {
    CAR, TRUCK, TROPHY, DRIFT, MUSCLE, ROCK_CRAWLER, MONSTER_TRUCK, INDY, BUS, CARGO_TRUCK,
    /** Any id this mapping does not recognise -- the same renderer fallback every future, not-yet-given-a-
     * silhouette car id used before this ticket. */
    FALLBACK;

    static VehicleModel forId(String vehicleId) {
        return switch (vehicleId) {
            case VehicleIds.CAR -> CAR;
            case VehicleIds.TRUCK -> TRUCK;
            case VehicleIds.TROPHY_TRUCK -> TROPHY;
            case VehicleIds.DRIFT_CAR -> DRIFT;
            case VehicleIds.MUSCLE_CAR -> MUSCLE;
            case VehicleIds.ROCK_CRAWLER -> ROCK_CRAWLER;
            case VehicleIds.MONSTER_TRUCK -> MONSTER_TRUCK;
            case VehicleIds.INDY_CAR -> INDY;
            case VehicleIds.BUS -> BUS;
            case VehicleIds.CARGO_TRUCK -> CARGO_TRUCK;
            default -> FALLBACK;
        };
    }
}
