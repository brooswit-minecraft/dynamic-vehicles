package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

import io.github.brooswitminecraft.dynamicvehicles.DispatcherOfferConfig;

/**
 * MINECRAFT-109 AC10: the interception point. A Dispatcher villager has no
 * vanilla trade offers at all (its profession registers none), so vanilla's
 * own {@code Villager#mobInteract} would just mark it "unhappy" and consume
 * the click without ever reaching our logic — we have to intercept and
 * cancel BEFORE that runs, on {@code PlayerInteractEvent.EntityInteract}.
 *
 * <p>This is also AC10's generation trigger: exactly one
 * {@link DispatcherOfferService#generateOffers} call per opened screen
 * (one call per handled interaction, result cached into the menu for the
 * session — never per tick, never for every loaded Dispatcher).
 */
public final class DispatcherInteractionHandler {
    private DispatcherInteractionHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        // Only the logical server generates/opens; on the client getEntity() is a LocalPlayer, never a ServerPlayer.
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.isSecondaryUseActive()) {
            return; // mirrors vanilla Villager#mobInteract: sneak-clicking falls through to default handling.
        }
        if (!(event.getTarget() instanceof Villager villager) || villager.isBaby() || !villager.isAlive()) {
            return;
        }
        if (villager.getVillagerData().getProfession() != DispatcherProfession.DISPATCHER.get()) {
            return;
        }
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        List<DestinationOffer> offers = DispatcherOfferService.generateOffers(
                serverLevel, villager.blockPosition(), villager.getVillagerData().getLevel(), DispatcherOfferConfig.toOfferConfig());

        double dangerMin = DispatcherOfferConfig.DANGER_MIN.get();
        double dangerMax = DispatcherOfferConfig.DANGER_MAX.get();
        List<DispatcherOfferRow> rows = DispatcherOfferMenu.toRows(offers);

        player.openMenu(
                new DispatcherOfferMenuProvider(offers, villager.getDisplayName(), dangerMin, dangerMax),
                buf -> DispatcherOfferMenu.writeExtraData(buf, dangerMin, dangerMax, rows));

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
