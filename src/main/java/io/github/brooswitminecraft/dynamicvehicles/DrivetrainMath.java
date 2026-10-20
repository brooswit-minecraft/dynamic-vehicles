package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure drivetrain arithmetic shared by every vehicle (MINECRAFT-401): manual gear ratios, the automatic
 * mode's own shift logic, and the engine RPM a driven wheel's angular speed implies through whichever
 * ratio is currently engaged. Free of Minecraft types so it can be unit tested. Like
 * {@link WheelMath#WHEEL_INERTIA}, the gear ratios, final drive and RPM thresholds here are a plain shared
 * drivetrain, not a per-vehicle tuning knob.
 *
 * <p>MINECRAFT-444 is the one exception, and only for the engine/clutch COUPLING parameters it added
 * (engine inertia, peak torque, engine braking torque, clutch capacity): the methods that use them take a
 * {@link VehicleSpec.DrivetrainCoupling} argument, so a per-car config file can change them (see
 * {@code docs/per-car-config.md}) while the constants below remain the shared stock engine every vehicle
 * on the roster still runs. Everything else here stays shared, MINECRAFT-449's rev-limit/boost scope
 * included.
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

    // --- Clutch, engine inertia and engine-axis reaction torque (MINECRAFT-444) ---------------------
    //
    // Everything above this point treats the engine as having no state of its own: engineRpm/rawRpm are a
    // pure, instantaneous function of the driven wheels' own spin, as if engine and wheels were welded
    // together through the gear ratio with zero rotating mass on the engine's side of that joint. That is
    // the genuine gap this ticket closes: the engine now carries its OWN angular speed
    // ({@link #engineAngularVelocity}), driven by its own torque balance (throttle demand, engine braking,
    // and whatever the clutch actually transmits -- {@link #clutchTorque}), and SableCarBody reads RPM
    // from that integrated state instead of snapping it from wheel speed every tick. The clutch is the
    // joint between the two: a torque-limited coupling, not a rigid one, so it can model engaged (locked,
    // negligible relative speed), partial (saturated, slipping under more torque than it can hold) and
    // disengaged (NEUTRAL, see SableCarBody's own NEUTRAL branch: no wheel-side speed to couple to at all)
    // the way the acceptance criteria name them explicitly, rather than approximating all three with one
    // softened snap.

    // The four constants below are the STOCK values of the engine/clutch coupling every vehicle on the
    // roster runs -- they are what VehicleSpec.DrivetrainCoupling.STOCK is built from. Review (round 1)
    // scope reconciliation: the methods in this class take a DrivetrainCoupling argument rather than
    // reading these directly, so a per-car config file can change them (docs/per-car-config.md) while
    // every car that sets nothing stays byte-for-byte identical. MINECRAFT-449 still owns the rev-limit
    // thresholds (IDLE_RPM/LIMITER_RPM/UPSHIFT_RPM/...), turbo, nitrous and ballast -- not these.

    /** Engine crankshaft rotational inertia about its own spin axis, kg*m^2 -- the shared stock engine's
     * own value, and the identity default behind {@link VehicleSpec.DrivetrainCoupling#STOCK}. */
    public static final double ENGINE_INERTIA = 0.18;

    /**
     * Peak torque the engine itself can produce at full throttle, before the clutch, N*m.
     *
     * <p>Review (round 1) calibration, not a free number: now that the clutch's own transmitted torque IS
     * the wheel command ({@link GearDrive#clutchWheelForce}), this value sets the car's actual stock drive
     * force, which must stay identity-preserving against the pre-existing, already-tuned
     * {@code SableCarBody.DRIVE_FORCE} (2300 N per wheel, tuned at top gear). Solving
     * {@code DRIVE_FORCE = (peak - ENGINE_BRAKING_TORQUE) * topRatio * FINAL_DRIVE / wheelRadius /
     * drivenWheels} for the 4-wheel, 0.35 m-wheel reference car at top gear's 0.9 ratio gives a net
     * ~875 N*m, hence this value once idle drag is added back. Because both this chain and the old
     * {@link #forceMultiplier} path scale linearly with the engaged ratio, every OTHER gear's force stays
     * in exactly the same proportion it already had -- that identity is why this is 930 and not a
     * plausible-sounding crankshaft figure.
     */
    public static final double ENGINE_PEAK_TORQUE = 930.0;

    /** Torque a running engine applies against its OWN rotation once the throttle stops demanding more --
     * compression/friction drag, not a brake on the wheels (that is {@code BRAKE_FORCE}, unrelated). Only
     * opposes rotation that is actually happening; see {@link #engineAngularVelocity}'s own guard. */
    public static final double ENGINE_BRAKING_TORQUE = 55.0;

    /**
     * Torque the clutch can transmit before it slips, N*m -- see {@link #clutchTorque}. Sized above
     * {@link #ENGINE_PEAK_TORQUE}'s own net output (~1.3x) so an ENGAGED clutch at full throttle is never
     * artificially capacity-limited (which would silently cut the stock drive force this ticket must
     * preserve), while a genuine speed mismatch -- launching from rest with the engine already spun up, a
     * shift, a wheel spinning far past the engine -- still demands more than this and so genuinely slips.
     */
    public static final double CLUTCH_TORQUE_CAPACITY = 1200.0;

    /** rad/s -> RPM. */
    public static double omegaToRpm(double omega) {
        return omega * 60.0 / (2.0 * Math.PI);
    }

    /** RPM -> rad/s. */
    public static double rpmToOmega(double rpm) {
        return rpm * 2.0 * Math.PI / 60.0;
    }

    /**
     * The drivetrain's own speed as seen AT THE ENGINE side of the clutch, rad/s: the driven wheel's
     * angular speed multiplied up through {@code ratio} and {@link #FINAL_DRIVE}, the same scaling
     * {@link #rawRpm} already applies on its way to RPM -- the quantity the clutch compares its own
     * {@code engineOmega} against. Sign-independent (abs), for the same reason {@link #rawRpm} is: a fixed
     * ratio spins the engine the same way regardless of which direction the wheel is actually turning.
     */
    public static double wheelSideOmega(double wheelAngularSpeed, double ratio) {
        return Math.abs(wheelAngularSpeed) * ratio * FINAL_DRIVE;
    }

    /**
     * Torque the engine itself tries to produce this tick, N*m, before the clutch: throttle-proportional,
     * and already cut by {@link #limiterTorqueFactor} against the engine's OWN raw RPM -- a rev limiter
     * cuts torque at the crank, not just at the wheel, so an engine spinning free (clutch slipping, or
     * disengaged in NEUTRAL) still cannot rev itself past {@link #LIMITER_RPM} any more than one driving
     * the wheels directly could. {@code throttle} is the car's signed accelerator-minus-brake value, like
     * {@link #freeRevRpm}'s own argument -- the brake must read as zero demand here, not negative torque.
     */
    public static double engineDriveTorque(double throttle, double engineRawRpm, double peakTorque) {
        double accelerator = Math.max(0.0, Math.min(1.0, throttle));
        return accelerator * peakTorque * limiterTorqueFactor(engineRawRpm);
    }

    /**
     * The driven wheels' own rotational inertia as the ENGINE's shaft sees it, kg*m^2: their rotation at
     * the wheel shaft, divided down by the square of the engaged gearing (a gearbox scales reflected
     * inertia by the square of its ratio). The second inertia in {@link #clutchExchangeTorque}'s two-body
     * solve.
     *
     * <p>Review round 3 corrected WHAT this is, and the distinction is the whole point of the chain
     * engine -&gt; clutch -&gt; wheel rotation -&gt; tire -&gt; car mass. Round 2 reflected the car's
     * TRANSLATING MASS onto this shaft, which is only valid while the tire grips: the instant a wheel
     * slips, that shaft accelerates away from the car, and a solve built on the car's mass collapsed the
     * exchange exactly when a tire let go -- measured in CI as a full-throttle car reaching 0.67 m/s after
     * 90 ticks, and a D1 launch losing to D5. The clutch is bolted to the WHEELS, so this is the wheels'
     * own inertia and nothing else; the car's mass enters the chain one link further along, through the
     * contact patch, which {@code WheelMath.tire}'s friction circle already owns. What couples this shaft
     * to the car is therefore not an inertia here at all but the tire's own reaction torque -- see
     * {@link #wheelSideTorqueAtEngine}.
     */
    public static double reflectedWheelInertia(int drivenWheels, double wheelInertia, double ratio) {
        double gearing = ratio * FINAL_DRIVE;
        if (!(gearing != 0.0)) {
            return 0.0;
        }
        return Math.max(0, drivenWheels) * wheelInertia / (gearing * gearing);
    }

    /**
     * Engine-shaft torque -&gt; the TOTAL longitudinal force it puts on the driven wheels' contact patches,
     * N, signed in the car's own forward frame (MINECRAFT-444 review round 3). Through the engaged gearing
     * ({@code ratio * FINAL_DRIVE}), the wheel radius, the engaged gear's own direction (REVERSE turns the
     * wheels backwards for the same engine rotation) and the vehicle's {@code forceScale}, which scales
     * drive force here exactly as it already does in {@link GearDrive#drive}.
     *
     * <p>{@link #wheelForceToClutchTorque} is its exact inverse, and {@link #wheelSideOmegaSigned} is the
     * matching speed transform, so for an ideal (lossless) gearbox the power agrees on both sides:
     * {@code totalForce * wheelSpin * wheelRadius == forceScale * torque * wheelSideOmegaSigned}. That is
     * the sense in which every engine-side torque in this class is "per unit forceScale": a vehicle's
     * forceScale scales its whole engine (torque, rotating inertia, clutch and drag alike), so its actual
     * crank torque is {@code forceScale} times the value these methods carry. Every stock car on which the
     * drivetrain tests run has a forceScale of exactly 1.0.
     */
    public static double clutchTorqueToTotalWheelForce(double torque, double wheelRadius, double ratio,
            double gearDirection, double forceScale) {
        if (!(wheelRadius > 0.0)) {
            return 0.0;
        }
        return torque * ratio * FINAL_DRIVE * gearDirection * forceScale / wheelRadius;
    }

    /**
     * The exact inverse of {@link #clutchTorqueToTotalWheelForce}: the engine-shaft torque (per unit
     * forceScale, positive = the engine driving the drivetrain in the engaged gear's own direction) that a
     * TOTAL driven-wheel longitudinal force signed in the car's forward frame amounts to. Used to turn the
     * wheel commands a tick actually applied back into the one torque the engine is charged for, and the
     * driver's governed demand into the torque the engine is asked for.
     */
    public static double wheelForceToClutchTorque(double totalWheelForce, double wheelRadius, double ratio,
            double gearDirection, double forceScale) {
        double gearing = ratio * FINAL_DRIVE;
        if (!(gearing != 0.0) || !(forceScale > 0.0)) {
            return 0.0;
        }
        return totalWheelForce * wheelRadius * gearDirection / (gearing * forceScale);
    }

    /**
     * The clutch's other shaft's inertia as the engine sees it, kg*m^2 -- the second inertia in
     * {@link #clutchExchangeTorque}'s solve, and the one place the chain
     * engine -&gt; clutch -&gt; wheel rotation -&gt; tire -&gt; car mass decides how far along itself it
     * reaches.
     *
     * <p>It depends on the CONTACT CONSTRAINT, which is the resolution of this ticket's hardest question.
     * While the tire is holding -- the production path's own zero-excess-force condition, the same flag its
     * recovery branch already keys on -- the wheel is kinematically tied to the car, so the car's
     * translating mass genuinely IS on this shaft and the clutch works against the whole vehicle. The
     * moment the tire lets go that constraint is gone, and the shaft is only the wheels' own rotation,
     * free to spin up away from the car.
     *
     * <p>Both unconditional answers were measured and both are wrong. Reflecting the car's mass always
     * (round 2) collapses the exchange exactly when a tire slips, so the car loses drive force in a
     * wheelspin -- CI caught a full-throttle car reaching 0.67 m/s after 90 ticks and a D1 launch losing to
     * D5. Using the wheels' own inertia always (round 3) leaves the clutch nothing to work against in a low
     * gear, and no integration resolution rescues it: sub-stepping the engine against a frozen partner gave
     * 1.60/11.47/15.09/7.57/18.88 m/s at 1/4/8/16/32 inner steps, wheel spin reversing sign. Keying the
     * inertia to the constraint converges at every resolution tested INCLUDING the production tick (D1
     * 17.09 m/s at one step per tick vs 16.08 converged; D5 16.47 vs 16.41), which is why this needs no
     * sub-stepping, no change to {@code wheelSubSteps} and no damping knob.
     *
     * <p>Divided by {@code forceScale} because the solve runs in the same per-unit-forceScale terms as
     * every engine-side torque here (see {@link #clutchTorqueToTotalWheelForce}): a vehicle whose engine
     * is {@code forceScale} times the stock one turns {@code forceScale} times the stock inertia, so the
     * load it sees, per unit of that engine, is this much. Exactly the old value at a forceScale of 1.0.
     *
     * @param contactHolding whether the driven wheels' contact constraint held through the previous step
     *        (every grounded driven wheel delivered what was commanded of it)
     */
    public static double clutchLoadInertia(double massKg, double wheelRadius, int drivenWheels,
            double wheelInertia, double ratio, boolean contactHolding, double forceScale) {
        double gearing = ratio * FINAL_DRIVE;
        if (!(gearing != 0.0) || !(wheelRadius > 0.0) || !(forceScale > 0.0)) {
            return 0.0;
        }
        double wheelsOwn = reflectedWheelInertia(drivenWheels, wheelInertia, ratio);
        if (!contactHolding) {
            return wheelsOwn / forceScale;
        }
        return (wheelsOwn + massKg * wheelRadius * wheelRadius / (gearing * gearing)) / forceScale;
    }

    /**
     * The torque the tire is applying BACK to the driveshaft, reflected to the engine's own shaft, N*m --
     * the external load on the clutch's other shaft, and the link through which the car's mass is felt
     * without ever appearing as an inertia in the coupling itself.
     *
     * <p>Negative while the tire resists the engine's own drive (its reaction to the traction force it is
     * delivering), which is what makes the locked case come out right: with the wheels not accelerating,
     * {@link #clutchExchangeTorque}'s solve reduces to passing essentially the engine's whole output to
     * the road, while during a slip -- where the delivered force, and so this torque, is small -- the
     * exchange is correspondingly smaller and the wheels spin up instead. Taken from the longitudinal force
     * the tires actually delivered over the previous tick, the same one-tick-lagged snapshot convention
     * {@code SableCarBody} already uses for {@code drivenWheelSpin} and the gear ratio -- and only from
     * tires that were actually touching something, so a wheel that has left the ground contributes no
     * reaction at all rather than its last grounded one.
     *
     * <p>Review round 3: this is the reaction to {@link #wheelForceToClutchTorque}, so it carries the
     * engaged gear's direction exactly as {@link #wheelSideOmegaSigned} carries it for speed. The earlier
     * version divided by the (always positive) gearing only, which is right for a forward gear and exactly
     * backwards in REVERSE: a tire delivering -4000 N while reversing (r 0.35, ratio 3.3) resists the
     * engine, so it must read -103.47 N*m here, not +103.47.
     *
     * @param totalWheelForce the driven wheels' delivered longitudinal force over the last tick, N, signed
     *        in the car's forward frame
     * @param gearDirection +1 for a forward gear, -1 for REVERSE
     */
    public static double wheelSideTorqueAtEngine(double totalWheelForce, double wheelRadius, double ratio,
            double gearDirection, double forceScale) {
        return -wheelForceToClutchTorque(totalWheelForce, wheelRadius, ratio, gearDirection, forceScale);
    }

    /**
     * The drivetrain's speed at the engine's own shaft, rad/s, SIGNED in the engine's own rotation sense --
     * unlike {@link #wheelSideOmega}, which takes the magnitude because the limiter and the display only
     * ever care how fast the engine turns, not which way.
     *
     * <p>The sign matters to the clutch and nowhere else: the gearbox's output shaft turns the engine
     * forwards in a forward gear and backwards in REVERSE, so a car rolling FORWARD with REVERSE engaged
     * is trying to turn its engine backwards. Taking the magnitude there (the first attempt) told the
     * clutch the two sides nearly agreed when in truth they were opposed, and the resulting force pointed
     * the wrong way -- it fought the reversal the driver had asked for, and the car stalled out at 2.5 m/s
     * forward in {@code opposingDirectionThrottleDecelerateAndReverseInBothMirroredDirections} instead of
     * reversing. Signed, that same case reads as a hard slip that drags the car toward reverse, which is
     * both what really happens and what the regression expects.
     *
     * @param wheelAngularSpeed the driven wheels' own spin rate, rad/s, signed positive for rolling
     *        forward
     * @param gearDirection +1 for a forward gear, -1 for REVERSE
     */
    public static double wheelSideOmegaSigned(double wheelAngularSpeed, double ratio, double gearDirection) {
        return wheelAngularSpeed * ratio * FINAL_DRIVE * gearDirection;
    }

    /**
     * The ONE torque the clutch exchanges between engine and drivetrain this (sub-)step, N*m: the engine
     * loses exactly this and the drivetrain gains exactly this (positive = engine driving the drivetrain,
     * negative = the drivetrain driving the engine, i.e. engine braking or spinning the engine up).
     *
     * <p>Review round 2's central finding, and the reason this method replaces the previous pair: the
     * engine was being integrated against a one-step SYNC torque while the wheels were handed a different,
     * "output minus drag" torque, so a changing relative speed loaded the engine without ever loading the
     * other shaft -- {@code engineOmega} 400, {@code wheelSideOmega} 500, throttle 0 accelerated the engine
     * by 100 rad/s in a tick on 415 N*m of sync torque while the wheels saw only -55. There is one shaft
     * pair and one friction interface, so there is one torque; both sides now read this value.
     *
     * <p>It is the standard two-inertia clutch solve, which is what makes it stable without a tuned gain:
     * <ul>
     * <li>a FEED-FORWARD share, {@code I_w/(I_e+I_w) * engineTorque} -- the part of the engine's own output
     * that reaches the drivetrain once the engine's inertia has taken its share of accelerating the pair
     * together. This is the locked-clutch result for two inertias, and it carries no {@code 1/dt} term at
     * all, which is precisely what the earlier one-step solve got wrong;
     * <li>a RE-SYNC share, {@code (I_e*I_w/(I_e+I_w))/dt * slip}, the torque that closes the REMAINING
     * relative speed over one step at the pair's own reduced inertia. That gain is derived from the two
     * inertias and {@code dt} rather than tuned, which bounds it: with the wheel side free the slip closes
     * exactly, and in the worst case (an infinitely stiff wheel side) the slip still decays by the strictly
     * positive factor {@code I_e/(I_e+I_w)} per step -- monotone, never overshooting, at any {@code dt}.
     * The earlier attempt's gain was independent of both inertias and overshot every step
     * ({@code 1 - gain*dt/I_e} below -1), which is the instability this must not reintroduce.
     * </ul>
     *
     * <p>Then {@link #CLUTCH_TORQUE_CAPACITY} bounds the result, because a friction clutch cannot transmit
     * more than its own clamp force allows: saturation IS slip, and the slip then decays at the fastest
     * rate the capacity permits instead of in one step. One asymmetry is deliberate and physical rather
     * than a third-law violation: when the engine would be driven below zero it stalls at the floor
     * ({@link #engineAngularVelocity}), and the difference between the exchanged torque and what the
     * stalled engine actually absorbed is dissipated in the slipping friction interface as heat -- the
     * pair is explicitly NOT locked in that case.
     *
     * @param engineOmega the engine's own speed this step, rad/s
     * @param wheelSideOmega {@link #wheelSideOmegaSigned} for the engaged gear -- signed, not a magnitude
     * @param engineTorque the engine's own net output before the clutch, N*m: {@link #engineDriveTorque}
     *        less its own drag (see {@link #engineOutputTorque})
     * @param wheelSideTorque the external torque already acting on the other shaft, N*m at the engine --
     *        {@link #wheelSideTorqueAtEngine}, i.e. the tire's own reaction. Round 3: this term is what
     *        carries the car's mass into the solve without pretending it is an inertia on this shaft, and
     *        it is what makes a gripping clutch pass nearly the engine's whole output while a slipping one
     *        does not.
     * @param reflectedInertia {@link #reflectedWheelInertia} for the engaged gear
     */
    public static double clutchExchangeTorque(double engineOmega, double wheelSideOmega, double engineTorque,
            double wheelSideTorque, double reflectedInertia, double dt,
            VehicleSpec.DrivetrainCoupling coupling) {
        double engineInertia = coupling.engineInertia();
        double totalInertia = engineInertia + reflectedInertia;
        double capacity = coupling.clutchTorqueCapacity();
        if (!(totalInertia > 0.0) || !(dt > 0.0)) {
            return Math.max(-capacity, Math.min(capacity, engineTorque));
        }
        // The two-body locked solve with an external torque on each shaft, which reduces to the familiar
        // (I_w*Te - I_e*Tw)/(I_e+I_w): the torque that leaves both shafts accelerating together.
        double feedForward = (reflectedInertia * engineTorque - engineInertia * wheelSideTorque) / totalInertia;
        double reducedInertia = engineInertia * reflectedInertia / totalInertia;
        double reSync = reducedInertia / dt * (engineOmega - wheelSideOmega);
        return Math.max(-capacity, Math.min(capacity, feedForward + reSync));
    }

    /**
     * The engine's own net output torque before the clutch, N*m: its throttle torque less the drag it
     * applies to its own rotation (nothing to drag against a stalled engine). The quantity both
     * {@link #clutchExchangeTorque} and {@link #engineAngularVelocity} mean by {@code engineTorque}, named
     * so the two can never disagree about whether drag was already subtracted.
     */
    public static double engineOutputTorque(double throttle, double engineRawRpm, double engineOmega,
            VehicleSpec.DrivetrainCoupling coupling) {
        double drag = engineOmega > 0.0 ? coupling.engineBrakingTorque() : 0.0;
        return engineDriveTorque(throttle, engineRawRpm, coupling.enginePeakTorque()) - drag;
    }

    /**
     * The engine's own net output torque while its clutch is ENGAGED, N*m (MINECRAFT-444 review round 3):
     * {@link #engineOutputTorque}, except that the engine is asked for no more torque than the driver's
     * governed demand needs it to put through the clutch.
     *
     * <p>The driver's demand ({@link GearDrive#drive}, taken back through the gearing to the engine shaft
     * as {@code demandTorque}) is what has always set how hard the wheels are pushed: its ratio scaling,
     * its governor near top speed, REVERSE's own much weaker force and the rev limiter. Round 2 applied that
     * ceiling to the WHEEL half of the exchange only, after the engine had been integrated against the
     * whole of it -- the wheels were cut, the engine was not, and the difference simply vanished. Charging
     * the engine for exactly what the wheels took (as {@code SableCarBody} now does) fixes the bookkeeping,
     * but on its own would leave the engine making torque nothing downstream takes: in REVERSE, whose
     * demand is about a sixth of the engine's output, it would race to the limiter within a few ticks. So
     * the cut is made where a drive-by-wire throttle makes it, at the engine: it is asked for
     * {@code demandTorque} plus whatever its own drag will eat, and never for more than it can make. In
     * steady driving that hands the clutch exactly the governed demand -- the same wheel force as before in
     * every gear at every throttle -- with both shafts agreeing on it.
     *
     * <p>When the engine is turning faster than the wheel side (just after an upshift, or while still
     * rolling against the gear just selected) it is asked for correspondingly LESS, down to nothing, so it
     * sheds that surplus into the clutch's own load within the step instead of holding it -- what a
     * rev-matching throttle does, and what keeps a clutch passing the demand from slipping at a constant
     * offset forever. It is never asked for less than zero: a closed throttle is the engine's own drag, not
     * a brake.
     *
     * @param demandTorque the driver's governed demand at the engine shaft, N*m (0 with the throttle shut)
     * @param wheelSideOmega {@link #wheelSideOmegaSigned}: what the engine would be turning at with the
     *        clutch locked. A wheel side turning against the gear counts as 0, the slowest a non-reversing
     *        engine can go.
     */
    public static double engagedEngineTorque(double throttle, double engineRawRpm, double engineOmega,
            double demandTorque, double wheelSideOmega, double dt, VehicleSpec.DrivetrainCoupling coupling) {
        double drag = engineOmega > 0.0 ? coupling.engineBrakingTorque() : 0.0;
        double available = engineDriveTorque(throttle, engineRawRpm, coupling.enginePeakTorque());
        double surplus = dt > 0.0
                ? coupling.engineInertia() * Math.max(0.0, engineOmega - Math.max(0.0, wheelSideOmega)) / dt
                : 0.0;
        double asked = Math.max(0.0, Math.max(0.0, demandTorque) + drag - surplus);
        return Math.min(available, asked) - drag;
    }

    /** Bound a signed exchange by the work measured at both shaft midpoint velocities.
     * External torques may reverse relative speed during the step, so endpoint locking alone
     * does not guarantee dissipation. Use the current partner response, before driver limits. */
    public static double passiveClutchTorque(double requested, double engineOmega, double wheelOmega,
            double engineTorque, double wheelTorque, double wheelInertia, double dt,
            VehicleSpec.DrivetrainCoupling coupling) {
        if (!(dt > 0.0) || !(wheelInertia > 0.0)) return 0.0;
        double mobility = 1.0 / coupling.engineInertia() + 1.0 / wheelInertia;
        // Work of the exchange is -T*dt times the midpoint relative speed of BOTH shafts.
        // External torque can reverse that speed during a step; an endpoint lock alone then
        // permits negative friction work. Bound at zero work before applying driver limits.
        double freeEndSlip = engineOmega - wheelOmega
                + dt * (engineTorque / coupling.engineInertia() - wheelTorque / wheelInertia);
        double zeroWorkTorque = (engineOmega - wheelOmega + freeEndSlip) / (dt * mobility);
        return requested >= 0.0 ? Math.min(requested, Math.max(0.0, zeroWorkTorque))
                : Math.max(requested, Math.min(0.0, zeroWorkTorque));
    }

    /**
     * How much of {@link #clutchExchangeTorque}'s lock torque the clutch can actually pass this step, N*m,
     * in the same sign convention (MINECRAFT-444 review round 3, finding 1).
     *
     * <p>{@code clutchExchangeTorque} answers "what would lock these two shafts together this step"; this
     * answers "what can a friction clutch, driven by this driver, really transmit", and it is the ONLY
     * torque either shaft is ever loaded with. Round 2 integrated the engine against the lock torque and
     * then let {@code GearDrive.driveThroughClutch} re-sign, cap or drop what the wheels got: with the
     * engine at 400 rad/s, the wheel side at 800, full throttle and no tire reaction (D1, wheels' own
     * inertia 0.02203 kg*m^2) the lock torque is -61.6 N*m, so the engine was spun UP to 660 rad/s while the
     * wheels were handed +649.6 N each -- both shafts gained energy. Every one of those decisions is made
     * here instead, before either shaft moves, and each can only shrink the lock torque toward zero, never
     * flip it or exceed it:
     *
     * <ul>
     * <li><b>Never past the other shaft.</b> A friction clutch drags the faster shaft down and the slower
     * one up; it cannot push the engine past the speed of the shaft it is slipping against.
     * {@code catchUp} is the transfer that would leave the engine exactly level with the wheel side at the
     * end of the step were the wheel side to stand still, so a positive transfer is capped by it (an engine
     * that starts BEHIND must first catch up on its own torque, and only what is left over after that can
     * be passed on -- in the case above, nothing) and a negative one floored by it. A wheel side turning
     * against the gear is the exception: the engine stops at zero before it could reach it, so there is no
     * speed to overshoot, and the engine merely anchors -- passive however hard it pulls.
     * <li><b>Open throttle: drive, or nothing.</b> A driver on the accelerator is asking the drivetrain to
     * push the car in the selected gear's direction, so the transfer is never negative here -- an exchange
     * that reads the other way (the wheel side over-running the engine) is a slipping clutch that has not
     * caught up yet, and the engine is left to catch up on its own torque rather than being spun up by the
     * wheels while they are simultaneously driven (the decoupling round 2 lacked). Nor is it ever more
     * than the driver's governed {@code demandTorque}, the ceiling every gear's ratio scaling, governor,
     * REVERSE force and limiter have always set.
     * <li><b>Closed throttle: engine braking only.</b> The transfer is left as the lock torque within the
     * two bounds above; whether it may reach the tire at all (it must oppose the car's actual motion, and
     * nothing below a standstill threshold) is decided per sub-step by
     * {@code GearDrive.driveThroughClutch}, and the engine is charged for whatever the wheels actually
     * took, so that decision stays an exchange too.
     * </ul>
     *
     * @param engineTorque {@link #engagedEngineTorque} for this step
     * @param demandTorque the driver's governed demand at the engine shaft, N*m
     * @param throttleOpen whether the accelerator (not the brake) is pressed
     */
    public static double realizableClutchTorque(double lockTorque, double engineOmega, double wheelSideOmega,
            double engineTorque, double demandTorque, boolean throttleOpen, double dt,
            VehicleSpec.DrivetrainCoupling coupling) {
        if (!(dt > 0.0)) {
            return 0.0;
        }
        double catchUp = engineTorque + coupling.engineInertia() * (engineOmega - wheelSideOmega) / dt;
        if (lockTorque > 0.0) {
            double ceiling;
            if (wheelSideOmega < 0.0) {
                ceiling = throttleOpen ? demandTorque : lockTorque;
            } else {
                ceiling = Math.max(0.0, catchUp);
                if (throttleOpen) {
                    ceiling = Math.min(ceiling, demandTorque);
                }
            }
            return Math.max(0.0, Math.min(lockTorque, ceiling));
        }
        if (lockTorque < 0.0 && !throttleOpen) {
            double floor = wheelSideOmega < 0.0 ? 0.0 : Math.min(0.0, catchUp);
            return Math.max(lockTorque, floor);
        }
        return 0.0;
    }

    /**
     * Advances the engine's own angular speed one (sub-)step, rad/s: its own net output
     * ({@link #engineOutputTorque}, drag already subtracted) less the torque the clutch is exchanging with
     * the drivetrain -- the SAME {@link #clutchExchangeTorque} value the wheels receive, which is what
     * makes this an exchange rather than two independent loads -- divided by the engine's own inertia and
     * integrated over {@code dt}. This IS the engine's rotational-inertia state (MINECRAFT-444): it no
     * longer snaps to whatever the wheels imply, it accelerates/decelerates at a rate its own stored
     * angular momentum bounds, exactly like {@link WheelMath#WHEEL_INERTIA} already bounds a wheel's own
     * spin. Floored at 0: this model has no reverse-spinning engine -- a stalled engine sits at 0, it does
     * not run backwards, and the clutch is then necessarily slipping (see
     * {@link #clutchExchangeTorque}'s own note on where that torque goes).
     */
    public static double engineAngularVelocity(double priorOmega, double engineTorque, double clutchTorque,
            double dt, VehicleSpec.DrivetrainCoupling coupling) {
        double netTorque = engineTorque - clutchTorque;
        return Math.max(0.0, priorOmega + netTorque / coupling.engineInertia() * dt);
    }

    /**
     * Engine-axis reaction torque this (sub-)step, N*m -- Newton's third law for the engine's own spinning
     * mass: the engine's ACTUAL angular-momentum change this step pushes back on whatever the engine is
     * bolted to with the opposite sign, about the engine's own spin axis.
     *
     * <p>Review (round 1) fix: this used to return the UNCLIPPED net torque
     * ({@code engineTorque - braking - clutchTorque}), which is not what actually moved the engine on any
     * step where {@link #engineAngularVelocity}'s own floor-at-zero clamped the result -- an engine 0.01
     * rad/s from a standstill sheds only {@code 0.0018 N*m*s} of angular momentum, while the unclipped
     * braking torque would have billed the chassis {@code 55 * dt}, conjuring an impulse out of nothing
     * exactly like the wheel-recovery reaction bug the round-6 review caught on the ground side. Taking
     * the realized {@code nextOmega - priorOmega} instead makes the identity exact on every step,
     * floor-crossing included, and at any dt -- the same way {@code SableCarBody}'s own recovery reaction
     * is computed from the spin change {@link #boundedSpinTowardSnap} actually returned, never from the
     * torque that was asked for.
     *
     * <p>Unlike that ground-sourced recovery reaction (which needs a wheel in contact to exist at all),
     * this is independent of gear, wheel contact or ground: a dyno'd engine with its wheels in the air
     * still torques its own mounts exactly this hard. Deliberately NOT a function of which gear is
     * selected -- REVERSE changes which way torque leaves the clutch toward the final drive, it does not
     * reverse which way the crankshaft itself spins, so this takes no gear argument at all and tracks only
     * the engine's own realized rotation (MINECRAFT-444 acceptance: "selecting reverse must not flip the
     * engine torque axis").
     *
     * <p>Scaled by {@code forceScale} because that is the engine's actual size: every engine-side value in
     * this class is per unit forceScale (see {@link #clutchTorqueToTotalWheelForce}), so a vehicle's real
     * crankshaft carries {@code forceScale} times {@code engineInertia}. Exactly the old value at a
     * forceScale of 1.0, which every stock car on the drivetrain tests has.
     *
     * @param priorOmega the engine's speed coming into this step, rad/s
     * @param nextOmega {@link #engineAngularVelocity}'s own result for this same step -- the REALIZED
     *        speed, after its floor, not a target
     */
    public static double engineReactionTorque(double priorOmega, double nextOmega, double dt,
            VehicleSpec.DrivetrainCoupling coupling, double forceScale) {
        return -(nextOmega - priorOmega) * coupling.engineInertia() * forceScale / dt;
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
