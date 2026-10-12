package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The safe operating range for an individual vehicle's saved tuning. These limits deliberately match the
 * domains of the existing drivetrain and static-alignment models: percentages and axle split are fractions,
 * while camber/toe stop at the point their grip model already treats as its maximum meaningful magnitude.
 */
final class VehicleTuningLimits {
    static final double MIN_LSD_LOCK = 0.0;
    static final double MAX_LSD_LOCK = 1.0;
    static final double MIN_FRONT_TORQUE_SPLIT = 0.0;
    static final double MAX_FRONT_TORQUE_SPLIT = 1.0;
    static final double MIN_VECTORING_GAIN = 0.0;
    static final double MAX_VECTORING_GAIN = 1.0;
    static final double MIN_CAMBER_DEG = -8.0;
    static final double MAX_CAMBER_DEG = 8.0;
    static final double MIN_TOE_DEG = -5.0;
    static final double MAX_TOE_DEG = 5.0;

    private VehicleTuningLimits() {}

    /**
     * Clamps player/config input and corrupted saved values. A non-finite value falls back to the vehicle
     * spec's own setting, so malformed NBT can never leak NaN/Infinity into wheel physics.
     */
    static VehicleSpec.VehicleTuning clamp(VehicleSpec.VehicleTuning requested, VehicleSpec.VehicleTuning defaults) {
        return new VehicleSpec.VehicleTuning(
                clamp(requested.lsdLockPercent(), MIN_LSD_LOCK, MAX_LSD_LOCK, defaults.lsdLockPercent()),
                clamp(requested.frontTorqueSplit(), MIN_FRONT_TORQUE_SPLIT, MAX_FRONT_TORQUE_SPLIT,
                        defaults.frontTorqueSplit()),
                clamp(requested.vectoringGain(), MIN_VECTORING_GAIN, MAX_VECTORING_GAIN, defaults.vectoringGain()),
                clamp(requested.camberDeg(), MIN_CAMBER_DEG, MAX_CAMBER_DEG, defaults.camberDeg()),
                clamp(requested.toeDeg(), MIN_TOE_DEG, MAX_TOE_DEG, defaults.toeDeg()));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
