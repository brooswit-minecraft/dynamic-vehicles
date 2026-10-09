package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.List;

/**
 * Thin {@link MenuProvider} that hands {@link DispatcherOfferService}'s
 * already-generated offers to a new {@link DispatcherOfferMenu}
 * (MINECRAFT-109 AC5). Built once per opened interaction by
 * {@link DispatcherInteractionHandler} — never generates offers itself.
 * Also carries the Dispatcher villager reference the created menu keys
 * its AC12 {@code stillValid} check on.
 */
public final class DispatcherOfferMenuProvider implements MenuProvider {
    private final List<DestinationOffer> offers;
    private final Component displayName;
    private final double dangerMin;
    private final double dangerMax;
    private final Villager dispatcher;

    public DispatcherOfferMenuProvider(List<DestinationOffer> offers, Component displayName, double dangerMin, double dangerMax,
            Villager dispatcher) {
        this.offers = offers;
        this.displayName = displayName;
        this.dangerMin = dangerMin;
        this.dangerMax = dangerMax;
        this.dispatcher = dispatcher;
    }

    @Override
    public Component getDisplayName() {
        return displayName;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new DispatcherOfferMenu(containerId, playerInventory, offers, dangerMin, dangerMax, dispatcher);
    }
}
