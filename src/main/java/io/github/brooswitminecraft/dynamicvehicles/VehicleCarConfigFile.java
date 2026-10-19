package io.github.brooswitminecraft.dynamicvehicles;

/**
 * The JSON shape of one {@code config/dynamicvehicles/cars/<id>.json} file (MINECRAFT-443). Every field is
 * a boxed {@code Double} so it can be absent (left {@code null} by Gson) &mdash; an absent field means
 * "keep this car's stock default", not zero. Gson silently ignores any JSON key with no matching field
 * here, which is how the reserved-but-not-yet-implemented top-level sections stay parseable without this
 * ticket exposing nonfunctional settings: {@code tireKit} (MINECRAFT-445), {@code wing} (MINECRAFT-446),
 * {@code diffuser} (MINECRAFT-447), {@code computer} (MINECRAFT-448) and {@code engine} (MINECRAFT-449)
 * each belong to that task, which adds its own field(s) to its own section here when it implements real
 * behaviour &mdash; none of them is read or round-tripped by this loader today.
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
}
