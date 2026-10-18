package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;

import io.github.brooswitminecraft.dynamicvehicles.PathfinderServerConfig;

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
            // Review follow-up: a dead player awaiting respawn must not advance or complete an
            // attempt on a tick that lands before onLivingDeath/onPlayerRespawn has cleared it.
            if (!player.isAlive()) {
                continue;
            }
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
            if (dimension == null) {
                return;
            }
            PathfinderRouteStorage.of(player.server).routeFor(dimension, record.origin(), record.level())
                    .ifPresent(route -> PathfinderAttempts.sendHud(player, record, route));
        });
    }

    /**
     * The selected death policy (MINECRAFT-379 review follow-up): death fails the attempt
     * outright, no partial credit, AT THE MOMENT OF DEATH — not deferred to respawn, which is too
     * late (a dead player awaiting respawn is still evaluated by {@link #onServerTick} on every
     * tick in between unless cleared here first; the {@code isAlive()} guard there is defense in
     * depth, not the primary fix). {@link #onPlayerRespawn} keeps the end-portal distinction as a
     * second, harmless no-op path: by the time any real death's respawn fires, this handler has
     * already cleared the attempt, so that call just finds nothing active.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        failActiveAttempt(player, "message.dynamicvehicles.pathfinder.died");
    }

    /** Catches the (expected to be rare/impossible given {@link #onLivingDeath}) case of a respawn with no matching prior death event. */
    @SubscribeEvent
    public static void onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        failActiveAttempt(player, "message.dynamicvehicles.pathfinder.died");
    }

    private static void failActiveAttempt(ServerPlayer player, String messageKey) {
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        if (storage.active(player.getUUID()).isEmpty()) {
            return;
        }
        storage.clear(player.getUUID());
        forceSave(player.server);
        player.sendSystemMessage(Component.translatable(messageKey));
        PathfinderAttempts.clearHud(player);
    }

    private static void evaluate(MinecraftServer server, PathfinderAttemptStorage storage, ServerPlayer player, PathfinderAttemptRecord record) {
        ServerLevel dimension = server.getLevel(dimensionKey(record.dimensionId()));
        if (dimension == null) {
            return;
        }
        Optional<PathfinderRoute> maybeRoute = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level());
        if (maybeRoute.isEmpty()) {
            // Structurally unexpected (the route was already generated and cached to start this
            // attempt), so just skip this evaluation rather than fail the attempt outright.
            return;
        }
        PathfinderRoute route = maybeRoute.get();
        PathfinderProgress progress = new PathfinderProgress(route, record.startTick(), record.timeLimitTicks(),
                record.reachedCount(), record.finished(), record.expired());

        long now = server.overworld().getGameTime();
        var pos = player.position();
        String playerDimension = player.level().dimension().location().toString();
        PathfinderPosition playerPosition = new PathfinderPosition(playerDimension, pos.x, pos.y, pos.z);

        boolean advanced = progress.onReach(playerPosition, now, PathfinderServerConfig.REACH_RADIUS.get());

        if (progress.isFinished()) {
            onFinished(server, player, storage, record);
        } else if (progress.isExpired()) {
            onExpired(server, player, storage);
        } else if (advanced) {
            PathfinderAttemptRecord updated = record.withProgress(progress);
            storage.update(updated);
            PathfinderAttempts.sendHud(player, updated, route);
        }
    }

    private static void onFinished(MinecraftServer server, ServerPlayer player,
                                    PathfinderAttemptStorage storage, PathfinderAttemptRecord record) {
        // Clear and force-save BEFORE granting the reward - see PathfinderAttemptStorage's class javadoc.
        storage.clear(player.getUUID());
        forceSave(server);
        PathfinderAttempts.recordVillagerCompletion(record, server);
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

    /**
     * A real persistence barrier, not just a narrowed window (review follow-up: scheduling an
     * async save and returning does not establish durability before payout). {@code
     * DimensionDataStorage#save()} only QUEUES each dirty {@code SavedData}'s write onto
     * NeoForge's shared IO-worker queue ({@code IOUtilities#withIOWorker}); {@code
     * IOUtilities#waitUntilIOWorkerComplete()} then blocks this thread until every queued write —
     * including the one just queued — has actually finished (that write is itself atomic: {@code
     * IOUtilities#atomicWrite} writes to a temp file, {@code force(true)}s it, then renames it
     * into place). By the time this method returns, the cleared attempt is durably on disk; a
     * crash after that point cannot reload the old, still-active record.
     */
    private static void forceSave(MinecraftServer server) {
        server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey(String dimensionId) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(dimensionId));
    }
}
