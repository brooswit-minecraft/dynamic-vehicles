package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

/**
 * Client-side holder of the last {@link PathfinderHudPayload} the server sent, mirroring {@code
 * delivery.ClientContractHud}: the HUD renders only what the server sent, never anything the
 * client computed about attempt state itself.
 */
final class PathfinderClientHud {
    private static volatile PathfinderHudPayload current = PathfinderHudPayload.inactive();

    private PathfinderClientHud() {
    }

    static void update(PathfinderHudPayload payload) {
        current = payload;
    }

    static PathfinderHudPayload current() {
        return current;
    }
}
