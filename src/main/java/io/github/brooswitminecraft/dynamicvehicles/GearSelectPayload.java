package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: an optional physical shifter selected one explicit gear state. */
public record GearSelectPayload(CarPhysics.Gear gear) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<GearSelectPayload> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "gear_select"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GearSelectPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(id -> CarPhysics.Gear.values()[id], CarPhysics.Gear::ordinal), GearSelectPayload::gear, GearSelectPayload::new);

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GearSelectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == player) car.selectGear(payload.gear());
        });
    }
}
