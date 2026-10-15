package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Locale;

/**
 * MINECRAFT-109 AC1/AC3/AC4/AC7: a screen resembling the vanilla villager
 * trade list, one row per destination contract, laying distance (left) and
 * danger (right, number + colour gauge) out as visibly separate columns so
 * neither reads as a restatement of the other. Renders only what the server
 * sent via {@link DispatcherOfferMenu} — never generates or recomputes an
 * offer (AC5). An empty offer list (AC7) shows a message instead of rows and
 * does not crash.
 */
public final class DispatcherOfferScreen extends Screen implements MenuAccess<DispatcherOfferMenu> {
    private static final int PANEL_WIDTH = 230;
    private static final int ROW_HEIGHT = 20;

    private final DispatcherOfferMenu menu;
    private int left;
    private int top;

    public DispatcherOfferScreen(DispatcherOfferMenu menu, Inventory playerInventory, Component title) {
        super(title);
        this.menu = menu;
    }

    @Override
    public DispatcherOfferMenu getMenu() {
        return menu;
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - PANEL_WIDTH) / 2;
        int rowCount = Math.max(1, menu.rows().size());
        this.top = Math.max(24, (this.height - rowCount * ROW_HEIGHT) / 2);
    }

    /**
     * MINECRAFT-264/MINECRAFT-260: vanilla's {@code Screen#renderBackground} runs a
     * full-screen GPU blur ({@code renderBlurredBackground}) plus a panorama/menu-background
     * texture. Villager trading ({@code MerchantScreen}, via {@code AbstractContainerScreen})
     * skips all of that and just dims with {@link #renderTransparentBackground}; this screen
     * matches that instead of the {@code Screen} default.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderTransparentBackground(graphics);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.top - 16, 0xFFFFFF);

        List<DispatcherOfferRow> rows = menu.rows();
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.dynamicvehicles.dispatcher.no_offers"),
                    this.width / 2, this.top + 4, 0xA0A0A0);
        } else {
            for (int i = 0; i < rows.size(); i++) {
                renderRow(graphics, rows.get(i), i, mouseX, mouseY);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRow(GuiGraphics graphics, DispatcherOfferRow row, int index, int mouseX, int mouseY) {
        int rowTop = this.top + index * ROW_HEIGHT;
        boolean hovered = isHoveringRow(mouseX, mouseY, rowTop);
        graphics.fill(this.left, rowTop, this.left + PANEL_WIDTH, rowTop + ROW_HEIGHT - 2, hovered ? 0x66FFFFFF : 0x44000000);

        // Distance column: independent of danger, left-aligned (AC2, AC4).
        graphics.drawString(this.font, OfferDistanceFormat.format(row.approxDistanceBlocks()), this.left + 6, rowTop + 5, 0xFFFFFF);

        // Danger column: number plus a colour gauge swatch, right-aligned (AC3, AC4).
        int swatchX = this.left + PANEL_WIDTH - 76;
        int swatchColor = DangerGauge.colorArgb(row.danger(), menu.dangerMin(), menu.dangerMax());
        graphics.fill(swatchX, rowTop + 3, swatchX + 12, rowTop + 15, swatchColor);
        DangerGauge.Category category = DangerGauge.categoryFor(row.danger(), menu.dangerMin(), menu.dangerMax());
        String dangerText = String.format(Locale.ROOT, "%.2f %s",
                row.danger(), Component.translatable(DangerGauge.translationKey(category)).getString());
        graphics.drawString(this.font, dangerText, swatchX + 16, rowTop + 5, 0xFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<DispatcherOfferRow> rows = menu.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (isHoveringRow((int) mouseX, (int) mouseY, this.top + i * ROW_HEIGHT)) {
                PacketDistributor.sendToServer(new DispatcherOfferAcceptPayload(i));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isHoveringRow(int mouseX, int mouseY, int rowTop) {
        return mouseX >= this.left && mouseX <= this.left + PANEL_WIDTH && mouseY >= rowTop && mouseY <= rowTop + ROW_HEIGHT - 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
