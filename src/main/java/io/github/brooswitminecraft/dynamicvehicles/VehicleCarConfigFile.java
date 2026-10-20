package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The JSON shape of one {@code config/dynamicvehicles/cars/<id>.json} file (MINECRAFT-443). Every field is
 * a boxed {@code Double} so it can be absent (left {@code null} by Gson) &mdash; an absent field means
 * "keep this car's stock default", not zero. Gson silently ignores any JSON key with no matching field
 * here, which is how the reserved-but-not-yet-implemented top-level sections stay parseable without this
 * ticket exposing nonfunctional settings: {@code diffuser} (MINECRAFT-447), {@code computer}
 * (MINECRAFT-448) and {@code engine} (MINECRAFT-449) each belong to that task, which adds its own
 * field(s) to its own section here when it implements real behaviour &mdash; none of them is read or
 * round-tripped by this loader today. Two sections ARE implemented: {@code tireKit} (MINECRAFT-445, via
 * {@link TireKit}) and {@code wing} (MINECRAFT-446, via {@link Wing}).
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
    }

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
