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
    void aDiffuserSectionOverridesOnlyTheFieldsItSetsAndClampsThem(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", """
                {
                  "diffuser": {
                    "rollSelfRightingEnabled": true,
                    "rollSelfRightingMaxTorque": 5000.0,
                    "headingAlignmentSteerSuppression": 7.0
                  }
                }
                """);

        VehicleSpecOverrides.load(configDir);
        VehicleSpec.DiffuserTuning resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR).diffuserTuning();

        assertEquals(true, resolved.rollSelfRightingEnabled());
        assertEquals(5000.0, resolved.rollSelfRightingMaxTorque());
        // 7.0 is out of DiffuserTuningLimits' 0-1 range for this field -- clamped, not rejected outright.
        assertEquals(DiffuserTuningLimits.MAX_STEER_SUPPRESSION, resolved.headingAlignmentSteerSuppression());
        // Fields the file never mentioned stay on CAR's own stock diffuser tuning (IDENTITY).
        assertEquals(VehicleSpec.DiffuserTuning.IDENTITY.pitchLandingMaxTorque(), resolved.pitchLandingMaxTorque());
        assertEquals(VehicleSpec.DiffuserTuning.IDENTITY.trajectoryAlignmentEnabled(), resolved.trajectoryAlignmentEnabled());
    }

    @Test
    void anAbsentDiffuserKeyLeavesTheCarOnIdentityDiffuserTuning(@TempDir Path configDir) throws IOException {
        writeCarsFile(configDir, "car", "{\"rideHeight\": 1.2}");

        VehicleSpecOverrides.load(configDir);
        VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.CAR, VehicleSpec.CAR);

        assertEquals(VehicleSpec.DiffuserTuning.IDENTITY, resolved.diffuserTuning());
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
