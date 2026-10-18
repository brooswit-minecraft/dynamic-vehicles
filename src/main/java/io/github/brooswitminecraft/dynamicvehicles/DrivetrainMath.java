package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure drivetrain arithmetic shared by every vehicle (MINECRAFT-401): manual gear ratios, the automatic
 * mode's own shift logic, and the engine RPM a driven wheel's angular speed implies through whichever
 * ratio is currently engaged. Free of Minecraft types so it can be unit tested. Like
 * {@link WheelMath#WHEEL_INERTIA}, these numbers are a plain shared drivetrain, not a per-vehicle tuning
 * knob -- no vehicle here has a different engine.
 */
public final class DrivetrainMath {
    /** Manual gear ratios for D1..D5, highest (most torque, lowest top speed) first. */
    public static final double[] GEAR_RATIOS = {3.6, 2.4, 1.7, 1.2, 0.9};
    /** Reverse's own single ratio -- there is only one reverse gear, so it needs no shift logic. */
    public static final double REVERSE_RATIO = 3.3;
    /** Multiplies every ratio above on its way from the gearbox to the driven wheel. */
    public static final double FINAL_DRIVE = 4.1;

    public static final double IDLE_RPM = 900.0;
    public static final double LIMITER_RPM = 6500.0;
    /** Automatic mode upshifts once the current gear's implied RPM would exceed this. */
    public static final double UPSHIFT_RPM = 5800.0;
    /** Automatic mode downshifts once the current gear's implied RPM drops below this -- comfortably
     * below {@link #UPSHIFT_RPM} so a steady speed near either threshold does not hunt between gears. */
    public static final double DOWNSHIFT_RPM = 2200.0;

    private DrivetrainMath() {}

    private static int clampGearIndex(int index) {
        return Math.max(0, Math.min(GEAR_RATIOS.length - 1, index));
    }

    /** D1..D5's own ratio, by 0-based index ({@link CarPhysics.Gear#manualIndex()}); out-of-range clamps. */
    public static double ratioFor(int gearIndex) {
        return GEAR_RATIOS[clampGearIndex(gearIndex)];
    }

    /**
     * The engine RPM a driven wheel's own angular speed implies through {@code ratio} and
     * {@link #FINAL_DRIVE}, clamped to {@link #IDLE_RPM}..{@link #LIMITER_RPM} -- an idling engine never
     * reads as stalled, and a wheel spinning well past what the ratio can carry never reads as revving
     * past the limiter the engine itself enforces.
     *
     * @param wheelAngularSpeed the driven wheel's own spin rate, rad/s (sign ignored: both directions spin
     *        the engine the same way through a fixed ratio)
     */
    public static double engineRpm(double wheelAngularSpeed, double ratio) {
        double rpm = Math.abs(wheelAngularSpeed) * ratio * FINAL_DRIVE * 60.0 / (2.0 * Math.PI);
        return Math.max(IDLE_RPM, Math.min(LIMITER_RPM, rpm));
    }

    /** Engine RPM in {@link #NEUTRAL}, where nothing couples the engine to the wheels: a free rev from
     * idle to the limiter purely as a function of throttle. */
    public static double freeRevRpm(double throttle) {
        double t = Math.max(0.0, Math.min(1.0, Math.abs(throttle)));
        return IDLE_RPM + t * (LIMITER_RPM - IDLE_RPM);
    }

    /**
     * Automatic mode's own next gear index from the one it is already in and the driven wheel's current
     * angular speed: upshifts while even the current gear's implied RPM is still above
     * {@link #UPSHIFT_RPM} (so a hard launch can skip more than one gear in a single call rather than
     * waiting one shift at a time to catch up), then downshifts while it is below
     * {@link #DOWNSHIFT_RPM} -- never both in the same call, and the gap between the two thresholds is
     * what keeps a steady speed near either one from hunting back and forth every tick.
     */
    public static int automaticGearIndex(int currentIndex, double wheelAngularSpeed) {
        int index = clampGearIndex(currentIndex);
        double speed = Math.abs(wheelAngularSpeed);
        while (index < GEAR_RATIOS.length - 1 && engineRpm(speed, ratioFor(index)) >= UPSHIFT_RPM) {
            index++;
        }
        while (index > 0 && engineRpm(speed, ratioFor(index)) <= DOWNSHIFT_RPM) {
            index--;
        }
        return index;
    }

    /**
     * How much more (or less) drive force a gear ratio delivers than top gear (the last ratio in
     * {@link #GEAR_RATIOS}), the ratio the existing per-wheel drive-force constants were tuned against --
     * so top gear's own force is exactly what it was before MINECRAFT-401 introduced ratios, and every
     * lower gear multiplies it up for more launch torque at a proportionally lower governed top speed.
     */
    public static double forceMultiplier(double ratio) {
        return ratio / GEAR_RATIOS[GEAR_RATIOS.length - 1];
    }
}
