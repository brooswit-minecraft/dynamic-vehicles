package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * Server to client (MINECRAFT-110 AC4, AC8): the player's active-contract
 * HUD state, server-authoritative. Sent once on accept, once on
 * complete/expire (with {@code active = false}, which clears the HUD), and
 * once on login/respawn if a persisted contract is already active - never
 * every tick. Carries only the destination's world position and the
 * deadline tick; direction and the remaining-time countdown are computed
 * client-side every frame from the player's own position/yaw and the
 * client level's own game time, exactly so a continuous HUD needs no
 * per-tick resync (AC8: "the HUD renders what the server sent" - the
 * server sends the destination and deadline; the client only redraws from
 * them).
 *
 * <p>Nine primitive/String fields exceed {@link StreamCodec#composite}'s
 * six-argument overloads, so this uses {@link StreamCodec#of} directly,
 * hand-mirroring {@link DeliveryContractCodec}'s field list.
 */
public record DeliveryHudPayload(
        boolean active,
        String destinationDimension,
        double destinationX,
        double destinationZ,
        long deadlineTick,
        double danger,
        double dangerMin,
        double dangerMax
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DeliveryHudPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "delivery_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeliveryHudPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.active());
                buf.writeUtf(payload.destinationDimension());
                buf.writeDouble(payload.destinationX());
                buf.writeDouble(payload.destinationZ());
                buf.writeVarLong(payload.deadlineTick());
                buf.writeDouble(payload.danger());
                buf.writeDouble(payload.dangerMin());
                buf.writeDouble(payload.dangerMax());
            },
            buf -> new DeliveryHudPayload(
                    buf.readBoolean(), buf.readUtf(), buf.readDouble(), buf.readDouble(),
                    buf.readVarLong(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    /** The HUD-cleared payload sent on complete/expire. */
    public static DeliveryHudPayload inactive() {
        return new DeliveryHudPayload(false, "", 0.0, 0.0, 0L, 0.0, 0.0, 0.0);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeliveryHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientContractHud.update(payload));
    }
}
