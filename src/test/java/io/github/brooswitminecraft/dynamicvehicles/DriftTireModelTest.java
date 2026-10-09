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
