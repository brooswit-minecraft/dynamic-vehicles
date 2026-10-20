package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-443 review fix (comment 48853): pins the per-car stock-default table in {@code
 * docs/per-car-config.md} against {@link VehicleSpec} itself, for every existing car and every field this
 * ticket's config file can override. If a future ticket changes one of these stock values (an ordinary,
 * expected kind of change), this test fails and says exactly which cell of the doc's table is now stale,
 * instead of the doc silently drifting from the actual shipped defaults.
 */
class PerCarConfigDocsDefaultsTest {

    private record Expected(double rideHeight, double dampingRate, double antiRollScale, double rollingScale,
            double handbrakeScale, double lsdLockPercent, double frontTorqueSplit, double vectoringGain,
            double camberDeg, double toeDeg,
            /** MINECRAFT-446: wing.frontDownforceCoeff/rearDownforceCoeff/dragCoeff. Every existing car is
             * 0.0/0.0/0.4 -- no wing, and the old global air-drag constant unchanged. */
            double frontDownforceCoeff, double rearDownforceCoeff, double dragCoeff) {}

    /** MINECRAFT-444's own four coupling columns. Every car on the roster ships the same shared stock
     * engine, so these are one expected row rather than ten -- but they are still pinned against
     * {@link VehicleSpec} per car, so a future per-car engine would fail here rather than drift. */
    private static final double DOC_ENGINE_INERTIA = 0.18;
    private static final double DOC_ENGINE_PEAK_TORQUE = 930.0;
    private static final double DOC_ENGINE_BRAKING_TORQUE = 55.0;
    private static final double DOC_CLUTCH_TORQUE_CAPACITY = 1200.0;

    @Test
    void everyDocumentedCarDefaultMatchesItsVehicleSpec() {
        assertRow("car", VehicleSpec.CAR, new Expected(0.9, 3400.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("truck", VehicleSpec.TRUCK, new Expected(1.3, 5390.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("trophy_truck", VehicleSpec.TROPHY, new Expected(1.6, 2570.0, 1.5, 0.6, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("drift_car", VehicleSpec.DRIFT, new Expected(0.75, 3117.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("muscle_car", VehicleSpec.MUSCLE, new Expected(0.95, 4816.67, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("rock_crawler", VehicleSpec.ROCK_CRAWLER, new Expected(1.9, 5383.33, 2.0, 0.5, 1.0, 0.65, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("monster_truck", VehicleSpec.MONSTER_TRUCK, new Expected(2.3, 4500.0, 1.0, 0.55, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("indy_car", VehicleSpec.INDY, new Expected(0.5, 1841.67, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("bus", VehicleSpec.BUS, new Expected(1.0, 11900.0, 1.0, 0.8, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
        assertRow("cargo_truck", VehicleSpec.CARGO_TRUCK, new Expected(1.1, 13600.0, 1.0, 0.7, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.4));
    }

    private static void assertRow(String id, VehicleSpec spec, Expected expected) {
        VehicleSpec.VehicleTuning tuning = spec.vehicleTuning();
        VehicleSpec.DrivetrainCoupling coupling = spec.drivetrainCoupling();
        assertEquals(DOC_ENGINE_INERTIA, coupling.engineInertia(), 1e-9, id + ".engineInertia (docs/per-car-config.md)");
        assertEquals(DOC_ENGINE_PEAK_TORQUE, coupling.enginePeakTorque(), 1e-9, id + ".enginePeakTorque (docs/per-car-config.md)");
        assertEquals(DOC_ENGINE_BRAKING_TORQUE, coupling.engineBrakingTorque(), 1e-9, id + ".engineBrakingTorque (docs/per-car-config.md)");
        assertEquals(DOC_CLUTCH_TORQUE_CAPACITY, coupling.clutchTorqueCapacity(), 1e-9, id + ".clutchTorqueCapacity (docs/per-car-config.md)");
        assertEquals(expected.rideHeight(), spec.rideHeight(), 1e-9, id + ".rideHeight (docs/per-car-config.md)");
        assertEquals(expected.dampingRate(), spec.dampingRate(), 1e-9, id + ".dampingRate (docs/per-car-config.md)");
        assertEquals(expected.antiRollScale(), spec.antiRollScale(), 1e-9, id + ".antiRollScale (docs/per-car-config.md)");
        assertEquals(expected.rollingScale(), spec.rollingScale(), 1e-9, id + ".rollingScale (docs/per-car-config.md)");
        assertEquals(expected.handbrakeScale(), spec.handbrakeScale(), 1e-9, id + ".handbrakeScale (docs/per-car-config.md)");
        assertEquals(expected.lsdLockPercent(), tuning.lsdLockPercent(), 1e-9, id + ".lsdLockPercent (docs/per-car-config.md)");
        assertEquals(expected.frontTorqueSplit(), tuning.frontTorqueSplit(), 1e-9, id + ".frontTorqueSplit (docs/per-car-config.md)");
        assertEquals(expected.vectoringGain(), tuning.vectoringGain(), 1e-9, id + ".vectoringGain (docs/per-car-config.md)");
        assertEquals(expected.camberDeg(), tuning.camberDeg(), 1e-9, id + ".camberDeg (docs/per-car-config.md)");
        assertEquals(expected.toeDeg(), tuning.toeDeg(), 1e-9, id + ".toeDeg (docs/per-car-config.md)");
        assertEquals(expected.frontDownforceCoeff(), spec.frontWingDownforceCoeff(), 1e-9, id + ".wing.frontDownforceCoeff (docs/per-car-config.md)");
        assertEquals(expected.rearDownforceCoeff(), spec.rearWingDownforceCoeff(), 1e-9, id + ".wing.rearDownforceCoeff (docs/per-car-config.md)");
        assertEquals(expected.dragCoeff(), spec.wingDragCoeff(), 1e-9, id + ".wing.dragCoeff (docs/per-car-config.md)");
    }
}
