package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side options for riding the car. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CHASE_CAMERA;
    public static final ModConfigSpec.DoubleValue CHASE_CAMERA_LAG;
    public static final ModConfigSpec.DoubleValue CHASE_CAMERA_RECENTER_SECONDS;
    public static final ModConfigSpec.DoubleValue SOUND_VOLUME;
    public static final ModConfigSpec.BooleanValue WHEEL_ENABLED;
    public static final ModConfigSpec.IntValue WHEEL_DEVICE;
    public static final ModConfigSpec.ConfigValue<String> WHEEL_NAME_CONTAINS;
    public static final ModConfigSpec.IntValue WHEEL_STEER_AXIS;
    public static final ModConfigSpec.IntValue WHEEL_THROTTLE_AXIS;
    public static final ModConfigSpec.IntValue WHEEL_BRAKE_AXIS;
    public static final ModConfigSpec.BooleanValue WHEEL_INVERT_STEER;
    public static final ModConfigSpec.BooleanValue WHEEL_COMBINED_PEDALS;
    public static final ModConfigSpec.BooleanValue WHEEL_INVERT_PEDALS;
    public static final ModConfigSpec.EnumValue<WheelMapping.PedalRest> WHEEL_PEDAL_REST;
    public static final ModConfigSpec.DoubleValue WHEEL_STEER_DEADZONE;
    public static final ModConfigSpec.DoubleValue WHEEL_STEER_SCALE;
    public static final ModConfigSpec.DoubleValue WHEEL_PEDAL_DEADZONE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CHASE_CAMERA = builder
                .comment("While riding the car, the camera yaw follows the car's heading. Moving the mouse is a temporary free look.")
                .define("chaseCamera", true);
        CHASE_CAMERA_LAG = builder
                .comment("Seconds the camera takes to catch up with the car's heading (larger = lazier).")
                .defineInRange("chaseCameraLag", 0.25, 0.02, 3.0);
        CHASE_CAMERA_RECENTER_SECONDS = builder
                .comment("Seconds without mouse movement before the camera starts following the car again.")
                .defineInRange("chaseCameraRecenterSeconds", 1.5, 0.0, 10.0);
        SOUND_VOLUME = builder
                .comment("Volume of the car's looping engine and tire sounds, 0 to 1 (on top of Minecraft's own sound sliders).")
                .defineInRange("soundVolume", 1.0, 0.0, 1.0);
        builder.push("wheel");
        WHEEL_ENABLED = builder
                .comment("Drive the car with a steering wheel and pedals (any joystick-class device GLFW sees). The keyboard keeps working alongside it.")
                .define("enabled", true);
        WHEEL_DEVICE = builder
                .comment("GLFW joystick index 0-15, or -1 to pick the first device whose name looks like a wheel (or matches nameContains). The startup log and /dvwheel list the devices.")
                .defineInRange("device", -1, -1, 15);
        WHEEL_NAME_CONTAINS = builder
                .comment("When device is -1, pick the first device whose name contains this text (case-insensitive). Empty = built-in wheel names (wheel, G29, G920, G923, G27, Driving Force, Racing...).")
                .define("nameContains", "");
        WHEEL_STEER_AXIS = builder.comment("Axis index of the steering wheel. /dvwheel shows live axis values.")
                .defineInRange("steerAxis", 0, 0, 31);
        WHEEL_THROTTLE_AXIS = builder.comment("Axis index of the throttle (accelerator) pedal. With combinedPedals, the one axis holding both pedals.")
                .defineInRange("throttleAxis", 2, 0, 31);
        WHEEL_BRAKE_AXIS = builder.comment("Axis index of the brake pedal. Ignored with combinedPedals.")
                .defineInRange("brakeAxis", 3, 0, 31);
        WHEEL_INVERT_STEER = builder.comment("Flip steering direction (default: axis + = wheel turned right).")
                .define("invertSteer", false);
        WHEEL_COMBINED_PEDALS = builder.comment("Throttle and brake share one axis (centred = neither, one end = throttle, the other = brake).")
                .define("combinedPedals", false);
        WHEEL_INVERT_PEDALS = builder.comment("With combinedPedals: swap which end of the axis is throttle.")
                .define("invertPedals", false);
        WHEEL_PEDAL_REST = builder.comment("Axis value of a released pedal: AUTO reads it when the device is found (keep the pedals released then), HIGH = +1, LOW = -1.")
                .defineEnum("pedalRest", WheelMapping.PedalRest.AUTO);
        WHEEL_STEER_DEADZONE = builder.comment("Steering axis values smaller than this count as straight ahead.")
                .defineInRange("steerDeadzone", 0.03, 0.0, 0.9);
        WHEEL_STEER_SCALE = builder.comment("Steering multiplier; above 1 reaches full lock before the wheel is fully turned.")
                .defineInRange("steerScale", 1.0, 0.1, 4.0);
        WHEEL_PEDAL_DEADZONE = builder.comment("Pedal travel (0-1) below this counts as released.")
                .defineInRange("pedalDeadzone", 0.03, 0.0, 0.9);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {}
}
