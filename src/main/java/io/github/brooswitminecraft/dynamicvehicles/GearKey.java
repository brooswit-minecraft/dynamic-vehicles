package io.github.brooswitminecraft.dynamicvehicles;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * R toggles the car's explicit reverse gear (MINECRAFT-228), on keyboard or on any controller the player
 * rebinds this {@link KeyMapping} to in the Controls menu. Physical H-pattern/sequential shifter hardware
 * is a separate, later caller of {@link CarEntity#toggleGear()}'s underlying gear state -- out of scope
 * here.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class GearKey {
    public static final KeyMapping TOGGLE_REVERSE = new KeyMapping("key.dynamicvehicles.gear_reverse",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.dynamicvehicles");

    private GearKey() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_REVERSE);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        boolean riding = player != null && player.getVehicle() instanceof CarEntity;
        while (TOGGLE_REVERSE.consumeClick()) {
            if (riding) {
                PacketDistributor.sendToServer(new GearTogglePayload());
            }
        }
    }
}
