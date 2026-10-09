package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * Client to server: the player selected a row in the {@link DispatcherOfferMenu}
 * (MINECRAFT-109 AC6). {@code offerIndex} is only ever interpreted against
 * the player's own currently-open {@link DispatcherOfferMenu} — never a
 * client-supplied container id — so a stale or forged index can only fail
 * the bounds check, never touch another player's session.
 */
public record DispatcherOfferAcceptPayload(int offerIndex) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DispatcherOfferAcceptPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "dispatcher_offer_accept"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DispatcherOfferAcceptPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, DispatcherOfferAcceptPayload::offerIndex, DispatcherOfferAcceptPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DispatcherOfferAcceptPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof DispatcherOfferMenu menu) {
                menu.acceptOffer(player, payload.offerIndex());
            }
        });
    }
}
