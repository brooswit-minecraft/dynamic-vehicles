package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

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
 * The persistent HUD element shown while a Pathfinder attempt is active - next-checkpoint
 * direction and remaining time, mirroring {@code delivery.ContractHudOverlay}'s approach
 * (direction/remaining-time computed every frame client-side from the payload's static position
 * and deadline, never resynced from the server except on actual state changes).
 */
public final class PathfinderHudOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        PathfinderHudPayload hud = PathfinderClientHud.current();
        if (!hud.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        long remainingTicks = hud.deadlineTick() - minecraft.level.getGameTime();
        String timeText = formatRemaining(remainingTicks);
        String progressText = hud.reachedCount() + "/" + hud.totalCheckpoints();
        String playerDimension = minecraft.level.dimension().location().toString();

        int x = 8;
        int y = 44;

        PathfinderHudPresentation.Mode mode = PathfinderHudPresentation.decide(hud.dimension(), playerDimension);
        if (mode == PathfinderHudPresentation.Mode.ARROW) {
            double dx = hud.nextX() - player.getX();
            double dz = hud.nextZ() - player.getZ();
            int arrowCenterX = x + 6;
            int arrowCenterY = y + 6;

            double absoluteBearing = Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0;
            double relativeBearing = Mth.wrapDegrees(absoluteBearing - player.getYRot());

            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(arrowCenterX, arrowCenterY, 0);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) relativeBearing));
            graphics.drawCenteredString(minecraft.font, "^", 0, -4, 0xFFFFFF);
            pose.popPose();
        } else {
            // Wrong dimension: an arrow computed in a different coordinate space would be meaningless.
            String wrongDimensionText = Component.translatable("gui.dynamicvehicles.pathfinder.wrong_dimension").getString();
            graphics.drawString(minecraft.font, wrongDimensionText, x, y - 10, 0xFFFF55);
        }

        graphics.drawString(minecraft.font, progressText, x + 16, y, 0xFFFFFF);
        graphics.drawString(minecraft.font, timeText, x + 16, y + 10, 0xFFFFFF);
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
