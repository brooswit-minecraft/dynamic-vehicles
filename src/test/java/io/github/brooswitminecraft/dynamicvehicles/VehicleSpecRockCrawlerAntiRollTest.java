package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-208/212/214: the rock crawler's narrow track relative to its tall ride height made it tip
 * over sideways far too easily, and {@code CarConfig.ANTI_ROLL} is one value shared by every vehicle, so
 * it could not be stiffened for the crawler alone. {@link VehicleSpec#antiRollScale} fixes that per
 * vehicle; {@code SableCarBody.tick} reads it as
 * {@code barRate = spec.springRate() * CarConfig.ANTI_ROLL.get() * spec.antiRollScale()}.
 *
 * <p>These checks are pure-function, deliberately simplified models (no Sable engine runs here, exactly
 * like {@link VehicleSpecRockCrawlerTest}) of the vehicle's quasi-static roll behaviour resting on a side
 * slope: a rigid-body tip-over angle ({@code atan(trackHalf / rideHeight)}, the same static-stability-
 * factor idea the background bug report uses) reduced by how far the sprung mass leans PAST the slope's
 * own angle under gravity, an extra lean this vehicle's combined coil-spring + anti-roll-bar roll
 * stiffness resists. Roll stiffness about the roll axis for a pair of wheels a distance
 * {@code trackHalf} either side of centre, each contributing a restoring force proportional to
 * {@code (springRate + barRate) * trackHalf * rollAngle} at a {@code trackHalf} moment arm, is the
 * standard {@code 2 * trackHalf^2 * (springRate + barRate)}; since {@code barRate} here is
 * {@code springRate * ANTI_ROLL_RATIO * antiRollScale} (the same formula {@code SableCarBody.tick} uses,
 * with {@code ANTI_ROLL_RATIO} fixed at {@code CarConfig.ANTI_ROLL}'s documented default of 1.0, since a
 * pure-function unit test cannot load the live Forge/NeoForge config), the combined stiffness is
 * {@code 2 * trackHalf^2 * springRate * (1 + ANTI_ROLL_RATIO * antiRollScale)} &mdash; monotonically
 * increasing in {@code antiRollScale}, so a bigger scale always leans less and never hurts the margin.
 */
class VehicleSpecRockCrawlerAntiRollTest {

    /** {@code CarConfig.ANTI_ROLL}'s own documented default ({@code defineInRange("antiRollRatio", 1.0, ...)});
     * a pure-function unit test has no live Forge/NeoForge config to read, so this mirrors it as a literal. */
    private static final double ANTI_ROLL_RATIO = 1.0;

    /** A representative side slope for a vehicle meant to climb broken, rocky ground (MINECRAFT-208's own
     * bug report): steep enough to be a real climbing obstacle, not a curb. */
    private static final double SIDE_SLOPE_DEGREES = 20.0;

    /** The roll-stability margin (tip-over angle minus the sprung mass's effective lean) the tuned rock
     * crawler must clear on {@link #SIDE_SLOPE_DEGREES}, in degrees. Chosen so the UNTUNED crawler
     * (antiRollScale 1.0, i.e. no scale beyond the shared global default) fails it -- see
     * {@link #untunedAntiRollScaleWouldFailTheMargin} -- while the tuned value (2.0) clears it with a
     * couple of degrees of headroom, not by a hair.
     */
    private static final double MARGIN_THRESHOLD_DEGREES = 2.0;

    /** Half the distance between a vehicle's left and right wheel mounts, metres -- this vehicle's track
     * is {@code 2 * trackHalf}. Assumes the mounts are symmetric left/right (true for every roster
     * vehicle; mounts()[0] is always a front wheel). */
    private static double trackHalf(VehicleSpec spec) {
        return Math.abs(spec.mounts()[0][0]);
    }

    /**
     * This vehicle's roll-stability margin on a side slope of {@code slopeDegrees} (sign ignored -- see
     * the class javadoc's symmetry note): positive means the simplified model predicts it stays upright,
     * negative means it predicts a tip. See the class javadoc for the model and its justification.
     */
    private static double rollStabilityMarginDegrees(VehicleSpec spec, double slopeDegrees) {
        double theta = Math.toRadians(Math.abs(slopeDegrees));
        double trackHalf = trackHalf(spec);
        double h = spec.rideHeight();
        double rollStiffness = 2.0 * trackHalf * trackHalf * spec.springRate()
                * (1.0 + ANTI_ROLL_RATIO * spec.antiRollScale());
        // Quasi-static torque balance: gravity's component along the slope, acting at the CG height,
        // leans the sprung mass by this extra angle against the combined spring + anti-roll restoring
        // stiffness (small-angle, consistent with the linear antiRollForce this mirrors).
        double extraLean = (spec.massKg() * 9.8 * Math.sin(theta) * h) / rollStiffness;
        double tipAngle = Math.atan(trackHalf / h);
        double effectiveLean = theta + extraLean;
        return Math.toDegrees(tipAngle - effectiveLean);
    }

    @Test
    void rockCrawlerIsTunedWithAHigherAntiRollScaleThanTheSharedDefault() {
        assertTrue(VehicleSpec.ROCK_CRAWLER.antiRollScale() > 1.0,
                "rock crawler should carry a per-vehicle anti-roll scale above the roster's 1.0 identity default");
    }

    @Test
    void rockCrawlerClearsItsRollStabilityMarginOnARepresentativeSideSlope() {
        double margin = rollStabilityMarginDegrees(VehicleSpec.ROCK_CRAWLER, SIDE_SLOPE_DEGREES);
        assertTrue(margin > MARGIN_THRESHOLD_DEGREES,
                "tuned rock crawler's roll-stability margin on a " + SIDE_SLOPE_DEGREES
                        + "-degree side slope should clear " + MARGIN_THRESHOLD_DEGREES
                        + " degrees of margin, got " + margin);
    }

    @Test
    void untunedAntiRollScaleWouldFailTheMargin() {
        // Justifies MARGIN_THRESHOLD_DEGREES above as a real bar, not decoration: at the shared 1.0
        // default (i.e. before this ticket's tuning), the same slope fails it -- this is MINECRAFT-208's
        // own bug, reproduced by this pure-function model.
        VehicleSpec untuned = new VehicleSpec(
                VehicleSpec.ROCK_CRAWLER.halfX(), VehicleSpec.ROCK_CRAWLER.halfY(), VehicleSpec.ROCK_CRAWLER.halfZ(),
                VehicleSpec.ROCK_CRAWLER.mounts(), VehicleSpec.ROCK_CRAWLER.wheelRadius(), VehicleSpec.ROCK_CRAWLER.wheelWidth(),
                VehicleSpec.ROCK_CRAWLER.rideHeight(), VehicleSpec.ROCK_CRAWLER.restLength(),
                VehicleSpec.ROCK_CRAWLER.massKg(), VehicleSpec.ROCK_CRAWLER.springRate(), VehicleSpec.ROCK_CRAWLER.dampingRate(),
                VehicleSpec.ROCK_CRAWLER.maxSpringForce(), VehicleSpec.ROCK_CRAWLER.wheelbase(), VehicleSpec.ROCK_CRAWLER.maxSpeed(),
                VehicleSpec.ROCK_CRAWLER.forceScale(), VehicleSpec.ROCK_CRAWLER.enginePitch(),
                VehicleSpec.ROCK_CRAWLER.seatY(), VehicleSpec.ROCK_CRAWLER.seatZ(),
                VehicleSpec.ROCK_CRAWLER.looseGrip(), VehicleSpec.ROCK_CRAWLER.rollingScale(),
                VehicleSpec.ROCK_CRAWLER.tireTuning());
        double margin = rollStabilityMarginDegrees(untuned, SIDE_SLOPE_DEGREES);
        assertTrue(margin < MARGIN_THRESHOLD_DEGREES,
                "untuned (antiRollScale 1.0) rock crawler should fail its own tuned margin threshold, got " + margin);
    }

    @Test
    void rockCrawlerRollStabilityMarginIsExactlyLeftRightSymmetric() {
        // The crawler's geometry is left/right symmetric, so a slope tilting one way must give exactly
        // the same margin as the mirrored slope tilting the other way -- the model takes Math.abs(theta),
        // so this also guards against a future edit accidentally breaking that symmetry.
        double positive = rollStabilityMarginDegrees(VehicleSpec.ROCK_CRAWLER, SIDE_SLOPE_DEGREES);
        double mirrored = rollStabilityMarginDegrees(VehicleSpec.ROCK_CRAWLER, -SIDE_SLOPE_DEGREES);
        assertEquals(positive, mirrored, 1e-12);
    }

    @Test
    void everyOtherVehicleKeepsAntiRollScaleAtTheIdentityDefault() {
        // The regression guarantee this story's shared-code change (VehicleSpec.antiRollScale +
        // SableCarBody.tick's barRate multiply) depends on: every vehicle that predates MINECRAFT-212/214
        // must carry exactly 1.0 here, so the antiRollScale multiply in SableCarBody.tick's barRate is a
        // mathematical no-op and every other vehicle's anti-roll path stays byte-for-byte unchanged.
        VehicleSpec[] everyOtherVehicle = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT,
                VehicleSpec.MUSCLE, VehicleSpec.MONSTER_TRUCK, VehicleSpec.INDY, VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK};
        for (VehicleSpec spec : everyOtherVehicle) {
            assertEquals(1.0, spec.antiRollScale(), 1e-12, "pre-existing vehicle must carry the identity anti-roll scale");
        }
    }
}
