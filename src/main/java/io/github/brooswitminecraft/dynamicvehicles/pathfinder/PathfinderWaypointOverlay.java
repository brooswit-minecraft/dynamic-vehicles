package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;

import io.github.brooswitminecraft.dynamicvehicles.delivery.OfferDistanceFormat;
import io.github.brooswitminecraft.dynamicvehicles.hud.CountdownFormat;
import io.github.brooswitminecraft.dynamicvehicles.hud.MissionPresentation;
import io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointLayout;
import io.github.brooswitminecraft.dynamicvehicles.hud.MissionWaypointRenderer;
import io.github.brooswitminecraft.dynamicvehicles.hud.ProgressFormat;

/**
 * MINECRAFT-411: the primary Pathfinder mission presentation - a world-projected,
 * edge-clamped marker at the next checkpoint, in the same compact dark-backed
 * style as {@code delivery.ContractWaypointOverlay}'s contract waypoint, through
 * the same shared {@link MissionWaypointRenderer}, instead of the old
 * fixed-corner {@link PathfinderHudOverlay} arrow.
 *
 * <p>Checkpoint count and remaining time are drawn as extra lines directly below
 * the marker, so progress/timer feedback sits where the player is actually
 * navigating rather than requiring a glance at a screen corner. The marker's
 * fixed colour (distinct from Dispatcher's danger-gauge colour) and the extra
 * lines (which Dispatcher's own marker never has) keep an active delivery
 * contract and an active Pathfinder attempt visually distinguishable even when
 * both are active and their markers land close together on screen.
 *
 * <p>State is server-authoritative, mirroring {@code delivery.ContractWaypointOverlay}:
 * direction/distance/remaining-time are computed every frame client-side from the
 * payload's static next-checkpoint position and deadline, never resynced from the
 * server except on actual state changes (checkpoint advance, start, complete/expire).
 */
public final class PathfinderWaypointOverlay implements LayeredDraw.Layer {

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        PathfinderHudPayload hud = PathfinderClientHud.current();
        if (!hud.active()) {
            // No marker occupies screen space this frame; clear so Dispatcher (rendered before this
            // overlay, next frame) never avoids a now-stale Pathfinder panel (MINECRAFT-411 PR #177 review).
            MissionWaypointLayout.clear(MissionWaypointLayout.Mission.PATHFINDER);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            MissionWaypointLayout.clear(MissionWaypointLayout.Mission.PATHFINDER);
            return;
        }

        String playerDimension = minecraft.level.dimension().location().toString();
        MissionPresentation.Mode mode = MissionPresentation.decide(hud.active(), hud.dimension().equals(playerDimension));
        if (mode == MissionPresentation.Mode.WRONG_DIMENSION) {
            // A marker computed in a different coordinate space would be meaningless; there is no
            // world position here to project, so fall back to a compact status line instead of a
            // misleading projected target.
            MissionWaypointLayout.clear(MissionWaypointLayout.Mission.PATHFINDER);
            String wrongDimensionText = Component.translatable("gui.dynamicvehicles.pathfinder.wrong_dimension").getString();
            MissionWaypointRenderer.renderStatus(graphics, minecraft, wrongDimensionText, 0xFFFF55);
            return;
        }

        double dx = hud.nextX() - player.getX();
        double dz = hud.nextZ() - player.getZ();
        String distance = OfferDistanceFormat.format(Math.sqrt(dx * dx + dz * dz));
        String progressText = ProgressFormat.format(hud.reachedCount(), hud.totalCheckpoints());
        long remainingTicks = hud.deadlineTick() - minecraft.level.getGameTime();
        String timeText = CountdownFormat.format(remainingTicks);

        MissionWaypointRenderer.renderMarker(graphics, minecraft, MissionWaypointLayout.Mission.PATHFINDER,
                hud.nextX(), hud.nextY(), hud.nextZ(), MARKER_COLOR_ARGB, distance,
                List.of(progressText, timeText));
    }

    /**
     * A fixed cyan, distinct from Dispatcher's danger-gauge marker colour (green-to-red): Pathfinder
     * has no danger value (MINECRAFT-411: do not invent Pathfinder threat semantics), so this stays
     * a plain neutral colour rather than reusing {@code delivery.DangerGauge} for something it
     * doesn't model.
     */
    private static final int MARKER_COLOR_ARGB = 0xFF55FFFF;
}
