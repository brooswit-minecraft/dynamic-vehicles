package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure numbers for what a driven car does to its surroundings: wear on the ground under the wheels and how
 * much exhaust and dust it emits. No Minecraft types, so it is unit-tested.
 */
public final class CarEffectsMath {
    /** Wear slip speed (m/s, what Dynamic Terrain's TireSlip.report takes) at full throttle from a standstill. */
    public static final double ACCEL_WEAR = 2.0;
    /** Speed (m/s) at which acceleration wear has doubled. */
    public static final double ACCEL_WEAR_SPEED = 20.0;
    /** Wear slip speed while braking hard at 10 m/s. */
    public static final double BRAKE_WEAR = 1.5;
    /** Below this speed the car does not emit exhaust, so a revving car standing still never builds a dense cloud around its driver. */
    public static final double MIN_EXHAUST_SPEED = 2.0;
    /** Dust starts at this speed (m/s). */
    public static final double MIN_DUST_SPEED = 5.0;

    private CarEffectsMath() {}

    /**
     * The slip speed to report to terrain for the wheels this tick: the larger of the tires' real slip and a
     * wear term that grows with throttle (and with speed while accelerating) and, less, with braking.
     *
     * @param throttle -1..1 (negative = brake)
     * @param speed forward speed in m/s (absolute value is used)
     * @param slip the tires' actual slip speed in m/s
     * @param strength config multiplier; 0 = only real slip
     */
    public static double wearSlip(double throttle, double speed, double slip, double strength) {
        double v = Math.abs(speed);
        double wear = 0.0;
        if (throttle > 0) {
            wear = ACCEL_WEAR * throttle * (1.0 + Math.min(v, ACCEL_WEAR_SPEED) / ACCEL_WEAR_SPEED);
        } else if (throttle < 0 && v > 3.0) {
            wear = BRAKE_WEAR * -throttle * v / 10.0;
        }
        return Math.max(slip, wear * Math.max(0.0, strength));
    }

    /** Exhaust amount for one emission: grows with throttle; 0 when stationary or off the throttle. */
    public static int exhaustAmount(double throttle, double speed, double strength) {
        if (throttle <= 0.05 || Math.abs(speed) < MIN_EXHAUST_SPEED || strength <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.round(1.0 + 3.0 * throttle * strength));
    }

    /** Dust amount for one emission: grows with speed above {@link #MIN_DUST_SPEED}, plus a bit while the tires slip. */
    public static int dustAmount(double speed, double slip, double strength) {
        double v = Math.abs(speed);
        if (strength <= 0 || (v < MIN_DUST_SPEED && slip < 3.0)) {
            return 0;
        }
        double raw = Math.max(0.0, v - MIN_DUST_SPEED) / 6.0 + Math.min(slip, 10.0) / 5.0;
        return (int) Math.max(1, Math.round((1.0 + raw) * strength));
    }
}
