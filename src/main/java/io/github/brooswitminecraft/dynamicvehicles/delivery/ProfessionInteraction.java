package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The interception guard shared by profession villagers that have no vanilla trades (Dispatcher,
 * Pathfinder). Vanilla's {@code Villager#mobInteract} would otherwise mark such a villager
 * "unhappy" and swallow the click, so each profession's handler subscribes to
 * {@code PlayerInteractEvent.EntityInteract}, asks {@link #match} whether the click is one it
 * should serve, and calls {@link #consume} once it has handled it.
 */
public final class ProfessionInteraction {
    private ProfessionInteraction() {}

    /** A validated click: a logical-server player main-hand clicking a living adult villager of the right profession. */
    public record Context(ServerPlayer player, ServerLevel level, Villager villager) {}

    public static Optional<Context> match(PlayerInteractEvent.EntityInteract event, Supplier<VillagerProfession> profession) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return Optional.empty();
        }
        // Only the logical server opens menus; on the client getEntity() is a LocalPlayer, never a ServerPlayer.
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return Optional.empty();
        }
        if (player.isSecondaryUseActive()) {
            return Optional.empty(); // mirrors vanilla Villager#mobInteract: sneak-clicking falls through to default handling.
        }
        if (!(event.getTarget() instanceof Villager villager) || villager.isBaby() || !villager.isAlive()) {
            return Optional.empty();
        }
        if (villager.getVillagerData().getProfession() != profession.get()) {
            return Optional.empty();
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return Optional.empty();
        }
        return Optional.of(new Context(player, level, villager));
    }

    /** Cancels the vanilla interaction and reports the click as handled. */
    public static void consume(PlayerInteractEvent.EntityInteract event) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }
}
