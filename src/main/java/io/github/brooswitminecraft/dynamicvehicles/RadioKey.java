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
 * N cycles the car radio (Off, then every server-configured station in order, then back to Off) while
 * driving a car (MINECRAFT-394). The wheel's configurable radio button ({@link WheelInput}) reaches the
 * same {@link RadioTogglePayload}.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class RadioKey {
    public static final KeyMapping TOGGLE = new KeyMapping("key.dynamicvehicles.radio",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.dynamicvehicles");

    private RadioKey() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        boolean riding = player != null && player.getVehicle() instanceof CarEntity;
        while (TOGGLE.consumeClick()) {
            if (riding) {
                PacketDistributor.sendToServer(new RadioTogglePayload());
            }
        }
    }
}
