package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.network.PacketDistributor;

import io.github.brooswitminecraft.dynamicvehicles.DispatcherOfferConfig;

/**
 * Thin Minecraft-facing layer (MINECRAFT-110): the only class that creates a
 * real {@link DeliveryContract} from a {@link DestinationOffer} and a
 * player, enforces AC2's one-contract rule server-side via
 * {@link DeliveryContractStorage}, and keeps the player's HUD
 * (AC4/AC8) in sync. All of the actual decisions - one contract at a time,
 * the contract's own field values - live in {@link ContractBook}/
 * {@link DeliveryContract}, which is what keeps them unit-testable despite
 * this class not being.
 */
public final class DeliveryContracts {

    private DeliveryContracts() {
    }

    /** AC2: used by {@link DispatcherInteractionHandler} to refuse opening a new offer screen at all while a contract is active. */
    public static boolean hasActive(ServerPlayer player) {
        return DeliveryContractStorage.of(player.server).book().hasActive(player.getUUID());
    }

    /**
     * AC1-2: creates and stores a real contract from {@code offer}, refusing
     * with player-visible feedback if one is already active (defense in
     * depth alongside {@link DispatcherInteractionHandler}'s own check,
     * for the same-tick race a stale menu could otherwise exploit).
     */
    public static void tryAccept(ServerPlayer player, Villager dispatcher, DestinationOffer offer) {
        DeliveryContractStorage storage = DeliveryContractStorage.of(player.server);
        long now = player.server.overworld().getGameTime();
        ConfirmedVillage village = offer.village();

        DeliveryContract contract = new DeliveryContract(
                player.getUUID(),
                dispatcher.getUUID(),
                village.identity().dimension().location().toString(),
                village.identity().region().x(),
                village.identity().region().z(),
                village.startBlockPos().getX(),
                village.startBlockPos().getY(),
                village.startBlockPos().getZ(),
                offer.terms().danger(),
                offer.terms().reward(),
                now,
                now + offer.terms().timeAllowanceTicks());

        ContractBook.AcceptResult result = storage.book().accept(contract);
        if (result == ContractBook.AcceptResult.ALREADY_ACTIVE) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.dispatcher.already_active"));
            return;
        }
        storage.setDirty();
        player.sendSystemMessage(Component.translatable("message.dynamicvehicles.dispatcher.accepted"));
        sendHud(player, contract);
    }

    static void sendHud(ServerPlayer player, DeliveryContract contract) {
        PacketDistributor.sendToPlayer(player, new DeliveryHudPayload(
                true,
                contract.destinationDimension(),
                contract.destinationStartX(),
                contract.destinationStartZ(),
                contract.deadlineTick(),
                contract.danger(),
                DispatcherOfferConfig.DANGER_MIN.get(),
                DispatcherOfferConfig.DANGER_MAX.get()));
    }

    static void clearHud(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, DeliveryHudPayload.inactive());
    }
}
