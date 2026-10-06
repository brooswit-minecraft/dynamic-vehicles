package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config for the car. */
public final class CarConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue USE_SABLE_PHYSICS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        USE_SABLE_PHYSICS = builder
                .comment("Drive the car with Sable rigid-body physics (per-wheel suspension) instead of the simple "
                        + "kinematic model. Needs the Sable mod; without it the simple model is used regardless.")
                .define("useSablePhysics", true);
        SPEC = builder.build();
    }

    private CarConfig() {}
}
