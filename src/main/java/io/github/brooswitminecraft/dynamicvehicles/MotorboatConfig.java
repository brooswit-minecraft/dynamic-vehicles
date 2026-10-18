package io.github.brooswitminecraft.dynamicvehicles;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * MINECRAFT-410: server config for the rigid-body motorboat (buoyancy, propulsion and drag/damping
 * tunables), mirroring {@link CarConfig}'s own {@code ModConfigSpec} builder idiom exactly. Kept separate
 * from {@code CarConfig} (its own file, its own registration below) rather than folded in, so the
 * car-drivetrain config this ticket must not touch (see the car-branch-conflict note on this epic) stays
 * byte-for-byte unchanged.
 */
public final class MotorboatConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue MASS_KG;
    public static final ModConfigSpec.DoubleValue HULL_BUOYANCY_STIFFNESS;
    public static final ModConfigSpec.DoubleValue HULL_BUOYANCY_DAMPING;
    public static final ModConfigSpec.DoubleValue MAX_POINT_BUOYANT_FORCE;
    public static final ModConfigSpec.DoubleValue MAX_THRUST;
    public static final ModConfigSpec.DoubleValue MAX_STEER_DEG;
    public static final ModConfigSpec.DoubleValue LONGITUDINAL_DRAG_RATE;
    public static final ModConfigSpec.DoubleValue LATERAL_DRAG_RATE;
    public static final ModConfigSpec.DoubleValue ANGULAR_DAMPING_RATE;
    public static final ModConfigSpec.DoubleValue VERTICAL_DAMPING_RATE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MASS_KG = builder
                .comment("Motorboat mass, kg.")
                .defineInRange("massKg", 300.0, 10.0, 10_000.0);
        HULL_BUOYANCY_STIFFNESS = builder
                .comment("Per-hull-point buoyancy spring rate, N/m of that point's own submersion depth. Four points (front/rear x port/starboard) each get their own independent spring, so the boat pitches/rolls/heaves from real differential immersion rather than a single whole-body column.")
                .defineInRange("hullBuoyancyStiffness", 3000.0, 0.0, 200_000.0);
        HULL_BUOYANCY_DAMPING = builder
                .comment("Per-hull-point buoyancy damping, N*s/m opposing that point's own vertical velocity -- keeps the spring settling instead of ringing forever.")
                .defineInRange("hullBuoyancyDamping", 700.0, 0.0, 200_000.0);
        MAX_POINT_BUOYANT_FORCE = builder
                .comment("Hard cap on a single hull point's buoyant force, N. Bounds a sudden deep-submersion spike (e.g. the boat was just teleported into a lake) so it can never produce an exploding one-tick impulse.")
                .defineInRange("maxPointBuoyantForce", 6000.0, 0.0, 2_000_000.0);
        MAX_THRUST = builder
                .comment("Motor's maximum thrust force at full throttle, N.")
                .defineInRange("maxThrust", 3000.0, 0.0, 200_000.0);
        MAX_STEER_DEG = builder
                .comment("Maximum thrust-vector steering angle at full rudder input, degrees. Steering works by rotating the off-center motor's own thrust direction (and so its torque about the hull), never by writing yaw directly.")
                .defineInRange("maxSteerDeg", 35.0, 0.0, 90.0);
        LONGITUDINAL_DRAG_RATE = builder
                .comment("Fraction of forward/reverse (longitudinal) speed removed per second by hydrodynamic resistance while immersed (1/s). Scaled down to zero as the hull leaves the water.")
                .defineInRange("longitudinalDragRate", 1.2, 0.0, 50.0);
        LATERAL_DRAG_RATE = builder
                .comment("Fraction of sideways-slip (lateral) speed removed per second while immersed (1/s) -- deliberately larger than longitudinalDragRate: real boats resist slipping sideways far more than they resist decelerating forward.")
                .defineInRange("lateralDragRate", 6.0, 0.0, 50.0);
        ANGULAR_DAMPING_RATE = builder
                .comment("Fraction of angular velocity (yaw/pitch/roll) removed per second while immersed (1/s).")
                .defineInRange("angularDampingRate", 3.0, 0.0, 50.0);
        VERTICAL_DAMPING_RATE = builder
                .comment("Extra fraction of vertical velocity removed per second while immersed (1/s), on top of the hull buoyancy springs' own damping.")
                .defineInRange("verticalDampingRate", 2.5, 0.0, 50.0);
        SPEC = builder.build();
    }

    private MotorboatConfig() {}
}
