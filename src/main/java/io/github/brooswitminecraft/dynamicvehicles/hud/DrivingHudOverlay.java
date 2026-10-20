package io.github.brooswitminecraft.dynamicvehicles.hud;

import io.github.brooswitminecraft.dynamicvehicles.CarEntity;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

/** Compact dashboard for the local driver, using server-measured shaft RPM and speed. */
public final class DrivingHudOverlay implements LayeredDraw.Layer {
    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !(minecraft.player.getVehicle() instanceof CarEntity car)) return;
        if (!DrivingHudPresentation.visible(minecraft.options.hideGui, true,
                car.getControllingPassenger() == minecraft.player, car.isRigidBody())) return;
        String speed = DrivingHudPresentation.speed(car.clientDrivingSpeed());
        String rpm = DrivingHudPresentation.rpm(car.clientDrivingRpm());
        int width = Math.max(minecraft.font.width(speed), minecraft.font.width(rpm));
        int x = graphics.guiWidth() - width - 12;
        int y = graphics.guiHeight() - 38;
        graphics.fill(x - 5, y - 4, x + width + 5, y + 23, 0xA0000000);
        graphics.drawString(minecraft.font, speed, x, y, 0xFFFFFF);
        graphics.drawString(minecraft.font, rpm, x, y + 12, 0xDDDDDD);
    }
}
