package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Chase camera: while the player rides a car, their view eases toward the car's full orientation each tick, so
 * you do not have to keep dragging the camera to look where you are driving. Any mouse movement is free look;
 * after a pause the camera eases back. Yaw and pitch move the player's view; roll is applied at camera render
 * time because Minecraft players have no roll state.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class ChaseCamera {
    private static boolean tracking;
    private static float lastYaw;
    private static float lastPitch;
    private static float cameraRoll;
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
            cameraRoll = 0.0f;
            return;
        }
        float playerYaw = player.getYRot();
        float playerPitch = player.getXRot();
        if (!tracking) {
            tracking = true;
            lastYaw = playerYaw;
            lastPitch = playerPitch;
            idleTicks = (int) Math.ceil(ClientConfig.CHASE_CAMERA_RECENTER_SECONDS.get() * 20.0); // start following at once
        } else if (Math.abs(Mth.wrapDegrees(playerYaw - lastYaw)) > 0.01f || Math.abs(playerPitch - lastPitch) > 0.01f) {
            idleTicks = 0; // the mouse moved: free look
        } else {
            idleTicks++;
        }
        float newYaw = playerYaw;
        float newPitch = playerPitch;
        if (idleTicks >= ClientConfig.CHASE_CAMERA_RECENTER_SECONDS.get() * 20.0) {
            double lag = ClientConfig.CHASE_CAMERA_LAG.get();
            float k = (float) (1.0 - Math.exp(-0.05 / lag));
            org.joml.Quaternionf orientation = car.renderOrientation(1.0f);
            ChaseCameraMath.Angles target = ChaseCameraMath.angles(orientation.x, orientation.y, orientation.z, orientation.w);
            newYaw = playerYaw + Mth.wrapDegrees(target.yaw() - playerYaw) * k;
            newPitch = playerPitch + (target.pitch() - playerPitch) * k;
            player.setYRot(newYaw);
            player.setYHeadRot(newYaw);
            player.setXRot(newPitch);
            cameraRoll += Mth.wrapDegrees(target.roll() - cameraRoll) * k;
        }
        lastYaw = newYaw;
        lastPitch = newPitch;
        if (--logCountdown <= 0) {
            logCountdown = 20;
            DynamicVehiclesMod.LOGGER.debug("CHASE yawError={} carHeading={} cameraYaw={} cameraPitch={} cameraRoll={} idleTicks={}",
                    String.format("%.1f", Mth.wrapDegrees(car.heading() - newYaw)), String.format("%.1f", car.heading()),
                    String.format("%.1f", Mth.wrapDegrees(newYaw)), String.format("%.1f", newPitch),
                    String.format("%.1f", cameraRoll), idleTicks);
        }
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (tracking && player != null && player.getVehicle() instanceof CarEntity car && car.isRigidBody()
                && ClientConfig.CHASE_CAMERA.get()) {
            event.setRoll(cameraRoll);
        }
    }
}
