package io.github.brooswitminecraft.dynamicvehicles.hud;

import io.github.brooswitminecraft.dynamicvehicles.CarEntity;
import io.github.brooswitminecraft.dynamicvehicles.DrivetrainMath;
import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Stateless replacement: ending the driving state immediately restores vanilla rendering. */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class DrivingHudOverlay implements LayeredDraw.Layer {
    static CarEntity drivenCar(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.player.isSpectator()
                || !(minecraft.player.getVehicle() instanceof CarEntity car)) return null;
        return DrivingHudPresentation.visible(minecraft.options.hideGui, true,
                car.getControllingPassenger() == minecraft.player, car.isRigidBody()) ? car : null;
    }

    @SubscribeEvent
    public static void replacePlayerLayers(RenderGuiLayerEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (drivenCar(minecraft) == null) return;
        var layer = event.getName();
        // Vanilla's canceled layers normally reserve space for later air/status overlays.
        // Reserve our 12-pixel rows too, so breathing bubbles and item names do not overlap.
        if (layer.equals(VanillaGuiLayers.PLAYER_HEALTH) || layer.equals(VanillaGuiLayers.ARMOR_LEVEL)) {
            minecraft.gui.leftHeight += 12;
        } else if (layer.equals(VanillaGuiLayers.FOOD_LEVEL)) {
            minecraft.gui.rightHeight += 12;
        }
        if (layer.equals(VanillaGuiLayers.PLAYER_HEALTH) || layer.equals(VanillaGuiLayers.FOOD_LEVEL)
                || layer.equals(VanillaGuiLayers.ARMOR_LEVEL) || layer.equals(VanillaGuiLayers.EXPERIENCE_BAR)
                || layer.equals(VanillaGuiLayers.EXPERIENCE_LEVEL) || layer.equals(VanillaGuiLayers.VEHICLE_HEALTH)) {
            event.setCanceled(true);
        }
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        CarEntity car = drivenCar(minecraft);
        if (car == null) return;
        int left = graphics.guiWidth() / 2 - 91, bottom = graphics.guiHeight();
        bar(graphics, minecraft, left, bottom - 41, 81, car.clientHealthFraction(), 0xFF943D3D,
                "Car " + Math.round(car.clientHealthFraction() * 100) + "%");
        bar(graphics, minecraft, left + 101, bottom - 41, 81,
                DrivingHudPresentation.fraction(car.clientDrivingSpeed(), car.clientTopSpeed()), 0xFF86602C,
                DrivingHudPresentation.speed(car.clientDrivingSpeed()));
        bar(graphics, minecraft, left, bottom - 53, 81,
                DrivingHudPresentation.slipFraction(car.clientTireSlip()), 0xFF606F86, "Tire slip");
        bar(graphics, minecraft, left, bottom - 29, 182,
                DrivingHudPresentation.fraction(car.clientDrivingRpm(), DrivetrainMath.LIMITER_RPM), 0xFF487C32,
                DrivingHudPresentation.rpm(car.clientDrivingRpm()));
    }

    private static void bar(GuiGraphics graphics, Minecraft minecraft, int x, int y, int width,
                            double fraction, int color, String text) {
        graphics.fill(x, y, x + width, y + 10, 0xD0202020);
        graphics.fill(x, y, x + (int) Math.round(width * DrivingHudPresentation.fraction(fraction, 1)), y + 10, color);
        graphics.drawCenteredString(minecraft.font, text, x + width / 2, y + 1, 0xFFFFFF);
    }
}
