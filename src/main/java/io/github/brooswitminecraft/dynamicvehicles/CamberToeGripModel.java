package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Small, bounded grip adjustments for a vehicle's static camber and toe settings. This is deliberately
 * not a tire simulation: it preserves the friction circle in {@link WheelMath} and only supplies the two
 * inputs it already exposes. The sign of either static alignment setting is symmetric here; this model
 * represents the magnitude of the setup change, not a left/right chassis asymmetry.
 */
public final class CamberToeGripModel {
    private static final double MAX_CAMBER_DEG = 8.0;
    private static final double CAMBER_SWEET_SPOT_DEG = 3.0;
    private static final double MAX_TOE_DEG = 5.0;

    private CamberToeGripModel() {}

    /** Multipliers consumed by {@link WheelMath}'s existing friction-circle calculation. */
    public record GripModifiers(double longitudinalMuScale, double lateralScale) {
        public static final GripModifiers IDENTITY = new GripModifiers(1.0, 1.0);
    }

    /**
     * Camber improves lateral grip up to three degrees, then gives that gain back by eight degrees while
     * steadily trimming straight-line grip. Toe is a small turn-in-response proxy: either direction adds
     * lateral bite at a greater longitudinal cost.
     */
    public static GripModifiers modifiers(double camberDeg, double toeDeg) {
        double camber = Math.min(MAX_CAMBER_DEG, Math.abs(camberDeg));
        double camberBite = Math.min(camber, CAMBER_SWEET_SPOT_DEG) / CAMBER_SWEET_SPOT_DEG;
        double camberOver = Math.max(0.0, camber - CAMBER_SWEET_SPOT_DEG)
                / (MAX_CAMBER_DEG - CAMBER_SWEET_SPOT_DEG);
        double toe = Math.min(MAX_TOE_DEG, Math.abs(toeDeg)) / MAX_TOE_DEG;

        double lateralScale = 1.0 + 0.08 * (camberBite - camberOver) + 0.04 * toe;
        double longitudinalMuScale = 1.0 - 0.05 * (camber / MAX_CAMBER_DEG) - 0.06 * toe;
        return new GripModifiers(longitudinalMuScale, lateralScale);
    }
}
