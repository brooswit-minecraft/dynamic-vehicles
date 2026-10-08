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
    public static final ModConfigSpec.BooleanValue COLLISION_BREAKING;
    public static final ModConfigSpec.DoubleValue COLLISION_MIN_SPEED;
    public static final ModConfigSpec.DoubleValue COLLISION_MAX_HARDNESS;
    public static final ModConfigSpec.BooleanValue HEADLAMP_LIGHT;
    public static final ModConfigSpec.BooleanValue TIRE_FORCE_AT_CONTACT;
    public static final ModConfigSpec.DoubleValue ANTI_ROLL;

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
                .defineInRange("wearMultiplier", 2.5, 0.0, 10.0);
        SMOKE_ENABLED = builder
                .comment("Cars emit Dynamic Atmosphere exhaust (while on the throttle and moving) and dust (at speed or sliding). Needs Dynamic Atmosphere.")
                .define("smokeEnabled", true);
        SMOKE_STRENGTH = builder
                .comment("Multiplier on how much exhaust and dust each emission puts into the atmosphere (0 = none).")
                .defineInRange("smokeMultiplier", 2.5, 0.0, 10.0);
        SMOKE_INTERVAL_TICKS = builder
                .comment("Ticks between emissions per car (the per-car cap: one exhaust and one dust emission per interval).")
                .defineInRange("smokeEmitTicks", 6, 5, 200);
        COLLISION_BREAKING = builder
                .comment("A hard hit has a chance to break the block ahead (more likely when faster and for softer blocks); a block that survives is eroded instead. Never breaks unbreakable blocks, block entities or fluids, and at most one block per impact.")
                .define("collisionBreaking", true);
        COLLISION_MIN_SPEED = builder
                .comment("Speed in m/s before the car was stopped below which a hit never breaks a block.")
                .defineInRange("collisionMinSpeed", 8.0, 0.0, 100.0);
        COLLISION_MAX_HARDNESS = builder
                .comment("Hardest block (vanilla destroy speed: dirt 0.5, stone 1.5, logs 2.0, iron blocks 5.0, obsidian 50) a car can break.")
                .defineInRange("collisionMaxHardness", 3.0, 0.0, 100.0);
        HEADLAMP_LIGHT = builder
                .comment("While the headlamps are on and the car is driven, place one invisible light block a few blocks ahead (only into air). All of them are recorded and removed when the lamps go off, the car unloads, or on the next server start.")
                .define("headlampLight", true);
        TIRE_FORCE_AT_CONTACT = builder
                .comment("Apply each wheel's tire force (drive, brake, grip) at its contact point on the ground instead of at body height. Realistic weight transfer; the anti-roll bars keep the car upright.")
                .define("tireForceAtContact", true);
        ANTI_ROLL = builder
                .comment("Anti-roll bar stiffness as a multiple of the wheel spring rate (0 = none).")
                .defineInRange("antiRollRatio", 1.0, 0.0, 10.0);
        SPEC = builder.build();
    }

    private CarConfig() {}
}
