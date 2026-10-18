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
 * <p>MINECRAFT-411: extracted (pixel layout unchanged for the zero-extra-lines
 * case) from {@code delivery.ContractWaypointOverlay} so Dispatcher's contract
 * waypoint and Pathfinder's mission waypoint render through one code path
 * instead of a second divergent imitation. Each caller still owns its own
 * marker colour and label text - this class only owns the shared projection,
 * clamping and layout.
 */
public final class MissionWaypointRenderer {
    private static final double EDGE_INSET = 0.05;
    private static final int LINE_HEIGHT = 10;

    private MissionWaypointRenderer() {
    }

    /**
     * Renders the marker for a destination at {@code (worldX, worldY, worldZ)}, relative to the
     * current render camera. {@code extraLines} are drawn directly below the distance label, top
     * to bottom, in the supplied order; pass an empty list for a Dispatcher-style marker with no
     * extra lines.
     */
    public static void renderMarker(
            GuiGraphics graphics, Minecraft minecraft,
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
        int x = (int) Math.round(point.x() * graphics.guiWidth());
        int y = (int) Math.round(point.y() * graphics.guiHeight());

        int textWidth = minecraft.font.width(distanceLabel);
        for (String line : extraLines) {
            textWidth = Math.max(textWidth, minecraft.font.width(line));
        }
        // Small backing and shadow retain contrast without a full opaque badge; the box grows
        // downward by one line per extra status line, matching the original Dispatcher-only box
        // bottom (y + 18) when there are none.
        int boxBottom = y + 18 + LINE_HEIGHT * extraLines.size();
        graphics.fill(x - 7, y - 7, x + Math.max(8, textWidth / 2 + 7), boxBottom, 0x90000000);
        graphics.fill(x - 3, y - 3, x + 3, y + 3, markerColorArgb);
        graphics.drawCenteredString(minecraft.font, distanceLabel, x + 1, y + 7, 0xFF000000);
        graphics.drawCenteredString(minecraft.font, distanceLabel, x, y + 6, markerColorArgb);

        int lineY = y + 17;
        for (String line : extraLines) {
            graphics.drawCenteredString(minecraft.font, line, x + 1, lineY + 1, 0xFF000000);
            graphics.drawCenteredString(minecraft.font, line, x, lineY, 0xFFFFFF);
            lineY += LINE_HEIGHT;
        }
    }

    /**
     * Renders a compact status line (e.g. "wrong dimension") when there is no meaningful world
     * position to project a marker at. Placed top-centre: readable without requiring the player
     * to look away from where they are navigating, and distinct from the legacy corner HUD this
     * replaces.
     */
    public static void renderStatus(GuiGraphics graphics, Minecraft minecraft, String text, int colorArgb) {
        graphics.drawCenteredString(minecraft.font, text, graphics.guiWidth() / 2, 8, colorArgb);
    }
}
