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
            onFinished(player, storage, record);
        } else if (progress.isExpired()) {
            onExpired(player, storage);
        } else if (advanced) {
            PathfinderAttemptRecord updated = record.withProgress(progress);
            storage.update(updated);
            PathfinderAttempts.sendHud(player, updated, route);
        }
    }

    private static void onFinished(ServerPlayer player, PathfinderAttemptStorage storage, PathfinderAttemptRecord record) {
        // Clear (and persist the removal) BEFORE granting the reward - see PathfinderAttemptStorage#clear.
        storage.clear(player.getUUID());
        PathfinderAttempts.grantReward(player, record.level());
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.completed",
                PathfinderConfig.rewardEmeralds(record.level())));
        PathfinderAttempts.clearHud(player);
    }

    private static void onExpired(ServerPlayer player, PathfinderAttemptStorage storage) {
        storage.clear(player.getUUID());
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.expired"));
        PathfinderAttempts.clearHud(player);
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey(String dimensionId) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(dimensionId));
    }
}
