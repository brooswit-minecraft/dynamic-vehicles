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
        // Reservations use the same scaled-pixel bounds as the actual gauges. Creative's
        // selected-item label adds 14 pixels after applying left/rightHeight, unlike survival.
        if (layer.equals(VanillaGuiLayers.PLAYER_HEALTH)) {
            minecraft.gui.leftHeight = Math.max(minecraft.gui.leftHeight, DrivingHudLayout.AIR_OFFSET);
        } else if (layer.equals(VanillaGuiLayers.ARMOR_LEVEL)) {
            minecraft.gui.leftHeight = Math.max(minecraft.gui.leftHeight, DrivingHudLayout.LEFT_OFFSET);
        } else if (layer.equals(VanillaGuiLayers.FOOD_LEVEL)) {
            minecraft.gui.rightHeight = Math.max(minecraft.gui.rightHeight, DrivingHudLayout.AIR_OFFSET);
        } else if (layer.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) {
            minecraft.gui.leftHeight = Math.max(minecraft.gui.leftHeight,
                    DrivingHudLayout.itemNameOffset(minecraft.gameMode.canHurtPlayer()));
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
        var layout = DrivingHudLayout.at(graphics.guiWidth(), graphics.guiHeight());
        bar(graphics, minecraft, layout.health(), car.clientHealthFraction(), 0xFF943D3D,
                "Car " + Math.round(car.clientHealthFraction() * 100) + "%");
        bar(graphics, minecraft, layout.speed(),
                DrivingHudPresentation.fraction(car.clientDrivingSpeed(), car.clientTopSpeed()), 0xFF86602C,
                DrivingHudPresentation.speed(car.clientDrivingSpeed()));
        bar(graphics, minecraft, layout.slip(),
                DrivingHudPresentation.slipFraction(car.clientTireSlip()), 0xFF606F86, "Tire slip");
        bar(graphics, minecraft, layout.rpm(),
                DrivingHudPresentation.fraction(car.clientDrivingRpm(), DrivetrainMath.LIMITER_RPM), 0xFF487C32,
                DrivingHudPresentation.rpm(car.clientDrivingRpm()));
    }

    private static void bar(GuiGraphics graphics, Minecraft minecraft, DrivingHudLayout.Rect bounds,
                            double fraction, int color, String text) {
        graphics.fill(bounds.x(), bounds.y(), bounds.right(), bounds.bottom(), 0xD0202020);
        graphics.fill(bounds.x(), bounds.y(), bounds.x() + (int) Math.round(bounds.width()
                * DrivingHudPresentation.fraction(fraction, 1)), bounds.bottom(), color);
        graphics.drawCenteredString(minecraft.font, text, bounds.x() + bounds.width() / 2, bounds.y() + 1, 0xFFFFFF);
    }
}
