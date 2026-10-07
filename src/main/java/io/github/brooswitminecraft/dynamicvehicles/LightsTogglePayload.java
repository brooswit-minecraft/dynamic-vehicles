package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the rider pressed the headlight key. The server cycles the car's mode (auto, on, off). */
public record LightsTogglePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LightsTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "lights_toggle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LightsTogglePayload> STREAM_CODEC = StreamCodec.unit(new LightsTogglePayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LightsTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player) {
                String label = car.cycleLights();
                player.displayClientMessage(Component.literal("Headlights: " + label), true);
            }
        });
    }
}
