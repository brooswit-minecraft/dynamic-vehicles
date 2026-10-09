package io.github.brooswitminecraft.dynamicvehicles;

import java.util.List;

/**
 * Everything that differs between vehicle types: the shape the physics, renderer and debug overlay share
 * (body frame: origin at the body's centre, x right, y up, z forward), the suspension and the drivetrain.
 * The car's numbers are {@link CarGeometry} and {@link WheelMath}'s, unchanged.
 */
public record VehicleSpec(
        double halfX, double halfY, double halfZ,
        double[][] mounts, double wheelRadius, double wheelWidth,
        /** Height of the body centre above the ground when resting on its springs. */
        double rideHeight,
        /** Distance from a wheel mount to the ground at which the spring is unloaded. */
        double restLength,
        double massKg, double springRate, double dampingRate, double maxSpringForce,
        double wheelbase, double maxSpeed,
        /** Engine force per wheel, and the brakes, relative to the car's. */
        double forceScale,
        /** Multiplies the engine sound's pitch (below 1: deeper). */
        double enginePitch,
        /** Where the driver sits, from the entity origin (bottom centre of the box). Kept alongside
         * {@link #seats} (rather than folded into it) so every existing vehicle's constructor call
         * continues to compile untouched -- see the legacy constructor below, which derives the
         * single-seat {@link #seats} list from exactly these two numbers. */
        double seatY, double seatZ,
        /** Grip on loose surfaces is multiplied by this (capped at the pavement's), and soft ground's rolling resistance by rollingScale. */
        double looseGrip, double rollingScale,
        /** This vehicle's tire model tuning; {@link TireTuning#IDENTITY} for every vehicle that has not opted in. */
        TireTuning tireTuning,
        /** Downforce (MINECRAFT-184): extra tire grip per m/s of forward speed, applied as a multiplier on
         * {@code gripMu} in {@link SableCarBody#tick} ({@code 1.0 + downforceGripPerSpeed * |forwardSpeed|}).
         * {@code 0.0} for every vehicle that has not opted in &mdash; the multiplier is then exactly 1.0, so
         * {@code SableCarBody.tick} is unchanged for them. Only the Indy Car sets this nonzero. */
        double downforceGripPerSpeed,
        /** Per-vehicle anti-roll bar stiffness multiplier (MINECRAFT-212/214): {@link SableCarBody#tick}
         * computes {@code barRate = springRate() * CarConfig.ANTI_ROLL.get() * antiRollScale()}, so a
         * vehicle can get a stiffer anti-roll bar without changing {@code CarConfig.ANTI_ROLL}, which is
         * one global value shared by every vehicle. {@code 1.0} (identity, no extra scale) for every
         * vehicle that has not opted in &mdash; only {@link #ROCK_CRAWLER} sets this above {@code 1.0}. */
        double antiRollScale,
        /**
         * Every seat this vehicle has, in boarding order; seat 0 is always the driver (MINECRAFT-172).
         * Supports any number of seats (an 8-seat bus included) &mdash; {@code CAR}, {@code TRUCK},
         * {@code TROPHY}, {@code DRIFT} and {@code MUSCLE} never set this directly: the legacy constructor
         * below derives their single-entry list from {@code seatY}/{@code seatZ} so they need no edits and
         * behave byte-for-byte as before (see {@code VehicleSeating}, which gates multi-seat boarding on
         * speed and never gates a one-seat vehicle at all).
         */
        List<Seat> seats) {

    /**
     * Legacy shape (MINECRAFT-172): exactly the field list every vehicle spec used before multi-seat
     * support existed. {@code CAR}, {@code TRUCK}, {@code TROPHY}, {@code DRIFT} and {@code MUSCLE} all
     * still call this constructor unedited; it derives a one-seat {@link #seats} list from {@code seatY}/
     * {@code seatZ} (driver at x = 0, seat 0) so those five specs need no edits and behave byte-for-byte as
     * before this ticket.
     */
    public VehicleSpec(double halfX, double halfY, double halfZ,
            double[][] mounts, double wheelRadius, double wheelWidth,
            double rideHeight, double restLength,
            double massKg, double springRate, double dampingRate, double maxSpringForce,
            double wheelbase, double maxSpeed,
            double forceScale, double enginePitch,
            double seatY, double seatZ,
            double looseGrip, double rollingScale,
            TireTuning tireTuning) {
        this(halfX, halfY, halfZ, mounts, wheelRadius, wheelWidth, rideHeight, restLength,
                massKg, springRate, dampingRate, maxSpringForce, wheelbase, maxSpeed,
                forceScale, enginePitch, seatY, seatZ, looseGrip, rollingScale, tireTuning,
                0.0, 1.0, List.of(new Seat(0.0, seatY, seatZ)));
    }

    /** As the legacy constructor above, plus an explicit {@link #downforceGripPerSpeed} (MINECRAFT-184) for
     * a single-seat vehicle that opts into speed-dependent grip, deriving its one-seat {@link #seats} list
     * from {@code seatY}/{@code seatZ} exactly as the legacy constructor does. {@link #antiRollScale}
     * defaults to {@code 1.0} (identity), same as the legacy constructor. */
    public VehicleSpec(double halfX, double halfY, double halfZ,
            double[][] mounts, double wheelRadius, double wheelWidth,
            double rideHeight, double restLength,
            double massKg, double springRate, double dampingRate, double maxSpringForce,
            double wheelbase, double maxSpeed,
            double forceScale, double enginePitch,
            double seatY, double seatZ,
            double looseGrip, double rollingScale,
            TireTuning tireTuning, double downforceGripPerSpeed) {
        this(halfX, halfY, halfZ, mounts, wheelRadius, wheelWidth, rideHeight, restLength,
                massKg, springRate, dampingRate, maxSpringForce, wheelbase, maxSpeed,
                forceScale, enginePitch, seatY, seatZ, looseGrip, rollingScale, tireTuning,
                downforceGripPerSpeed, 1.0, List.of(new Seat(0.0, seatY, seatZ)));
    }

    /** As the legacy constructor above, plus an explicit {@link #antiRollScale} (MINECRAFT-212/214) for a
     * single-seat vehicle that needs a stiffer anti-roll bar than the shared global {@code
     * CarConfig.ANTI_ROLL}, deriving its one-seat {@link #seats} list from {@code seatY}/{@code seatZ}
     * exactly as the legacy constructor does. {@link #downforceGripPerSpeed} defaults to {@code 0.0}
     * (identity), same as the legacy constructor; only {@link #ROCK_CRAWLER} uses this constructor. */
    public VehicleSpec(double halfX, double halfY, double halfZ,
            double[][] mounts, double wheelRadius, double wheelWidth,
            double rideHeight, double restLength,
            double massKg, double springRate, double dampingRate, double maxSpringForce,
            double wheelbase, double maxSpeed,
            double forceScale, double enginePitch,
            double seatY, double seatZ,
            double looseGrip, double rollingScale,
            TireTuning tireTuning, double downforceGripPerSpeed, double antiRollScale) {
        this(halfX, halfY, halfZ, mounts, wheelRadius, wheelWidth, rideHeight, restLength,
                massKg, springRate, dampingRate, maxSpringForce, wheelbase, maxSpeed,
                forceScale, enginePitch, seatY, seatZ, looseGrip, rollingScale, tireTuning,
                downforceGripPerSpeed, antiRollScale, List.of(new Seat(0.0, seatY, seatZ)));
    }

    /**
     * One seat's local offset from the entity origin (bottom centre of the box): x right, y up, z forward
     * &mdash; the same frame {@link #seatY}/{@link #seatZ} already used. Doubles as both the passenger
     * attachment point and (vanilla ties the rider's view to that same point) the per-seat camera position;
     * {@code VehicleSeating} derives the per-seat dismount point from it separately, since a dismount must
     * land clear of the body rather than inside it.
     */
    public record Seat(double x, double y, double z) {}

    /** Seat 0, the driver &mdash; {@code CarEntity.getControllingPassenger} always reads this seat, never
     * whichever passenger happens to be first in the entity's own passenger list, so a passenger can never
     * steer regardless of boarding/dismount order. */
    public Seat driverSeat() {
        return seats.get(0);
    }

    /** How many seats this vehicle has, including the driver's. */
    public int seatCount() {
        return seats.size();
    }

    /**
     * Per-vehicle knobs for {@link DriftTireModel}, the pure-function tire model layered on top of
     * {@link WheelMath}'s own friction-circle tire. {@link #IDENTITY} is the value every field must take
     * for {@link SableCarBody#tick} to skip {@link DriftTireModel} entirely and run the untouched
     * {@link WheelMath#tire} path instead (see {@link #isIdentity()}) &mdash; the hard constraint that
     * {@code CAR}, {@code TRUCK} and {@code TROPHY} stay byte-for-byte unchanged depends on every one of
     * them carrying exactly {@code IDENTITY}.
     *
     * @param rearGripScale rear tire grip (mu) as a fraction of the front's; 1.0 = no front/rear split (identity)
     * @param slipAngleThreshold lateral slip speed, m/s, below which a tire grips at its full (scaled) mu;
     *        beyond it grip falls off toward {@code 1 - gripFalloff}. Unused while {@code gripFalloff} is 0
     * @param gripFalloff fraction of grip a tire can lose once sliding well past {@code slipAngleThreshold};
     *        0 = no falloff curve at all (identity) &mdash; grip stays at the plain scaled mu regardless of slip
     * @param handbrakeRearGripCut extra multiplier on the rear handbrake's existing lateral-grip cut
     *        (applied on top of {@code SableCarBody}'s own handbrake {@code lateralScale}, which every
     *        vehicle already gets); 1.0 = no extra cut (identity)
     * @param throttleBite fraction of a rear tire's grip given up to the drive force's own share of the
     *        friction circle (friction-circle style: the more of the circle the drive force is already
     *        using, the less lateral grip is left); 0 = no extra bite beyond the plain friction circle
     *        every vehicle already has (identity)
     * @param counterSteerAssist fraction of the grip lost to {@code gripFalloff} restored when the driver
     *        steers into the slide (the classic countersteer) at this wheel; 0 = no recovery assist (identity)
     */
    public record TireTuning(double rearGripScale, double slipAngleThreshold, double gripFalloff,
            double handbrakeRearGripCut, double throttleBite, double counterSteerAssist) {

        /** No front/rear split, no falloff, no extra handbrake cut, no throttle bite, no countersteer assist. */
        public static final TireTuning IDENTITY = new TireTuning(1.0, 999.0, 0.0, 1.0, 0.0, 0.0);

        /** Whether this tuning is exactly {@link #IDENTITY} &mdash; the gate {@code SableCarBody.tick} uses to skip {@link DriftTireModel}. */
        public boolean isIdentity() {
            return this.equals(IDENTITY);
        }
    }

    /**
     * MINECRAFT-182: 2+2 seating &mdash; the driver (seat 0, unmoved at x = 0) and a front passenger beside
     * it, each at {@code seatZ}; two rear passengers side by side 0.9 m further back. Every x offset (0.5 m)
     * sits well inside {@code halfX} (0.95 m), and the rear row (z = -1.0) well inside {@code halfZ} (1.5 m).
     * The front passenger sits on the opposite side (x &lt; 0) from the driver's default dismount (x &gt;= 0
     * dismounts right, see {@link VehicleSeating#dismountOffset}) so the two front seats never share a
     * dismount point; the rear row's own z keeps its dismount points distinct from the front row's.
     */
    public static final VehicleSpec CAR = new VehicleSpec(
            CarGeometry.HALF_X, CarGeometry.HALF_Y, CarGeometry.HALF_Z,
            CarGeometry.MOUNTS, CarGeometry.WHEEL_RADIUS, CarGeometry.WHEEL_WIDTH, CarGeometry.RIDE_HEIGHT,
            WheelMath.REST_LENGTH, 1200.0, WheelMath.SPRING_RATE, WheelMath.DAMPING_RATE, WheelMath.MAX_FORCE,
            CarPhysics.WHEELBASE, 32.0, 1.0, 1.0, 0.55, -0.1, 1.0, 1.0, TireTuning.IDENTITY, 0.0, 1.0,
            List.of(
                    new Seat(0.0, 0.55, -0.1),
                    new Seat(-0.5, 0.55, -0.1),
                    new Seat(-0.5, 0.55, -1.0),
                    new Seat(0.5, 0.55, -1.0)));

    /**
     * Slightly wider, taller and longer than the car, 2200 kg. The body box bottom rides 0.65 m off the
     * ground (the car's is 0.4), so a half slab (0.5 m) passes under it and the wheels climb on; the
     * springs have 1.0 m of rest length for the extra travel. Spring and damper are the car's scaled to a
     * quarter of the truck's mass, so it sags the same 0.2 m.
     */
    /**
     * MINECRAFT-182: same 2+2 layout as {@link #CAR} (driver seat 0 unmoved at x = 0, a front passenger
     * beside it on the opposite side, two rear passengers 1.0 m back), scaled to this truck's roomier
     * {@code halfX} (1.1 m) and {@code halfZ} (2.0 m) &mdash; see {@code #CAR}'s own javadoc for why the
     * front passenger sits opposite the driver and the rear row has its own z.
     */
    public static final VehicleSpec TRUCK = new VehicleSpec(
            1.1, 0.65, 2.0,
            new double[][] {{-0.95, -0.5, 1.5}, {0.95, -0.5, 1.5}, {-0.95, -0.5, -1.5}, {0.95, -0.5, -1.5}},
            0.5, 0.4, 1.3,
            1.0, 2200.0, 26_980.0, 5_390.0, 73_000.0,
            3.0, 26.0, 2200.0 / 1200.0, 0.75, 0.75, 0.3, 1.0, 1.0, TireTuning.IDENTITY, 0.0, 1.0,
            List.of(
                    new Seat(0.0, 0.75, 0.3),
                    new Seat(-0.55, 0.75, 0.3),
                    new Seat(-0.55, 0.75, -0.7),
                    new Seat(0.55, 0.75, -0.7)));

    /**
     * An offroad racer: 2.6 m wide (a wide track keeps it from rolling), a low 1.0 m body riding 1.1 m off
     * the ground on long, soft suspension (1.6 m rest length, sagging 0.3 m), 1500 kg with strong drive for
     * its weight, and tires that keep more grip and roll easier on loose ground. The body's bottom clears a
     * full block.
     *
     * <p>MINECRAFT-182: 2 seats &mdash; the driver (seat 0, unmoved at x = 0) and one passenger beside it at
     * the same {@code seatZ}, on the opposite side (x &lt; 0) so the passenger's dismount point (left) never
     * collides with the driver's default one (right, see {@link VehicleSeating#dismountOffset}). The 0.65 m
     * offset sits well inside {@code halfX} (1.3 m).
     */
    public static final VehicleSpec TROPHY = new VehicleSpec(
            1.3, 0.5, 1.9,
            new double[][] {{-1.25, -0.3, 1.4}, {1.25, -0.3, 1.4}, {-1.25, -0.3, -1.4}, {1.25, -0.3, -1.4}},
            0.6, 0.5, 1.6,
            1.6, 1500.0, 12_260.0, 2_570.0, 50_000.0,
            2.8, 40.0, 1.8, 1.25, 0.5, 0.1, 1.35, 0.6, TireTuning.IDENTITY, 0.0, 1.0,
            List.of(
                    new Seat(0.0, 0.5, 0.1),
                    new Seat(-0.65, 0.5, 0.1)));

    /**
     * DRIFT's own opt-in marker (see {@link TireTuning#isIdentity()}): these exact numbers are never read
     * directly by {@link SableCarBody#tick}, which instead reads the live, operator-tunable
     * {@code CarConfig.driftTireTuning()} once a vehicle's spec has already opted in by way of this field
     * being non-identity. Kept here mainly as the opt-in marker and as the defaults {@code CarConfig}'s own
     * drift tire tuning values mirror, and so pure-function tests of {@link DriftTireModel} have a concrete
     * tuning to exercise without touching config.
     */
    private static final TireTuning TIRE_TUNING = new TireTuning(0.72, 1.2, 0.45, 0.5, 0.5, 0.6);

    /**
     * A low, sporty car: slightly narrower and noticeably lower than the car (0.75 m ride height against
     * the car's 0.9), 1100 kg with a shorter 0.6 m spring rest length to match the lower stance. Spring and
     * damper are the car's scaled to its mass. Drive force, top speed and grip match the car's exactly
     * (see {@code VehicleSpecTest.driftDrivesLikeTheCarForNow}); what makes it drift is its own
     * {@code tireTuning}, applied by {@link DriftTireModel} on top of the shared per-wheel physics: lower
     * rear grip than front, an early, forgiving slide onset, a sharper handbrake-induced rear-grip cut,
     * throttle-induced oversteer, and countersteer recovery.
     *
     * <p>MINECRAFT-182: 2 seats, same shape as {@link #TROPHY}'s &mdash; driver (seat 0, unmoved at x = 0)
     * and one passenger beside it at the same {@code seatZ}, on the opposite side so the two seats' dismount
     * points never collide. The 0.45 m offset sits well inside {@code halfX} (0.9 m).
     */
    public static final VehicleSpec DRIFT = new VehicleSpec(
            0.9, 0.42, 1.5,
            new double[][] {{-0.75, -0.35, 1.2}, {0.75, -0.35, 1.2}, {-0.75, -0.35, -1.2}, {0.75, -0.35, -1.2}},
            0.35, 0.3, 0.75,
            0.6, 1100.0, 17_985.0, 3_117.0, 36_667.0,
            2.4, 32.0, 1.0, 1.0, 0.5, -0.1, 1.0, 1.0, TIRE_TUNING, 0.0, 1.0,
            List.of(
                    new Seat(0.0, 0.5, -0.1),
                    new Seat(-0.45, 0.5, -0.1)));

    /**
     * A heavy, rear-biased-feeling muscle car (MINECRAFT-165): 1700 kg, a 2.3 m wheelbase shorter than the
     * car's 2.4 m (less pitch leverage) under a 0.95 m ride height taller than the car's 0.9 m (more CoM
     * height above the wheels), driven by {@code forceScale} 2.1 &mdash; more than double the car's 1.0,
     * and ahead of {@code forceScale/massKg} (a launch-acceleration proxy: force is {@code forceScale}
     * times a shared per-wheel constant, so mass is the only other variable) for every existing vehicle,
     * including {@code TRUCK}'s 1.83 and {@code TROPHY}'s 1.8, the next-strongest raw {@code forceScale}s
     * on the roster. Spring, damper and the spring force cap are the car's scaled to its mass (same pattern
     * as {@code DRIFT}), so it sags the same ~0.15 m at rest. The combination of a short wheelbase, a
     * comparatively tall ride height and a hard-launching engine is a tuning/feel target for "nearly pops a
     * wheelie on a hard launch" (not a hard physics assertion here; {@code VehicleSpecMuscleTest} checks
     * the spec's own ingredients for that feel, not a simulated outcome) &mdash; tires, not assists,
     * explain any wheelspin that shows up first.
     */
    public static final VehicleSpec MUSCLE = new VehicleSpec(
            1.0, 0.5, 1.65,
            new double[][] {{-0.85, -0.4, 1.15}, {0.85, -0.4, 1.15}, {-0.85, -0.4, -1.15}, {0.85, -0.4, -1.15}},
            0.38, 0.34, 0.95,
            0.7, 1700.0, 27_795.0, 4_816.67, 56_666.67,
            2.3, 34.0, 2.1, 0.7, 0.5, -0.15, 1.0, 1.0, TireTuning.IDENTITY);

    /**
     * A climbing-tuned crawler (MINECRAFT-176): the tallest ride height on the roster (1.9 m, above
     * {@code TROPHY}'s 1.6) on the longest suspension travel too ({@code restLength} 2.0 m, above
     * {@code TROPHY}'s 1.6 m) for maximum wheel articulation over broken ground, a short 2.2 m wheelbase
     * (shorter than every existing vehicle) for tight turning and flex, big 0.65 m wheels wrapped in wide
     * 0.55 m tires, and the roster's highest {@code looseGrip} (1.6, above {@code TROPHY}'s 1.35) for
     * climbing bite on loose surfaces. 1900 kg, lighter than {@code TRUCK} but heavier than everything
     * else, with a {@code forceScale} of 2.0 (second only to {@code MUSCLE}'s 2.1) delivered through a low
     * 18 m/s top speed &mdash; low-end torque over speed, not a racer. Spring, damper and the spring force
     * cap are the car's scaled to its mass (same pattern as {@code DRIFT}/{@code MUSCLE}). Carries no drift
     * tire tuning: the climbing character comes from the base spec's own suspension and grip fields, not
     * from {@link DriftTireModel}, so {@code tireTuning()} stays {@link TireTuning#IDENTITY} and this
     * vehicle routes through the plain {@code WheelMath} tire path exactly like {@code CAR}/{@code
     * TRUCK}/{@code TROPHY}/{@code MUSCLE}.
     *
     * <p>MINECRAFT-208/212/214: this vehicle's narrow track relative to its tall ride height (static
     * stability factor {@code track/(2*rideHeight)} &asymp; 0.50, against the car's &asymp; 0.89) made it
     * tip over sideways far too easily, and {@code CarConfig.ANTI_ROLL} is one value every vehicle shares,
     * so it could not be stiffened for this vehicle alone. {@code antiRollScale} 2.0 (double the roster's
     * shared default) fixes that without touching geometry/CG &mdash; see {@code
     * VehicleSpecRockCrawlerAntiRollTest} for the roll-stability-margin test this value is tuned against.
     */
    public static final VehicleSpec ROCK_CRAWLER = new VehicleSpec(
            1.0, 0.6, 1.6,
            new double[][] {{-0.95, -0.5, 1.1}, {0.95, -0.5, 1.1}, {-0.95, -0.5, -1.1}, {0.95, -0.5, -1.1}},
            0.65, 0.55, 1.9,
            2.0, 1900.0, 31_065.0, 5_383.33, 63_333.33,
            2.2, 18.0, 2.0, 0.6, 0.6, -0.2, 1.6, 0.5, TireTuning.IDENTITY, 0.0, 2.0);

    /**
     * A monster truck (MINECRAFT-180, story MINECRAFT-160): the roster's biggest wheels (0.9 m radius,
     * 0.75 m wide, above {@code ROCK_CRAWLER}'s 0.65 m) under its tallest ride height (2.3 m, above
     * {@code ROCK_CRAWLER}'s 1.9 m) and longest suspension travel ({@code restLength} 2.3 m, above
     * {@code ROCK_CRAWLER}'s 2.0 m). 2600 kg, the roster's heaviest, with a {@code forceScale} of 2.4 -
     * the roster's strongest - for shoving through smaller obstacles. Unlike every other vehicle, whose
     * spring and damper are the car's scaled to mass at the <em>same</em> damping ratio (so each sags the
     * same fraction of its own travel and settles the same way), this one keeps the mass-scaled spring
     * rate ({@code springRate} 42,510, i.e. {@code WheelMath.SPRING_RATE * 2600 / 1200}) but deliberately
     * under-damps it ({@code dampingRate} 4,500 instead of the mass-scaled 7,366.67 the car's ratio would
     * give it) - the "bouncy suspension" character knob: it settles more slowly and overshoots on its way
     * there instead of critically-damped-feeling like the rest of the roster. {@code maxSpringForce}
     * (86,666.67) stays mass-scaled like every other vehicle, so the softer damping can still ride out its
     * full travel without instantly clipping. A middling 24 m/s top speed (faster than {@code
     * ROCK_CRAWLER}'s climbing-tuned 18, far short of a racer) and a deep 0.5 {@code enginePitch}, the
     * roster's lowest. Carries no drift tire tuning: like {@code MUSCLE}/{@code ROCK_CRAWLER}, the
     * character here comes from the base spec's own suspension, mass and drive fields, not from {@link
     * DriftTireModel}, so {@code tireTuning()} stays {@link TireTuning#IDENTITY} and this vehicle routes
     * through the plain {@code WheelMath} tire path exactly like the rest of the roster.
     *
     * <p>"Crushes smaller obstacles/vehicles" is this vertical slice's deferred half of the ticket's
     * character brief: driving over short obstacles already falls out of the huge ride height/wheel
     * radius here (the same mechanism {@code TRUCK}/{@code ROCK_CRAWLER} already rely on to clear a slab),
     * and the existing speed-gated block-breaking ({@code CarConfig.COLLISION_BREAKING}) already applies
     * to every vehicle with no spec change needed. A genuine damage-dealing crush-on-contact mechanic
     * against other entities does not exist for any vehicle today and would mean new shared {@code
     * CarEntity} collision-handling, not just a new spec plus tuning - see the MINECRAFT-156 comment this
     * ticket posted before touching anything beyond that.
     */
    public static final VehicleSpec MONSTER_TRUCK = new VehicleSpec(
            1.2, 0.75, 2.1,
            new double[][] {{-1.05, -0.6, 1.3}, {1.05, -0.6, 1.3}, {-1.05, -0.6, -1.3}, {1.05, -0.6, -1.3}},
            0.9, 0.75, 2.3,
            2.3, 2600.0, 42_510.0, 4_500.0, 86_666.67,
            2.6, 24.0, 2.4, 0.5, 0.85, -0.2, 1.3, 0.55, TireTuning.IDENTITY);

    /**
     * An Indy car (MINECRAFT-161/184): the roster's lowest, lightest and fastest vehicle, open-wheel look.
     * Lowest body on the roster (0.35 m half-height, below {@code DRIFT}'s 0.42) riding the lowest at
     * 0.5 m (below {@code DRIFT}'s 0.75) on a short, stiff 0.35 m suspension travel. Lightest on the
     * roster at 650 kg (below {@code DRIFT}'s 1100), with the roster's best power-to-weight
     * ({@code forceScale}/{@code massKg} 1.3/650 &asymp; 0.002, above every other vehicle's) delivered
     * through the roster's highest top speed (48 m/s, above {@code TROPHY}'s 40) &mdash; a long 2.7 m
     * wheelbase (above every existing vehicle's) for stability at that speed. Open-wheel look: the wheel
     * mounts sit outside {@code halfX} (0.95 m against the body's own 0.75 m half-width), so the wheels
     * protrude past the body in {@code CarRenderer} instead of tucking under it like every other vehicle's.
     * Low {@code looseGrip} (0.6, below the car's 1.0) &mdash; a pavement racer, not an off-roader.
     *
     * <p>Downforce (the brief's own "downforce-like grip at speed"): {@code downforceGripPerSpeed} is
     * 0.012, the roster's only nonzero value &mdash; see {@link #downforceGripMultiplier} and the gripMu
     * multiplier it feeds in {@link SableCarBody#tick}. At this car's own top speed that is a &asymp;58%
     * grip bonus (1.0 + 0.012 * 48 &asymp; 1.58), tapering to none at a stop; every other existing vehicle
     * carries {@code 0.0} here, so the multiplier stays exactly 1.0 for them and {@code SableCarBody.tick}
     * is unchanged for the rest of the roster. Carries no drift tuning of its own: the speed-grip character
     * is this new, separate mechanism, not {@link DriftTireModel}'s cornering-slip one, so
     * {@code tireTuning()} stays {@link TireTuning#IDENTITY} and this vehicle's cornering grip still routes
     * through the plain {@code WheelMath} tire path, exactly like {@code CAR}/{@code TRUCK}/{@code
     * TROPHY}/{@code MUSCLE}/{@code ROCK_CRAWLER}/{@code MONSTER_TRUCK}.
     */
    public static final VehicleSpec INDY = new VehicleSpec(
            0.75, 0.35, 1.8,
            new double[][] {{-0.95, -0.28, 1.5}, {0.95, -0.28, 1.5}, {-0.95, -0.28, -1.5}, {0.95, -0.28, -1.5}},
            0.3, 0.25, 0.5,
            0.35, 650.0, 10_627.5, 1_841.67, 21_666.67,
            2.7, 48.0, 1.3, 1.3, 0.4, 0.0, 0.6, 1.0, TireTuning.IDENTITY, 0.012);

    /**
     * A bus (MINECRAFT-162/191): the roster's longest ({@code halfZ} 3.7 m, above {@code TRUCK}'s 2.0 m),
     * heaviest (4200 kg, above {@code MONSTER_TRUCK}'s 2600 kg) and, by far, slowest-turning vehicle. The
     * "slow to turn, large turning radius" character needs no new mechanism at all: {@link
     * WheelMath#maxSteerAngle} already takes {@code wheelbase}, and a turning radius is (informally)
     * wheelbase divided by the tangent of the steer angle it is holding &mdash; so simply giving this
     * vehicle the roster's longest wheelbase (4.6 m, above {@code INDY}'s previous-longest 2.7 m) widens
     * its turning circle on its own, with the shared mechanical steer limit ({@code CarPhysics.MAX_STEER})
     * and every other vehicle's own wheelbase completely untouched. Ride height (1.0 m) and suspension
     * travel (1.0 m) match {@code TRUCK}'s; spring, damper and the spring force cap are the car's own
     * (1200 kg) numbers scaled to this vehicle's mass at that <em>same</em> damping ratio (the norm every
     * vehicle but {@code MONSTER_TRUCK} follows), so it settles the same fraction of its travel the same
     * way. A middling 20 m/s top speed and a {@code forceScale} of 3.0 &mdash; {@code forceScale}/{@code
     * massKg} &asymp; 0.00071, below every other vehicle's own ratio (the car's own 1.0/1200 &asymp;
     * 0.00083 included) &mdash; a heavy, unhurried vehicle that still has enough drive to get its own mass
     * moving. A deep 0.6 {@code enginePitch} (between the car's 1.0 and {@code MONSTER_TRUCK}'s lowest,
     * 0.5). {@code looseGrip} (0.9) and {@code rollingScale} (0.8) sit a little under the car's own 1.0/1.0
     * &mdash; a heavy pavement vehicle, not tuned for loose ground either way. Carries no drift tuning and
     * no downforce: {@code tireTuning()} stays {@link TireTuning#IDENTITY} and {@code
     * downforceGripPerSpeed} stays {@code 0.0}, so this vehicle's cornering grip routes through the plain
     * {@code WheelMath} tire path exactly like {@code CAR}/{@code TRUCK}/{@code TROPHY}/{@code MUSCLE}/
     * {@code ROCK_CRAWLER}/{@code MONSTER_TRUCK}/{@code INDY}, and {@code SableCarBody.tick}'s downforce
     * multiplier is exactly 1.0 for it too.
     *
     * <p>8 seats (MINECRAFT-172's multi-seat base): the driver (seat 0, unmoved at x = 0, z = 3.0, near the
     * front) and 7 passengers in three rows of two plus one front passenger, each row a distinct {@code z}
     * further back (1.4, -0.2, -1.8) so every same-side seat keeps its own distinct dismount point (see
     * {@link VehicleSeating#dismountOffset}, which keys a seat's dismount x purely off which side of the
     * body &mdash; {@code x &gt;= 0} or not &mdash; the seat sits on). Every seat's {@code |x|} (0 or 0.6 m)
     * sits well inside {@code halfX} (1.15 m), and every {@code |z|} (at most 3.0 m) well inside {@code
     * halfZ} (3.7 m).
     */
    public static final VehicleSpec BUS = new VehicleSpec(
            1.15, 0.9, 3.7,
            new double[][] {{-1.0, -0.6, 2.3}, {1.0, -0.6, 2.3}, {-1.0, -0.6, -2.3}, {1.0, -0.6, -2.3}},
            0.55, 0.45, 1.0,
            1.0, 4200.0, 68_670.0, 11_900.0, 140_000.0,
            4.6, 20.0, 3.0, 0.6, 0.95, 3.0, 0.9, 0.8, TireTuning.IDENTITY, 0.0, 1.0,
            List.of(
                    new Seat(0.0, 0.95, 3.0),
                    new Seat(-0.6, 0.95, 3.0),
                    new Seat(0.6, 0.95, 1.4),
                    new Seat(-0.6, 0.95, 1.4),
                    new Seat(0.6, 0.95, -0.2),
                    new Seat(-0.6, 0.95, -0.2),
                    new Seat(0.6, 0.95, -1.8),
                    new Seat(-0.6, 0.95, -1.8)));

    /**
     * A cargo truck (MINECRAFT-163/192): the roster's heaviest vehicle (4800 kg, above {@code BUS}'s
     * 4200 kg) and weakest power-to-weight ({@code forceScale}/{@code massKg} 2.8/4800 &asymp; 0.000583,
     * below {@code BUS}'s previous-lowest 3.0/4200 &asymp; 0.000714) delivered through the roster's
     * lowest top speed (16 m/s, below {@code ROCK_CRAWLER}'s climbing-tuned 18) &mdash; slow to get
     * moving and slow at the top end, the "heavy hauler" brief. A long 4.0 m wheelbase (behind only
     * {@code BUS}'s 4.6 m) widens its turning circle the same way {@code BUS}'s does (see that spec's
     * own javadoc for the mechanism: {@link WheelMath#maxSteerAngle} takes {@code wheelbase} directly,
     * with the shared mechanical steer limit and every other vehicle's own wheelbase untouched) for the
     * brief's "sluggish steering". Spring, damper and the spring force cap are the car's scaled to this
     * mass at the <em>same</em> damping ratio as {@code CAR} (the norm every vehicle but {@code
     * MONSTER_TRUCK} follows) &mdash; critically damped, not bouncy, for the brief's "stable". A wide
     * 1.15 m half-width (matching {@code BUS}'s own) and a moderate 1.1 m ride height keep the centre of
     * mass low and the stance wide relative to the body's own height, rather than top-heavy. A deep 0.45
     * {@code enginePitch}, between {@code MONSTER_TRUCK}'s 0.5 and {@code BUS}'s 0.6. {@code looseGrip}
     * (1.0) and {@code rollingScale} (0.7) sit close to the car's, a street/yard hauler rather than an
     * off-roader. Carries no drift tuning and no downforce: {@code tireTuning()} stays {@link
     * TireTuning#IDENTITY} and {@code downforceGripPerSpeed} stays {@code 0.0}, so this vehicle's
     * cornering grip routes through the plain {@code WheelMath} tire path exactly like the rest of the
     * roster, and {@code SableCarBody.tick}'s downforce multiplier is exactly 1.0 for it too.
     *
     * <p>LOAD AREA: the base vehicle class ({@code CarEntity}) implements no item-storage capability at
     * all (no {@code Container}/menu, unlike, say, a vanilla chest minecart) &mdash; every existing
     * vehicle, including {@code TRUCK}'s and {@code TROPHY}'s own "cargo bed", is purely a rigid-body box
     * plus wheels and seats, with the bed rendered in {@code CarRenderer} as a visual flourish only. Per
     * the ticket's own LOAD AREA RULE, this vertical slice does NOT change that base class silently
     * (comment posted on MINECRAFT-163 and MINECRAFT-156 first); the cargo truck ships chassis-only, with
     * a big visual cargo bed in {@code CarRenderer} (no functional storage) &mdash; see this ticket's PR
     * description and changelog entry for the documented limitation.
     *
     * <p>2 seats: the driver (seat 0, unmoved at x = 0) and one passenger beside it at the same {@code
     * seatZ}, on the opposite side (x &lt; 0) so the two seats' dismount points never collide (see
     * {@link VehicleSeating#dismountOffset}). Both seats sit well forward (z = 1.3) over the front axle,
     * in the cab ahead of the load bed; {@code |x|} (0 or 0.65 m) sits well inside {@code halfX}
     * (1.15 m), and {@code z} (1.3 m) well inside {@code halfZ} (2.6 m).
     */
    public static final VehicleSpec CARGO_TRUCK = new VehicleSpec(
            1.15, 0.75, 2.6,
            new double[][] {{-1.0, -0.55, 2.0}, {1.0, -0.55, 2.0}, {-1.0, -0.55, -2.0}, {1.0, -0.55, -2.0}},
            0.55, 0.45, 1.1,
            1.2, 4800.0, 78_480.0, 13_600.0, 160_000.0,
            4.0, 16.0, 2.8, 0.45, 0.95, 1.3, 1.0, 0.7, TireTuning.IDENTITY, 0.0, 1.0,
            List.of(
                    new Seat(0.0, 0.95, 1.3),
                    new Seat(-0.65, 0.95, 1.3)));

    /** The gripMu multiplier {@link SableCarBody#tick} applies for this vehicle's own {@code
     * downforceGripPerSpeed} at this forward speed (m/s, either direction): 1.0 (no change) for every
     * vehicle whose {@code downforceGripPerSpeed} is 0.0 &mdash; see the field's own javadoc. */
    public double downforceGripMultiplier(double forwardSpeed) {
        return 1.0 + downforceGripPerSpeed * Math.abs(forwardSpeed);
    }

    public double wheelCentreY() {
        return -rideHeight + wheelRadius;
    }

    /** Entity width and height, metres: the physics box. */
    public float width() {
        return (float) (2 * halfX);
    }

    public float height() {
        return (float) (2 * halfY);
    }
}
