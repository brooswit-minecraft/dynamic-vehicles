package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure-function checks for {@link DriftTireModel} and {@link VehicleSpec.TireTuning} (MINECRAFT-144). */
class DriftTireModelTest {
    private static final double MU = WheelMath.BASE_FRICTION;
    private static final double N = 2943.0; // a quarter of a ~1200 kg car at rest
    private static final VehicleSpec.TireTuning DRIFT_TUNING = VehicleSpec.DRIFT.tireTuning();

    // --- (a) front/rear grip split -----------------------------------------------------------------

    @Test
    void rearGripIsLowerThanFrontForTheDriftTuning() {
        // Below the slip threshold, so the falloff curve is not itself in play - isolates the plain split.
        double frontMu = DriftTireModel.effectiveMu(MU, 0.1, 0.0, N, 0.0, DRIFT_TUNING, true);
        double rearMu = DriftTireModel.effectiveMu(MU, 0.1, 0.0, N, 0.0, DRIFT_TUNING, false);
        assertEquals(MU, frontMu, 1e-9, "front grip is untouched by the rear split");
        assertTrue(rearMu < frontMu, "rear grip must be lower than front for a drift tuning");
        assertEquals(MU * DRIFT_TUNING.rearGripScale(), rearMu, 1e-9);
    }

    @Test
    void identityTuningGivesNoFrontRearSplit() {
        assertEquals(MU, DriftTireModel.effectiveMu(MU, 0.1, 0.0, N, 0.0, VehicleSpec.TireTuning.IDENTITY, true), 1e-9);
        assertEquals(MU, DriftTireModel.effectiveMu(MU, 0.1, 0.0, N, 0.0, VehicleSpec.TireTuning.IDENTITY, false), 1e-9);
    }

    // --- (b) easy slide initiation: below threshold grips, above threshold breaks away --------------

    @Test
    void belowTheSlipThresholdTheRearTireGripsAtItsFullScaledMu() {
        double mu = DriftTireModel.effectiveMu(MU, DRIFT_TUNING.slipAngleThreshold() * 0.5, 0.0, N, 0.0, DRIFT_TUNING, false);
        assertEquals(MU * DRIFT_TUNING.rearGripScale(), mu, 1e-9);
    }

    @Test
    void wellPastTheSlipThresholdGripBreaksAwayTowardTheFalloffFloor() {
        double atThreshold = DriftTireModel.effectiveMu(MU, DRIFT_TUNING.slipAngleThreshold(), 0.0, N, 0.0, DRIFT_TUNING, false);
        double wellPast = DriftTireModel.effectiveMu(MU, DRIFT_TUNING.slipAngleThreshold() * 20.0, 0.0, N, 0.0, DRIFT_TUNING, false);
        double baseRearMu = MU * DRIFT_TUNING.rearGripScale();
        assertTrue(wellPast < atThreshold, "grip past the threshold must be lower than right at it");
        assertTrue(wellPast > baseRearMu * (1.0 - DRIFT_TUNING.gripFalloff()) - 1e-9,
                "falloff saturates toward a floor, it never reaches exactly zero grip");
        assertTrue(wellPast < baseRearMu * (1.0 - DRIFT_TUNING.gripFalloff() * 0.5),
                "far past the threshold grip should sit closer to the floor than halfway to it");
    }

    @Test
    void aLowSlipThresholdBreaksAwayEarlierThanAHighOne() {
        VehicleSpec.TireTuning lowThreshold = new VehicleSpec.TireTuning(1.0, 0.5, 0.5, 1.0, 0.0, 0.0);
        VehicleSpec.TireTuning highThreshold = new VehicleSpec.TireTuning(1.0, 5.0, 0.5, 1.0, 0.0, 0.0);
        double vLat = 1.0;
        double lowMu = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 0.0, lowThreshold, false);
        double highMu = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 0.0, highThreshold, false);
        assertTrue(lowMu < highMu, "the lower threshold has already started falling off grip at this slip speed, the higher one has not");
    }

    @Test
    void zeroGripFalloffNeverBreaksAwayRegardlessOfSlip() {
        VehicleSpec.TireTuning noFalloff = new VehicleSpec.TireTuning(0.7, 0.1, 0.0, 1.0, 0.0, 0.0);
        assertEquals(MU * 0.7, DriftTireModel.effectiveMu(MU, 50.0, 0.0, N, 0.0, noFalloff, false), 1e-9);
    }

    // --- (c) handbrake-induced oversteer --------------------------------------------------------------

    @Test
    void handbrakeSharplyCutsRearGripButLeavesFrontUnaffected() {
        double lateralScale = 0.35; // SableCarBody's own unconditional handbrake cut, shared by every vehicle
        WheelMath.Tire rearNoHandbrake = DriftTireModel.tire(0, 1.0, N, MU, WheelMath.ROLLING_RESISTANCE,
                lateralScale, 0, 0, 1.0, 300.0, 0.05, DRIFT_TUNING, false, false, 0.0);
        WheelMath.Tire rearHandbrake = DriftTireModel.tire(0, 1.0, N, MU, WheelMath.ROLLING_RESISTANCE,
                lateralScale, 0, 0, 1.0, 300.0, 0.05, DRIFT_TUNING, false, true, 0.0);
        assertTrue(Math.abs(rearHandbrake.lateral()) < Math.abs(rearNoHandbrake.lateral()),
                "the handbrake must cut the rear tire's lateral grip further still");

        WheelMath.Tire frontNoHandbrake = DriftTireModel.tire(0, 1.0, N, MU, WheelMath.ROLLING_RESISTANCE,
                1.0, 0, 0, 1.0, 300.0, 0.05, DRIFT_TUNING, true, false, 0.0);
        WheelMath.Tire frontHandbrake = DriftTireModel.tire(0, 1.0, N, MU, WheelMath.ROLLING_RESISTANCE,
                1.0, 0, 0, 1.0, 300.0, 0.05, DRIFT_TUNING, true, true, 0.0);
        assertEquals(frontNoHandbrake.lateral(), frontHandbrake.lateral(), 1e-9,
                "the handbrake's extra cut is rear-only; a front tire is untouched by it");
    }

    // --- (d) throttle-induced oversteer (friction-circle style) --------------------------------------

    @Test
    void throttleEatsIntoRearLateralGrip() {
        double coasting = DriftTireModel.effectiveMu(MU, 1.0, 0.0, N, 0.0, DRIFT_TUNING, false);
        double underThrottle = DriftTireModel.effectiveMu(MU, 1.0, MU * N * 0.8, N, 0.0, DRIFT_TUNING, false);
        assertTrue(underThrottle < coasting, "a rear tire under heavy throttle must have less lateral grip left than one coasting");
    }

    @Test
    void throttleBiteDoesNotTouchTheFrontTire() {
        double coasting = DriftTireModel.effectiveMu(MU, 1.0, 0.0, N, 0.0, DRIFT_TUNING, true);
        double underThrottle = DriftTireModel.effectiveMu(MU, 1.0, MU * N * 0.8, N, 0.0, DRIFT_TUNING, true);
        assertEquals(coasting, underThrottle, 1e-9, "throttle bite only ever applies to a driven rear wheel");
    }

    // --- (e) countersteer recovery ---------------------------------------------------------------------

    @Test
    void steeringIntoTheSlideRestoresGripComparedToNotSteering() {
        double vLat = DRIFT_TUNING.slipAngleThreshold() * 5.0; // well past the threshold, so the falloff curve has already bitten
        double noSteer = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 0.0, DRIFT_TUNING, false);
        double counterSteer = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, -1.0, DRIFT_TUNING, false);
        assertTrue(counterSteer > noSteer, "steering into the slide must restore some of the grip the falloff curve took away");
        assertTrue(counterSteer <= MU * DRIFT_TUNING.rearGripScale() + 1e-9, "recovery never lifts grip above the un-sliding, scaled mu");
    }

    @Test
    void steeringAwayFromTheSlideGivesNoRecovery() {
        double vLat = DRIFT_TUNING.slipAngleThreshold() * 5.0;
        double noSteer = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 0.0, DRIFT_TUNING, false);
        double wrongWay = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 1.0, DRIFT_TUNING, false);
        assertEquals(noSteer, wrongWay, 1e-9, "steering further into the slide's own direction of travel gives no recovery");
    }

    @Test
    void counterSteerRecoveryIsSymmetricInBothSlideDirections() {
        // The steer convention (-1..1, positive = left, same as CarPhysics.step's own `steer`) must give a
        // countersteer benefit regardless of which way the tire is sliding: steering opposite the sign of
        // vLat is "into" a positive-vLat slide (steer negative, i.e. right) and "into" a negative-vLat slide
        // (steer positive, i.e. left) alike - only the SIGN relationship matters, not which side is which.
        double vLat = DRIFT_TUNING.slipAngleThreshold() * 5.0;
        double noSteerPositiveSlide = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 0.0, DRIFT_TUNING, false);
        double counterPositiveSlide = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, -1.0, DRIFT_TUNING, false);
        double wrongWayPositiveSlide = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, 1.0, DRIFT_TUNING, false);

        double noSteerNegativeSlide = DriftTireModel.effectiveMu(MU, -vLat, 0.0, N, 0.0, DRIFT_TUNING, false);
        double counterNegativeSlide = DriftTireModel.effectiveMu(MU, -vLat, 0.0, N, 1.0, DRIFT_TUNING, false);
        double wrongWayNegativeSlide = DriftTireModel.effectiveMu(MU, -vLat, 0.0, N, -1.0, DRIFT_TUNING, false);

        assertTrue(counterPositiveSlide > noSteerPositiveSlide, "steering right (-1) into a positive-vLat slide must recover grip");
        assertEquals(noSteerPositiveSlide, wrongWayPositiveSlide, 1e-9, "steering left (+1), away from a positive-vLat slide, must not recover grip");

        assertTrue(counterNegativeSlide > noSteerNegativeSlide, "steering left (+1) into a negative-vLat slide must recover grip");
        assertEquals(noSteerNegativeSlide, wrongWayNegativeSlide, 1e-9, "steering right (-1), away from a negative-vLat slide, must not recover grip");

        // Mirrored slides with mirrored countersteer recover by the same amount - the model has no bias
        // toward either steering direction, only toward whichever direction opposes this wheel's own slide.
        assertEquals(counterPositiveSlide, counterNegativeSlide, 1e-9);
    }

    @Test
    void aFullCounterSteerCanFullyRecoverGripWhenAssistIsMaxed() {
        VehicleSpec.TireTuning fullAssist = new VehicleSpec.TireTuning(1.0, 0.1, 0.9, 1.0, 0.0, 1.0);
        double vLat = 1.0;
        double recovered = DriftTireModel.effectiveMu(MU, vLat, 0.0, N, -1.0, fullAssist, false);
        assertEquals(MU, recovered, 1e-9, "a maxed-out countersteer assist with a full countersteer input restores full grip");
    }

    // --- regression: identity tuning is behaviour-identical to plain WheelMath.tire -------------------

    @Test
    void carTruckAndTrophyAllCarryIdentityTireTuning() {
        assertTrue(VehicleSpec.CAR.tireTuning().isIdentity());
        assertTrue(VehicleSpec.TRUCK.tireTuning().isIdentity());
        assertTrue(VehicleSpec.TROPHY.tireTuning().isIdentity());
    }

    @Test
    void driftCarCarriesANonIdentityTireTuning() {
        assertTrue(!VehicleSpec.DRIFT.tireTuning().isIdentity());
    }

    // --- MINECRAFT-210: DRIFT's tire tuning retune sits between MUSCLE (identity) and the old drift values ---

    // The values DRIFT carried before MINECRAFT-210 (VehicleSpec.TIRE_TUNING's old constructor args),
    // pinned here as a named constant rather than re-derived, so this test keeps failing if someone
    // reverts the retune without updating it.
    private static final VehicleSpec.TireTuning OLD_DRIFT_TUNING =
            new VehicleSpec.TireTuning(0.72, 1.2, 0.45, 0.5, 0.5, 0.6);
    private static final VehicleSpec.TireTuning MUSCLE_TUNING = VehicleSpec.TireTuning.IDENTITY;

    @Test
    void retunedRearGripScaleSitsBetweenMuscleAndOldDrift() {
        assertTrue(MUSCLE_TUNING.rearGripScale() > DRIFT_TUNING.rearGripScale(),
                "muscle (identity) has a higher rearGripScale than old drift");
        assertTrue(DRIFT_TUNING.rearGripScale() > OLD_DRIFT_TUNING.rearGripScale(),
                "new rearGripScale must be strictly greater than the old drift value");
        assertTrue(DRIFT_TUNING.rearGripScale() < MUSCLE_TUNING.rearGripScale(),
                "new rearGripScale must be strictly less than muscle's identity value");
    }

    @Test
    void retunedSlipAngleThresholdSitsBetweenMuscleAndOldDrift() {
        assertTrue(MUSCLE_TUNING.slipAngleThreshold() > DRIFT_TUNING.slipAngleThreshold(),
                "muscle (identity) has a higher slipAngleThreshold than old drift");
        assertTrue(DRIFT_TUNING.slipAngleThreshold() > OLD_DRIFT_TUNING.slipAngleThreshold(),
                "new slipAngleThreshold must be strictly greater than the old drift value");
        assertTrue(DRIFT_TUNING.slipAngleThreshold() < MUSCLE_TUNING.slipAngleThreshold(),
                "new slipAngleThreshold must be strictly less than muscle's identity value");
    }

    @Test
    void retunedGripFalloffSitsBetweenMuscleAndOldDrift() {
        assertTrue(MUSCLE_TUNING.gripFalloff() < DRIFT_TUNING.gripFalloff(),
                "muscle (identity) has a lower gripFalloff than old drift");
        assertTrue(DRIFT_TUNING.gripFalloff() < OLD_DRIFT_TUNING.gripFalloff(),
                "new gripFalloff must be strictly less than the old drift value");
        assertTrue(DRIFT_TUNING.gripFalloff() > MUSCLE_TUNING.gripFalloff(),
                "new gripFalloff must be strictly greater than muscle's identity value");
    }

    @Test
    void retunedHandbrakeRearGripCutSitsBetweenMuscleAndOldDrift() {
        assertTrue(MUSCLE_TUNING.handbrakeRearGripCut() > DRIFT_TUNING.handbrakeRearGripCut(),
                "muscle (identity) has a higher handbrakeRearGripCut than old drift");
        assertTrue(DRIFT_TUNING.handbrakeRearGripCut() > OLD_DRIFT_TUNING.handbrakeRearGripCut(),
                "new handbrakeRearGripCut must be strictly greater than the old drift value");
        assertTrue(DRIFT_TUNING.handbrakeRearGripCut() < MUSCLE_TUNING.handbrakeRearGripCut(),
                "new handbrakeRearGripCut must be strictly less than muscle's identity value");
    }

    @Test
    void retunedThrottleBiteSitsBetweenMuscleAndOldDrift() {
        assertTrue(MUSCLE_TUNING.throttleBite() < DRIFT_TUNING.throttleBite(),
                "muscle (identity) has a lower throttleBite than old drift");
        assertTrue(DRIFT_TUNING.throttleBite() < OLD_DRIFT_TUNING.throttleBite(),
                "new throttleBite must be strictly less than the old drift value");
        assertTrue(DRIFT_TUNING.throttleBite() > MUSCLE_TUNING.throttleBite(),
                "new throttleBite must be strictly greater than muscle's identity value");
    }

    @Test
    void retunedCounterSteerAssistDeliberatelyExceedsOldDriftInsteadOfSittingBelowIt() {
        // KNOWN AMBIGUITY (MINECRAFT-210, confirmed by the story's boss comment): identity's 0.0 means "no
        // slide to recover from", not an "easier" endpoint, so a muscle-like recovery feel means going
        // HIGHER than the old drift value, not sitting between it and identity. Tested as old < new <= 1.0.
        assertTrue(DRIFT_TUNING.counterSteerAssist() > OLD_DRIFT_TUNING.counterSteerAssist(),
                "new counterSteerAssist must be strictly greater than the old drift value");
        assertTrue(DRIFT_TUNING.counterSteerAssist() <= 1.0,
                "new counterSteerAssist must stay within CarConfig's allowed range");
    }

    @Test
    void carConfigDriftDefaultsMatchVehicleSpecDriftTuning() {
        VehicleSpec.TireTuning configDefaults = CarConfig.driftTireTuning();
        assertEquals(DRIFT_TUNING.rearGripScale(), configDefaults.rearGripScale(), 1e-9);
        assertEquals(DRIFT_TUNING.slipAngleThreshold(), configDefaults.slipAngleThreshold(), 1e-9);
        assertEquals(DRIFT_TUNING.gripFalloff(), configDefaults.gripFalloff(), 1e-9);
        assertEquals(DRIFT_TUNING.handbrakeRearGripCut(), configDefaults.handbrakeRearGripCut(), 1e-9);
        assertEquals(DRIFT_TUNING.throttleBite(), configDefaults.throttleBite(), 1e-9);
        assertEquals(DRIFT_TUNING.counterSteerAssist(), configDefaults.counterSteerAssist(), 1e-9);
    }

    @Test
    void identityTuningMakesDriftTireModelExactlyWheelMathTireForASpreadOfInputs() {
        VehicleSpec.TireTuning identity = VehicleSpec.TireTuning.IDENTITY;
        double[] vLongs = {-8.0, 0.0, 3.0, 12.0};
        double[] vLats = {-2.0, 0.0, 0.3, 4.0};
        double[] drives = {0.0, 500.0, 6000.0};
        double[] brakes = {0.0, 4000.0};
        double[] steers = {-1.0, 0.0, 1.0};
        boolean[] handbrakes = {false, true};
        boolean[] fronts = {true, false};
        for (double vLong : vLongs) {
            for (double vLat : vLats) {
                for (double drive : drives) {
                    for (double brake : brakes) {
                        for (double steer : steers) {
                            for (boolean handbrake : handbrakes) {
                                for (boolean front : fronts) {
                                    WheelMath.Tire expected = WheelMath.tire(vLong, vLat, N, MU,
                                            WheelMath.ROLLING_RESISTANCE, 1.0, drive, brake, 1.0, 300.0, 0.05);
                                    WheelMath.Tire actual = DriftTireModel.tire(vLong, vLat, N, MU,
                                            WheelMath.ROLLING_RESISTANCE, 1.0, drive, brake, 1.0, 300.0, 0.05,
                                            identity, front, handbrake, steer);
                                    assertEquals(expected.longitudinal(), actual.longitudinal(), 0,
                                            "longitudinal must be bit-identical under identity tuning");
                                    assertEquals(expected.lateral(), actual.lateral(), 0,
                                            "lateral must be bit-identical under identity tuning");
                                    assertEquals(expected.slipSpeed(), actual.slipSpeed(), 0);
                                    assertEquals(expected.commandLongitudinal(), actual.commandLongitudinal(), 0);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
