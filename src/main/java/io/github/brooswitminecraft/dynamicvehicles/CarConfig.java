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
    public static final ModConfigSpec.IntValue WHEEL_SUB_STEPS;
    public static final ModConfigSpec.DoubleValue DRIFT_REAR_GRIP_SCALE;
    public static final ModConfigSpec.DoubleValue DRIFT_SLIP_ANGLE_THRESHOLD;
    public static final ModConfigSpec.DoubleValue DRIFT_GRIP_FALLOFF;
    public static final ModConfigSpec.DoubleValue DRIFT_HANDBRAKE_REAR_GRIP_CUT;
    public static final ModConfigSpec.DoubleValue DRIFT_THROTTLE_BITE;
    public static final ModConfigSpec.DoubleValue DRIFT_COUNTER_STEER_ASSIST;

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
        WHEEL_SUB_STEPS = builder
                .comment("Run the wheel force step this many times per tick (same raycasts, re-read velocity each time) "
                        + "so the tire's relaxation term and the suspension's damping stay stable at 20 Hz instead of "
                        + "overshooting between big, infrequent impulses. 3 is a reasonable middle: close to the "
                        + "stability of 4 at a lower cost, and clearly steadier than 2 under hard cornering/braking. "
                        + "1 is not a behaviour-identical \"off\": air drag is still applied after the (single) "
                        + "sub-step's impulses rather than before any wheel force, and the friction circle can still "
                        + "saturate differently than it did before sub-stepping existed (see SableCarBody.tick).")
                .defineInRange("wheelSubSteps", 3, 1, 4);
        // Drift tire tuning (MINECRAFT-144, retuned MINECRAFT-210): only read for a vehicle whose
        // VehicleSpec already opted into a non-identity TireTuning (currently DRIFT) - see DriftTireModel
        // and VehicleSpec.TireTuning. CAR, TRUCK and TROPHY carry VehicleSpec.TireTuning.IDENTITY
        // hard-wired in code, not from config, so these six values can never affect them regardless of
        // what an operator sets here. Defaults mirror VehicleSpec.DRIFT's own tireTuning() numbers, so a
        // fresh config changes nothing out of the box.
        DRIFT_REAR_GRIP_SCALE = builder
                .comment("A drift-tuned vehicle's rear tire grip (mu) as a fraction of its front's (1.0 = no front/rear split).")
                .defineInRange("driftRearGripScale", 0.88, 0.0, 1.0);
        DRIFT_SLIP_ANGLE_THRESHOLD = builder
                .comment("Lateral slip speed (m/s) below which a drift-tuned tire grips at its full (scaled) mu; beyond it grip starts falling off toward driftGripFalloff. Lower = slides start sooner.")
                .defineInRange("driftSlipAngleThreshold", 3.5, 0.01, 50.0);
        DRIFT_GRIP_FALLOFF = builder
                .comment("Fraction of grip a drift-tuned tire can lose once sliding well past driftSlipAngleThreshold (0 = no falloff curve at all, grip stays at the plain scaled mu regardless of slip).")
                .defineInRange("driftGripFalloff", 0.15, 0.0, 1.0);
        DRIFT_HANDBRAKE_REAR_GRIP_CUT = builder
                .comment("Extra multiplier on a drift-tuned rear tire's handbrake lateral-grip cut, on top of the cut every vehicle already gets (1.0 = no extra cut).")
                .defineInRange("driftHandbrakeRearGripCut", 0.8, 0.0, 1.0);
        DRIFT_THROTTLE_BITE = builder
                .comment("Fraction of a drift-tuned rear tire's grip given up to the drive force's own share of the friction circle (friction-circle style throttle-induced oversteer; 0 = no extra bite beyond the plain friction circle every vehicle already has).")
                .defineInRange("driftThrottleBite", 0.2, 0.0, 1.0);
        DRIFT_COUNTER_STEER_ASSIST = builder
                .comment("Fraction of a drift-tuned tire's grip lost to driftGripFalloff restored when the driver steers into the slide, i.e. countersteers (0 = no recovery assist, 1 = a full countersteer fully restores grip).")
                .defineInRange("driftCounterSteerAssist", 0.8, 0.0, 1.0);
        SPEC = builder.build();
    }

    private CarConfig() {}

    /**
     * The current config values for a drift-tuned vehicle's {@link DriftTireModel}, read live (so a
     * config reload takes effect without a restart) - callers must only use this for a {@code VehicleSpec}
     * whose own {@code tireTuning()} is already non-identity; see the drift tire tuning block above for why
     * {@code CAR}/{@code TRUCK}/{@code TROPHY} never reach here.
     */
    public static VehicleSpec.TireTuning driftTireTuning() {
        return new VehicleSpec.TireTuning(DRIFT_REAR_GRIP_SCALE.get(), DRIFT_SLIP_ANGLE_THRESHOLD.get(),
                DRIFT_GRIP_FALLOFF.get(), DRIFT_HANDBRAKE_REAR_GRIP_CUT.get(), DRIFT_THROTTLE_BITE.get(),
                DRIFT_COUNTER_STEER_ASSIST.get());
    }
}
