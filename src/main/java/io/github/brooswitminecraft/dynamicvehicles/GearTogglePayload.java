package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the rider pressed the explicit reverse-gear key (MINECRAFT-228). The server toggles
 * between reverse and the default forward gear -- see {@link CarEntity#toggleGear()}.
 */
public record GearTogglePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GearTogglePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "gear_toggle"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GearTogglePayload> STREAM_CODEC = StreamCodec.unit(new GearTogglePayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GearTogglePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player) {
                String label = car.toggleGear();
                player.displayClientMessage(Component.literal("Gear: " + label), true);
            }
        });
    }
}
