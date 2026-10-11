package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.world.phys.Vec3;

/** Additive world-projected waypoint marker for the active delivery contract. */
public final class ContractWaypointOverlay implements LayeredDraw.Layer {
    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        DeliveryHudPayload hud = ClientContractHud.current();
        Minecraft minecraft = Minecraft.getInstance();
        if (!hud.active() || minecraft.player == null || minecraft.level == null) {
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.getPosition();
        var forward = camera.getLookVector();
        var left = camera.getLeftVector();
        var up = camera.getUpVector();
        WaypointMarkerLayout.Point point = WaypointMarkerLayout.project(
                hud.destinationX() - cameraPosition.x, hud.destinationY() - cameraPosition.y, hud.destinationZ() - cameraPosition.z,
                forward.x(), forward.y(), forward.z(),
                -left.x(), -left.y(), -left.z(), up.x(), up.y(), up.z(),
                minecraft.options.fov().get(), (double) graphics.guiWidth() / graphics.guiHeight(), 0.05);
        int x = (int) Math.round(point.x() * graphics.guiWidth());
        int y = (int) Math.round(point.y() * graphics.guiHeight());
        int color = DangerGauge.colorArgb(hud.danger(), hud.dangerMin(), hud.dangerMax());
        double dx = hud.destinationX() - minecraft.player.getX();
        double dz = hud.destinationZ() - minecraft.player.getZ();
        String distance = OfferDistanceFormat.format(Math.sqrt(dx * dx + dz * dz));
        int textWidth = minecraft.font.width(distance);
        // Small backing and shadow retain contrast without a full opaque badge.
        graphics.fill(x - 7, y - 7, x + Math.max(8, textWidth / 2 + 7), y + 18, 0x90000000);
        graphics.fill(x - 3, y - 3, x + 3, y + 3, color);
        graphics.drawCenteredString(minecraft.font, distance, x + 1, y + 7, 0xFF000000);
        graphics.drawCenteredString(minecraft.font, distance, x, y + 6, color);
    }
}
