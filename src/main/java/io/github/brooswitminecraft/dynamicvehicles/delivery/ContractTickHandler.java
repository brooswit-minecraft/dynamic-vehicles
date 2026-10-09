package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * MINECRAFT-110 AC5, AC7: the coarse server timer that evaluates each
 * online player's active contract against {@link ArrivalPredicate} and the
 * deadline, instead of only checking on interaction. Deliberately never
 * calls {@link DispatcherOfferService#generateOffers} or
 * {@link VillagePlacementService#findVillages} - both do blocking chunk
 * loads, and a contract already carries its own destination and start
 * location, so neither is needed here. Runs every {@link #EVALUATE_INTERVAL_TICKS}
 * ticks per player, not every tick, and skips players with no active
 * contract via {@link ContractBook#evaluate} without touching the book's
 * map otherwise.
 *
 * <p>MINECRAFT-130 hooks its periodic ambush roll into this same sweep's
 * {@code STILL_ACTIVE} case, and its encounter cleanup sweep into this same
 * tick, rather than adding a second timer or player sweep.
 */
public final class ContractTickHandler {

    /** 20 ticks = 1 second: coarse enough to never evaluate every contract every tick, tight enough to read as "immediate" arrival/expiry. */
    static final int EVALUATE_INTERVAL_TICKS = 20;

    private ContractTickHandler() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % EVALUATE_INTERVAL_TICKS != 0) {
            return;
        }
        DeliveryContractStorage storage = DeliveryContractStorage.of(server);
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BlockPos pos = player.blockPosition();
            String dimension = player.level().dimension().location().toString();
            ContractBook.Evaluation evaluation = storage.book().evaluate(player.getUUID(), dimension, pos.getX(), pos.getZ(), () -> now);
            switch (evaluation.outcome()) {
                case COMPLETED -> onCompleted(player, evaluation.contract(), storage);
                case EXPIRED -> onExpired(player, storage);
                case STILL_ACTIVE -> PillagerAmbushHandler.attemptRoll(player, evaluation.contract(), now);
                case NO_ACTIVE_CONTRACT -> {
                    // Nothing to do.
                }
            }
        }
        // MINECRAFT-130 AC4: resolves every tracked ambush mob against the cleanup predicate once per
        // sweep, not per player - bounded by the (small) number of outstanding encounters, not by player count.
        PillagerAmbushHandler.sweep(server, storage, now);
    }

    /** MINECRAFT-130 AC4's logout case: cleans up that player's own encounter mobs immediately, rather than waiting up to one sweep. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.server;
        DeliveryContractStorage storage = DeliveryContractStorage.of(server);
        PillagerAmbushHandler.cleanupForOwner(server, storage, player.getUUID(), server.overworld().getGameTime());
    }

    /** Resyncs the HUD for a player who already has a persisted active contract (AC3: survives logout/relog). */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DeliveryContractStorage storage = DeliveryContractStorage.of(player.server);
        storage.book().active(player.getUUID()).ifPresent(contract -> DeliveryContracts.sendHud(player, contract));
    }

    private static void onCompleted(ServerPlayer player, DeliveryContract contract, DeliveryContractStorage storage) {
        storage.setDirty();
        int rewardCount = (int) Math.max(1, Math.round(contract.reward()));
        player.getInventory().placeItemBackInInventory(new ItemStack(Items.EMERALD, rewardCount));
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.dispatcher.completed", rewardCount));
        DeliveryContracts.clearHud(player);
    }

    private static void onExpired(ServerPlayer player, DeliveryContractStorage storage) {
        storage.setDirty();
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.dispatcher.expired"));
        DeliveryContracts.clearHud(player);
    }
}
