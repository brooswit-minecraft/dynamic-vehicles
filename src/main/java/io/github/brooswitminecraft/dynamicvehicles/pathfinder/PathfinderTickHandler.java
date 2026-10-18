package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

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
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        storage.active(player.getUUID()).ifPresent(record -> markDiedAndClear(player.server, player, storage, record));
    }

    /** Catches the (expected to be rare/impossible given {@link #onLivingDeath}) case of a respawn with no matching prior death event. */
    @SubscribeEvent
    public static void onPlayerRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        storage.active(player.getUUID()).ifPresent(record -> markDiedAndClear(player.server, player, storage, record));
    }

    /**
     * Review follow-up: marks and durably persists {@code died} on the record BEFORE attempting
     * the clear, rather than clearing the original still-"live" record directly. If the clear's
     * durable-save verification then fails, the record that goes back into storage (via {@link
     * #attemptDurableClear}'s retry path) already carries {@code died=true} - so a later tick,
     * even one landing after respawn, recognizes it as terminal via {@link #evaluate}'s {@code
     * record.died()} check instead of advancing or completing it as if it were still live.
     */
    private static void markDiedAndClear(MinecraftServer server, ServerPlayer player, PathfinderAttemptStorage storage, PathfinderAttemptRecord record) {
        PathfinderAttemptRecord diedRecord = record.withDied();
        storage.update(diedRecord);
        attemptDurableClear(server, player, storage, diedRecord, "message.dynamicvehicles.pathfinder.died");
    }

    private static void evaluate(MinecraftServer server, PathfinderAttemptStorage storage, ServerPlayer player, PathfinderAttemptRecord record) {
        // A prior tick already finished/expired/died on this attempt but failed to durably
        // confirm the clear; retry only that clear/verify/(pay) step - do not re-evaluate
        // checkpoints or re-credit progression (see PathfinderAttemptRecord#progressionCredited).
        // died is checked first and unconditionally: a dead player must never fall through to
        // normal checkpoint evaluation below, even on a tick landing after respawn.
        if (record.died()) {
            attemptDurableClear(server, player, storage, record, "message.dynamicvehicles.pathfinder.died");
            return;
        }
        if (record.finished()) {
            attemptDurablePayout(server, player, storage, record);
            return;
        }
        if (record.expired()) {
            attemptDurableClear(server, player, storage, record, "message.dynamicvehicles.pathfinder.expired");
            return;
        }

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
            // Review follow-up (round 7): progression credit used to be applied AND persisted to
            // the attempt record here, before any durability check ever ran - attemptDurablePayout
            // now owns the whole credit-then-confirm sequence itself (see its own javadoc for why
            // ordering it that way matters), so this just records the checkpoint/time progress.
            PathfinderAttemptRecord finishedRecord = record.withProgress(progress);
            storage.update(finishedRecord);
            attemptDurablePayout(server, player, storage, finishedRecord);
        } else if (progress.isExpired()) {
            PathfinderAttemptRecord expiredRecord = record.withProgress(progress);
            storage.update(expiredRecord);
            attemptDurableClear(server, player, storage, expiredRecord, "message.dynamicvehicles.pathfinder.expired");
        } else if (advanced) {
            PathfinderAttemptRecord updated = record.withProgress(progress);
            storage.update(updated);
            PathfinderAttempts.sendHud(player, updated, route);
        }
    }

    /**
     * Two-phase durable payout (review follow-up, round 7). The attempt's own persisted state
     * must never show the progression credit as applied - either by being cleared, or by
     * carrying {@code progressionCredited=true} - before the progression write is INDEPENDENTLY
     * confirmed durable first: doing it the other way round (clear/mark-credited optimistically,
     * verify after, restore in-memory on failure) let a save that landed the attempt-clear while
     * the progression write failed durably delete the completed run from disk with only an
     * in-memory restoration backing it up - a crash/restart right then loses the whole run, and
     * even without a crash, a later unrelated autosave of that in-memory-restored record could
     * persist {@code progressionCredited=true} while the progression file remains stale,
     * permanently settling the attempt as "credited" with the credit itself never having landed.
     * <ul>
     * <li>Phase 1 (only while {@code progressionCredited} is still false): credit progression in
     * memory and confirm THAT alone is durable on disk. The attempt record itself is not touched
     * here, so a crash/retry at any point in this phase just resumes from whatever was last
     * durably saved - {@link PathfinderVillagerProgressStorage}'s per-attempt {@code attemptKey}
     * tracking makes re-entering this phase (e.g. after a restart) safe even if the credit had
     * already landed just before the attempt's own {@code progressionCredited} flag caught up.
     * <li>Phase 2 (once progression is confirmed durable, in-memory OR via a prior tick):
     * progression is already durably settled, so it is now safe to clear the attempt and confirm
     * THAT durably - exactly the same retryable shape as the no-progression-involved paths.
     * </ul>
     */
    private static void attemptDurablePayout(MinecraftServer server, ServerPlayer player, PathfinderAttemptStorage storage, PathfinderAttemptRecord record) {
        PathfinderAttemptRecord current = record;
        if (!current.progressionCredited()) {
            PathfinderAttempts.recordVillagerCompletion(current, server);
            PathfinderVillagerProgressStorage progressStorage = PathfinderVillagerProgressStorage.of(server);
            // A FAILED write silently clears a SavedData's dirty flag too (same gotcha as the
            // attempt file) - without forcing it dirty again on every attempt, a failed
            // progression write would never be retried at all.
            progressStorage.markDirtyForRetry();
            PathfinderVillagerProgress.State expectedProgress = progressStorage.stateOf(current.villagerId());
            if (!forceSaveAndVerifyProgressDurable(server, current.villagerId(), expectedProgress)) {
                // current (progressionCredited=false) is exactly what's already in storage -
                // nothing to restore. recordVillagerCompletion is idempotent per attempt, so
                // retrying this phase again next tick (even after a crash/restart) is safe.
                return;
            }
            current = current.withProgressionCredited();
            storage.update(current);
        }

        storage.clear(player.getUUID());
        if (!forceSaveAndVerifyAbsent(server, player.getUUID())) {
            storage.update(current);
            return;
        }
        // This attempt is now fully, durably settled (reward about to be granted, attempt
        // cleared for good) and will never be evaluated again - safe to drop its key from the
        // still-retryable set (see PathfinderVillagerProgressStorage's class javadoc on round 8's
        // A,B,A interleaving bug: an unbounded-but-never-shrinking set isn't wrong, just wasteful,
        // so this keeps it bounded to attempts that are still actually pending).
        PathfinderVillagerProgressStorage.of(server).forgetCreditedAttempt(current.villagerId(), PathfinderAttempts.attemptKey(current));
        int rewardedEmeralds = PathfinderAttempts.grantReward(player, current.level());
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.completed", rewardedEmeralds));
        PathfinderAttempts.clearHud(player);
    }

    /** Same retryable durable-clear shape as {@link #attemptDurablePayout}, for the no-reward paths (death/expiry). */
    private static void attemptDurableClear(MinecraftServer server, ServerPlayer player, PathfinderAttemptStorage storage,
                                             PathfinderAttemptRecord record, String messageKey) {
        storage.clear(player.getUUID());
        if (!forceSaveAndVerifyAbsent(server, player.getUUID())) {
            storage.update(record);
            return;
        }
        player.sendSystemMessage(Component.translatable(messageKey));
        PathfinderAttempts.clearHud(player);
    }

    /**
     * Queues a save of every dirty {@code SavedData} in the overworld's data storage, blocks
     * until NeoForge's IO worker has actually finished attempting all of them ({@code
     * IOUtilities#waitUntilIOWorkerComplete}), and THEN reads this mod's attempt file straight
     * back off disk (bypassing the in-memory cache entirely) to confirm {@code playerId} is
     * actually absent there. See {@link PathfinderAttemptStorage}'s class javadoc for why the
     * join alone is not sufficient: a failed write is caught and logged inside the queued task,
     * never surfaced to the joining thread.
     */
    private static boolean forceSaveAndVerifyAbsent(MinecraftServer server, UUID playerId) {
        server.overworld().getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        try {
            return readAttemptsFromDisk(server).active(playerId).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Same save-then-read-back-off-disk shape as {@link #forceSaveAndVerifyAbsent}, but confirms
     * {@code villagerId}'s progression credit alone - deliberately NOT the attempt file (see
     * {@link #attemptDurablePayout}'s javadoc for why the two must be confirmed as separate,
     * ordered phases rather than one combined check).
     */
    private static boolean forceSaveAndVerifyProgressDurable(MinecraftServer server, UUID villagerId, PathfinderVillagerProgress.State expectedProgress) {
        server.overworld().getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        try {
            return readVillagerProgressFromDisk(server).stateOf(villagerId).equals(expectedProgress);
        } catch (IOException e) {
            return false;
        }
    }

    private static PathfinderAttemptStorage readAttemptsFromDisk(MinecraftServer server) throws IOException {
        CompoundTag root = server.overworld().getDataStorage()
                .readTagFromDisk(PathfinderAttemptStorage.NAME, null, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        return PathfinderAttemptStorage.load(root.getCompound("data"), server.overworld().registryAccess());
    }

    private static PathfinderVillagerProgressStorage readVillagerProgressFromDisk(MinecraftServer server) throws IOException {
        CompoundTag root = server.overworld().getDataStorage()
                .readTagFromDisk(PathfinderVillagerProgressStorage.NAME, null, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        return PathfinderVillagerProgressStorage.load(root.getCompound("data"), server.overworld().registryAccess());
    }

    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimensionKey(String dimensionId) {
        return net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(dimensionId));
    }
}
