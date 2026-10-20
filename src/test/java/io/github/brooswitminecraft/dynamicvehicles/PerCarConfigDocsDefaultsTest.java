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
            double camberDeg, double toeDeg) {}

    @Test
    void everyDocumentedCarDefaultMatchesItsVehicleSpec() {
        assertRow("car", VehicleSpec.CAR, new Expected(0.9, 3400.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("truck", VehicleSpec.TRUCK, new Expected(1.3, 5390.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("trophy_truck", VehicleSpec.TROPHY, new Expected(1.6, 2570.0, 1.5, 0.6, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("drift_car", VehicleSpec.DRIFT, new Expected(0.75, 3117.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("muscle_car", VehicleSpec.MUSCLE, new Expected(0.95, 4816.67, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("rock_crawler", VehicleSpec.ROCK_CRAWLER, new Expected(1.9, 5383.33, 2.0, 0.5, 1.0, 0.65, 0.5, 0.0, 0.0, 0.0));
        assertRow("monster_truck", VehicleSpec.MONSTER_TRUCK, new Expected(2.3, 4500.0, 1.0, 0.55, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("indy_car", VehicleSpec.INDY, new Expected(0.5, 1841.67, 1.0, 1.0, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("bus", VehicleSpec.BUS, new Expected(1.0, 11900.0, 1.0, 0.8, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
        assertRow("cargo_truck", VehicleSpec.CARGO_TRUCK, new Expected(1.1, 13600.0, 1.0, 0.7, 1.0, 0.0, 0.5, 0.0, 0.0, 0.0));
    }

    /**
     * MINECRAFT-445: the {@code tireKit} section's documented claim that every car on the roster still
     * ships identity (single-rate, no bump stop) suspension tuning, so adding two-rate damping and bump
     * stops changed no existing car's stock feel. A future ticket that genuinely tunes one of these cars
     * is expected to fail this and update both the doc and this list.
     */
    @Test
    void everyCarStillShipsSingleRateDampingAndNoBumpStop() {
        for (VehicleSpec spec : new VehicleSpec[] {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
                VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK,
                VehicleSpec.INDY, VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK}) {
            assertEquals(VehicleSpec.SuspensionTuning.singleRate(spec.dampingRate()), spec.suspensionTuning(),
                    "suspensionTuning must still be singleRate(dampingRate) (docs/per-car-config.md, tireKit)");
        }
    }

    /**
     * MINECRAFT-445: the {@code tireKit} section's documented claim that no car on the roster steers its
     * rear wheels, so adding rear steering changed no existing car's handling. A rear ratio of exactly
     * 0.0 makes the rear wheels' steer angle exactly 0.0 -- bit for bit the literal the production code
     * used before the field existed.
     */
    @Test
    void everyCarStillShipsWithoutRearSteering() {
        for (VehicleSpec spec : new VehicleSpec[] {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
                VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK,
                VehicleSpec.INDY, VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK}) {
            assertEquals(VehicleSpec.SteeringTuning.IDENTITY, spec.steeringTuning(),
                    "steeringTuning must still be IDENTITY (docs/per-car-config.md, tireKit)");
            assertEquals(0.0, spec.steeringTuning().rearSteerRatio(),
                    "a roster car gained rear steering without the docs table being updated");
        }
    }

    /** MINECRAFT-445: no roster car configures a tire pressure, so every one sits at nominal, where both
     * pressure multipliers are exactly 1.0 and grip/rolling resistance are unchanged. */
    @Test
    void everyCarStillShipsAtNominalTirePressure() {
        for (VehicleSpec spec : new VehicleSpec[] {VehicleSpec.CAR, VehicleSpec.TRUCK, VehicleSpec.TROPHY,
                VehicleSpec.DRIFT, VehicleSpec.MUSCLE, VehicleSpec.ROCK_CRAWLER, VehicleSpec.MONSTER_TRUCK,
                VehicleSpec.INDY, VehicleSpec.BUS, VehicleSpec.CARGO_TRUCK}) {
            assertEquals(WheelMath.NOMINAL_TIRE_PRESSURE_BAR, spec.tirePressureBar(),
                    "a roster car gained a non-nominal tire pressure without the docs table being updated");
        }
    }

    private static void assertRow(String id, VehicleSpec spec, Expected expected) {
        VehicleSpec.VehicleTuning tuning = spec.vehicleTuning();
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
    }
}
