package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicvehicles.VehicleSpec.SuspensionTuning;
import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-445: the four-rate (slow/fast compression, slow/fast rebound) damping plus bump-stop
 * {@code suspensionForce} overload, independent of any production wiring through {@code SableCarBody} or
 * {@code VehicleSpecOverrides} (covered by {@code VehicleSpecOverridesTest} instead).
 */
class WheelMathSuspensionTuningTest {

    /** The car's own spring rate, so these cases sit on a real vehicle's scale rather than an invented one. */
    private static final double SPRING_RATE = WheelMath.SPRING_RATE;
    /** The car's own damper rate, used wherever a test needs one rate that is not the point of the test. */
    private static final double DAMPING_RATE = WheelMath.DAMPING_RATE;
    /** A force cap high enough never to clamp, so a test asserting the formula sees the formula. */
    private static final double UNCAPPED_FORCE = Double.MAX_VALUE;
    /** Compression (m) well inside every roster car's travel, used wherever the exact depth is incidental. */
    private static final double TOUCHING_COMPRESSION = 0.1;
    /** A compression rate (m/s) of exactly zero: the damper contributes nothing, isolating spring + bump stop. */
    private static final double AT_REST = 0.0;
    /** The knee speed (m/s) these cases tune around; distinct from {@link SuspensionTuning#DEFAULT_DAMPING_KNEE_SPEED}
     * only where a test needs to prove the knee's position is what moved the result. */
    private static final double KNEE_SPEED = 1.0;
    /** How far past {@link #KNEE_SPEED} the "fast" assertions probe, m/s. */
    private static final double SPEED_PAST_KNEE = 1.0;
    /** A slow-stroke rate (N*s/m) deliberately far below {@link #FAST_RATE}, so a slow/fast mix-up is obvious. */
    private static final double SLOW_RATE = 1_000.0;
    /** A fast-stroke rate (N*s/m) deliberately far above {@link #SLOW_RATE}. */
    private static final double FAST_RATE = 9_000.0;
    /** Compression travel (m) past which the bump-stop cases engage their stop. */
    private static final double BUMP_STOP_TRAVEL = 0.2;
    /** A bump-stop rate (N/m) stiffer than {@link #SPRING_RATE}, as a real mechanical stop is. */
    private static final double BUMP_STOP_RATE = 50_000.0;
    /** Tolerance for comparisons of a force assembled by the same arithmetic in test and production. */
    private static final double FORCE_TOLERANCE = 1e-6;
    /** Tolerance for an equality that must hold to the algebraic reduction, not merely approximately. */
    private static final double REDUCTION_TOLERANCE = 1e-9;

    /** The four-rate overload at one shared rate for all four strokes and no bump stop -- the configuration
     * {@link SuspensionTuning#singleRate} produces, expressed here as a call so each test states which rate
     * it is collapsing to. */
    private static double singleRateForce(double compression, double compressionRate, double rate, double kneeSpeed,
            double maxForce) {
        return WheelMath.suspensionForce(compression, compressionRate, SPRING_RATE, rate, rate, rate, rate,
                kneeSpeed, maxForce, SuspensionTuning.BUMP_STOP_DISABLED_TRAVEL, SuspensionTuning.NO_BUMP_STOP_RATE);
    }

    @Test
    void reducesToSingleRateWhenEveryDampingRateMatches() {
        for (double compressionRate : new double[] {AT_REST, 10.0}) {
            double single = WheelMath.suspensionForce(TOUCHING_COMPRESSION, compressionRate, SPRING_RATE,
                    DAMPING_RATE, WheelMath.MAX_FORCE);
            double fourRate = singleRateForce(TOUCHING_COMPRESSION, compressionRate, DAMPING_RATE, KNEE_SPEED,
                    WheelMath.MAX_FORCE);
            assertEquals(single, fourRate, REDUCTION_TOLERANCE, "compressionRate=" + compressionRate);
        }
    }

    /**
     * MINECRAFT-445 review fix (comment 49018): the single-rate reduction must hold BIT FOR BIT, not merely
     * to a tolerance. A tolerance-only equality still let the four-rate path return a last-bits-different
     * force from the single-rate one for compression rates ABOVE the knee ({@code slowRate * knee +
     * fastRate * (speed - knee)} is not the same floating-point expression as {@code rate * speed}, even
     * when every rate is equal). A car dropping onto its springs at spawn does exceed the knee, so every
     * stock car's settling transient diverged from the pre-ticket formula in its last bits. Pinning raw
     * bits here keeps "no existing car's stock feel changes" a mechanical fact rather than an algebraic
     * argument -- which is the whole justification for the guard in {@code WheelMath}.
     *
     * <p>Deliberately NOT claimed: that this rounding difference caused any particular GameTest failure.
     * An earlier revision of this javadoc blamed it for the D1-vs-D5 drivetrain failure; that causation was
     * retracted on MINECRAFT-445 (comment 49035) once CI showed that test passing at one commit and failing
     * at another with identical production behaviour, i.e. nondeterministic for reasons of its own.
     */
    @Test
    void reducesToSingleRateBitForBitAboveTheKneeWhereTheArithmeticDiffers() {
        // Sweep the compression rates a car actually sees dropping onto its springs, all above the knee.
        for (double compressionRate = KNEE_SPEED + 1e-4; compressionRate < 6.0; compressionRate += 3.7e-4) {
            for (double signedRate : new double[] {compressionRate, -compressionRate}) {
                double single = WheelMath.suspensionForce(TOUCHING_COMPRESSION, signedRate, SPRING_RATE,
                        DAMPING_RATE, WheelMath.MAX_FORCE);
                double fourRate = singleRateForce(TOUCHING_COMPRESSION, signedRate, DAMPING_RATE, KNEE_SPEED,
                        WheelMath.MAX_FORCE);
                assertEquals(Double.doubleToRawLongBits(single), Double.doubleToRawLongBits(fourRate),
                        "compressionRate=" + signedRate + " single=" + single + " fourRate=" + fourRate);
            }
        }
    }

    @Test
    void reducesToSingleRateForAnyKneeSpeedWhenEveryDampingRateMatches() {
        // The algebraic reduction must not depend on where the knee sits, since every rate on both sides of
        // it is identical -- which is what makes SuspensionTuning.DEFAULT_DAMPING_KNEE_SPEED's exact value
        // unobservable for every car on the roster today.
        double compressionRate = 2.5;
        double single = WheelMath.suspensionForce(TOUCHING_COMPRESSION, compressionRate, SPRING_RATE, DAMPING_RATE,
                WheelMath.MAX_FORCE);
        for (double knee : new double[] {0.0, 0.5, SuspensionTuning.DEFAULT_DAMPING_KNEE_SPEED, 5.0, 100.0}) {
            double fourRate = singleRateForce(TOUCHING_COMPRESSION, compressionRate, DAMPING_RATE, knee,
                    WheelMath.MAX_FORCE);
            assertEquals(single, fourRate, REDUCTION_TOLERANCE, "kneeSpeed=" + knee);
        }
    }

    @Test
    void fastCompressionDampingOnlyAppliesAboveTheKnee() {
        double springForce = SPRING_RATE * TOUCHING_COMPRESSION;

        // At exactly the knee: only the slow rate has applied so far.
        double atKnee = compressionForce(KNEE_SPEED);
        assertEquals(springForce + SLOW_RATE * KNEE_SPEED, atKnee, FORCE_TOLERANCE);

        // Just below the knee uses only the slow rate, scaling linearly with speed.
        double belowKnee = compressionForce(KNEE_SPEED / 2);
        assertEquals(springForce + SLOW_RATE * KNEE_SPEED / 2, belowKnee, FORCE_TOLERANCE);

        // Above the knee, the extra speed is damped at the FAST rate, continuous with the value at the knee.
        double aboveKnee = compressionForce(KNEE_SPEED + SPEED_PAST_KNEE);
        assertEquals(springForce + SLOW_RATE * KNEE_SPEED + FAST_RATE * SPEED_PAST_KNEE, aboveKnee, FORCE_TOLERANCE);
    }

    /** {@link #SLOW_RATE}/{@link #FAST_RATE} on both strokes, no bump stop, at this compression rate. */
    private static double compressionForce(double compressionRate) {
        return WheelMath.suspensionForce(TOUCHING_COMPRESSION, compressionRate, SPRING_RATE, SLOW_RATE, FAST_RATE,
                SLOW_RATE, FAST_RATE, KNEE_SPEED, UNCAPPED_FORCE, SuspensionTuning.BUMP_STOP_DISABLED_TRAVEL,
                SuspensionTuning.NO_BUMP_STOP_RATE);
    }

    @Test
    void reboundUsesItsOwnRatesIndependentlyOfCompression() {
        // Compression rates an order of magnitude above the rebound ones, so a mix-up would be obvious.
        double unusedCompressionRate = FAST_RATE * 10;
        double reboundSpeed = KNEE_SPEED + SPEED_PAST_KNEE;

        double force = WheelMath.suspensionForce(TOUCHING_COMPRESSION, -reboundSpeed, SPRING_RATE,
                unusedCompressionRate, unusedCompressionRate, SLOW_RATE, FAST_RATE, KNEE_SPEED, UNCAPPED_FORCE,
                SuspensionTuning.BUMP_STOP_DISABLED_TRAVEL, SuspensionTuning.NO_BUMP_STOP_RATE);

        double expectedDampingForce = -(SLOW_RATE * KNEE_SPEED + FAST_RATE * SPEED_PAST_KNEE);
        assertEquals(Math.max(0.0, SPRING_RATE * TOUCHING_COMPRESSION + expectedDampingForce), force, FORCE_TOLERANCE);
    }

    @Test
    void bumpStopAddsNoForceBelowItsOwnTravelThreshold() {
        double withoutBumpStop = singleRateForce(TOUCHING_COMPRESSION, AT_REST, DAMPING_RATE, KNEE_SPEED,
                UNCAPPED_FORCE);
        double belowThreshold = WheelMath.suspensionForce(TOUCHING_COMPRESSION, AT_REST, SPRING_RATE, DAMPING_RATE,
                DAMPING_RATE, DAMPING_RATE, DAMPING_RATE, KNEE_SPEED, UNCAPPED_FORCE, BUMP_STOP_TRAVEL,
                BUMP_STOP_RATE);

        // TOUCHING_COMPRESSION is inside BUMP_STOP_TRAVEL, so the configured stop must contribute nothing.
        assertTrue(TOUCHING_COMPRESSION < BUMP_STOP_TRAVEL, "this case requires compression inside the stop");
        assertEquals(withoutBumpStop, belowThreshold, REDUCTION_TOLERANCE);
    }

    @Test
    void bumpStopAddsProgressiveForcePastItsOwnTravelThreshold() {
        double pastTheStop = 0.05;
        double compression = BUMP_STOP_TRAVEL + pastTheStop;

        double force = WheelMath.suspensionForce(compression, AT_REST, SPRING_RATE, DAMPING_RATE, DAMPING_RATE,
                DAMPING_RATE, DAMPING_RATE, KNEE_SPEED, UNCAPPED_FORCE, BUMP_STOP_TRAVEL, BUMP_STOP_RATE);

        assertEquals(SPRING_RATE * compression + BUMP_STOP_RATE * pastTheStop, force, FORCE_TOLERANCE);
    }

    @Test
    void bumpStopNeverPullsForceNegativeAndStaysCappedAtMaxForce() {
        // A deep compression against a very stiff stop: the sum far exceeds the cap, which must still hold.
        double force = WheelMath.suspensionForce(5.0, AT_REST, SPRING_RATE, DAMPING_RATE, DAMPING_RATE,
                DAMPING_RATE, DAMPING_RATE, KNEE_SPEED, WheelMath.MAX_FORCE, TOUCHING_COMPRESSION,
                VehicleSpecLimits.MAX_BUMP_STOP_RATE);

        assertTrue(force >= 0.0);
        assertEquals(WheelMath.MAX_FORCE, force, 0);
    }

    @Test
    void notTouchingTheGroundIsStillZeroRegardlessOfTireKitFields() {
        double notTouching = -TOUCHING_COMPRESSION;

        assertEquals(0.0, WheelMath.suspensionForce(notTouching, 5.0, SPRING_RATE, SLOW_RATE, FAST_RATE,
                SLOW_RATE, FAST_RATE, KNEE_SPEED, WheelMath.MAX_FORCE, BUMP_STOP_TRAVEL, BUMP_STOP_RATE), 0);
    }
}
