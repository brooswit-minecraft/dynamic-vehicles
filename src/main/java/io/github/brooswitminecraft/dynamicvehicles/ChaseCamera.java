package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Chase camera: while the player rides a car, their view yaw eases toward the car's heading each tick, so
 * you do not have to keep dragging the camera to look where you are driving. Pitch stays yours. Any mouse
 * movement is free look; after a pause the camera eases back. Because it moves the player's own yaw, it works
 * in first and third person alike.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class ChaseCamera {
    private static boolean tracking;
    private static float lastYaw;
    private static int idleTicks;
    /** Debug: the camera's yaw error against the car heading, degrees, for the harness log. */
    private static int logCountdown;

    private ChaseCamera() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !(player.getVehicle() instanceof CarEntity car) || !car.isRigidBody()
                || !ClientConfig.CHASE_CAMERA.get()) {
            tracking = false;
            return;
        }
        float playerYaw = player.getYRot();
        if (!tracking) {
            tracking = true;
            lastYaw = playerYaw;
            idleTicks = (int) Math.ceil(ClientConfig.CHASE_CAMERA_RECENTER_SECONDS.get() * 20.0); // start following at once
        } else if (Math.abs(Mth.wrapDegrees(playerYaw - lastYaw)) > 0.01f) {
            idleTicks = 0; // the mouse moved: free look
        } else {
            idleTicks++;
        }
        float newYaw = playerYaw;
        if (idleTicks >= ClientConfig.CHASE_CAMERA_RECENTER_SECONDS.get() * 20.0) {
            double lag = ClientConfig.CHASE_CAMERA_LAG.get();
            float k = (float) (1.0 - Math.exp(-0.05 / lag));
            newYaw = playerYaw + Mth.wrapDegrees(car.heading() - playerYaw) * k;
            player.setYRot(newYaw);
            player.setYHeadRot(newYaw);
        }
        lastYaw = newYaw;
        if (--logCountdown <= 0) {
            logCountdown = 20;
            DynamicVehiclesMod.LOGGER.debug("CHASE yawError={} carHeading={} cameraYaw={} idleTicks={}",
                    String.format("%.1f", Mth.wrapDegrees(car.heading() - newYaw)), String.format("%.1f", car.heading()),
                    String.format("%.1f", Mth.wrapDegrees(newYaw)), idleTicks);
        }
    }
}
