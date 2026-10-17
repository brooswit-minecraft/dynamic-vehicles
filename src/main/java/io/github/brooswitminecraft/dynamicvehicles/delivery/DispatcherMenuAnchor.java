package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.world.entity.player.Player;

/**
 * Server-side-only liveness/validity check for an open {@link DispatcherOfferMenu}
 * (MINECRAFT-110 AC12), abstracted over what opened it — a Dispatcher villager or a
 * fixed level-one {@link DispatcherBlocks#DISPATCH_BOARD} — so the menu itself does
 * not need a {@code Villager} reference to validate against. {@code null} on the
 * client-constructed menu, exactly like the {@code Villager} reference it replaces.
 */
@FunctionalInterface
public interface DispatcherMenuAnchor {
    boolean isValid(Player player);
}
