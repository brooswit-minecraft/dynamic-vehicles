package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;

/**
 * The coarse server timer that evaluates each online player's active Pathfinder attempt against
 * their live position every {@link #EVALUATE_INTERVAL_TICKS} ticks — mirrors {@code
 * delivery.ContractTickHandler}'s shape. Works on foot or in a vehicle equally (MINECRAFT-379's
 * requirement): this only ever reads {@link ServerPlayer#blockPosition()}/{@link
 * ServerPlayer#level()}, never anything about how the player got there.
 */
public final class PathfinderTickHandler {

    static final int EVALUATE_INTERVAL_TICKS = 20;

    private PathfinderTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVALUATE_INTERVAL_TICKS != 0) {
            return;
        }
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Optional<PathfinderAttemptRecord> active = storage.active(player.getUUID());
            if (active.isEmpty()) {
                continue;
            }
            evaluate(server, storage, player, active.get());
        }
    }

    /** Resyncs the HUD for a player who already has a persisted active attempt (survives logout/relog). */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PathfinderAttemptStorage.of(player.server).active(player.getUUID()).ifPresent(record -> {
            ServerLevel dimension = player.server.getLevel(dimensionKey(record.dimensionId()));
            if (dimension != null) {
                PathfinderRoute route = PathfinderRouteStorage.of(player.server).routeFor(dimension, record.origin(), record.level());
                PathfinderAttempts.sendHud(player, record, route);
            }
        });
    }

    /**
     * The selected death policy (MINECRAFT-379 review follow-up): death fails the attempt
     * outright, no partial credit. {@code PlayerRespawnEvent} fires once the respawn actually
     * happens, which is the point a dead player's attempt should be cleared; {@code
     * isEndConquered()} distinguishes an actual death-respawn from an end-portal "respawn" at the
     * same event, which must NOT fail an attempt.
     */
    @SubscribeEvent
    public static void onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        if (storage.active(player.getUUID()).isEmpty()) {
            return;
        }
        storage.clear(player.getUUID());
        forceSave(player.server);
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.died"));
        PathfinderAttempts.clearHud(player);
    }

    private static void evaluate(MinecraftServer server, PathfinderAttemptStorage storage, ServerPlayer player, PathfinderAttemptRecord record) {
        ServerLevel dimension = server.getLevel(dimensionKey(record.dimensionId()));
        if (dimension == null) {
            return;
        }
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level());
        PathfinderProgress progress = new PathfinderProgress(route, record.startTick(), record.timeLimitTicks(),
                record.reachedCount(), record.finished(), record.expired());

        long now = server.overworld().getGameTime();
        var pos = player.position();
        String playerDimension = player.level().dimension().location().toString();
        PathfinderPosition playerPosition = new PathfinderPosition(playerDimension, pos.x, pos.y, pos.z);

        boolean advanced = progress.onReach(playerPosition, now);

        if (progress.isFinished()) {
            onFinished(server, dimension, player, storage, record);
        } else if (progress.isExpired()) {
            onExpired(server, player, storage);
        } else if (advanced) {
            PathfinderAttemptRecord updated = record.withProgress(progress);
            storage.update(updated);
            PathfinderAttempts.sendHud(player, updated, route);
        }
    }

    private static void onFinished(MinecraftServer server, ServerLevel dimension, ServerPlayer player,
                                    PathfinderAttemptStorage storage, PathfinderAttemptRecord record) {
        // Clear and force-save BEFORE granting the reward - see PathfinderAttemptStorage's class javadoc.
        storage.clear(player.getUUID());
        forceSave(server);
        PathfinderAttempts.recordVillagerCompletion(dimension, record);
        int rewardedEmeralds = PathfinderAttempts.grantReward(player, record.level());
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.completed", rewardedEmeralds));
        PathfinderAttempts.clearHud(player);
    }

    private static void onExpired(MinecraftServer server, ServerPlayer player, PathfinderAttemptStorage storage) {
        storage.clear(player.getUUID());
        forceSave(server);
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.expired"));
        PathfinderAttempts.clearHud(player);
    }

    /** See {@link PathfinderAttemptStorage}'s class javadoc: narrows, does not eliminate, the reward-duplication crash window. */
    private static void forceSave(MinecraftServer server) {
        server.overworld().getDataStorage().save();
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey(String dimensionId) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(dimensionId));
    }
}
