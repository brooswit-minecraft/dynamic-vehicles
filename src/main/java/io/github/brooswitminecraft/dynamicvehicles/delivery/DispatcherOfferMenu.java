package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative menu for MINECRAFT-109: holds the {@link DestinationOffer}s
 * {@link DispatcherOfferService} already generated (AC5 — this class never
 * generates or recomputes them) and the {@link DispatcherOfferRow} view of
 * them sent to the client. Has no item slots; it exists purely to carry
 * offer data to the client and to validate accept requests (AC6) against the
 * session the player actually opened. This is the only class in this slice
 * that touches {@code RegistryFriendlyByteBuf} directly — see
 * {@link DispatcherOfferPayloadCodec} for the unit-tested field-list
 * extraction this mirrors.
 */
public final class DispatcherOfferMenu extends AbstractContainerMenu {

    private final List<DestinationOffer> offers;
    private final List<DispatcherOfferRow> rows;
    private final double dangerMin;
    private final double dangerMax;
    /** Server-side only (AC12): the villager this session's validity is keyed on. Always {@code null} client-side. */
    private final Villager dispatcher;

    /** Server-side: the real, already-generated offers for this opened session. */
    public DispatcherOfferMenu(int containerId, Inventory playerInventory, List<DestinationOffer> offers, double dangerMin, double dangerMax,
            Villager dispatcher) {
        super(DispatcherOfferMenus.DISPATCHER_OFFER.get(), containerId);
        this.offers = List.copyOf(offers);
        this.rows = toRows(offers);
        this.dangerMin = dangerMin;
        this.dangerMax = dangerMax;
        this.dispatcher = dispatcher;
    }

    /** Client-side (via {@link net.neoforged.neoforge.network.IContainerFactory}): reads what the server sent. */
    public DispatcherOfferMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        super(DispatcherOfferMenus.DISPATCHER_OFFER.get(), containerId);
        this.offers = List.of();
        this.dangerMin = extraData.readDouble();
        this.dangerMax = extraData.readDouble();
        int count = extraData.readVarInt();
        List<DispatcherOfferRow> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            decoded.add(new DispatcherOfferRow(extraData.readDouble(), extraData.readDouble(), extraData.readVarLong(), extraData.readDouble()));
        }
        this.rows = List.copyOf(decoded);
        this.dispatcher = null;
    }

    public static List<DispatcherOfferRow> toRows(List<DestinationOffer> offers) {
        List<DispatcherOfferRow> rows = new ArrayList<>(offers.size());
        for (DestinationOffer offer : offers) {
            rows.add(new DispatcherOfferRow(
                    offer.village().approxDistance(), offer.terms().danger(), offer.terms().timeAllowanceTicks(), offer.terms().reward()));
        }
        return List.copyOf(rows);
    }

    /** Mirrors {@link DispatcherOfferPayloadCodec#write} field-for-field against the real network buffer. */
    public static void writeExtraData(RegistryFriendlyByteBuf buf, double dangerMin, double dangerMax, List<DispatcherOfferRow> rows) {
        buf.writeDouble(dangerMin);
        buf.writeDouble(dangerMax);
        buf.writeVarInt(rows.size());
        for (DispatcherOfferRow row : rows) {
            buf.writeDouble(row.approxDistanceBlocks());
            buf.writeDouble(row.danger());
            buf.writeVarLong(row.timeAllowanceTicks());
            buf.writeDouble(row.reward());
        }
    }

    public List<DispatcherOfferRow> rows() {
        return rows;
    }

    public double dangerMin() {
        return dangerMin;
    }

    public double dangerMax() {
        return dangerMax;
    }

    /**
     * MINECRAFT-110 AC1-2, AC12: validates the index, re-checks server-side
     * validity (AC12 - a stale/forged request must not bypass the same
     * check {@link #stillValid} performs), then hands off to
     * {@link DeliveryContracts#tryAccept} to enforce the one-contract
     * invariant and create real contract state.
     */
    public void acceptOffer(ServerPlayer player, int offerIndex) {
        if (!stillValid(player)) {
            player.sendSystemMessage(Component.translatable("message.dynamicvehicles.dispatcher.no_longer_valid"));
            player.closeContainer();
            return;
        }
        if (offerIndex < 0 || offerIndex >= offers.size()) {
            return;
        }
        DestinationOffer offer = offers.get(offerIndex);
        DeliveryContracts.tryAccept(player, dispatcher, offer);
        player.closeContainer();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /**
     * MINECRAFT-110 AC12: the Dispatcher villager must still exist, be
     * alive, carry the {@code dispatcher} profession, and be within normal
     * interaction range of the player - mirrors vanilla merchant menus
     * tying validity to the merchant, instead of the previous (slice-d,
     * disclosed) always-true stub. Only the server-constructed instance
     * carries a live {@link #dispatcher} reference; the client-constructed
     * instance (no reference at all - see the second constructor) always
     * returns true, since the server is what actually enforces this
     * (AC12 explicitly requires the refusal to be server-side).
     */
    @Override
    public boolean stillValid(Player player) {
        if (dispatcher == null) {
            return true;
        }
        boolean sameLevel = dispatcher.level() == player.level();
        boolean hasProfession = dispatcher.getVillagerData().getProfession() == DispatcherProfession.DISPATCHER.get();
        double distanceSquared = sameLevel ? dispatcher.distanceToSqr(player) : Double.MAX_VALUE;
        return DispatcherMenuValidity.isValid(dispatcher.isAlive(), hasProfession, sameLevel, distanceSquared);
    }
}
