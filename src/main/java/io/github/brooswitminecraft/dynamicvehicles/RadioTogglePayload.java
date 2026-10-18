package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the rider pressed the radio key (MINECRAFT-394). The server cycles the car's station
 * selection -- Off, then each server-configured station in order, then back to Off.
 */
public record RadioTogglePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RadioTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "radio_toggle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RadioTogglePayload> STREAM_CODEC = StreamCodec.unit(new RadioTogglePayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RadioTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player) {
                String label = car.cycleRadio();
                player.displayClientMessage(Component.literal("Radio: " + label), true);
            }
        });
    }
}
