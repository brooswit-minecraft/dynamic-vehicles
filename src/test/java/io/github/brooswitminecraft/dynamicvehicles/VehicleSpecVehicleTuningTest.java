package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-227/245: the per-vehicle tuning data model (LSD lock, front/rear torque split, torque-
 * vectoring gain, camber, toe). This story is data and plumbing only -- no vehicle implements any of the
 * five fields yet, so every vehicle on the roster must carry exactly {@link VehicleSpec.VehicleTuning#IDENTITY}
 * and {@code WheelMath.tire}'s overload that accepts a {@code VehicleTuning} must be a mathematical no-op
 * regardless of what tuning is passed to it -- the regression guarantee the epic's hard constraint
 * ("defaults leave every vehicle's handling byte-for-byte unchanged") depends on, the same pattern
 * {@code VehicleSpecIndyTest.everyPreExistingVehicleHasNoDownforceAndAnUnchangedGripMultiplier} and
 * {@code VehicleSpecRockCrawlerAntiRollTest.everyOtherVehicleKeepsAntiRollScaleAtTheIdentityDefault} already
 * check for {@link VehicleSpec#downforceGripPerSpeed} and {@link VehicleSpec#antiRollScale}.
 */
class VehicleSpecVehicleTuningTest {

    /** Every vehicle on the roster, the same set {@code VehicleSpecRockCrawlerAntiRollTest} iterates. */
    private static final VehicleSpec[] EVERY_VEHICLE = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
            VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK,
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
    void everyVehicleOnTheRosterCarriesTheIdentityVehicleTuning() {
        // No vehicle has opted in yet -- this story is data and plumbing only (no LSD/vectoring/camber/toe
        // behaviour), so every existing VehicleSpec constant must still carry exactly IDENTITY here.
        for (VehicleSpec spec : EVERY_VEHICLE) {
            assertTrue(spec.vehicleTuning().isIdentity(),
                    "pre-existing vehicle must carry the identity vehicle tuning");
        }
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
