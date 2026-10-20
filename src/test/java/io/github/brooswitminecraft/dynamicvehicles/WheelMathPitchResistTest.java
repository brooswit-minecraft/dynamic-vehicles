package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-445: the anti-dive / anti-squat application-height blend. The production wiring is covered by
 * {@code SuspensionTuningGameTests}; what this pins is the blend itself, and above all that a fraction of
 * 0.0 returns the contact height bit-for-bit, which is what keeps every roster car's pitch behaviour
 * exactly as it was.
 */
class WheelMathPitchResistTest {

    /** A representative contact-patch height in the body frame: below the body, as a wheel always is. */
    private static final double CONTACT_Y = -0.75;
    /** The height a force applied straight into the chassis acts at, matching the production constant. */
    private static final double BODY_Y = -0.1;

    @Test
    void zeroFractionReturnsTheContactHeightBitForBit() {
        // Raw bits, not a tolerance: this is the identity claim that keeps the roster unchanged, and the
        // suspension slice on this ticket already shipped one last-bits difference that a tolerance test
        // would have accepted.
        assertEquals(Double.doubleToRawLongBits(CONTACT_Y),
                Double.doubleToRawLongBits(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.0)));
    }

    @Test
    void fullFractionAppliesTheForceAtBodyHeight() {
        assertEquals(BODY_Y, WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 1.0), 1e-12);
    }

    @Test
    void theBlendMovesMonotonicallyTowardTheBodyAsTheFractionRises() {
        double previous = WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.0);
        for (double fraction = 0.1; fraction <= 1.0; fraction += 0.1) {
            double height = WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, fraction);
            assertTrue(height > previous,
                    "a larger anti-dive/anti-squat fraction must move the force application point further "
                            + "toward the body (shorter moment arm): fraction=" + fraction + " height="
                            + height + " previous=" + previous);
            previous = height;
        }
    }

    /** The moment arm about the body centre is what actually produces pitch, so that is what must shrink. */
    @Test
    void aHigherFractionShortensTheMomentArmAboutTheBody() {
        double noResist = Math.abs(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.0) - BODY_Y);
        double halfResist = Math.abs(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 0.5) - BODY_Y);
        double fullResist = Math.abs(WheelMath.pitchResistApplicationHeight(CONTACT_Y, BODY_Y, 1.0) - BODY_Y);
        assertTrue(halfResist < noResist, "half resistance must shorten the arm: " + halfResist + " vs " + noResist);
        assertEquals(0.0, fullResist, 1e-12, "full resistance must cancel the arm entirely");
    }

    // ---- Which fraction applies (PR184 review 5580581955) ----------------------------------------------
    //
    // The cases below run one wheel's sub-step through the same real helpers SableCarBody.tick calls, in
    // the same order: WheelMath.tire -> WheelMath.spinRate -> DrivetrainMath.boundedSpinTowardSnap ->
    // DrivetrainMath.recoveryReactionImpulse -> WheelMath.chassisLongitudinalImpulse ->
    // WheelMath.pitchResistFraction. Anti-dive and anti-squat are deliberately DIFFERENT values, so the
    // assertion says which one was picked rather than passing whichever it was.

    private static final double ANTI_DIVE = 0.3;
    private static final double ANTI_SQUAT = 0.7;
    /** One server tick, s: what SableCarBody.tick divides into wheelSubSteps sub-steps. */
    private static final double TICK_DT = 0.05;
    /** The sub-step counts the production config allows at its two ends of interest. */
    private static final int[] SUB_STEP_COUNTS = {1, 4};
    private static final double NORMAL_FORCE = 1000.0;
    private static final double CRUISE_SPEED = 10.0;
    private static final double WHEEL_RADIUS = VehicleSpec.CAR.wheelRadius();
    private static final double EFFECTIVE_MASS = VehicleSpec.CAR.massKg() / 4.0;

    /** One wheel's sub-step, as production evaluates it, from tire force to the fraction it selects. */
    private record Selection(double tireLongitudinal, double reactionImpulse, double chassisImpulse,
            double fraction) {}

    private static Selection select(double vLong, double drive, double brake, double priorSpin, int subSteps) {
        double subDt = TICK_DT / subSteps;
        WheelMath.Tire tire = WheelMath.tire(vLong, 0.0, NORMAL_FORCE, WheelMath.BASE_FRICTION,
                WheelMath.ROLLING_RESISTANCE, 1.0, drive, brake, 1.0, EFFECTIVE_MASS, subDt,
                VehicleSpec.VehicleTuning.IDENTITY);
        double excessForce = tire.commandLongitudinal() - tire.longitudinal();
        double snappedSpin = WheelMath.spinRate(priorSpin, WHEEL_RADIUS, vLong, excessForce,
                WheelMath.WHEEL_INERTIA, subDt);
        double reactionImpulse = 0.0;
        if (excessForce == 0.0) {
            double headroom = Math.max(0.0, tire.frictionLimit() - Math.hypot(tire.longitudinal(), tire.lateral()));
            double recoveredSpin = DrivetrainMath.boundedSpinTowardSnap(priorSpin, snappedSpin, headroom,
                    WHEEL_RADIUS, WheelMath.WHEEL_INERTIA, subDt);
            reactionImpulse = DrivetrainMath.recoveryReactionImpulse(priorSpin, recoveredSpin,
                    WheelMath.WHEEL_INERTIA, WHEEL_RADIUS);
        }
        double chassisImpulse = WheelMath.chassisLongitudinalImpulse(tire.longitudinal(), subDt, reactionImpulse);
        return new Selection(tire.longitudinal(), reactionImpulse, chassisImpulse,
                WheelMath.pitchResistFraction(chassisImpulse, vLong, ANTI_DIVE, ANTI_SQUAT));
    }

    /**
     * The reviewer's own reachable case: coasting forward (no drive, no brake), so the tire's only
     * longitudinal force is rolling drag, backward -- but the wheel is overspinning, and bleeding that spin
     * off hands the chassis a FORWARD recovery impulse larger than the drag's. The chassis is being pushed
     * forward, which is anti-SQUAT's regime, at every sub-step count.
     */
    @Test
    void coastingOverspinRecoveryThatOutweighsRollingDragSelectsAntiSquat() {
        double groundSpin = CRUISE_SPEED / WHEEL_RADIUS;
        for (int subSteps : SUB_STEP_COUNTS) {
            Selection s = select(CRUISE_SPEED, 0.0, 0.0, groundSpin + 1.0, subSteps);
            assertTrue(s.tireLongitudinal() < 0.0, "premise: rolling drag must oppose motion, subSteps="
                    + subSteps + " " + s);
            assertTrue(s.reactionImpulse() > 0.0, "premise: bleeding overspin must push the chassis forward, "
                    + "subSteps=" + subSteps + " " + s);
            assertTrue(s.tireLongitudinal() + s.reactionImpulse() < 0.0, "premise: summing the force (N) with "
                    + "the impulse (N*s) must point the OTHER way, or this case would not catch that bug: " + s);
            assertTrue(s.chassisImpulse() > 0.0, "the impulse actually applied is forward: subSteps="
                    + subSteps + " " + s);
            assertEquals(ANTI_SQUAT, s.fraction(), "a forward net impulse must select anti-squat, subSteps="
                    + subSteps + " " + s);
        }
    }

    /**
     * The inverse: a wheel coming out of lock-up under gentle drive. The tire pushes forward, but spinning
     * the wheel back up to ground speed costs the chassis a BACKWARD recovery impulse larger than the
     * drive's, so the chassis is being slowed -- anti-DIVE's regime. At four sub-steps the recovery is
     * friction-bounded (it cannot complete in one sub-step), at one it is not; both must agree.
     */
    @Test
    void lockUpRecoveryThatOutweighsGentleDriveSelectsAntiDive() {
        double groundSpin = CRUISE_SPEED / WHEEL_RADIUS;
        for (int subSteps : SUB_STEP_COUNTS) {
            Selection s = select(CRUISE_SPEED, 100.0, 0.0, groundSpin - 5.0, subSteps);
            assertTrue(s.tireLongitudinal() > 0.0, "premise: the tire must be driving, subSteps=" + subSteps
                    + " " + s);
            assertTrue(s.reactionImpulse() < 0.0, "premise: spinning a locked wheel back up must pull the "
                    + "chassis back, subSteps=" + subSteps + " " + s);
            assertTrue(s.tireLongitudinal() + s.reactionImpulse() > 0.0, "premise: summing the force (N) with "
                    + "the impulse (N*s) must point the OTHER way, or this case would not catch that bug: " + s);
            assertTrue(s.chassisImpulse() < 0.0, "the impulse actually applied is backward: subSteps="
                    + subSteps + " " + s);
            assertEquals(ANTI_DIVE, s.fraction(), "a backward net impulse must select anti-dive, subSteps="
                    + subSteps + " " + s);
        }
    }

    /** Without any spin recovery, plain braking is anti-dive and plain driving is anti-squat. */
    @Test
    void plainBrakingSelectsAntiDiveAndPlainDrivingSelectsAntiSquat() {
        double groundSpin = CRUISE_SPEED / WHEEL_RADIUS;
        for (int subSteps : SUB_STEP_COUNTS) {
            Selection braking = select(CRUISE_SPEED, 0.0, 800.0, groundSpin, subSteps);
            assertTrue(braking.reactionImpulse() == 0.0, "premise: no recovery, subSteps=" + subSteps);
            assertEquals(ANTI_DIVE, braking.fraction(), "braking, subSteps=" + subSteps + " " + braking);
            Selection driving = select(CRUISE_SPEED, 800.0, 0.0, groundSpin, subSteps);
            assertTrue(driving.reactionImpulse() == 0.0, "premise: no recovery, subSteps=" + subSteps);
            assertEquals(ANTI_SQUAT, driving.fraction(), "driving, subSteps=" + subSteps + " " + driving);
        }
    }

    /** Direction of travel is what "opposing motion" is measured against, so reversing flips it. */
    @Test
    void inReverseAForwardImpulseIsTheOneThatOpposesMotion() {
        assertEquals(ANTI_DIVE, WheelMath.pitchResistFraction(+1.0, -CRUISE_SPEED, ANTI_DIVE, ANTI_SQUAT));
        assertEquals(ANTI_SQUAT, WheelMath.pitchResistFraction(-1.0, -CRUISE_SPEED, ANTI_DIVE, ANTI_SQUAT));
        assertEquals(ANTI_SQUAT, WheelMath.pitchResistFraction(-1.0, 0.0, ANTI_DIVE, ANTI_SQUAT),
                "at rest nothing opposes motion");
    }

    /** The extracted reaction is the one SableCarBody used to inline: spin bleeding off pushes forward. */
    @Test
    void recoveryReactionImpulseIsTheInlinedFormulaBitForBit() {
        double prior = 31.0;
        double recovered = 28.5;
        assertEquals(Double.doubleToRawLongBits(-(recovered - prior) * WheelMath.WHEEL_INERTIA / WHEEL_RADIUS),
                Double.doubleToRawLongBits(DrivetrainMath.recoveryReactionImpulse(prior, recovered,
                        WheelMath.WHEEL_INERTIA, WHEEL_RADIUS)));
    }
}
