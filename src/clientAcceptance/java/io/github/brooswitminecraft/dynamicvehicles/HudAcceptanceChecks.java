package io.github.brooswitminecraft.dynamicvehicles;

import io.github.brooswitminecraft.dynamicvehicles.hud.DrivingHudLayout;
import io.github.brooswitminecraft.dynamicvehicles.hud.DrivingHudOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Test-only real-client probes of the production event handler; no production test switch. */
final class HudAcceptanceChecks {
    static void verify(Minecraft mc, boolean driving) {
        var originalMode = mc.gameMode.getPlayerMode();
        int originalScale = mc.options.guiScale().get();
        boolean originalHidden = mc.options.hideGui;
        int originalLeft = mc.gui.leftHeight, originalRight = mc.gui.rightHeight;
        try {
            for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
                mc.gameMode.setLocalMode(mode);
                for (int scale : new int[] {1, 2, 3, 4}) {
                    mc.options.guiScale().set(scale);
                    mc.resizeDisplay();
                    for (boolean hidden : new boolean[] {false, true}) {
                        mc.options.hideGui = hidden;
                        boolean expected = driving && !hidden;
                        mc.gui.leftHeight = mc.gui.rightHeight = 39;
                        for (ResourceLocation layer : new ResourceLocation[] {VanillaGuiLayers.EXPERIENCE_BAR,
                                VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL,
                                VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.EXPERIENCE_LEVEL}) {
                            var event = new RenderGuiLayerEvent.Pre(null, null, layer, null);
                            DrivingHudOverlay.replacePlayerLayers(event);
                            check(event.isCanceled() == expected, "HUD cancellation/restoration: " + layer);
                        }
                        var name = new RenderGuiLayerEvent.Pre(null, null, VanillaGuiLayers.SELECTED_ITEM_NAME, null);
                        DrivingHudOverlay.replacePlayerLayers(name);
                        check(!name.isCanceled(), "selected item name must remain visible");
                        if (!expected) {
                            check(mc.gui.leftHeight == 39 && mc.gui.rightHeight == 39,
                                    "HUD reservations leaked after exit/F1");
                        } else {
                            int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
                            var layout = DrivingHudLayout.at(w, h);
                            int y = h - Math.max(Math.max(mc.gui.leftHeight, mc.gui.rightHeight), 59)
                                    + (mc.gameMode.canHurtPlayer() ? 0 : 14);
                            int textWidth = mc.font.width("A very long selected item name spanning every driving gauge");
                            var label = new DrivingHudLayout.Rect((w-textWidth)/2, y, textWidth, mc.font.lineHeight);
                            var hotbar = new DrivingHudLayout.Rect(w/2-91, h-22, 182, 22);
                            for (var rect : new DrivingHudLayout.Rect[] {layout.health(), layout.speed(), layout.slip(), layout.rpm()}) {
                                check(!rect.intersects(label), "selected item overlap: " + mode + " scale=" + scale);
                                check(!rect.intersects(hotbar), "hotbar overlap: scale=" + scale);
                            }
                        }
                    }
                }
            }
            DynamicVehiclesMod.LOGGER.info("CLIENT_ACCEPTANCE_HUD_PASS driving={} modes=survival,creative scales=1,2,3,4 hidden=true,false", driving);
        } finally {
            mc.gameMode.setLocalMode(originalMode);
            mc.options.guiScale().set(originalScale);
            mc.options.hideGui = originalHidden;
            mc.resizeDisplay();
            mc.gui.leftHeight = originalLeft;
            mc.gui.rightHeight = originalRight;
        }
    }

    private static void check(boolean pass, String message) {
        if (!pass) throw new IllegalStateException(message);
    }
}
