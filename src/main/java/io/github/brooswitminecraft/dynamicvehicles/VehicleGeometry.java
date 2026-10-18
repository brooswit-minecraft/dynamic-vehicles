package io.github.brooswitminecraft.dynamicvehicles;

/** MINECRAFT-380: hitbox half-extents for the three vehicles that carry no {@link VehicleSpec} of their
 * own (see {@link SimpleVehicleEntity}). Kept in a plain class with no Minecraft imports, unlike {@link
 * MotorboatEntity}/{@link ChopperEntity}/{@link BiplaneEntity} themselves, so a pure-function test can
 * check them without the Minecraft classpath the main source set has but the test source set does not. */
final class VehicleGeometry {
    private VehicleGeometry() {}

    static final double MOTORBOAT_HALF_WIDTH = 0.7;
    static final double MOTORBOAT_HALF_HEIGHT = 0.45;
    static final double MOTORBOAT_HALF_LENGTH = 1.6;

    static final double CHOPPER_HALF_WIDTH = 0.8;
    static final double CHOPPER_HALF_HEIGHT = 0.6;
    static final double CHOPPER_HALF_LENGTH = 1.5;

    static final double BIPLANE_HALF_WIDTH = 1.4;
    static final double BIPLANE_HALF_HEIGHT = 0.6;
    static final double BIPLANE_HALF_LENGTH = 2.0;
}
