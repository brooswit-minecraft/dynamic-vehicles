package io.github.brooswitminecraft.dynamicvehicles;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.Command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Steering wheel and pedals (MINECRAFT-66). Reads a joystick-class device through GLFW, which Minecraft already
 * ships, and feeds the rider's movement input while riding a car. The car, the server and the physics are
 * untouched: the vanilla input packet already carries analog xxa/zza floats. The keyboard keeps working: the wheel
 * overrides a channel only while it is actually moving it.
 */
@EventBusSubscriber(modid = DynamicVehiclesMod.MODID, value = Dist.CLIENT)
public final class WheelInput {
    private static final String[] WHEEL_NAMES = {"wheel", "g29", "g920", "g923", "g27", "g25", "driving force", "racing", "steering", "simucube", "fanatec", "thrustmaster", "t300"};
    private static final int RESCAN_TICKS = 40;

    private static int device = -1;
    private static String deviceName = "";
    private static double detectedThrottleRest = Double.NaN;
    private static double detectedBrakeRest = Double.NaN;
    private static int rescanCountdown;
    private static float[] axes = new float[0];
    private static boolean wasRiding;

    private WheelInput() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(WheelInput::logDevices);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientConfig.WHEEL_ENABLED.get()) {
            return;
        }
        if (device >= 0 && !GLFW.glfwJoystickPresent(device)) {
            DynamicVehiclesMod.LOGGER.info("Wheel disconnected: {}", deviceName);
            device = -1;
            axes = new float[0];
        }
        if (device < 0) {
            if (rescanCountdown-- > 0) {
                return;
            }
            rescanCountdown = RESCAN_TICKS;
            device = findDevice();
            if (device >= 0) {
                deviceName = String.valueOf(GLFW.glfwGetJoystickName(device));
                detectedThrottleRest = Double.NaN;
                detectedBrakeRest = Double.NaN;
                DynamicVehiclesMod.LOGGER.info("Wheel selected: joystick {} \"{}\"", device, deviceName);
                logDevices();
            }
        }
        if (device < 0) {
            return;
        }
        axes = readAxes(device);
        boolean riding = isRiding();
        // Latch each pedal's released value once, when it is seen sitting at an end of its axis outside a car.
        if (!riding) {
            if (Double.isNaN(detectedThrottleRest)) {
                detectedThrottleRest = WheelMapping.detectRest(WheelMapping.axis(axes, ClientConfig.WHEEL_THROTTLE_AXIS.get()));
            }
            if (Double.isNaN(detectedBrakeRest)) {
                detectedBrakeRest = WheelMapping.detectRest(WheelMapping.axis(axes, ClientConfig.WHEEL_BRAKE_AXIS.get()));
            }
        } else if (!wasRiding) {
            DynamicVehiclesMod.LOGGER.info("Wheel in use: \"{}\" axes={} pedalRest throttle={} brake={}", deviceName, axes.length,
                    WheelMapping.rest(ClientConfig.WHEEL_PEDAL_REST.get(), detectedThrottleRest),
                    WheelMapping.rest(ClientConfig.WHEEL_PEDAL_REST.get(), detectedBrakeRest));
        }
        wasRiding = riding;
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (device < 0 || !(event.getEntity().getVehicle() instanceof CarEntity) || !ClientConfig.WHEEL_ENABLED.get()) {
            return;
        }
        WheelMapping.Output out = current();
        var input = event.getInput();
        input.leftImpulse = WheelMapping.merge(input.leftImpulse, out.steer());
        input.forwardImpulse = WheelMapping.merge(input.forwardImpulse, out.forward());
    }

    @SubscribeEvent
    public static void onClientCommands(RegisterClientCommandsEvent event) {
        // /dvwheel: lists joysticks and shows the live axes, to find the axis numbers for the config.
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("dvwheel").executes(context -> {
            say("wheel " + (device >= 0 ? "joystick " + device + " \"" + deviceName + "\"" : "not found")
                    + (ClientConfig.WHEEL_ENABLED.get() ? "" : " (disabled in config)"));
            StringBuilder sb = new StringBuilder("axes:");
            for (int i = 0; i < axes.length; i++) {
                sb.append(String.format(Locale.ROOT, " %d=%.2f", i, axes[i]));
            }
            say(sb.toString());
            if (device >= 0) {
                WheelMapping.Output out = current();
                say(String.format(Locale.ROOT, "mapped: steer(+left)=%.2f throttle=%.2f brake=%.2f; buttons down: %s",
                        out.steer(), out.throttle(), out.brake(), pressedButtons(device)));
            }
            logDevices();
            return Command.SINGLE_SUCCESS;
        }));
    }

    private static WheelMapping.Output current() {
        WheelMapping.Settings settings = new WheelMapping.Settings(
                ClientConfig.WHEEL_STEER_AXIS.get(), ClientConfig.WHEEL_THROTTLE_AXIS.get(), ClientConfig.WHEEL_BRAKE_AXIS.get(),
                ClientConfig.WHEEL_INVERT_STEER.get(), ClientConfig.WHEEL_COMBINED_PEDALS.get(), ClientConfig.WHEEL_INVERT_PEDALS.get(),
                ClientConfig.WHEEL_STEER_DEADZONE.get(), ClientConfig.WHEEL_STEER_SCALE.get(), ClientConfig.WHEEL_PEDAL_DEADZONE.get());
        var mode = ClientConfig.WHEEL_PEDAL_REST.get();
        return WheelMapping.map(axes, settings, WheelMapping.rest(mode, detectedThrottleRest), WheelMapping.rest(mode, detectedBrakeRest));
    }

    private static boolean isRiding() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.getVehicle() instanceof CarEntity;
    }

    private static int findDevice() {
        int configured = ClientConfig.WHEEL_DEVICE.get();
        if (configured >= 0) {
            return GLFW.glfwJoystickPresent(configured) ? configured : -1;
        }
        String filter = ClientConfig.WHEEL_NAME_CONTAINS.get().toLowerCase(Locale.ROOT).trim();
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (!GLFW.glfwJoystickPresent(jid)) {
                continue;
            }
            String name = String.valueOf(GLFW.glfwGetJoystickName(jid)).toLowerCase(Locale.ROOT);
            if (!filter.isEmpty()) {
                if (name.contains(filter)) {
                    return jid;
                }
                continue;
            }
            for (String wheel : WHEEL_NAMES) {
                if (name.contains(wheel)) {
                    return jid;
                }
            }
        }
        return -1;
    }

    private static float[] readAxes(int jid) {
        FloatBuffer buffer = GLFW.glfwGetJoystickAxes(jid);
        if (buffer == null) {
            return new float[0];
        }
        float[] out = new float[buffer.remaining()];
        buffer.get(buffer.position(), out);
        return out;
    }

    private static String pressedButtons(int jid) {
        ByteBuffer buttons = GLFW.glfwGetJoystickButtons(jid);
        if (buttons == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buttons.remaining(); i++) {
            if (buttons.get(buttons.position() + i) != 0) {
                sb.append(i).append(' ');
            }
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    /** The startup debug line: every joystick GLFW can see, with its axis, button and hat counts. */
    static void logDevices() {
        boolean any = false;
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (!GLFW.glfwJoystickPresent(jid)) {
                continue;
            }
            any = true;
            FloatBuffer a = GLFW.glfwGetJoystickAxes(jid);
            ByteBuffer b = GLFW.glfwGetJoystickButtons(jid);
            ByteBuffer h = GLFW.glfwGetJoystickHats(jid);
            DynamicVehiclesMod.LOGGER.info("Joystick {}: \"{}\" guid={} axes={} buttons={} hats={}{}", jid, GLFW.glfwGetJoystickName(jid),
                    GLFW.glfwGetJoystickGUID(jid), a == null ? 0 : a.remaining(), b == null ? 0 : b.remaining(), h == null ? 0 : h.remaining(),
                    jid == device ? " (selected)" : "");
        }
        if (!any) {
            DynamicVehiclesMod.LOGGER.info("Joystick: none detected (wheel input idle; plugging one in is picked up automatically)");
        }
    }

    private static void say(String text) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(text));
    }
}
