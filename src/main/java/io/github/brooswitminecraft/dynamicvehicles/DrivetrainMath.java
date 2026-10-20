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
    /**
     * How far below {@link #LIMITER_RPM} {@link #limiterTorqueFactor} starts tapering torque.
     *
     * <p>Review history worth keeping: an earlier revision (round 2) additionally smoothed the RPM value
     * the limiter itself reads (a {@code smoothedRpm} function, since removed) to keep a single tick from
     * crossing this whole band at once. Widening THIS band instead, to get the same multi-tick margin
     * without touching that separate smoothing rate, was tried (round 3) and rejected: D1's own ratio
     * means ordinary driving (not just a wall-pinned burnout) commonly runs with RAW RPM near the limiter
     * at fairly light throttle (confirmed by production telemetry), so widening this band visibly slowed
     * D1's ordinary acceleration enough to flip the production-path ratio-multiplier regression test
     * ({@code CarDrivetrainGameTests.d1ReachesAHigherSpeedThanD5OverTheSameShortWindow}) -- this band's
     * WIDTH is not free to grow without changing normal-operation behavior. The separate RPM-smoothing
     * step was removed entirely in round 5 once {@link WheelMath#WHEEL_INERTIA} itself was given a
     * physically-grounded, bounded recovery at the wheel level ({@code DrivetrainMath.boundedSpinTowardSnap},
     * applied to {@code wheelSpin} in {@code SableCarBody}) -- that bound alone already keeps
     * {@code drivenWheelSpin}, and so the RPM this reads, changing gradually at the source, so a second,
     * independent engine-side smoothing pass was redundant and (worse) compounded into enough extra lag
     * to fail other production-path tests that need the drivetrain to actually recover within their own
     * windows. A caller is still free to tune this band's own width for the limiter's feel; it no longer
     * has a cross-constant margin to maintain against a removed RPM-slew rate.
     */
    public static final double LIMITER_TAPER_RPM = 400.0;

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

    /** {@link #IDLE_RPM}..{@link #LIMITER_RPM}, clamping an already-computed RPM value rather than
     * recomputing it from a wheel speed -- the display-only clamp {@link #engineRpm} applies to
     * {@link #rawRpm}, factored out so a caller already holding a raw RPM value can clamp it directly. */
    public static double clampRpm(double rpm) {
        return Math.max(IDLE_RPM, Math.min(LIMITER_RPM, rpm));
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

    /**
     * Review, round 5: {@link WheelMath#spinRate}'s documented contract snaps a wheel's own spin straight
     * to {@code groundSpeed/radius} the instant {@code excessForce} reads exactly 0 -- correct for the
     * small, continuous corrections that contract was built for, but once the limiter (MINECRAFT-401) can
     * make a wheel carrying real stored angular momentum from a sustained burnout hit exactly that
     * condition in a single step, the same snap discards that momentum (the model's own declared
     * {@code WheelMath.WHEEL_INERTIA}) without a bounded physical reaction. Kinetic friction can only
     * despin a wheel so fast: the torque available is {@code frictionForce * wheelRadius} (the same
     * friction force the friction circle already computed as available at this contact), and the spin
     * rate it can change is that torque divided by the wheel's own rotational inertia. This clamps
     * {@code snappedSpin} (whatever {@link WheelMath#spinRate} already returned) to at most that much
     * change from {@code priorSpin} in this (sub-)step, instead of the unbounded jump -- a caller applies
     * this ONLY in the {@code excessForce == 0.0} branch (see the call site's own comment); every other
     * case keeps reading {@link WheelMath#spinRate}'s own return value unmodified, so nothing here
     * changes the EXCESS-force integration WheelMath already owns, and nothing here touches the official
     * slip-REPORTING contract (MINECRAFT-73/118), which never read {@code wheelSpin} in the first place.
     *
     * <p>
     * Review, round 6: {@code frictionForce} must be the circle's remaining HEADROOM this step
     * ({@code frictionLimit - hypot(longitudinal, lateral)}), not the full {@code mu * normalForce}
     * circle round 5 passed -- this same friction is already partly (or wholly) spent by this step's own
     * longitudinal/lateral tire force; treating the full circle as available again here double-counts
     * it. The caller must also pay the chassis the equal-and-opposite reaction for whatever change this
     * method actually returns -- unlike {@link WheelMath#spinRate}'s own {@code excessForce} branch,
     * where the extra torque is the engine's own and needs no ground reaction, the torque THIS method
     * bounds is ground-sourced (see the call site's own comment for the reaction impulse itself).
     *
     * @param priorSpin this wheel's own spin rate coming into the step, rad/s
     * @param snappedSpin {@link WheelMath#spinRate}'s own result for this same step (what a caller would
     *        have used unmodified before this fix)
     * @param frictionForce the friction circle's remaining HEADROOM at this contact this step, N (what
     *        this step's own longitudinal/lateral tire force has NOT already spent of
     *        {@code gripMu * normalForce} -- sign-independent, only its magnitude matters here)
     */
    public static double boundedSpinTowardSnap(double priorSpin, double snappedSpin, double frictionForce,
            double wheelRadius, double wheelInertia, double dt) {
        double maxChange = Math.abs(frictionForce) * wheelRadius / wheelInertia * dt;
        double delta = snappedSpin - priorSpin;
        if (Math.abs(delta) <= maxChange) {
            return snappedSpin;
        }
        return priorSpin + Math.copySign(maxChange, delta);
    }

    /**
     * The one-time chassis impulse, N*s along the wheel's forward axis, that pays for a ground-sourced spin
     * change {@link #boundedSpinTowardSnap} returned -- extracted verbatim out of {@code SableCarBody.tick}
     * (which calls this, not a copy of it) so the sign of what a recovering wheel actually hands the chassis
     * is testable without a Sable body. Spin decreasing (overspin bleeding off) pays out as a FORWARD
     * impulse, spin increasing (a locked wheel catching back up) as a backward one; see the call site's own
     * comment for the torque balance this solves.
     *
     * <p>An IMPULSE, not a force: it is the whole recovery's momentum, so it does not scale with the
     * sub-step's length the way a tire force times {@code subDt} does. MINECRAFT-445's anti-dive/anti-squat
     * selector caught exactly that units mix-up once (PR184 review 5580581955).
     */
    public static double recoveryReactionImpulse(double priorSpin, double recoveredSpin, double wheelInertia,
            double wheelRadius) {
        return -(recoveredSpin - priorSpin) * wheelInertia / wheelRadius;
    }
}
