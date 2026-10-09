package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * Client-side holder of the last {@link DeliveryHudPayload} the server
 * sent (MINECRAFT-110 AC8: the HUD renders only what the server sent,
 * never anything the client computed about contract state itself).
 * {@link ContractHudOverlay} reads this every frame to draw direction,
 * distance and remaining time; only this field is mutated, by the network
 * handler thread-confined to the client's main thread via
 * {@code IPayloadContext#enqueueWork}.
 */
final class ClientContractHud {
    private static volatile DeliveryHudPayload current = DeliveryHudPayload.inactive();

    private ClientContractHud() {
    }

    static void update(DeliveryHudPayload payload) {
        current = payload;
    }

    static DeliveryHudPayload current() {
        return current;
    }
}
