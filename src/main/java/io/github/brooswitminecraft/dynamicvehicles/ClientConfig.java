package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side options for riding the car. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CHASE_CAMERA;
    public static final ModConfigSpec.DoubleValue CHASE_CAMERA_LAG;
    public static final ModConfigSpec.DoubleValue CHASE_CAMERA_RECENTER_SECONDS;
    public static final ModConfigSpec.DoubleValue SOUND_VOLUME;
    public static final ModConfigSpec.BooleanValue HEADLIGHTS;
    public static final ModConfigSpec.DoubleValue HEADLIGHT_BEAM;
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
    public static final ModConfigSpec.DoubleValue WHEEL_LOCK_DEGREES;
    public static final ModConfigSpec.DoubleValue WHEEL_EFFECTIVE_DEGREES;
    public static final ModConfigSpec.DoubleValue WHEEL_STEER_CURVE;
    public static final ModConfigSpec.DoubleValue WHEEL_PEDAL_DEADZONE;
    public static final ModConfigSpec.IntValue WHEEL_HANDBRAKE_BUTTON;
    public static final ModConfigSpec.IntValue WHEEL_HONK_BUTTON;

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
        HEADLIGHTS = builder
                .comment("Draw car headlights (lamps and a faint beam; visual only, no real light). Press H in a car to cycle auto, on, off; auto is on at night and in rain.")
                .define("headlights", true);
        HEADLIGHT_BEAM = builder
                .comment("Beam length in blocks; 0 draws the lamps only.")
                .defineInRange("headlightBeamLength", 9.0, 0.0, 24.0);
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
        WHEEL_STEER_AXIS = builder.comment("Axis index of the steering wheel, or -1 for the device's built-in profile (Logitech G29 known; others 0). /dvwheel shows live axis values.")
                .defineInRange("steerAxisOverride", -1, -1, 31);
        WHEEL_THROTTLE_AXIS = builder.comment("Axis index of the throttle (accelerator) pedal, or -1 for the device's built-in profile (Logitech G29 known; others 2). With combinedPedals, the one axis holding both pedals.")
                .defineInRange("throttleAxisOverride", -1, -1, 31);
        WHEEL_BRAKE_AXIS = builder.comment("Axis index of the brake pedal, or -1 for the device's built-in profile (Logitech G29 known; others 3). Ignored with combinedPedals.")
                .defineInRange("brakeAxisOverride", -1, -1, 31);
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
        WHEEL_STEER_SCALE = builder.comment("Extra steering multiplier on top of the lock settings below; above 1 reaches full lock sooner.")
                .defineInRange("steerScale", 1.0, 0.1, 4.0);
        WHEEL_LOCK_DEGREES = builder.comment("How far the wheel turns lock to lock in degrees (G29: 900, or the operating range set in G HUB).")
                .defineInRange("lockDegrees", 900.0, 90.0, 2700.0);
        WHEEL_EFFECTIVE_DEGREES = builder.comment("Lock to lock in degrees that should steer the car fully, default 600 = a 1.5x gain on a 900 degree wheel; 360 would be 2.5x. Set equal to lockDegrees for the full range. Larger lockDegrees / smaller effectiveDegrees = more sensitive.")
                .defineInRange("effectiveDegrees", 600.0, 90.0, 2700.0);
        WHEEL_STEER_CURVE = builder.comment("Response curve: 1 = linear; above 1 is gentler near the centre and still reaches full lock (steer = travel^curve).")
                .defineInRange("steerCurve", 1.25, 1.0, 3.0);
        WHEEL_PEDAL_DEADZONE = builder.comment("Pedal travel (0-1) below this counts as released.")
                .defineInRange("pedalDeadzone", 0.03, 0.0, 0.9);
        WHEEL_HANDBRAKE_BUTTON = builder.comment("GLFW button index of the right shifter paddle (handbrake), or -1 to disable. "
                        + "Default 5 is an UNVERIFIED guess (common right-paddle index on G29-class wheels in community mappings, not measured on real hardware) "
                        + "-- confirm with /dvwheel's pressed-button list and correct if wrong.")
                .defineInRange("handbrakeButton", 5, -1, 31);
        WHEEL_HONK_BUTTON = builder.comment("GLFW button index of the left shifter paddle (honk), or -1 to disable. "
                        + "Default 4 is an UNVERIFIED guess (common left-paddle index on G29-class wheels in community mappings, not measured on real hardware) "
                        + "-- confirm with /dvwheel's pressed-button list and correct if wrong.")
                .defineInRange("honkButton", 4, -1, 31);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {}
}
