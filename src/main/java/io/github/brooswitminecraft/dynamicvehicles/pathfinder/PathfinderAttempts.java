package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

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
    public static PathfinderLevel levelOf(Villager villager) {
        return PathfinderVillagerLevel.levelOf(villager);
    }

    public static PathfinderRegion regionOf(Villager villager) {
        return PathfinderRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
    }

    /**
     * Starts a new attempt at {@code villager}'s region/level, refusing with player-visible
     * feedback if one is already active (MINECRAFT-379's multiplayer-isolation and one-attempt
     * invariant — each player gets their own persisted record, so concurrent players never share
     * or corrupt each other's progress).
     */
    public static void tryStart(ServerPlayer player, ServerLevel level, Villager villager) {
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(player.server);
        if (storage.active(player.getUUID()).isPresent()) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.already_active"));
            return;
        }

        PathfinderLevel pfLevel = levelOf(villager);
        PathfinderRegion origin = regionOf(villager);
        PathfinderRoute route = PathfinderRouteStorage.of(player.server).routeFor(level, origin, pfLevel);
        long now = player.server.overworld().getGameTime();
        long timeLimitTicks = PathfinderConfig.timeLimitTicks(route, PathfinderServerConfig.TICKS_PER_LEG.get());

        PathfinderAttemptRecord record = new PathfinderAttemptRecord(
                player.getUUID(), villager.getUUID(), level.dimension().location().toString(), origin, pfLevel, now, timeLimitTicks, 0, false, false);

        if (!storage.tryStart(record)) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.already_active"));
            return;
        }

        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.pathfinder.started", pfLevel.level()));
        sendHud(player, record, route);
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

    /** @return the emerald count actually granted, so callers can report the exact amount instead of recomputing it. */
    static int grantReward(ServerPlayer player, PathfinderLevel level) {
        int amount = PathfinderConfig.rewardEmeralds(level, PathfinderServerConfig.REWARD_BASE_EMERALDS.get(), PathfinderServerConfig.REWARD_PER_LEVEL_EMERALDS.get());
        player.getInventory().placeItemBackInInventory(new ItemStack(Items.EMERALD, amount));
        return amount;
    }

    /**
     * Awards {@link PathfinderVillagerLevel} progression to the specific villager a finished
     * attempt was started at, looking it up by id in its own recorded dimension. A missing/
     * unloaded villager (killed, chunk unloaded) just means no progression this time — the
     * player's reward is unaffected, and the next completed attempt at any Pathfinder villager
     * still progresses normally.
     */
    static void recordVillagerCompletion(ServerLevel dimension, PathfinderAttemptRecord record) {
        Entity entity = dimension.getEntity(record.villagerId());
        if (entity instanceof Villager villager) {
            PathfinderVillagerLevel.recordCompletion(villager, PathfinderServerConfig.COMPLETIONS_TO_ADVANCE.get());
        }
    }
}
