package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MINECRAFT-443 review fix (comment 48829, "client/lifecycle"): {@link VehicleSpecOverrides#resolveForSide}
 * is the exact side-aware decision {@code CarEntity}'s own constructor makes, extracted as a pure function
 * (a {@code boolean} plus two immutable values, no {@code Level}) so the logical-client isolation fix is
 * directly, headlessly testable -- no Minecraft bootstrap, no GameTest server needed to construct a real
 * client-side {@code Level} (which the headless {@code gameTestServer} this repo's CI uses cannot do at
 * all; it only ever runs a logical server).
 */
class VehicleSpecOverridesResolveForSideTest {

    @BeforeEach
    @AfterEach
    void resetOverrides() {
        VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"), "dynamicvehicles-no-such-config-dir"));
    }

    @Test
    void aLogicalClientIgnoresALoadedOverrideWhileTheServerConsultsIt(@TempDir Path configDir) throws IOException {
        Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
        Files.createDirectories(carsDir);
        Files.writeString(carsDir.resolve(VehicleIds.TRUCK + ".json"), "{\"rideHeight\": 3.0}");
        VehicleSpecOverrides.load(configDir);

        VehicleSpec serverSide = VehicleSpecOverrides.resolveForSide(false, VehicleIds.TRUCK, VehicleSpec.TRUCK);
        VehicleSpec clientSide = VehicleSpecOverrides.resolveForSide(true, VehicleIds.TRUCK, VehicleSpec.TRUCK);

        // The override is genuinely loaded (the server side picks it up) -- this is not just "nothing was
        // loaded so of course the client matches stock".
        org.junit.jupiter.api.Assertions.assertEquals(3.0, serverSide.rideHeight(),
                "test setup failed: server side did not pick up the loaded override, so this test cannot "
                        + "actually distinguish client isolation from \"nothing loaded\"");
        assertSame(VehicleSpec.TRUCK, clientSide,
                "logical client must always receive the plain stock spec, never the loaded override");
    }

    @Test
    void withNoOverrideLoadedBothSidesAgreeOnTheExactSameStockInstance() {
        assertSame(VehicleSpec.TRUCK, VehicleSpecOverrides.resolveForSide(false, VehicleIds.TRUCK, VehicleSpec.TRUCK));
        assertSame(VehicleSpec.TRUCK, VehicleSpecOverrides.resolveForSide(true, VehicleIds.TRUCK, VehicleSpec.TRUCK));
    }
}
