package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure numbers for what a driven car does to its surroundings: wear on the ground under the wheels and how
 * much exhaust and dust it emits. No Minecraft types, so it is unit-tested.
 */
public final class CarEffectsMath {
    /** Wear slip speed (m/s, what Dynamic Terrain's TireSlip.report takes) at full throttle from a standstill. */
    public static final double ACCEL_WEAR = 2.0;
    /**
     * How much of the acceleration/braking wear term is added on top of the tires' real slip. Small on purpose:
     * driving normally should only scuff the ground, while burnouts, drifts and locked brakes (real slip) do
     * the real damage.
     */
    public static final double WEAR_SCALE = 0.3;
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
     * The slip speed to report to terrain for the wheels this tick: the tires' real slip plus a small wear term
     * ({@link #WEAR_SCALE}) that grows with throttle (and with speed while accelerating) and, less, with braking.
     * Real slip therefore always adds to the wear instead of being hidden by it.
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
        return slip + wear * WEAR_SCALE * Math.max(0.0, strength);
    }

    /** Exhaust amount for one emission: grows with throttle; 0 when stationary or off the throttle. */
    public static int exhaustAmount(double throttle, double speed, double strength) {
        return exhaustAmount(throttle, speed, strength, 0.05, 1.0, 32);
    }

    /**
     * Exhaust amount for one emission, mapped from the driver throttle after a configurable dead band and
     * response curve. This is deliberately evaluated once per entity emission interval, not per wheel or
     * physics sub-step, so changing wheel sub-steps cannot multiply atmosphere output.
     *
     * @param throttle driver throttle, normally 0..1
     * @param speed authoritative forward speed in m/s
     * @param strength emission scale; 0 disables exhaust
     * @param throttleStart throttle dead band, 0..less than 1
     * @param throttleCurve ramp exponent; 1 is linear
     * @param maxAmount inclusive per-emission cap
     */
    public static int exhaustAmount(double throttle, double speed, double strength, double throttleStart,
            double throttleCurve, int maxAmount) {
        if (throttle <= throttleStart || Math.abs(speed) < MIN_EXHAUST_SPEED || strength <= 0 || maxAmount <= 0) {
            return 0;
        }
        double normalized = Math.min(1.0, (throttle - throttleStart) / (1.0 - throttleStart));
        double curved = Math.pow(normalized, throttleCurve);
        int requested = (int) Math.round(1.0 + 3.0 * curved * strength);
        return Math.min(maxAmount, Math.max(1, requested));
    }

    /** Dust amount for one emission: grows with speed above {@link #MIN_DUST_SPEED}, plus a bit while the tires slip. */
    public static int dustAmount(double speed, double slip, double strength) {
        return dustAmount(speed, slip, strength, MIN_DUST_SPEED, 3.0, 32);
    }

    /**
     * Dust amount for one grounded wheel contact. Chassis speed and that wheel's actual slip are deliberately
     * separate signals: ordinary driving dust follows speed while a stationary burnout follows wheel slip.
     * This runs once per car emission interval, outside physics sub-steps, so changing sub-step count cannot
     * multiply output.
     */
    public static int dustAmount(double speed, double slip, double strength, double speedStart, double slipStart,
            int maxAmount) {
        double v = Math.abs(speed);
        if (strength <= 0 || maxAmount <= 0 || (v < speedStart && slip < slipStart)) {
            return 0;
        }
        double raw = Math.max(0.0, v - speedStart) / 6.0 + Math.max(0.0, Math.min(slip, 10.0) - slipStart) / 5.0;
        int requested = (int) Math.round((1.0 + raw) * strength);
        return Math.min(maxAmount, Math.max(1, requested));
    }
}
