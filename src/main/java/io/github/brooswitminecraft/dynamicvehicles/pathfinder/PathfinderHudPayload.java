package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;

/**
 * Server to client: the player's active-attempt HUD state, server-authoritative, mirroring
 * {@code delivery.DeliveryHudPayload}'s shape and the same reasoning: carries only the next
 * checkpoint's position and the deadline tick; direction and remaining-time countdown are
 * computed client-side every frame from the player's own position/yaw and the client level's
 * own game time. Sent on start, on each checkpoint advance (the destination changes), on
 * complete/expire ({@code active = false}, which clears the HUD), and on login if a persisted
 * attempt is already active — never every tick.
 */
public record PathfinderHudPayload(
        boolean active,
        String dimension,
        double nextX,
        double nextY,
        double nextZ,
        int reachedCount,
        int totalCheckpoints,
        long deadlineTick
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PathfinderHudPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "pathfinder_hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PathfinderHudPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBoolean(payload.active());
                buf.writeUtf(payload.dimension());
                buf.writeDouble(payload.nextX());
                buf.writeDouble(payload.nextY());
                buf.writeDouble(payload.nextZ());
                buf.writeVarInt(payload.reachedCount());
                buf.writeVarInt(payload.totalCheckpoints());
                buf.writeVarLong(payload.deadlineTick());
            },
            buf -> new PathfinderHudPayload(
                    buf.readBoolean(), buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));

    public static PathfinderHudPayload inactive() {
        return new PathfinderHudPayload(false, "", 0.0, 0.0, 0.0, 0, 0, 0L);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PathfinderHudPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PathfinderClientHud.update(payload));
    }
}
