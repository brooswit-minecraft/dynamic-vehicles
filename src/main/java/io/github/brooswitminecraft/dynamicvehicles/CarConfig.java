package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config for the car. */
public final class CarConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue USE_SABLE_PHYSICS;
    public static final ModConfigSpec.BooleanValue WEAR_ENABLED;
    public static final ModConfigSpec.DoubleValue WEAR_STRENGTH;
    public static final ModConfigSpec.BooleanValue SMOKE_ENABLED;
    public static final ModConfigSpec.DoubleValue SMOKE_STRENGTH;
    public static final ModConfigSpec.IntValue SMOKE_INTERVAL_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        USE_SABLE_PHYSICS = builder
                .comment("Drive the car with Sable rigid-body physics (per-wheel suspension) instead of the simple "
                        + "kinematic model. Needs the Sable mod; without it the simple model is used regardless.")
                .define("useSablePhysics", true);
        WEAR_ENABLED = builder
                .comment("Cars wear the ground under their wheels, more while accelerating (needs Dynamic Terrain's vehicleErosion, default on; world erosion can stay off).")
                .define("wearEnabled", true);
        WEAR_STRENGTH = builder
                .comment("Multiplier on the acceleration/braking wear (0 = only real tire slip wears the ground).")
                .defineInRange("wearStrength", 1.0, 0.0, 10.0);
        SMOKE_ENABLED = builder
                .comment("Cars emit Dynamic Atmosphere exhaust (while on the throttle and moving) and dust (at speed or sliding). Needs Dynamic Atmosphere.")
                .define("smokeEnabled", true);
        SMOKE_STRENGTH = builder
                .comment("Multiplier on how much exhaust and dust each emission puts into the atmosphere (0 = none).")
                .defineInRange("smokeStrength", 1.0, 0.0, 10.0);
        SMOKE_INTERVAL_TICKS = builder
                .comment("Ticks between emissions per car (the per-car cap: one exhaust and one dust emission per interval).")
                .defineInRange("smokeIntervalTicks", 10, 5, 200);
        SPEC = builder.build();
    }

    private CarConfig() {}
}
