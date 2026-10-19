package io.github.brooswitminecraft.dynamicvehicles.hud;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Shared Minecraft-aware drawing for a world-projected mission waypoint marker:
 * an in-view dot that edge-clamps to the screen border when the destination is
 * off-screen, a readable distance label, and optional additional status lines
 * drawn directly below it (e.g. Pathfinder's checkpoint count and countdown).
 *
 * <p>MINECRAFT-411: extracted (pixel layout unchanged for the zero-extra-lines,
 * no-overlap case) from {@code delivery.ContractWaypointOverlay} so Dispatcher's
 * contract waypoint and Pathfinder's mission waypoint render through one code
 * path instead of a second divergent imitation. Each caller still owns its own
 * marker colour and label text - this class owns the shared projection,
 * clamping, panel layout ({@link MarkerPanelLayout}) and inter-mission overlap
 * avoidance ({@link MissionWaypointLayout}).
 */
public final class MissionWaypointRenderer {
    private static final double EDGE_INSET = 0.05;

    private MissionWaypointRenderer() {
    }

    /**
     * Renders the marker for a destination at {@code (worldX, worldY, worldZ)}, relative to the
     * current render camera. {@code extraLines} are drawn directly below the distance label, top
     * to bottom, in the supplied order; pass an empty list for a Dispatcher-style marker with no
     * extra lines. The panel (backing box, distance label, extra lines) stays within the viewport
     * and is shifted clear of any other mission's panel already reserved for this frame via
     * {@code mission} - the dot itself is always drawn at the true projected/clamped position.
     */
    public static void renderMarker(
            GuiGraphics graphics, Minecraft minecraft, MissionWaypointLayout.Mission mission,
            double worldX, double worldY, double worldZ,
            int markerColorArgb, String distanceLabel, List<String> extraLines) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.getPosition();
        var forward = camera.getLookVector();
        var left = camera.getLeftVector();
        var up = camera.getUpVector();
        WaypointMarkerLayout.Point point = WaypointMarkerLayout.project(
                worldX - cameraPosition.x, worldY - cameraPosition.y, worldZ - cameraPosition.z,
                forward.x(), forward.y(), forward.z(),
                -left.x(), -left.y(), -left.z(), up.x(), up.y(), up.z(),
                minecraft.options.fov().get(), (double) graphics.guiWidth() / graphics.guiHeight(), EDGE_INSET);
        int dotX = (int) Math.round(point.x() * graphics.guiWidth());
        int dotY = (int) Math.round(point.y() * graphics.guiHeight());

        int textWidth = minecraft.font.width(distanceLabel);
        for (String line : extraLines) {
            textWidth = Math.max(textWidth, minecraft.font.width(line));
        }

        MarkerPanelLayout.Result layout = MarkerPanelLayout.layout(
                dotX, dotY, textWidth / 2, extraLines.size(), graphics.guiWidth(), graphics.guiHeight());
        Rect reservedBox = MissionWaypointLayout.reserve(mission, layout.box());
        if (!reservedBox.equals(layout.box())) {
            layout = layout.withBox(reservedBox);
        }

        graphics.fill(layout.box().left(), layout.box().top(), layout.box().right(), layout.box().bottom(), 0x90000000);
        graphics.fill(dotX - 3, dotY - 3, dotX + 3, dotY + 3, markerColorArgb);
        int textCenterX = layout.textCenterX();
        int textOriginY = layout.textOriginY();
        graphics.drawCenteredString(minecraft.font, distanceLabel, textCenterX + 1, textOriginY + 1, 0xFF000000);
        graphics.drawCenteredString(minecraft.font, distanceLabel, textCenterX, textOriginY, markerColorArgb);

        for (int i = 0; i < extraLines.size(); i++) {
            String line = extraLines.get(i);
            int lineY = layout.extraLineY(i);
            graphics.drawCenteredString(minecraft.font, line, textCenterX + 1, lineY + 1, 0xFF000000);
            graphics.drawCenteredString(minecraft.font, line, textCenterX, lineY, 0xFFFFFF);
        }
    }

    /**
     * Renders a compact status line (e.g. "wrong dimension") when there is no meaningful world
     * position to project a marker at. Placed top-centre: readable without requiring the player
     * to look away from where they are navigating, and distinct from the legacy corner HUD this
     * replaces. Callers must still call {@link MissionWaypointLayout#clear} for their mission in
     * this case, since no panel is reserved here.
     */
    public static void renderStatus(GuiGraphics graphics, Minecraft minecraft, String text, int colorArgb) {
        graphics.drawCenteredString(minecraft.font, text, graphics.guiWidth() / 2, 8, colorArgb);
    }
}
