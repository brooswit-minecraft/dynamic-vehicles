package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-443: the actual loading-to-production path for per-car config files, through the exact
 * {@link VehicleSpecOverrides#load}/{@link VehicleSpecOverrides#resolve} calls {@code DynamicVehiclesMod}
 * and {@code CarEntity} use &mdash; not just the pure-function clamping {@link VehicleSpecLimits} already
 * covers.
 */
class VehicleSpecOverridesTest {

    /** Every test starts from "nothing loaded" and leaves it that way, since {@link VehicleSpecOverrides}
     * holds its table in a static field shared with the rest of the suite (including, implicitly, any
     * other test that constructs a {@code CarEntity}). */
    @BeforeEach
    @AfterEach
    void resetOverrides() {
        VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"), "dynamicvehicles-no-such-config-dir"));
    }

    @Test
    void beforeLoadIsCalledResolveReturnsTheExactStockInstance() {
        assertSame(VehicleSpec.CAR, VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR));
    }

    @Test
    void aMissingConfigDirLeavesEveryCarOnItsStockDefault(@TempDir Path configDir) {
        VehicleSpecOverrides.load(configDir.resolve("does-not-exist"));

        for (String id : new String[] {VehicleIds.CAR, VehicleIds.TRUCK, "rock_crawler"}) {
            assertSame(stockFor(id), VehicleSpecOverrides.resolve(id, stockFor(id)));
        }
    }

    @Test
    void aMissingFileForOneCarLeavesThatCarOnItsStockDefault(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "truck", "{\"rideHeight\": 9.0}");

        VehicleSpecOverrides.load(configDir);

        assertSame(VehicleSpec.CAR, VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR));
    }

    @Test
    void aValidFileOverridesOnlyTheFieldsItSetsAndClampsThem(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "rock_crawler", """
                {
                  "rideHeight": 1.2,
                  "antiRollScale": 999.0,
                  "lsdLockPercent": 0.9
                }
                """);

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve("rock_crawler", VehicleSpec.ROCK_CRAWLER);

        assertEquals(1.2, resolved.rideHeight());
        // restLength must be re-derived from the actual spring-equilibrium physics (review fix 48903), not
        // scaled by the old restLength's own ratio to the old rideHeight -- see WheelMath.restLengthForRideHeight.
        double expectedRestLength = WheelMath.restLengthForRideHeight(1.2, VehicleSpec.ROCK_CRAWLER.mounts()[0][1],
                VehicleSpec.ROCK_CRAWLER.massKg(), VehicleSpec.ROCK_CRAWLER.springRate(),
                VehicleSpec.ROCK_CRAWLER.mounts().length);
        assertEquals(expectedRestLength, resolved.restLength(), 1e-9);
        // 999.0 is out of VehicleSpecLimits' range -- clamped, not rejected outright.
        assertEquals(VehicleSpecLimits.MAX_ANTI_ROLL_SCALE, resolved.antiRollScale());
        assertEquals(0.9, resolved.vehicleTuning().lsdLockPercent());

        // Every field the file did not mention keeps the stock car's own value, unchanged.
        assertEquals(VehicleSpec.ROCK_CRAWLER.dampingRate(), resolved.dampingRate());
        assertEquals(VehicleSpec.ROCK_CRAWLER.rollingScale(), resolved.rollingScale());
        assertEquals(VehicleSpec.ROCK_CRAWLER.handbrakeScale(), resolved.handbrakeScale());
        assertEquals(VehicleSpec.ROCK_CRAWLER.vehicleTuning().frontTorqueSplit(), resolved.vehicleTuning().frontTorqueSplit());
        assertEquals(VehicleSpec.ROCK_CRAWLER.massKg(), resolved.massKg());
        assertEquals(VehicleSpec.ROCK_CRAWLER.seats(), resolved.seats());
    }

    @Test
    void handbrakeScaleOverridesAndClampsIndependentlyOfTheOtherFields(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "truck", "{\"handbrakeScale\": -3.0}");

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.TRUCK, VehicleSpec.TRUCK);

        // -3.0 is out of VehicleSpecLimits' range -- clamped, not rejected outright.
        assertEquals(VehicleSpecLimits.MIN_HANDBRAKE_SCALE, resolved.handbrakeScale());
        assertEquals(VehicleSpec.TRUCK.rideHeight(), resolved.rideHeight());
    }

    /** MINECRAFT-443 review fix (comment 48913): VehicleSpecLimits' global 0.1 m floor is inside its own
     * documented 0.1-5.0 m range but not usable for CAR's own geometry (halfY 0.5, mount depth 0.4) --
     * must fall back to CAR's exact stock rideHeight/restLength pair entirely, not clamp to 0.1 and feed
     * it to restLengthForRideHeight anyway (which would return a negative, degenerate restLength). */
    @Test
    void aRideHeightBelowThisCarsOwnGeometryFloorFallsBackToStockEntirely(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"rideHeight\": 0.1}");

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR);

        assertEquals(VehicleSpec.CAR.rideHeight(), resolved.rideHeight());
        assertEquals(VehicleSpec.CAR.restLength(), resolved.restLength());
    }

    @Test
    void restLengthStaysStockWhenRideHeightIsNotOverridden(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "rock_crawler", "{\"antiRollScale\": 1.0}");

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve("rock_crawler", VehicleSpec.ROCK_CRAWLER);

        assertEquals(VehicleSpec.ROCK_CRAWLER.restLength(), resolved.restLength());
        assertEquals(VehicleSpec.ROCK_CRAWLER.rideHeight(), resolved.rideHeight());
    }

    @Test
    void nonFiniteFieldsFallBackToTheStockValueInsteadOfPropagatingNanOrInfinity(@TempDir Path configDir)
            throws IOException {
        // JSON has no NaN/Infinity literal, so this is the realistic corrupt-number shape: a value Gson
        // happily parses as a Double but that this loader must still treat as unusable, same as
        // VehicleTuningLimits already does for saved-entity NBT.
        writeCarsFile(configDir, "car", "{\"dampingRate\": 1e400}");

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR);

        assertEquals(VehicleSpec.CAR.dampingRate(), resolved.dampingRate());
    }

    @Test
    void malformedJsonRejectsTheWholeFileAndKeepsTheCarOnStock(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{ not valid json");

        assertDoesNotThrow(() -> VehicleSpecOverrides.load(configDir));

        assertSame(VehicleSpec.CAR, VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR));
    }

    @Test
    void reservedDownstreamSectionsDoNotBreakParsingOrLeakIntoTheSpec(@TempDir Path configDir) throws IOException {
        // The ownership contract (MINECRAFT-443's ticket discussion): future tasks (445-449) will add their
        // own keys under these section names. This loader must tolerate them existing in the file today
        // without implementing, exposing or erroring on any of them.
        writeCarsFile(configDir, "bus", """
                {
                  "rideHeight": 1.5,
                  "tireKit": {"someFutureField": 1.0},
                  "wing": {},
                  "diffuser": {},
                  "computer": {},
                  "engine": {}
                }
                """);

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve("bus", VehicleSpec.BUS);

        assertEquals(1.5, resolved.rideHeight());
        assertEquals(VehicleSpec.BUS.massKg(), resolved.massKg());
    }

    /** Past {@link VehicleSpecLimits#MAX_TIRE_KIT_DAMPING}, so the loader must clamp rather than reject. */
    private static final double OVER_MAX_DAMPING = VehicleSpecLimits.MAX_TIRE_KIT_DAMPING * 10;
    /** In-range tireKit values, written into the file and asserted back out of the resolved spec, so the
     * JSON and its expectation can never drift apart. */
    private static final double REQUESTED_SLOW_REBOUND_DAMPING = 500.0;
    private static final double REQUESTED_BUMP_STOP_TRAVEL = 0.1;
    private static final double REQUESTED_BUMP_STOP_RATE = 60_000.0;
    /** An in-range flat dampingRate override, distinct from every roster car's own stock rate. */
    private static final double REQUESTED_DAMPING_RATE = 5_000.0;
    /** JSON parses this as a Double but it is not finite -- the realistic corrupt-number shape, since JSON
     * has no NaN/Infinity literal. */
    private static final String NON_FINITE_JSON_NUMBER = "1e400";

    @Test
    void tireKitOverridesOnlyTheFieldsItSetsAndClampsThem(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", """
                {
                  "tireKit": {
                    "compressionDampingFast": %s,
                    "reboundDampingSlow": %s,
                    "bumpStopTravel": %s,
                    "bumpStopRate": %s
                  }
                }
                """.formatted(OVER_MAX_DAMPING, REQUESTED_SLOW_REBOUND_DAMPING, REQUESTED_BUMP_STOP_TRAVEL,
                        REQUESTED_BUMP_STOP_RATE));

        VehicleSpecOverrides.load(configDir);
        VehicleSpec.SuspensionTuning tuning = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).suspensionTuning();

        // OVER_MAX_DAMPING is out of VehicleSpecLimits' range -- clamped, not rejected outright.
        assertEquals(VehicleSpecLimits.MAX_TIRE_KIT_DAMPING, tuning.fastCompressionDamping());
        assertEquals(REQUESTED_SLOW_REBOUND_DAMPING, tuning.slowReboundDamping());
        assertEquals(REQUESTED_BUMP_STOP_TRAVEL, tuning.bumpStopTravel());
        assertEquals(REQUESTED_BUMP_STOP_RATE, tuning.bumpStopRate());
        // Fields the tireKit section did not mention keep the stock car's own value.
        assertEquals(VehicleSpec.CAR.suspensionTuning().fastReboundDamping(), tuning.fastReboundDamping());
        assertEquals(VehicleSpec.CAR.suspensionTuning().dampingKneeSpeed(), tuning.dampingKneeSpeed());
    }

    @Test
    void anAbsentTireKitSectionKeepsTheStockSuspensionTuningInstance(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"rideHeight\": 1.0}");

        VehicleSpecOverrides.load(configDir);

        assertSame(VehicleSpec.CAR.suspensionTuning(),
                VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).suspensionTuning());
    }

    @Test
    void overridingDampingRateWithNoTireKitSectionStillActsAsSingleRate(@TempDir Path configDir) throws IOException {
        // A file that only sets the flat dampingRate (no tireKit at all) must still behave as single-rate
        // damping at its NEW dampingRate, not the stock car's old one.
        writeCarsFile(configDir, "car", "{\"dampingRate\": %s}".formatted(REQUESTED_DAMPING_RATE));

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR);

        assertEquals(REQUESTED_DAMPING_RATE, resolved.dampingRate());
        assertEquals(VehicleSpec.SuspensionTuning.singleRate(REQUESTED_DAMPING_RATE), resolved.suspensionTuning());
    }

    @Test
    void nonFiniteTireKitFieldsFallBackToTheStockValue(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car",
                "{\"tireKit\": {\"bumpStopRate\": %s}}".formatted(NON_FINITE_JSON_NUMBER));

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR);

        assertEquals(VehicleSpec.CAR.suspensionTuning().bumpStopRate(), resolved.suspensionTuning().bumpStopRate());
    }

    /** An in-range opposite-phase rear-steer request, written into the file and asserted back out. */
    private static final double REQUESTED_REAR_STEER_RATIO = -0.25;

    @Test
    void rearSteerRatioOverridesAndClampsThroughTheTireKitSection(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car",
                "{\"tireKit\": {\"rearSteerRatio\": %s}}".formatted(REQUESTED_REAR_STEER_RATIO));

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR);

        assertEquals(REQUESTED_REAR_STEER_RATIO, resolved.steeringTuning().rearSteerRatio());
        // Nothing else in the tireKit section was mentioned, so the suspension side keeps its stock
        // VALUES. Deliberately not assertSame: a present tireKit section rebuilds the suspension record
        // from its own per-field stock fallbacks, so the instance is equal but new. Instance identity is
        // only contractual when the section is ABSENT entirely -- see
        // anAbsentTireKitSectionKeepsTheStockSuspensionTuningInstance, which pins that case.
        assertEquals(VehicleSpec.CAR.suspensionTuning(), resolved.suspensionTuning());
    }

    @Test
    void anOutOfRangeRearSteerRatioIsClampedNotRejected(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"tireKit\": {\"rearSteerRatio\": -9.0}}");

        VehicleSpecOverrides.load(configDir);

        assertEquals(VehicleSpecLimits.MIN_REAR_STEER_RATIO,
                VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).steeringTuning().rearSteerRatio());
    }

    @Test
    void aTireKitWithoutRearSteerKeepsTheStockSteeringInstance(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"tireKit\": {\"bumpStopRate\": 1000.0}}");

        VehicleSpecOverrides.load(configDir);

        assertSame(VehicleSpec.CAR.steeringTuning(),
                VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).steeringTuning());
    }

    @Test
    void aNonFiniteRearSteerRatioFallsBackToStock(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car",
                "{\"tireKit\": {\"rearSteerRatio\": %s}}".formatted(NON_FINITE_JSON_NUMBER));

        VehicleSpecOverrides.load(configDir);

        assertEquals(VehicleSpec.CAR.steeringTuning().rearSteerRatio(),
                VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).steeringTuning().rearSteerRatio());
    }

    @Test
    void tirePressureOverridesAndClampsThroughTheTireKitSection(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"tireKit\": {\"tirePressureBar\": 1.1}}");

        VehicleSpecOverrides.load(configDir);

        assertEquals(1.1, VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).tirePressureBar());
    }

    @Test
    void anOutOfRangeTirePressureIsClampedIntoTheSafeRange(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"tireKit\": {\"tirePressureBar\": 0.0}}");

        VehicleSpecOverrides.load(configDir);

        // 0.0 would divide by zero in both pressure multipliers; the loader must clamp it to the floor.
        assertEquals(VehicleSpecLimits.MIN_TIRE_PRESSURE_BAR,
                VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).tirePressureBar());
    }

    @Test
    void aTireKitWithoutTirePressureKeepsTheStockNominalPressure(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"tireKit\": {\"rearSteerRatio\": -0.1}}");

        VehicleSpecOverrides.load(configDir);

        assertEquals(VehicleSpec.CAR.tirePressureBar(),
                VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).tirePressureBar());
    }

    @Test
    void oneCorruptFileDoesNotAffectAnotherCarsValidFile(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{ not valid json");
        writeCarsFile(configDir, "truck", "{\"rideHeight\": 2.0}");

        VehicleSpecOverrides.load(configDir);

        assertSame(VehicleSpec.CAR, VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR));
        assertEquals(2.0, VehicleSpecOverrides.resolve(VehicleIds.TRUCK, VehicleSpec.TRUCK).rideHeight());
    }

    private static VehicleSpec stockFor(String id) {
        return switch (id) {
            case "truck" -> VehicleSpec.TRUCK;
            case "rock_crawler" -> VehicleSpec.ROCK_CRAWLER;
            default -> VehicleSpec.CAR;
        };
    }

    private static void writeCarsFile(Path configDir, String carId, String json) throws IOException {
        Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
        Files.createDirectories(carsDir);
        Files.writeString(carsDir.resolve(carId + ".json"), json);
    }
}
