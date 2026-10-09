package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** Server-side: the real, already-generated offers for this opened session. */
    public DispatcherOfferMenu(int containerId, Inventory playerInventory, List<DestinationOffer> offers, double dangerMin, double dangerMax) {
        super(DispatcherOfferMenus.DISPATCHER_OFFER.get(), containerId);
        this.offers = List.copyOf(offers);
        this.rows = toRows(offers);
        this.dangerMin = dangerMin;
        this.dangerMax = dangerMax;
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
     * STUB (MINECRAFT-109 AC6): validates the index and tells the player what
     * was selected, but creates no contract/lifecycle state at all — the
     * lifecycle slice owns that and must replace this method's body.
     */
    public void acceptOffer(ServerPlayer player, int offerIndex) {
        if (offerIndex < 0 || offerIndex >= offers.size()) {
            return;
        }
        DestinationOffer offer = offers.get(offerIndex);
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                "Accepted offer #%d (ring %d, danger %.2f) - no contract was created; the delivery lifecycle slice "
                        + "still needs to implement acceptance.",
                offerIndex + 1, offer.village().ring(), offer.terms().danger())));
        player.closeContainer();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
