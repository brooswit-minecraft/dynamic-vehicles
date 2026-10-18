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
    /** How far below {@link #LIMITER_RPM} {@link #limiterTorqueFactor} starts tapering torque. */
    public static final double LIMITER_TAPER_RPM = 400.0;
    /**
     * Maximum RPM {@link #smoothedRpm} may change by in one tick -- the engine's own rotational inertia
     * (review, round 2): {@link WheelMath#spinRate} legitimately snaps a wheel's own spin straight to
     * {@code groundSpeed/radius} the instant it detects grip ({@code excessForce == 0}), which includes
     * the instant the limiter cuts drive force to (near) zero -- that snap is correct for the TIRE's own
     * contact state, but reading it directly as the engine's RPM would mean the engine itself has zero
     * rotational inertia: it would lose all its spin in a single tick, the limiter would see RPM already
     * back under the taper band next tick, torque would resume at full, the wheel would spin back up and
     * trip the limiter again next tick, forever -- an on/off reset, not limiter hunting. A real engine (and
     * the driveline between it and the wheel) keeps turning under its own momentum for a while after
     * torque is cut; {@link #smoothedRpm} models exactly that missing inertia, decoupling what the limiter
     * reads from the tire model's own instant snap.
     */
    public static final double MAX_RPM_CHANGE_PER_TICK = 500.0;

    private DrivetrainMath() {}

    private static int clampGearIndex(int index) {
        return Math.max(0, Math.min(GEAR_RATIOS.length - 1, index));
    }

    /** D1..D5's own ratio, by 0-based index ({@link CarPhysics.Gear#manualIndex()}); out-of-range clamps. */
    public static double ratioFor(int gearIndex) {
        return GEAR_RATIOS[clampGearIndex(gearIndex)];
    }

    /**
     * The RAW engine RPM a driven wheel's own angular speed implies through {@code ratio} and
     * {@link #FINAL_DRIVE} -- NOT clamped to the idle/limiter band, unlike {@link #engineRpm}. This is the
     * value {@link #limiterTorqueFactor} and the automatic shift logic must read: a wheel spinning well
     * past what the ratio/limiter can carry has to actually lose torque, not just stop climbing in the
     * number displayed to the player while the driveline keeps injecting the same force into it forever
     * (MINECRAFT-401 review).
     *
     * @param wheelAngularSpeed the driven wheel's own spin rate, rad/s (sign ignored: both directions spin
     *        the engine the same way through a fixed ratio)
     */
    public static double rawRpm(double wheelAngularSpeed, double ratio) {
        return Math.abs(wheelAngularSpeed) * ratio * FINAL_DRIVE * 60.0 / (2.0 * Math.PI);
    }

    /**
     * The engine RPM a driven wheel's own angular speed implies, clamped to {@link #IDLE_RPM}..
     * {@link #LIMITER_RPM} for DISPLAY/AUDIO purposes only -- an idling engine never reads as stalled, and
     * a wheel spinning past what the ratio can carry never reads as revving past the limiter. This clamp
     * is cosmetic: the torque actually delivered is governed separately by {@link #limiterTorqueFactor}
     * against the UNCLAMPED {@link #rawRpm}, so a coupled caller must read both, not just this one.
     */
    public static double engineRpm(double wheelAngularSpeed, double ratio) {
        return clampRpm(rawRpm(wheelAngularSpeed, ratio));
    }

    /** {@link #IDLE_RPM}..{@link #LIMITER_RPM}, clamping an already-computed RPM value (raw or smoothed)
     * rather than recomputing it from a wheel speed -- the display-only clamp {@link #engineRpm} applies
     * to {@link #rawRpm}, factored out so a caller tracking {@link #smoothedRpm} can clamp that instead. */
    public static double clampRpm(double rpm) {
        return Math.max(IDLE_RPM, Math.min(LIMITER_RPM, rpm));
    }

    /**
     * One tick's worth of the engine's own rotational inertia (see {@link #MAX_RPM_CHANGE_PER_TICK}):
     * {@code currentRpm} moves toward {@code targetRawRpm} (this tick's instantaneous, tire-model-derived
     * {@link #rawRpm}) by at most that many RPM, in either direction. Call once per tick with the previous
     * call's own return value as {@code currentRpm} (persisted state, like {@link WheelMath#spinRate}'s
     * own {@code spin} parameter) -- never recomputed from scratch, or the inertia this models vanishes.
     */
    public static double smoothedRpm(double currentRpm, double targetRawRpm) {
        double delta = targetRawRpm - currentRpm;
        if (Math.abs(delta) <= MAX_RPM_CHANGE_PER_TICK) {
            return targetRawRpm;
        }
        return currentRpm + Math.copySign(MAX_RPM_CHANGE_PER_TICK, delta);
    }

    /**
     * 1.0 while comfortably under the limiter, tapering linearly to 0.0 as {@code rawRpm} (the UNCLAMPED
     * value -- see {@link #rawRpm}) crosses into the last {@link #LIMITER_TAPER_RPM} below
     * {@link #LIMITER_RPM}, and staying 0.0 past it. A real rev limiter cuts torque near redline; it does
     * not merely cap the number shown on the gauge while the engine keeps shoving the car just as hard
     * (MINECRAFT-401 review) -- multiply this into the drive force a caller is about to apply so a wheel
     * pinned above the limiter (e.g. mid-burnout in a low gear) stops gaining speed/spin instead of
     * accelerating forever.
     */
    public static double limiterTorqueFactor(double rawRpm) {
        double start = LIMITER_RPM - LIMITER_TAPER_RPM;
        if (rawRpm <= start) {
            return 1.0;
        }
        if (rawRpm >= LIMITER_RPM) {
            return 0.0;
        }
        return (LIMITER_RPM - rawRpm) / LIMITER_TAPER_RPM;
    }

    /** Engine RPM in {@link CarPhysics.Gear#NEUTRAL}, where nothing couples the engine to the wheels: a
     * free rev from idle to the limiter purely as a function of the ACCELERATOR -- never the brake, whose
     * negative throttle sign must read as zero here, not as a demand to rev (MINECRAFT-401 review: a
     * caller passing the car's signed throttle -- accelerator minus brake -- straight through must not
     * free-rev the engine while only the brake is held). */
    public static double freeRevRpm(double throttle) {
        double accelerator = Math.max(0.0, Math.min(1.0, throttle));
        return IDLE_RPM + accelerator * (LIMITER_RPM - IDLE_RPM);
    }

    /**
     * Automatic mode's own next gear index from the one it is already in and the driven wheel's current
     * angular speed: upshifts while even the current gear's implied RAW RPM is still above
     * {@link #UPSHIFT_RPM} (so a hard launch can skip more than one gear in a single call rather than
     * waiting one shift at a time to catch up), then downshifts while it is below
     * {@link #DOWNSHIFT_RPM} -- never both in the same call, and the gap between the two thresholds is
     * what keeps a steady speed near either one from hunting back and forth every tick. Reads the
     * UNCLAMPED {@link #rawRpm}, not the display-clamped {@link #engineRpm}, so a wheel spinning well past
     * the limiter in the current gear still reliably triggers the upshift that would relieve it.
     */
    public static int automaticGearIndex(int currentIndex, double wheelAngularSpeed) {
        int index = clampGearIndex(currentIndex);
        double speed = Math.abs(wheelAngularSpeed);
        while (index < GEAR_RATIOS.length - 1 && rawRpm(speed, ratioFor(index)) >= UPSHIFT_RPM) {
            index++;
        }
        while (index > 0 && rawRpm(speed, ratioFor(index)) <= DOWNSHIFT_RPM) {
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
