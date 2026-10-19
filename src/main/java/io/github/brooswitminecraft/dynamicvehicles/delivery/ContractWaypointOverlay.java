package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

import java.util.List;

import io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout;
import io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointRenderer;

/** Additive world-projected waypoint marker for the active delivery contract. */
public final class ContractWaypointOverlay implements LayeredDraw.Layer {
    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        DeliveryHudPayload hud = ClientContractHud.current();
        Minecraft minecraft = Minecraft.getInstance();
        if (!hud.active() || minecraft.player == null || minecraft.level == null) {
            // No marker occupies screen space this frame; clear so Pathfinder (rendered after this
            // overlay) never avoids a now-stale Dispatcher panel (MINECRAFT-411 PR #177 review).
            MissionWaypointLayout.clear(MissionWaypointLayout.Mission.DISPATCHER);
            return;
        }
        int color = DangerGauge.colorArgb(hud.danger(), hud.dangerMin(), hud.dangerMax());
        double dx = hud.destinationX() - minecraft.player.getX();
        double dz = hud.destinationZ() - minecraft.player.getZ();
        String distance = OfferDistanceFormat.format(Math.sqrt(dx * dx + dz * dz));
        MissionWaypointRenderer.renderMarker(graphics, minecraft, MissionWaypointLayout.Mission.DISPATCHER,
                hud.destinationX(), hud.destinationY(), hud.destinationZ(), color, distance, List.of());
    }
}
