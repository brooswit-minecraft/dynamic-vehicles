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
                List.of(new Seat(0.0, seatY, seatZ)));
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

    public static final VehicleSpec CAR = new VehicleSpec(
            CarGeometry.HALF_X, CarGeometry.HALF_Y, CarGeometry.HALF_Z,
            CarGeometry.MOUNTS, CarGeometry.WHEEL_RADIUS, CarGeometry.WHEEL_WIDTH, CarGeometry.RIDE_HEIGHT,
            WheelMath.REST_LENGTH, 1200.0, WheelMath.SPRING_RATE, WheelMath.DAMPING_RATE, WheelMath.MAX_FORCE,
            CarPhysics.WHEELBASE, 32.0, 1.0, 1.0, 0.55, -0.1, 1.0, 1.0, TireTuning.IDENTITY);

    /**
     * Slightly wider, taller and longer than the car, 2200 kg. The body box bottom rides 0.65 m off the
     * ground (the car's is 0.4), so a half slab (0.5 m) passes under it and the wheels climb on; the
     * springs have 1.0 m of rest length for the extra travel. Spring and damper are the car's scaled to a
     * quarter of the truck's mass, so it sags the same 0.2 m.
     */
    public static final VehicleSpec TRUCK = new VehicleSpec(
            1.1, 0.65, 2.0,
            new double[][] {{-0.95, -0.5, 1.5}, {0.95, -0.5, 1.5}, {-0.95, -0.5, -1.5}, {0.95, -0.5, -1.5}},
            0.5, 0.4, 1.3,
            1.0, 2200.0, 26_980.0, 5_390.0, 73_000.0,
            3.0, 26.0, 2200.0 / 1200.0, 0.75, 0.75, 0.3, 1.0, 1.0, TireTuning.IDENTITY);

    /**
     * An offroad racer: 2.6 m wide (a wide track keeps it from rolling), a low 1.0 m body riding 1.1 m off
     * the ground on long, soft suspension (1.6 m rest length, sagging 0.3 m), 1500 kg with strong drive for
     * its weight, and tires that keep more grip and roll easier on loose ground. The body's bottom clears a
     * full block.
     */
    public static final VehicleSpec TROPHY = new VehicleSpec(
            1.3, 0.5, 1.9,
            new double[][] {{-1.25, -0.3, 1.4}, {1.25, -0.3, 1.4}, {-1.25, -0.3, -1.4}, {1.25, -0.3, -1.4}},
            0.6, 0.5, 1.6,
            1.6, 1500.0, 12_260.0, 2_570.0, 50_000.0,
            2.8, 40.0, 1.8, 1.25, 0.5, 0.1, 1.35, 0.6, TireTuning.IDENTITY);

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
     */
    public static final VehicleSpec DRIFT = new VehicleSpec(
            0.9, 0.42, 1.5,
            new double[][] {{-0.75, -0.35, 1.2}, {0.75, -0.35, 1.2}, {-0.75, -0.35, -1.2}, {0.75, -0.35, -1.2}},
            0.35, 0.3, 0.75,
            0.6, 1100.0, 17_985.0, 3_117.0, 36_667.0,
            2.4, 32.0, 1.0, 1.0, 0.5, -0.1, 1.0, 1.0, TIRE_TUNING);

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
     */
    public static final VehicleSpec ROCK_CRAWLER = new VehicleSpec(
            1.0, 0.6, 1.6,
            new double[][] {{-0.95, -0.5, 1.1}, {0.95, -0.5, 1.1}, {-0.95, -0.5, -1.1}, {0.95, -0.5, -1.1}},
            0.65, 0.55, 1.9,
            2.0, 1900.0, 31_065.0, 5_383.33, 63_333.33,
            2.2, 18.0, 2.0, 0.6, 0.6, -0.2, 1.6, 0.5, TireTuning.IDENTITY);

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
