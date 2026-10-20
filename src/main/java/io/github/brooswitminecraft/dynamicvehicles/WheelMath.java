package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure per-wheel arithmetic for the Sable rigid-body car: the spring-damper
 * suspension. Free of Minecraft and Sable types so it can be unit tested.
 * Starting numbers for a 1200 kg car on four wheels, to be tuned in play.
 */
public final class WheelMath {
    /** Distance from a wheel mount to the ground at which the spring is unloaded, metres. */
    public static final double REST_LENGTH = 0.65;
    /** Spring rate, N/m: about 0.15 m of compression under a quarter of the car. */
    public static final double SPRING_RATE = 19_620.0;
    /** Damper rate, N*s/m: roughly 0.7 of critical damping for a 300 kg quarter. */
    public static final double DAMPING_RATE = 3_400.0;
    /** Never pull the car down onto the ground: a spring can only push. */
    public static final double MAX_FORCE = 40_000.0;

    /** Share of the car carried and driven by one wheel, kg (a quarter of 1200 kg). */
    public static final double EFFECTIVE_MASS = 300.0;
    /** Peak tire friction coefficient on a reference (dry pavement) surface. */
    public static final double BASE_FRICTION = 1.1;
    /** Rolling resistance coefficient: a force of this times the wheel load opposes rolling. */
    public static final double ROLLING_RESISTANCE = 0.015;
    /** How much of the correction to a sliding tire is applied per tick; below 1 so it never overshoots. */
    public static final double RELAXATION = 0.6;
    /** Extra rolling drag a fully deformable surface (sand) adds on top of its own rollingResistance: wheels sink in and push ground ahead of them, not just scuff it. */
    public static final double DEFORMABILITY_ROLLING_GAIN = 0.5;

    /** Rotational inertia of one wheel+tire about its axle, kg*m^2: a plain number for a ~0.35 m wheel, shared by every vehicle (not a per-spec tuning knob; MINECRAFT-75 owns tuning). */
    public static final double WHEEL_INERTIA = 1.2;

    /** m/s^2, matching {@code SableCarBody}'s own private constant of the same value. */
    public static final double GRAVITY = 9.81;

    private WheelMath() {}

    /**
     * The widest front-wheel angle (radians) the car can hold without exceeding the tires' grip at this speed:
     * cornering needs a lateral acceleration of v^2 * tan(angle) / wheelbase, and we allow 80% of mu * g.
     * At low speed the mechanical limit applies instead.
     */
    public static double maxSteerAngle(double speed, double mu, double wheelbase, double mechanicalLimit) {
        double v2 = Math.max(speed * speed, 1.0);
        return Math.min(mechanicalLimit, Math.atan(0.8 * mu * 9.81 * wheelbase / v2));
    }

    /**
     * What one tire does this tick.
     *
     * @param longitudinal force along the wheel's rolling direction, N
     * @param lateral force across it, N
     * @param slipSpeed metres per second of sliding the tire could not correct this tick (0 while gripping)
     * @param commandLongitudinal the longitudinal force the driveline/brake/rolling-resistance math wanted
     *        along the rolling direction BEFORE the friction-circle clamp, N - identical to
     *        {@code longitudinal} whenever the circle did not have to scale anything down (gripping), and
     *        the honest basis for {@link #spinRate}'s own torque balance (not a value a caller should
     *        try to approximate itself: it already folds in rolling resistance and the brake's own
     *        relaxation clamp, both of which a drive/brake-force-only approximation misses).
     * @param frictionLimit {@code mu * normalForce} as THIS call actually used it (0 when not gripping at
     *        all) -- the same circle {@code longitudinal}/{@code lateral} were clamped to, exposed so a
     *        caller computing the circle's remaining HEADROOM this step ({@code frictionLimit -
     *        hypot(longitudinal, lateral)}, see {@code SableCarBody}'s own wheel-spin-recovery call site)
     *        does not have to recompute {@code mu * normalForce} itself: {@link DriftTireModel} tunes
     *        {@code mu} before calling {@link #tire}, so recomputing it at a caller would silently use
     *        the wrong (untuned) circle for a drift-tuned vehicle.
     */
    public record Tire(double longitudinal, double lateral, double slipSpeed, double commandLongitudinal,
            double frictionLimit) {}

    /**
     * A friction-circle tire: it wants to cancel sideways sliding and to deliver the drive or brake force,
     * but the combined force cannot exceed {@code mu * normalForce}; whatever the circle cannot supply shows
     * up as slip.
     *
     * @param vLong contact-patch velocity along the wheel's rolling direction, m/s
     * @param vLat contact-patch velocity across it, m/s
     * @param normalForce load on the tire (the suspension force), N
     * @param mu friction coefficient available on this surface
     * @param lateralScale 1 normally; below 1 lets the tire slide (handbrake)
     * @param driveForce engine force wanted at the contact, N (negative: reverse)
     * @param brakeForce maximum braking force, N (0 when not braking)
     */
    public static Tire tire(double vLong, double vLat, double normalForce, double mu, double lateralScale,
            double driveForce, double brakeForce, double dt) {
        return tire(vLong, vLat, normalForce, mu, ROLLING_RESISTANCE, lateralScale, driveForce, brakeForce, dt);
    }

    /** As above, with the surface's own rolling resistance coefficient (sand is many times pavement). */
    public static Tire tire(double vLong, double vLat, double normalForce, double mu, double rollingCoefficient,
            double lateralScale, double driveForce, double brakeForce, double dt) {
        return tire(vLong, vLat, normalForce, mu, rollingCoefficient, lateralScale, driveForce, brakeForce, 1.0, dt);
    }

    /** As above, with a brake gain above 1 for a parked car that must not creep down a slope. */
    public static Tire tire(double vLong, double vLat, double normalForce, double mu, double rollingCoefficient,
            double lateralScale, double driveForce, double brakeForce, double brakeGain, double dt) {
        return tire(vLong, vLat, normalForce, mu, rollingCoefficient, lateralScale, driveForce, brakeForce, brakeGain, EFFECTIVE_MASS, dt);
    }

    /** As above, for a vehicle whose wheel carries {@code effectiveMass} kg. */
    public static Tire tire(double vLong, double vLat, double normalForce, double mu, double rollingCoefficient,
            double lateralScale, double driveForce, double brakeForce, double brakeGain, double effectiveMass, double dt) {
        if (!(normalForce > 0) || !(mu > 0)) {
            return new Tire(0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double limit = mu * normalForce;
        double wantLat = -effectiveMass * vLat / dt * RELAXATION * lateralScale;
        double wantLong;
        if (brakeForce > 0) {
            double stop = effectiveMass * vLong / dt * RELAXATION * brakeGain;
            wantLong = -Math.max(-brakeForce, Math.min(brakeForce, stop));
        } else {
            // MINECRAFT-450: capped at the force that would bring vLong to exactly 0 this step
            // (effectiveMass * |vLong| / dt), not just rollingCoefficient * normalForce outright - a
            // fixed-magnitude rolling force applied for a full dt/subDt can otherwise remove MORE momentum
            // than the wheel actually has once vLong is small but still above the 0.05 dead zone (e.g. a
            // high-rollingCoefficient surface with wheelSubSteps=1, the longest subDt the live config
            // allows), overshooting past zero into the opposite sign - exactly the "near-rest sign flip"
            // the dead zone above exists to prevent, just one step later than the dead zone alone catches.
            // Proven by a failing test before this fix (WheelMathTest.coastingToRestNeverFlipsSignNearZeroSpeed):
            // repeatedly stepping WheelMath.tire's own output at vLong just above 0.05 made it oscillate
            // across zero indefinitely instead of settling. Far from zero this cap is a no-op - every
            // existing coast-down test's rollingCoefficient * normalForce is already far smaller than
            // effectiveMass * |vLong| / dt there, so this changes no other behaviour.
            double rollingMagnitude = Math.min(rollingCoefficient * normalForce, effectiveMass * Math.abs(vLong) / dt);
            double rolling = Math.abs(vLong) > 0.05 ? -Math.signum(vLong) * rollingMagnitude : 0.0;
            wantLong = driveForce + rolling;
        }
        double demand = Math.hypot(wantLong, wantLat);
        double scale = demand > limit ? limit / demand : 1.0;
        double slip = demand > limit ? (demand - limit) * dt / effectiveMass : 0.0;
        return new Tire(wantLong * scale, wantLat * scale, slip, wantLong, limit);
    }

    /** As above, applying the vehicle's static camber/toe setup before the existing friction-circle math.
     * Other {@link VehicleSpec.VehicleTuning} fields remain owned by their respective models. */
    public static Tire tire(double vLong, double vLat, double normalForce, double mu, double rollingCoefficient,
            double lateralScale, double driveForce, double brakeForce, double brakeGain, double effectiveMass,
            double dt, VehicleSpec.VehicleTuning tuning) {
        CamberToeGripModel.GripModifiers modifiers = CamberToeGripModel.modifiers(tuning.camberDeg(), tuning.toeDeg());
        return tire(vLong, vLat, normalForce, mu * modifiers.longitudinalMuScale(),
                rollingCoefficient, lateralScale * modifiers.lateralScale(), driveForce, brakeForce,
                brakeGain, effectiveMass, dt);
    }

    /**
     * The rolling resistance coefficient a wheel feels: the surface's own rollingResistance, scaled
     * per-vehicle, plus extra drag on a deformable (soft/loose) surface where the wheel sinks in rather
     * than just scuffing across it.
     *
     * @param rollingResistance the surface's {@code SurfaceProperties.rollingResistance()}, 0..1
     * @param deformability the surface's {@code SurfaceProperties.deformability()}, 0..1
     * @param rollingScale per-vehicle scale for soft-ground rolling resistance ({@code VehicleSpec.rollingScale()},
     *        dimensionless, see that field's own javadoc for its range/default/fallback - no clamp is applied here,
     *        so a caller passing an out-of-range value gets a proportionally out-of-range coefficient back)
     * @return the dimensionless rolling resistance coefficient {@link #tire} multiplies by the wheel's own
     *         normal force to get the rolling drag force, N
     */
    public static double rollingCoefficient(double rollingResistance, double deformability, double rollingScale) {
        return rollingResistance * rollingScale * (1.0 + deformability * DEFORMABILITY_ROLLING_GAIN);
    }

    /**
     * MINECRAFT-443: the production seam a per-car {@code rideHeight} config override actually reaches,
     * extracted out of {@code SableCarBody.tick}'s raycast loop (which calls this exact method, not a
     * reimplementation of it) so the restLength-to-compression step is itself directly testable without
     * Sable/Minecraft bootstrap, the same way {@link #suspensionForce} already is.
     *
     * @param restLength metres from a wheel mount to the ground at which the spring is unloaded
     *        ({@code VehicleSpec#restLength}, derived by {@code VehicleSpecOverrides} from any
     *        {@code rideHeight} override via {@link #restLengthForRideHeight})
     * @param distance metres from the wheel mount to the raycast hit (the ground)
     * @return metres the spring is shorter than {@code restLength} (negative: not touching)
     */
    public static double compression(double restLength, double distance) {
        return restLength - distance;
    }

    /**
     * MINECRAFT-443 review fix (comment 48903): the {@code restLength} a {@code rideHeight} config
     * override must produce so the car's ACTUAL static resting height (where spring force balances its
     * own static per-wheel load, {@link #suspensionForce}'s {@code compression * springRate} at zero
     * compression rate) equals the REQUESTED {@code rideHeight} -- not a value merely proportional to the
     * stock {@code restLength}, which {@link #compression}'s own geometry (restLength, mount height and
     * static sag are not related by a common ratio) does not preserve.
     *
     * <p>This is NOT claimed to reproduce every existing car's own stock {@code restLength} when called
     * with that car's own stock {@code rideHeight} (review fix 48913's correction of an earlier, wrong
     * claim here): only CAR, TRUCK, TROPHY and MUSCLE's stock numbers happen to already satisfy this
     * equilibrium exactly (see {@code WheelMathTest}); DRIFT, ROCK_CRAWLER, MONSTER_TRUCK, INDY, BUS and
     * CARGO_TRUCK's stock pairings do not (hand-tuned/rounded, not derived). That is expected and fine --
     * this method is only ever called with a NEWLY REQUESTED {@code rideHeight} from an actual override,
     * never with a car's own unmodified stock value (see the {@code rideHeight == stock.rideHeight()}
     * guard in {@code VehicleSpecOverrides#applyOverride}), so no existing car's stock pairing needs to
     * satisfy this formula for the override case to be correct.
     *
     * <p>Derivation: at rest, {@code distance = rideHeight + mountY} (mountY is negative: the mount sits
     * below the body centre {@code VehicleSpec#mounts()} describes), and {@code compression = restLength
     * - distance} must equal the static compression that makes {@code springRate * compression} carry
     * this wheel's even share of the car's weight: {@code massKg * GRAVITY / wheelCount / springRate}.
     * Solving for {@code restLength} gives this method's return value.
     *
     * @param rideHeight the REQUESTED resting height, metres (the config override's own value)
     * @param mountY this car's wheel mount height in the body frame, metres (negative; {@code
     *        VehicleSpec#mounts()[wheel][1]}, uniform across every existing car's own four wheels)
     * @param massKg {@code VehicleSpec#massKg} (not file-overridable, so always the stock value)
     * @param springRate {@code VehicleSpec#springRate} (likewise always stock)
     * @param wheelCount {@code VehicleSpec#mounts().length}
     */
    public static double restLengthForRideHeight(double rideHeight, double mountY, double massKg,
            double springRate, int wheelCount) {
        double staticCompression = massKg * GRAVITY / wheelCount / springRate;
        return rideHeight + mountY + staticCompression;
    }

    /**
     * MINECRAFT-443 review fix (comment 48913): {@link VehicleSpecLimits}' own {@code rideHeight} range
     * (0.1 - 5.0 m) is car-agnostic, but a {@code rideHeight} this low is not usable for every car: {@link
     * #restLengthForRideHeight} can return a negative or near-zero {@code restLength} once {@code
     * rideHeight} is smaller than this car's own mount depth, and {@code SableCarBody.tick}'s raycast
     * (length {@code restLength + 0.25}) can even reverse direction once that goes negative enough. A
     * {@code rideHeight} at or below this car's own {@code halfY} also sinks the chassis itself into the
     * ground, independent of suspension entirely.
     *
     * <p>The exact, sufficient condition for a physically sane result: the wheel mount must end up above
     * ground at rest ({@code rideHeight + mountY > 0}, i.e. {@code rideHeight > -mountY}), which alone
     * guarantees {@code restLengthForRideHeight} returns a value strictly greater than the (always
     * positive) static compression; and the chassis bottom must clear the ground ({@code rideHeight >
     * halfY}). Both are per-car geometry constants (never file-overridable), so this check is itself
     * independent of anything a config file could possibly request.
     *
     * @param rideHeight the REQUESTED resting height, metres
     * @param halfY {@code VehicleSpec#halfY} -- half the body's own height
     * @param mountY {@code VehicleSpec#mounts()[wheel][1]} (negative)
     * @return whether {@code rideHeight} is usable for a car with this geometry
     */
    public static boolean isUsableRideHeight(double rideHeight, double halfY, double mountY) {
        return rideHeight > halfY && rideHeight > -mountY;
    }

    /**
     * @param compression metres the spring is shorter than its rest length (negative: not touching)
     * @param compressionRate m/s the spring is being squeezed (positive: the wheel is moving toward the car body)
     * @return the force the spring pushes the car up with, N (never negative)
     */
    public static double suspensionForce(double compression, double compressionRate) {
        return suspensionForce(compression, compressionRate, SPRING_RATE, DAMPING_RATE, MAX_FORCE);
    }

    /** As above, for a vehicle with its own spring, damper and force limit. */
    public static double suspensionForce(double compression, double compressionRate, double springRate, double dampingRate, double maxForce) {
        if (!(compression > 0)) {
            return 0.0;
        }
        double force = springRate * compression + dampingRate * compressionRate;
        return Math.max(0.0, Math.min(maxForce, force));
    }

    /**
     * MINECRAFT-443: the production seam a per-car {@code handbrakeScale} config override actually
     * reaches, extracted out of {@code SableCarBody.tick}'s handbrake branch (which calls this exact
     * method, not a reimplementation of it) so the multiplication is itself directly testable without
     * Sable/Minecraft bootstrap.
     *
     * @param handbrakeForce the shared base handbrake force constant, N ({@code
     *        SableCarBody#HANDBRAKE_FORCE})
     * @param forceScale this vehicle's overall drive/brake force scale ({@code VehicleSpec#forceScale})
     * @param handbrakeScale this vehicle's own handbrake strength multiplier ({@code
     *        VehicleSpec#handbrakeScale}, {@code 1.0} = identity)
     * @return the actual rear-wheel brake force applied while the handbrake is held, N
     */
    public static double handbrakeForce(double handbrakeForce, double forceScale, double handbrakeScale) {
        return handbrakeForce * forceScale * handbrakeScale;
    }

    /**
     * Advances one wheel's own spin rate (rad/s) by one (sub-)step of real torque balance: how much of the
     * driveline's own commanded longitudinal force the ground could not absorb this step -
     * {@code excessForce}, meant to be {@link Tire#commandLongitudinal()} minus {@link Tire#longitudinal()}
     * from the SAME {@link #tire} call that produced this step's applied force (reusing both values; no
     * extra {@code tire()} call). Both of those numbers come out of {@code tire()}'s own single internal
     * {@code wantLong}: {@code commandLongitudinal} is it unscaled, {@code longitudinal} is it multiplied
     * by the friction-circle's {@code scale}. Whenever {@code scale} is exactly 1.0 (gripping - the
     * circle did not have to reduce anything, including every case where the wheel was never asking for
     * more than rolling resistance or a relaxed brake target in the first place), that multiplication by
     * the literal {@code double} {@code 1.0} leaves {@code longitudinal} bit-identical to
     * {@code commandLongitudinal}, so {@code excessForce} is exactly 0.0 - not approximately, and not only
     * for a caller who happens to be asking for the full drive/brake force with nothing else going on.
     * <p>
     * An {@code excessForce} of exactly 0 means the wheel is gripping right now, so it is pulled to ground
     * speed rather than carrying forward a slip rate it no longer has any cause for. A nonzero
     * {@code excessForce} integrates into spin away from the ground (positive: wheelspin, the drive
     * command outran what the ground gave back; negative: lock-up, the brake command did) - the wheel's
     * own vertical-load-scaled grip limit is already baked into {@code longitudinal} by {@code tire()},
     * so nothing further about load needs to appear here.
     *
     * @param spin this wheel's spin rate coming into the step, rad/s
     * @param wheelRadius metres
     * @param groundSpeed the contact patch's own velocity along the wheel's rolling direction this step, m/s
     * @param excessForce {@code commandLongitudinal - longitudinal} from this step's own {@code tire()} call, N
     * @param wheelInertia kg*m^2
     * @param dt seconds this step covers
     */
    public static double spinRate(double spin, double wheelRadius, double groundSpeed, double excessForce,
            double wheelInertia, double dt) {
        if (excessForce == 0.0) {
            return groundSpeed / wheelRadius;
        }
        return spin + excessForce * wheelRadius / wheelInertia * dt;
    }

    /**
     * The longitudinal slip speed this wheel's own tracked spin implies against the ground, m/s: positive
     * while the wheel spins faster than the ground (wheelspin), negative while it spins slower (lock-up),
     * exactly 0 whenever {@link #spinRate} last found the wheel gripping.
     */
    public static double slipFromSpin(double spin, double wheelRadius, double groundSpeed) {
        return spin * wheelRadius - groundSpeed;
    }

    /**
     * Anti-roll bar: a force that resists the two wheels of an axle being compressed differently.
     * @param compression this wheel's spring compression, metres (0 when airborne)
     * @param otherCompression the other wheel of the axle
     * @param barRate N per metre of compression difference
     * @return extra vertical force on this wheel, N (positive pushes the car up here); the other wheel gets the opposite
     */
    public static double antiRollForce(double compression, double otherCompression, double barRate) {
        return barRate * (Math.max(0.0, compression) - Math.max(0.0, otherCompression));
    }

    /**
     * The load this wheel's anti-roll bar actually moves to or from its axle partner this tick: the
     * desired transfer ({@link #antiRollForce}), capped to what the wheel giving up load can actually
     * surrender (its own suspension force). Call once per wheel with that wheel's own suspension force
     * and compression first, the partner's second; the partner's call with the arguments swapped always
     * returns the exact negation, so applying each wheel's own result to its own suspension force keeps
     * the pair antisymmetric and the axle's total load unchanged - never clamp each wheel's combined
     * total independently, or the two sides stop summing to zero and the chassis gains load from
     * nowhere.
     * <p>The cap is the giver's own suspension force, already clamped to its {@code maxForce}, not the
     * receiver's remaining headroom under its own {@code maxForce} - so the receiver's resulting total
     * (its suspension force plus this transfer) is not itself re-clamped here, and can exceed
     * {@code maxForce}, bounded at twice it. Deliberate: re-clamping the receiver alone would, like the
     * bug this replaces, break the antisymmetric pair (the giver would still have paid the uncapped
     * amount). Accepted as a rare, bounded overshoot rather than complicating both sides' caps together.
     */
    public static double antiRollTransfer(double suspensionForce, double compression,
            double otherSuspensionForce, double otherCompression, double barRate) {
        double desired = antiRollForce(compression, otherCompression, barRate);
        double giverForce = desired >= 0 ? otherSuspensionForce : suspensionForce;
        double cap = Math.max(0.0, giverForce);
        return Math.max(-cap, Math.min(cap, desired));
    }
}
