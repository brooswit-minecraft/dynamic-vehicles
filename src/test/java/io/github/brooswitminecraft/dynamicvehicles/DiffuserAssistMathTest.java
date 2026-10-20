package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiffuserAssistMathTest {
    private static final VehicleSpec.DiffuserTuning IDENTITY = VehicleSpec.DiffuserTuning.IDENTITY;

    private static VehicleSpec.DiffuserTuning rollTuning(double maxTorque, double gain, double damping) {
        return new VehicleSpec.DiffuserTuning(true, maxTorque, gain, damping,
                false, 0, 0, 0, 0, false, 0, 0, 0, 0, false, 0, 0, 0, 0);
    }

    private static VehicleSpec.DiffuserTuning pitchTuning(double maxTorque, double horizon, double gain, double damping) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0,
                true, maxTorque, horizon, gain, damping, false, 0, 0, 0, 0, false, 0, 0, 0, 0);
    }

    private static VehicleSpec.DiffuserTuning headingTuning(double maxTorque, double gain, double damping, double steerSuppression) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0,
                true, maxTorque, gain, damping, steerSuppression, false, 0, 0, 0, 0);
    }

    private static VehicleSpec.DiffuserTuning trajectoryTuning(double maxTorque, double gain, double damping, double minSpeed) {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0, false, 0, 0, 0, 0,
                true, maxTorque, gain, damping, minSpeed);
    }

    // ---- disabled settings are truly inactive ----

    @Test
    void identityTuningGivesZeroTorqueForEveryAssistRegardlessOfInputs() {
        assertEquals(0.0, DiffuserAssistMath.rollSelfRightingTorque(Math.PI / 2, 10.0, IDENTITY), 0);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.5, 2.0, 0.1, IDENTITY), 0);
        assertEquals(0.0, DiffuserAssistMath.headingAlignmentTorque(1.0, 1.0, 0.0, IDENTITY), 0);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(1.0, 1.0, 20.0, false, IDENTITY), 0);
    }

    @Test
    void enabledFlagFalseStaysInactiveEvenWithNonzeroBoundsAndGains() {
        VehicleSpec.DiffuserTuning disabledButNonzero = new VehicleSpec.DiffuserTuning(
                false, 500, 100, 10,
                false, 500, 1.0, 100, 10,
                false, 500, 100, 10, 0.5,
                false, 500, 100, 10, 0.5);
        assertEquals(0.0, DiffuserAssistMath.rollSelfRightingTorque(1.0, 1.0, disabledButNonzero), 0);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.3, 0.1, 0.1, disabledButNonzero), 0);
        assertEquals(0.0, DiffuserAssistMath.headingAlignmentTorque(1.0, 0.0, 0.0, disabledButNonzero), 0);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(1.0, 0.0, 20.0, false, disabledButNonzero), 0);
    }

    @Test
    void zeroMaxTorqueDisablesEvenWhenEnabledFlagIsTrue() {
        assertEquals(0.0, DiffuserAssistMath.rollSelfRightingTorque(1.0, 0.0, rollTuning(0.0, 100, 10)), 0);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.3, 0.0, 0.1, pitchTuning(0.0, 1.0, 100, 10)), 0);
        assertEquals(0.0, DiffuserAssistMath.headingAlignmentTorque(1.0, 0.0, 0.0, headingTuning(0.0, 100, 10, 0.5)), 0);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(1.0, 0.0, 20.0, false, trajectoryTuning(0.0, 100, 10, 1.0)), 0);
    }

    // ---- R16 roll self-righting: inverted roll recovery ----

    @Test
    void uprightCarGetsNoRollTorque() {
        assertEquals(0.0, DiffuserAssistMath.rollSelfRightingTorque(0.0, 0.0, rollTuning(500, 100, 10)), 1e-9);
    }

    @Test
    void invertedCarGetsPushedTowardUprightNotFurtherOver() {
        VehicleSpec.DiffuserTuning tuning = rollTuning(500, 100, 10);
        double justPastUpsideDown = Math.PI - 0.1; // tipped almost all the way, slightly past straight-up inversion
        double torque = DiffuserAssistMath.rollSelfRightingTorque(justPastUpsideDown, 0.0, tuning);
        // sin(pi - 0.1) > 0, so the restoring torque (-gain*sin) must be negative: it pushes to continue
        // rolling the short way back to upright (0), not to keep rolling toward +pi and overshoot.
        assertTrue(torque < 0.0);
    }

    @Test
    void exactlyUpsideDownIsAnUnstableEquilibriumWithNoSpuriousKick() {
        assertEquals(0.0, DiffuserAssistMath.rollSelfRightingTorque(Math.PI, 0.0, rollTuning(500, 100, 10)), 1e-9);
        assertEquals(0.0, DiffuserAssistMath.rollSelfRightingTorque(-Math.PI, 0.0, rollTuning(500, 100, 10)), 1e-9);
    }

    @Test
    void rollTorqueIsBoundedByMaxTorque() {
        VehicleSpec.DiffuserTuning tuning = rollTuning(50, 10_000, 10_000);
        double torque = DiffuserAssistMath.rollSelfRightingTorque(Math.PI / 2, 100.0, tuning);
        assertTrue(Math.abs(torque) <= 50.0 + 1e-9);
    }

    @Test
    void rollDampingOpposesRollRate() {
        VehicleSpec.DiffuserTuning tuning = rollTuning(500, 0.0, 10.0);
        assertTrue(DiffuserAssistMath.rollSelfRightingTorque(0.0, 5.0, tuning) < 0.0);
        assertTrue(DiffuserAssistMath.rollSelfRightingTorque(0.0, -5.0, tuning) > 0.0);
    }

    // ---- R17 pitch/landing: time-to-touchdown behaviour ----

    @Test
    void longTimeToTouchdownBeyondHorizonGivesNoAuthority() {
        VehicleSpec.DiffuserTuning tuning = pitchTuning(500, 1.0, 100, 10);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, 5.0, tuning), 0);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, 1.0, tuning), 0); // at the horizon: still 0 authority
    }

    @Test
    void shortTimeToTouchdownNearlyFullAuthority() {
        VehicleSpec.DiffuserTuning tuning = pitchTuning(500, 1.0, 100, 0);
        double nearlyLanding = DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, 0.01, tuning);
        double halfway = DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, 0.5, tuning);
        assertTrue(Math.abs(nearlyLanding) > Math.abs(halfway));
    }

    @Test
    void invalidTimeToTouchdownEstimatesGiveNoTorque() {
        VehicleSpec.DiffuserTuning tuning = pitchTuning(500, 1.0, 100, 10);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, Double.NaN, tuning), 0);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, Double.POSITIVE_INFINITY, tuning), 0);
        assertEquals(0.0, DiffuserAssistMath.pitchLandingTorque(0.5, 0.0, -1.0, tuning), 0);
    }

    @Test
    void pitchTorqueLevelsNoseDownWhenPitchedUpAndCloseToTouchdown() {
        VehicleSpec.DiffuserTuning tuning = pitchTuning(500, 1.0, 100, 0);
        double torque = DiffuserAssistMath.pitchLandingTorque(0.8, 0.0, 0.1, tuning);
        assertTrue(torque < 0.0);
    }

    @Test
    void pitchTorqueBoundShrinksWithAuthority() {
        VehicleSpec.DiffuserTuning tuning = pitchTuning(100, 1.0, 10_000, 0);
        double farFromHorizon = DiffuserAssistMath.pitchLandingTorque(10.0, 0.0, 0.9, tuning); // authority 0.1
        assertTrue(Math.abs(farFromHorizon) <= 10.0 + 1e-9);
    }

    // ---- R18 heading alignment: player steering interaction ----

    @Test
    void headingErrorPullsTowardTargetWhenNotSteering() {
        VehicleSpec.DiffuserTuning tuning = headingTuning(500, 100, 0, 1.0);
        assertTrue(DiffuserAssistMath.headingAlignmentTorque(0.5, 0.0, 0.0, tuning) > 0.0);
        assertTrue(DiffuserAssistMath.headingAlignmentTorque(-0.5, 0.0, 0.0, tuning) < 0.0);
    }

    @Test
    void fullPlayerSteeringFullyOverridesWhenSuppressionIsOne() {
        VehicleSpec.DiffuserTuning tuning = headingTuning(500, 100, 0, 1.0);
        assertEquals(0.0, DiffuserAssistMath.headingAlignmentTorque(1.0, 0.0, 1.0, tuning), 1e-9);
    }

    @Test
    void zeroSuppressionNeverLetsSteeringReduceAuthority() {
        VehicleSpec.DiffuserTuning tuning = headingTuning(500, 100, 0, 0.0);
        double noSteer = DiffuserAssistMath.headingAlignmentTorque(0.5, 0.0, 0.0, tuning);
        double fullSteer = DiffuserAssistMath.headingAlignmentTorque(0.5, 0.0, 1.0, tuning);
        assertEquals(noSteer, fullSteer, 1e-9);
    }

    // ---- R19 trajectory alignment: independent of heading, gated on speed/sliding ----

    @Test
    void trajectoryAndHeadingAlignmentAreIndependentAssists() {
        // Same shared tuning constant, but only trajectory enabled this time vs. only heading above:
        // the two never share state, so heading's own error/target cannot leak into trajectory's output.
        VehicleSpec.DiffuserTuning trajectoryOnly = trajectoryTuning(500, 100, 0, 1.0);
        VehicleSpec.DiffuserTuning headingOnly = headingTuning(500, 100, 0, 1.0);
        double trajectoryTorque = DiffuserAssistMath.trajectoryAlignmentTorque(0.5, 0.0, 20.0, false, trajectoryOnly);
        double headingTorque = DiffuserAssistMath.headingAlignmentTorque(0.5, 0.0, 0.0, headingOnly);
        assertTrue(trajectoryTorque > 0.0);
        assertTrue(headingTorque > 0.0);
        // Cross-check: trajectory tuning never reacts to heading's own call, and vice versa (disabled halves).
        assertEquals(0.0, DiffuserAssistMath.headingAlignmentTorque(0.5, 0.0, 0.0, trajectoryOnly), 0);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(0.5, 0.0, 20.0, false, headingOnly), 0);
    }

    @Test
    void nearZeroSpeedGivesNoTrajectoryTorque() {
        VehicleSpec.DiffuserTuning tuning = trajectoryTuning(500, 100, 0, 1.0);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(0.5, 0.0, 0.05, false, tuning), 0);
    }

    @Test
    void intentionalSlideSuppressesTrajectoryAlignmentEntirely() {
        VehicleSpec.DiffuserTuning tuning = trajectoryTuning(500, 100, 0, 1.0);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(0.5, 0.0, 20.0, true, tuning), 0);
    }

    @Test
    void trajectoryTorqueBoundedByMaxTorque() {
        VehicleSpec.DiffuserTuning tuning = trajectoryTuning(25, 10_000, 0, 1.0);
        double torque = DiffuserAssistMath.trajectoryAlignmentTorque(Math.PI, 0.0, 30.0, false, tuning);
        assertTrue(Math.abs(torque) <= 25.0 + 1e-9);
    }

    // ---- wrapAngle ----

    @Test
    void wrapAngleNormalizesToPlusMinusPi() {
        assertEquals(0.0, DiffuserAssistMath.wrapAngle(0.0), 1e-9);
        assertEquals(Math.PI, Math.abs(DiffuserAssistMath.wrapAngle(Math.PI)), 1e-9);
        assertEquals(-Math.PI / 2, DiffuserAssistMath.wrapAngle(3 * Math.PI / 2), 1e-9);
        assertEquals(0.1, DiffuserAssistMath.wrapAngle(2 * Math.PI + 0.1), 1e-9);
        assertEquals(-0.1, DiffuserAssistMath.wrapAngle(-2 * Math.PI - 0.1), 1e-9);
    }

    private static final double RAMP = DiffuserAssistMath.REVERSE_AUTHORITY_RAMP;
    /** Comfortably beyond the ramp, so authority is saturated and the target itself is what is under test. */
    private static final double FULLY_MOVING = 12.0;

    @Test
    void goingForwardTheTrajectoryTargetIsSimplyTheTravelDirection() {
        double error = DiffuserAssistMath.trajectoryErrorRad(10.0, 0.0, FULLY_MOVING, false, RAMP);
        assertEquals(Math.toRadians(-10.0), error, 1e-9);
    }

    @Test
    void aNearSidewaysSlideKeepsFullForwardAuthority() {
        // Longitudinal component tiny, actual speed high: not reversing, and the nose genuinely should be
        // pulled toward travel. An earlier threshold-based version suppressed this case.
        assertEquals(Math.toRadians(-10.0),
                DiffuserAssistMath.trajectoryErrorRad(10.0, 0.0, 0.01, false, RAMP), 1e-9);
    }

    @Test
    void anIntentionalReverseTargetsTheTravelDirectionTurnedAroundNotA180DegreeSpin() {
        double error = DiffuserAssistMath.trajectoryErrorRad(180.0, 0.0, -FULLY_MOVING, true, RAMP);
        assertEquals(0.0, error, 1e-9);
    }

    @Test
    void anIntentionalReverseStillStraightensARealMisalignment() {
        double error = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, -FULLY_MOVING, true, RAMP);
        assertEquals(Math.toRadians(-10.0), error, 1e-9);
    }

    @Test
    void aSlowStraightReverseIsNotHandedTheForwardTargetAndSoIsNeverSpun() {
        // Review round 2: below any plausible threshold, so a threshold-based classifier called this
        // "forward" and handed it a ~180 degree error. With reverse selected it must take the REVERSED
        // target at reduced authority -- same sign as a fast reverse, nowhere near a half turn.
        double slow = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, -0.3, true, RAMP);
        double fast = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, -FULLY_MOVING, true, RAMP);
        assertTrue(Math.abs(slow) < Math.abs(fast), "slow reverse should carry less authority: " + slow);
        assertTrue(slow * fast > 0.0, "slow reverse must pull the same way as a fast one, got " + slow);
        assertTrue(Math.abs(slow) < Math.toRadians(90.0), "slow reverse must never approach a half turn");
    }

    @Test
    void theTargetNeverFlipsAtFullTorqueAcrossTheForwardBackwardBoundary() {
        // Review round 2: the exact -0.499 / -0.501 pair that used to swing the target 180 degrees at full
        // authority. Either side of every switch point the error must be continuous and near zero.
        for (boolean reverseSelected : new boolean[] {true, false}) {
            double justAbove = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, -0.499, reverseSelected, RAMP);
            double justBelow = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, -0.501, reverseSelected, RAMP);
            assertTrue(Math.abs(justAbove - justBelow) < 0.05,
                    "error jumped across the boundary (reverseSelected=" + reverseSelected + "): "
                            + justAbove + " vs " + justBelow);
        }
        // And across zero itself, where the reverse target changes identity.
        double justForward = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, 0.001, true, RAMP);
        double justBackward = DiffuserAssistMath.trajectoryErrorRad(190.0, 0.0, -0.001, true, RAMP);
        assertTrue(Math.abs(justForward) < 0.02 && Math.abs(justBackward) < 0.02,
                "authority must vanish where the reverse target changes identity: " + justForward
                        + " vs " + justBackward);
    }

    @Test
    void rollingBackwardsWithoutReverseSelectedFadesTheTargetOutEntirely() {
        double error = DiffuserAssistMath.trajectoryErrorRad(180.0, 0.0, -FULLY_MOVING, false, RAMP);
        assertEquals(0.0, error, 1e-9);
        assertEquals(0.0, DiffuserAssistMath.trajectoryAlignmentTorque(error, 0.0, FULLY_MOVING, false,
                enabledTrajectory()), 1e-9);
    }

    @Test
    void reverseSelectedWhileStillRollingForwardIsTreatedAsForward() {
        double error = DiffuserAssistMath.trajectoryErrorRad(10.0, 0.0, FULLY_MOVING, true, RAMP);
        assertEquals(Math.toRadians(-10.0), error, 1e-9);
    }

    private static VehicleSpec.DiffuserTuning enabledTrajectory() {
        return new VehicleSpec.DiffuserTuning(false, 0, 0, 0, false, 0, 0, 0, 0,
                false, 0, 0, 0, 0, true, 8000.0, 6000.0, 1200.0, 1.0);
    }
}
