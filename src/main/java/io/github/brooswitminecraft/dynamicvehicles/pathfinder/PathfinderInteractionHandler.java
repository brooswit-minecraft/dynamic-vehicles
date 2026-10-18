package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import io.github.brooswitminecraft.dynamicvehicles.delivery.ProfessionInteraction;

/**
 * Right-click a Pathfinder villager to start (or check the status of) an attempt — the villager
 * itself, not only the Route Board, per John's review follow-up ("board interaction may
 * complement, not replace the requested villager"). Reuses {@code
 * delivery.ProfessionInteraction}'s interception guard, the same seam {@code
 * DispatcherInteractionHandler} uses and whose own javadoc already names this profession.
 */
public final class PathfinderInteractionHandler {
    private PathfinderInteractionHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        var click = ProfessionInteraction.match(event, PathfinderProfession.PATHFINDER).orElse(null);
        if (click == null) {
            return;
        }
        ServerPlayer player = click.player();
        ServerLevel level = click.level();

        if (PathfinderAttemptStorage.of(player.server).active(player.getUUID()).isPresent()) {
            PathfinderAttempts.reportStatus(player);
        } else {
            PathfinderAttempts.tryStart(player, level, click.villager());
        }
        ProfessionInteraction.consume(event);
    }
}
