package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the wheel's shifter paddles (MINECRAFT-167). Honk and handbrake are independent of each
 * other and, while a wheel is active, independent of the jump key too -- the client suppresses jump-driven
 * honk/handbrake itself and reports the paddle state here instead, since rider.jumping is the only input
 * field that already reaches the server through vanilla player-movement syncing.
 */
public record WheelPaddlesPayload(boolean honk, boolean handbrake) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WheelPaddlesPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "wheel_paddles"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WheelPaddlesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, WheelPaddlesPayload::honk,
            ByteBufCodecs.BOOL, WheelPaddlesPayload::handbrake,
            WheelPaddlesPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WheelPaddlesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player) {
                car.setWheelPaddles(payload.honk(), payload.handbrake());
            }
        });
    }
}
