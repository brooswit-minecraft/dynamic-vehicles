package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side options for riding the car. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue CHASE_CAMERA;
    public static final ModConfigSpec.DoubleValue CHASE_CAMERA_LAG;
    public static final ModConfigSpec.DoubleValue CHASE_CAMERA_RECENTER_SECONDS;

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
        SPEC = builder.build();
    }

    private ClientConfig() {}
}
