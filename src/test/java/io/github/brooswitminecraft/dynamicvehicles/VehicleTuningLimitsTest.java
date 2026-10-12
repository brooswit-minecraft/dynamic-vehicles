package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Regression coverage for the persisted per-vehicle tuning boundary. */
class VehicleTuningLimitsTest {

    @Test
    void clampsEveryRuntimeDialToTheDocumentedSafeRange() {
        VehicleSpec.VehicleTuning clamped = VehicleTuningLimits.clamp(
                new VehicleSpec.VehicleTuning(5.0, -2.0, 7.0, -80.0, 50.0), VehicleSpec.VehicleTuning.IDENTITY);

        assertEquals(1.0, clamped.lsdLockPercent());
        assertEquals(0.0, clamped.frontTorqueSplit());
        assertEquals(1.0, clamped.vectoringGain());
        assertEquals(-8.0, clamped.camberDeg());
        assertEquals(5.0, clamped.toeDeg());
    }

    @Test
    void nonFiniteSavedValuesFallBackToTheVehicleDefault() {
        VehicleSpec.VehicleTuning defaults = VehicleSpec.ROCK_CRAWLER.vehicleTuning();
        VehicleSpec.VehicleTuning safe = VehicleTuningLimits.clamp(
                new VehicleSpec.VehicleTuning(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        Double.NaN, Double.POSITIVE_INFINITY), defaults);

        assertEquals(defaults, safe);
    }

    @Test
    void untouchedVehicleDefaultsRemainExactlyUnchanged() {
        VehicleSpec[] roster = {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY, VehicleSpec.DRIFT,
                VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK, VehicleSpec.INDY,
                VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK};
        for (VehicleSpec spec : roster) {
            assertEquals(spec.vehicleTuning(), VehicleTuningLimits.clamp(spec.vehicleTuning(), spec.vehicleTuning()),
                    "default tuning must remain an exact identity for " + spec);
        }
    }
}
