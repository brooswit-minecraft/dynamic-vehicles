package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The JSON shape of one {@code config/dynamicvehicles/cars/<id>.json} file (MINECRAFT-443). Every field is
 * Optional fields retain stock defaults when absent. tireKit, wing and diffuser are implemented;
 * engine remains reserved for its owning task; computer is implemented by MINECRAFT-448.
 *
 * <p>See {@code docs/per-car-config.md} for the full schema, units, ranges and defaults.
 */
final class VehicleCarConfigFile {
    Double rideHeight;
    Double dampingRate;
    Double antiRollScale;
    Double rollingScale;
    Double handbrakeScale;
    Double lsdLockPercent;
    Double frontTorqueSplit;
    Double vectoringGain;
    Double camberDeg;
    Double toeDeg;
    TireKit tireKit;

    /**
     * MINECRAFT-445: two-rate (compression/rebound) damping and bump stop, layered on top of the existing
     * {@code dampingRate} field above (which this section treats as the car's slow-compression rate,
     * unchanged). Every field here is itself optional, same "absent means stock" rule as the flat fields
     * above &mdash; an absent {@code tireKit} section, or a {@code tireKit} missing some of these fields,
     * keeps this car's stock {@link VehicleSpec.SuspensionTuning} for exactly the fields it did not mention.
     * See {@code docs/per-car-config.md} for units/ranges/defaults.
     */
    static final class TireKit {
        Double compressionDampingFast;
        Double reboundDampingSlow;
        Double reboundDampingFast;
        Double dampingKneeSpeed;
        Double bumpStopTravel;
        Double bumpStopRate;
        Double rearSteerRatio;
        Double tirePressureBar;
        Double antiDive;
        Double antiSquat;
        /** MINECRAFT-478: positive caster angle of the front steering axis, degrees. */
        Double casterDeg;
        /** MINECRAFT-478: lateral-force application height above the tire contact patch, metres. */
        Double rollCenterHeight;
    }

    // Engine/clutch coupling (MINECRAFT-444). Deliberately flat top-level fields, like every field above,
    // and deliberately NOT inside the reserved `engine` section: that section stays MINECRAFT-449's own
    // (rev-limit thresholds, turbo, nitrous, ballast). These four are the coupling parameters
    // MINECRAFT-444 itself implements -- see docs/per-car-config.md for the boundary.
    Double engineInertia;
    Double enginePeakTorque;
    Double engineBrakingTorque;
    Double clutchTorqueCapacity;

    /** Driver aids, each a 0..1 intervention level.  Zero disables that aid. */
    Computer computer;

    static final class Computer {
        Double tractionControlLevel;
        Double absLevel;
    }


    /** The {@code "diffuser": { ... }} nested object (MINECRAFT-447), absent ({@code null}) entirely if the
     * file has no such key at all &mdash; every field within it is independently optional the same way the
     * top-level fields are. */
    Diffuser diffuser;

    /** The Diffuser KIT's own sub-schema: one field per {@link VehicleSpec.DiffuserTuning} field, same
     * name, same null-means-keep-stock convention. See {@code docs/per-car-config.md}'s own Diffuser KIT
     * section for units/ranges/defaults. */
    static final class Diffuser {
        Boolean rollSelfRightingEnabled;
        Double rollSelfRightingMaxTorque;
        Double rollSelfRightingGain;
        Double rollSelfRightingDamping;
        Boolean pitchLandingEnabled;
        Double pitchLandingMaxTorque;
        Double pitchLandingHorizonSeconds;
        Double pitchLandingGain;
        Double pitchLandingDamping;
        Boolean headingAlignmentEnabled;
        Double headingAlignmentMaxTorque;
        Double headingAlignmentGain;
        Double headingAlignmentDamping;
        Double headingAlignmentSteerSuppression;
        Boolean trajectoryAlignmentEnabled;
        Double trajectoryAlignmentMaxTorque;
        Double trajectoryAlignmentGain;
        Double trajectoryAlignmentDamping;
        Double trajectoryAlignmentMinSpeed;
    }

    /** The {@code "wing": { ... }} nested object (MINECRAFT-446). */
    Wing wing;

    /** The {@code wing} section's own fields (MINECRAFT-446), every one optional and absent meaning
     * "keep this car's stock default", exactly like every top-level field above. An absent {@code wing}
     * object entirely is equivalent to one whose fields are all absent. */
    static final class Wing {
        Double frontDownforceCoeff;
        Double rearDownforceCoeff;
        Double dragCoeff;
    }
}
