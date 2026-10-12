package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-227/245: the per-vehicle tuning data model (LSD lock, front/rear torque split, torque-
 * vectoring gain, camber, toe). MINECRAFT-296 opts only {@link VehicleSpec#ROCK_CRAWLER} into its LSD
 * lock percentage; every other vehicle must carry exactly {@link VehicleSpec.VehicleTuning#IDENTITY}, whose
 * zero lock makes the differential's exact no-op path preserve existing open-differential behaviour. The
 * {@code WheelMath.tire} overload that accepts a {@code VehicleTuning} remains a mathematical no-op,
 * preserving the remaining tuning fields for their later stories. This is the regression guarantee the
 * epic's hard constraint ("defaults leave every vehicle's handling byte-for-byte unchanged") depends on,
 * following the same pattern
 * {@code VehicleSpecIndyTest.everyPreExistingVehicleHasNoDownforceAndAnUnchangedGripMultiplier} and
 * {@code VehicleSpecRockCrawlerAntiRollTest.everyOtherVehicleKeepsAntiRollScaleAtTheIdentityDefault} already
 * check for {@link VehicleSpec#downforceGripPerSpeed} and {@link VehicleSpec#antiRollScale}.
 */
class VehicleSpecVehicleTuningTest {

    /** Every vehicle on the roster, the same set {@code VehicleSpecRockCrawlerAntiRollTest} iterates. */
    private static final VehicleSpec[] OPEN_DIFFERENTIAL_VEHICLES = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
            VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.MONSTER_TRUCK,
            VehicleSpec.INDY, VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK};

    @Test
    void vehicleTuningIdentityHasNoLockAnEvenSplitNoVectoringNoCamberNoToe() {
        VehicleSpec.VehicleTuning identity = VehicleSpec.VehicleTuning.IDENTITY;
        assertEquals(0.0, identity.lsdLockPercent(), 1e-12, "identity should carry no LSD lock");
        assertEquals(0.5, identity.frontTorqueSplit(), 1e-12, "identity should carry an even front/rear torque split");
        assertEquals(0.0, identity.vectoringGain(), 1e-12, "identity should carry no torque-vectoring gain");
        assertEquals(0.0, identity.camberDeg(), 1e-12, "identity should carry no camber");
        assertEquals(0.0, identity.toeDeg(), 1e-12, "identity should carry no toe");
        assertTrue(identity.isIdentity());
    }

    @Test
    void everyNonOptInVehicleKeepsTheIdentityVehicleTuning() {
        // MINECRAFT-296 deliberately opts only ROCK_CRAWLER into LSD. Every other existing VehicleSpec
        // must retain the exact identity tuning, whose zero lock takes LimitedSlipDifferential's exact
        // early-return path and leaves today's open-differential drive values byte-for-byte unchanged.
        for (VehicleSpec spec : OPEN_DIFFERENTIAL_VEHICLES) {
            assertTrue(spec.vehicleTuning().isIdentity(),
                    "non-opt-in vehicle must carry the identity vehicle tuning");
        }
    }

    @Test
    void rockCrawlerIsTheOnlyLsdOptIn() {
        assertEquals(0.65, VehicleSpec.ROCK_CRAWLER.vehicleTuning().lsdLockPercent(), 1e-12);
        assertTrue(!VehicleSpec.ROCK_CRAWLER.vehicleTuning().isIdentity());
    }

    @Test
    void wheelMathTireOverloadIgnoresVehicleTuningRegardlessOfItsValue() {
        // The no-op guarantee SableCarBody.tick's new spec.vehicleTuning() wiring depends on: passing a
        // deliberately non-identity tuning must not change the result at all, not merely at IDENTITY.
        WheelMath.Tire withoutTuning = WheelMath.tire(5.0, 1.0, 4000.0, 1.1, WheelMath.ROLLING_RESISTANCE,
                1.0, 1500.0, 0.0, 1.0, WheelMath.EFFECTIVE_MASS, 0.05);
        VehicleSpec.VehicleTuning nonIdentity = new VehicleSpec.VehicleTuning(0.8, 0.7, 0.5, -3.0, 1.5);
        WheelMath.Tire withTuning = WheelMath.tire(5.0, 1.0, 4000.0, 1.1, WheelMath.ROLLING_RESISTANCE,
                1.0, 1500.0, 0.0, 1.0, WheelMath.EFFECTIVE_MASS, 0.05, nonIdentity);
        assertEquals(withoutTuning, withTuning,
                "WheelMath.tire's VehicleTuning overload must not change the result, identity or not");
    }
}
