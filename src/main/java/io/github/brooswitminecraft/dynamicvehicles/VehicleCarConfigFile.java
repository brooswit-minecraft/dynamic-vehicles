package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The JSON shape of one {@code config/dynamicvehicles/cars/<id>.json} file (MINECRAFT-443). Every field is
 * a boxed {@code Double}/{@code Boolean} so it can be absent (left {@code null} by Gson) &mdash; an absent
 * field means "keep this car's stock default", not zero/false. Gson silently ignores any JSON key with no
 * matching field here, which is how a still-reserved-but-not-yet-implemented top-level section stays
 * parseable without exposing nonfunctional settings: {@code tireKit} (MINECRAFT-445), {@code computer}
 * (MINECRAFT-448) and {@code engine} (MINECRAFT-449) each belong to that task, which adds its own field(s)
 * to its own section here when it implements real behaviour &mdash; none of those three is read or
 * round-tripped by this loader today. Two sections ARE implemented: {@code wing} (MINECRAFT-446) via
 * {@link Wing}, and {@code diffuser} (MINECRAFT-447) via {@link Diffuser}.
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
