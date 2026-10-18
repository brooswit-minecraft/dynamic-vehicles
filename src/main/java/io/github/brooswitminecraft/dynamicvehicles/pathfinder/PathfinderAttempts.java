package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

import io.github.brooswitminecraft.dynamicvehicles.PathfinderServerConfig;

/**
 * The Minecraft-facing layer that turns a Pathfinder villager interaction into a real, persisted
 * {@link PathfinderAttemptRecord}, mirrors {@code delivery.DeliveryContracts}: the actual
 * decisions (one attempt at a time, checkpoint/expiry validation) live in {@link
 * PathfinderProgress}/{@link PathfinderAttemptStorage}, which is what keeps them unit-testable
 * despite this class not being.
 */
public final class PathfinderAttempts {
    private PathfinderAttempts() {
    }

    /**
     * The villager's own Pathfinder progression level — {@link PathfinderVillagerLevel}, NOT
     * {@code villager.getVillagerData().getLevel()}. See {@link PathfinderVillagerProgress}'s
     * javadoc for the anti-bypass reason: {@code VillagerData#setProfession} preserves the
     * existing level across a profession change, so trusting it directly would let a villager
     * trade-leveled under any OTHER profession instantly unlock high Pathfinder levels.
     */
    public static PathfinderLevel levelOf(ServerLevel level, Villager villager) {
        return PathfinderVillagerLevel.levelOf(level, villager);
    }

    public static PathfinderRegion regionOf(Villager villager) {
        return PathfinderRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
    }

    /** The reach distance (blocks) {@code /pathfinder start} searches for a nearby Pathfinder villager. */
    public static final double COMMAND_INTERACTION_RANGE = 8.0;

    /** Nearest Pathfinder villager within {@link #COMMAND_INTERACTION_RANGE} of {@code player}, if any. */
    public static Optional<Villager> nearestPathfinderVillager(ServerPlayer player) {
        return player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(COMMAND_INTERACTION_RANGE),
                        v -> v.isAlive() && v.getVillagerData().getProfession() == PathfinderProfession.PATHFINDER.get())
                .stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));
    }

    /** Right-click convenience: starts at the villager's own current unlocked maximum level. */
    public static void tryStart(ServerPlayer player, ServerLevel level, Villager villager) {
        tryStart(player, level, villager, levelOf(level, villager));
    }

    /**
     * Starts a new attempt at {@code villager}'s region, at {@code requestedLevel} — review
     * follow-up: route selection was missing entirely; interaction always auto-started at the
     * villager's current maximum unlocked level, with no way to run an earlier, already-unlocked
     * circuit again. Refuses with player-visible feedback if one is already active
     * (MINECRAFT-379's multiplayer-isolation and one-attempt invariant), if {@code
     * requestedLevel} is higher than the villager has actually unlocked (server-validated — see
     * {@link #levelOf}'s anti-bypass reasoning, which this check depends on being correct), or if
     * no safe route could be resolved right now (review follow-up: fail closed with a retryable
     * message rather than starting an attempt against an invalid/uncached route).
     */
    public static void tryStart(ServerPlayer player, ServerLevel level, Villager villager, PathfinderLevel requestedLevel) {
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        if (storage.active(player.getUUID()).isPresent()) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.already_active"));
            return;
        }

        PathfinderLevel unlockedMax = levelOf(level, villager);
        if (requestedLevel.level() > unlockedMax.level()) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.level_locked", requestedLevel.level(), unlockedMax.level()));
            return;
        }
        PathfinderLevel pfLevel = requestedLevel;
        PathfinderRegion origin = regionOf(villager);
        Optional<PathfinderRoute> route = PathfinderRouteStorage.of(player.server).routeFor(level, origin, pfLevel);
        if (route.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.route_unavailable"));
            return;
        }
        long now = player.server.overworld().getGameTime();
        long timeLimitTicks = PathfinderConfig.timeLimitTicks(route.get(), PathfinderServerConfig.TICKS_PER_LEG.get());

        PathfinderAttemptRecord record = new PathfinderAttemptRecord(
                player.getUUID(), villager.getUUID(), level.dimension().location().toString(), origin, pfLevel, now, timeLimitTicks, 0, false, false, false, false);

        if (!storage.tryStart(record)) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.already_active"));
            return;
        }

        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.started", pfLevel.level()));
        sendHud(player, record, route.get());
    }

    /** Status report for a right-click while an attempt is already active, instead of refusing silently. */
    public static void reportStatus(ServerPlayer player) {
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        storage.active(player.getUUID()).ifPresentOrElse(
                record -> player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.status",
                        record.reachedCount(), record.level().checkpointCount())),
                () -> player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.no_attempt")));
    }

    static void sendHud(ServerPlayer player, PathfinderAttemptRecord record, PathfinderRoute route) {
        PathfinderProgress progress = new PathfinderProgress(route, record.startTick(), record.timeLimitTicks(),
                record.reachedCount(), record.finished(), record.expired());
        PathfinderCheckpoint next = progress.nextExpected();
        PacketDistributor.sendToPlayer(player, new PathfinderHudPayload(
                true, next.position().dimensionId(), next.position().x(), next.position().y(), next.position().z(),
                record.reachedCount(), route.checkpoints().size(), record.startTick() + record.timeLimitTicks()));
    }

    static void clearHud(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, PathfinderHudPayload.inactive());
    }

    /**
     * @return the emerald count actually granted, so callers can report the exact amount instead
     *         of recomputing it. Reads {@code PathfinderServerConfig} at the moment of payout,
     *         not snapshotted at attempt start — a level doesn't change mid-attempt, so this
     *         only means a config change takes effect immediately, the same as {@link
     *         PathfinderProgress#onReach}'s reach radius; unlike {@code timeLimitTicks}, which IS
     *         genuinely snapshotted into {@link PathfinderAttemptRecord#timeLimitTicks()} at
     *         start (a deadline that moved mid-attempt would be far more surprising than a
     *         reward amount that does).
     */
    static int grantReward(ServerPlayer player, PathfinderLevel level) {
        int amount = PathfinderConfig.rewardEmeralds(level, PathfinderServerConfig.REWARD_BASE_EMERALDS.get(), PathfinderServerConfig.REWARD_PER_LEVEL_EMERALDS.get());
        player.getInventory().placeItemBackInInventory(new ItemStack(Items.EMERALD, amount));
        return amount;
    }

    /**
     * Awards {@link PathfinderVillagerLevel} progression to the specific villager a finished
     * attempt was started at, by UUID — independent of whether that villager's entity is
     * currently loaded (review follow-up: a completion routinely happens with the source
     * villager regions away and unloaded; crediting only when loaded silently dropped progress
     * in the normal case, not a rare edge case).
     */
    static void recordVillagerCompletion(PathfinderAttemptRecord record, net.minecraft.server.MinecraftServer server) {
        PathfinderVillagerLevel.recordCompletion(server, record.villagerId(), record.level().level(), PathfinderServerConfig.COMPLETIONS_TO_ADVANCE.get());
    }
}
