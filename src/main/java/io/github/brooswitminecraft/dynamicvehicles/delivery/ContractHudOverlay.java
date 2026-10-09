package io.github.brooswitminecraft.dynamicvehicles.delivery;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * MINECRAFT-110 AC4: the persistent HUD element shown while a contract is
 * active - destination direction, remaining distance, remaining time.
 * Renders only {@link ClientContractHud#current()}, the last payload the
 * server sent (AC8); direction and remaining time are computed here, every
 * frame, from the player's own live position/yaw and the client level's
 * own game time, never from anything the client decided about contract
 * state itself. Reuses {@link DangerGauge} and {@link OfferDistanceFormat}
 * so this never disagrees with the offer screen about the same contract.
 */
public final class ContractHudOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        DeliveryHudPayload hud = ClientContractHud.current();
        if (!hud.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        double dx = hud.destinationX() - player.getX();
        double dz = hud.destinationZ() - player.getZ();
        double distanceBlocks = Math.sqrt(dx * dx + dz * dz);

        long remainingTicks = hud.deadlineTick() - minecraft.level.getGameTime();
        String timeText = formatRemaining(remainingTicks);

        DangerGauge.Category category = DangerGauge.categoryFor(hud.danger(), hud.dangerMin(), hud.dangerMax());
        int swatchColor = DangerGauge.colorArgb(hud.danger(), hud.dangerMin(), hud.dangerMax());
        String dangerText = Component.translatable(DangerGauge.translationKey(category)).getString();

        int x = 8;
        int y = 8;
        int arrowCenterX = x + 6;
        int arrowCenterY = y + 6;

        // Facing-relative bearing to the destination, in degrees (0 = straight ahead).
        double absoluteBearing = Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0;
        double relativeBearing = Mth.wrapDegrees(absoluteBearing - player.getYRot());

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(arrowCenterX, arrowCenterY, 0);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) relativeBearing));
        graphics.drawCenteredString(minecraft.font, "^", 0, -4, 0xFFFFFF);
        pose.popPose();

        graphics.drawString(minecraft.font, OfferDistanceFormat.format(distanceBlocks), x + 16, y, 0xFFFFFF);
        graphics.drawString(minecraft.font, timeText, x + 16, y + 10, 0xFFFFFF);
        graphics.fill(x + 16, y + 21, x + 28, y + 29, swatchColor);
        graphics.drawString(minecraft.font, dangerText, x + 32, y + 20, 0xFFFFFF);
    }

    private static String formatRemaining(long remainingTicks) {
        if (remainingTicks <= 0) {
            return "0:00";
        }
        long totalSeconds = remainingTicks / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
