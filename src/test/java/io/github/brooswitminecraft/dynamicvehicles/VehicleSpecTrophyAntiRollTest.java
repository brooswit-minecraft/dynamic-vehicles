package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-208/223: the trophy truck's geometry is fine (track 2.5 m over a 1.6 m ride height, static
 * stability factor &asymp;0.78, well above {@link VehicleSpec#ROCK_CRAWLER}'s &asymp;0.50) &mdash; this is
 * a stability/feel TUNING change for landings and rough terrain (sway-bar style suspension coupling, not a
 * roll cage), NOT a rollover-defect fix. Its {@code springRate} (12,260) is weaker in absolute N/m than the
 * car's or the crawler's, so the sway-bar-style anti-roll coupling {@link VehicleSpec#antiRollScale} buys
 * it is softer too at the shared 1.0 default; {@code SableCarBody.tick} reads it as
 * {@code barRate = spec.springRate() * CarConfig.ANTI_ROLL.get() * spec.antiRollScale()}, exactly as it
 * does for every other vehicle.
 *
 * <p>These checks are pure-function, deliberately simplified models (no Sable engine runs here) of the
 * vehicle's quasi-static roll behaviour resting on a side slope, modelled on {@link
 * VehicleSpecRockCrawlerAntiRollTest} (read that class's own javadoc for the full derivation): a rigid-body
 * tip-over angle ({@code atan(trackHalf / rideHeight)}) reduced by how far the sprung mass leans PAST the
 * slope's own angle under gravity, an extra lean this vehicle's combined coil-spring + anti-roll-bar roll
 * stiffness ({@code 2 * trackHalf^2 * springRate * (1 + ANTI_ROLL_RATIO * antiRollScale)}) resists &mdash;
 * monotonically increasing in {@code antiRollScale}, so a bigger scale always leans less and never hurts
 * the margin.
 */
class VehicleSpecTrophyAntiRollTest {

    /** {@code CarConfig.ANTI_ROLL}'s own documented default ({@code defineInRange("antiRollRatio", 1.0, ...)});
     * a pure-function unit test has no live Forge/NeoForge config to read, so this mirrors it as a literal. */
    private static final double ANTI_ROLL_RATIO = 1.0;

    /** A representative side slope for landings and rough off-road terrain -- steeper than the rock
     * crawler test's 20 degrees because the trophy truck's own geometry (SSF &asymp;0.78) is already far
     * more tip-resistant than the crawler's (&asymp;0.50), so a gentler slope would clear the margin at the
     * untuned 1.0 scale too and the test below would not be exercising anything. */
    private static final double SIDE_SLOPE_DEGREES = 25.0;

    /** The roll-stability margin (tip-over angle minus the sprung mass's effective lean) the tuned trophy
     * truck must clear on {@link #SIDE_SLOPE_DEGREES}, in degrees. Chosen so the UNTUNED trophy truck
     * (antiRollScale 1.0, i.e. no scale beyond the shared global default) fails it -- see
     * {@link #untunedAntiRollScaleWouldFailTheMargin} -- while the tuned value (1.5) clears it, but only by
     * about half a degree of headroom, by a hair, not a couple of degrees. The real engine's
     * antiRollTransfer cap (transfer capped at the compressed wheel's own spring force, not modelled here)
     * can only make the real margin thinner still.
     */
    private static final double MARGIN_THRESHOLD_DEGREES = 6.5;

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
    void trophyTruckIsTunedWithAHigherAntiRollScaleThanTheSharedDefault() {
        assertTrue(VehicleSpec.TROPHY.antiRollScale() > 1.0,
                "trophy truck should carry a per-vehicle anti-roll scale above the roster's 1.0 identity default");
    }

    @Test
    void trophyTruckClearsItsRollStabilityMarginOnARepresentativeSideSlope() {
        double margin = rollStabilityMarginDegrees(VehicleSpec.TROPHY, SIDE_SLOPE_DEGREES);
        assertTrue(margin > MARGIN_THRESHOLD_DEGREES,
                "tuned trophy truck's roll-stability margin on a " + SIDE_SLOPE_DEGREES
                        + "-degree side slope should clear " + MARGIN_THRESHOLD_DEGREES
                        + " degrees of margin, got " + margin);
    }

    @Test
    void untunedAntiRollScaleWouldFailTheMargin() {
        // Justifies MARGIN_THRESHOLD_DEGREES above as a real bar, not decoration: at the shared 1.0
        // default (i.e. before this ticket's tuning), the same slope fails it.
        VehicleSpec untuned = new VehicleSpec(
                VehicleSpec.TROPHY.halfX(), VehicleSpec.TROPHY.halfY(), VehicleSpec.TROPHY.halfZ(),
                VehicleSpec.TROPHY.mounts(), VehicleSpec.TROPHY.wheelRadius(), VehicleSpec.TROPHY.wheelWidth(),
                VehicleSpec.TROPHY.rideHeight(), VehicleSpec.TROPHY.restLength(),
                VehicleSpec.TROPHY.massKg(), VehicleSpec.TROPHY.springRate(), VehicleSpec.TROPHY.dampingRate(),
                VehicleSpec.TROPHY.maxSpringForce(), VehicleSpec.TROPHY.wheelbase(), VehicleSpec.TROPHY.maxSpeed(),
                VehicleSpec.TROPHY.forceScale(), VehicleSpec.TROPHY.enginePitch(),
                VehicleSpec.TROPHY.seatY(), VehicleSpec.TROPHY.seatZ(),
                VehicleSpec.TROPHY.looseGrip(), VehicleSpec.TROPHY.rollingScale(),
                VehicleSpec.TROPHY.tireTuning());
        double margin = rollStabilityMarginDegrees(untuned, SIDE_SLOPE_DEGREES);
        assertTrue(margin < MARGIN_THRESHOLD_DEGREES,
                "untuned (antiRollScale 1.0) trophy truck should fail its own tuned margin threshold, got " + margin);
    }

    @Test
    void trophyTruckRollStabilityMarginIsExactlyLeftRightSymmetric() {
        // The trophy truck's geometry is left/right symmetric, so a slope tilting one way must give exactly
        // the same margin as the mirrored slope tilting the other way -- the model takes Math.abs(theta),
        // so this also guards against a future edit accidentally breaking that symmetry.
        double positive = rollStabilityMarginDegrees(VehicleSpec.TROPHY, SIDE_SLOPE_DEGREES);
        double mirrored = rollStabilityMarginDegrees(VehicleSpec.TROPHY, -SIDE_SLOPE_DEGREES);
        assertEquals(positive, mirrored, 1e-12);
    }

    @Test
    void trophyTuningDoesNotLeakIntoOtherVehicles() {
        // The regression guarantee this ticket's TROPHY-only tuning depends on: no other vehicle's
        // antiRollScale moved as a side effect of this change. ROCK_CRAWLER keeps its own pre-existing
        // non-identity tuning (2.0, untouched by this ticket); every other vehicle must stay at the 1.0
        // identity default -- see VehicleSpecRockCrawlerAntiRollTest.everyOtherVehicleKeepsAntiRollScaleAtTheIdentityDefault
        // for that assertion, which no longer lists TROPHY since this ticket moved it off identity.
        assertEquals(2.0, VehicleSpec.ROCK_CRAWLER.antiRollScale(), 1e-12,
                "rock crawler's own anti-roll tuning must be untouched by the trophy truck's tuning");
        VehicleSpec[] mustStayAtIdentity = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.DRIFT,
                VehicleSpec.MUSCLE, VehicleSpec.MONSTER_TRUCK, VehicleSpec.INDY, VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK};
        for (VehicleSpec spec : mustStayAtIdentity) {
            assertEquals(1.0, spec.antiRollScale(), 1e-12,
                    "trophy truck's new anti-roll scale must not leak into any other vehicle");
        }
    }
}
