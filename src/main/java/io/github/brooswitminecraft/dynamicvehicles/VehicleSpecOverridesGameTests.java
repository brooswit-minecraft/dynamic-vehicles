package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MINECRAFT-443 review fix (comment 48790): production regressions for a real {@link CarEntity} spawned
 * while a per-car config override is loaded, through the actual production entry point
 * ({@code DynamicVehiclesMod}'s real {@code ServerAboutToStartEvent} listener calls {@link
 * VehicleSpecOverrides#load} once before any level loads; these tests call it again directly, same method,
 * to simulate "a file was present at that moment" without needing to seed the gametest run's own config
 * directory before Gradle starts it). {@link VehicleSpecOverridesTest}'s pure tests already cover the
 * loader's own parsing/clamping/fallback in isolation; what they cannot catch is a bug in how the rest of
 * the mod CONSUMES the resolved spec -- specifically, the reviewed bug: {@link CarRenderer} and {@link
 * CarEntity#hitBlockAhead} used to dispatch model/collision behaviour on {@code spec == VehicleSpec.TRUCK}
 * reference identity, which an override file (even an empty {@code {}}) silently broke, since the
 * resolved spec is then a new, merely-equal-by-value {@link VehicleSpec} instance.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class VehicleSpecOverridesGameTests {
    private VehicleSpecOverridesGameTests() {}

    /** An override file for the truck -- even an empty one, the exact shape of the reviewed regression --
     * must not change which car {@link CarEntity#vehicleId()} reports; that id, not spec identity, is what
     * {@link CarRenderer} and {@link CarEntity#hitBlockAhead} key their model/collision dispatch on. */
    @GameTest(template = "settlement_test")
    public static void anEmptyOverrideFileStillReportsTheRealVehicleId(GameTestHelper helper) {
        withLoadedOverride(VehicleIds.TRUCK, "{}", () -> {
            CarEntity truck = helper.spawn(DynamicVehiclesMod.TRUCK.get(), new BlockPos(5, 3, 5));
            helper.assertTrue(VehicleIds.TRUCK.equals(truck.vehicleId()),
                    "an empty per-car override file changed the truck's reported vehicle id: " + truck.vehicleId());
            // An empty file overrides nothing, so the resolved spec must still equal the stock truck's
            // values even though (by design, see VehicleSpecOverrides' own javadoc) it is a new instance.
            helper.assertTrue(truck.spec().equals(VehicleSpec.TRUCK),
                    "an empty per-car override file changed the truck's resolved spec values");
            helper.succeed();
        });
    }

    /** A real {@code rideHeight} override must move {@code restLength} with it by the same ratio (the
     * reviewed "rideHeight left disconnected from suspension geometry" fix), observed through the actual
     * resolved spec a real entity receives. */
    @GameTest(template = "settlement_test")
    public static void aRideHeightOverrideScalesRestLengthByTheSameRatio(GameTestHelper helper) {
        double requestedRideHeight = VehicleSpec.ROCK_CRAWLER.rideHeight() / 2.0;
        withLoadedOverride(VehicleIds.ROCK_CRAWLER, "{\"rideHeight\": " + requestedRideHeight + "}", () -> {
            VehicleSpec resolved = VehicleSpecOverrides.resolve(VehicleIds.ROCK_CRAWLER, VehicleSpec.ROCK_CRAWLER);
            double expectedRestLength = VehicleSpec.ROCK_CRAWLER.restLength() * 0.5;
            helper.assertTrue(Math.abs(resolved.restLength() - expectedRestLength) < 1e-9,
                    "restLength did not scale with the rideHeight override: expected " + expectedRestLength
                            + " got " + resolved.restLength());
            helper.succeed();
        });
    }

    /** Writes {@code <tmp>/dynamicvehicles/cars/<id>.json}, calls the real {@link VehicleSpecOverrides#load}
     * against that directory (the same method the production {@code ServerAboutToStartEvent} listener
     * calls), runs {@code body}, then always resets the override table back to empty so this test's
     * override cannot leak into an unrelated test sharing the same gametest server process. */
    private static void withLoadedOverride(String carId, String json, Runnable body) {
        try {
            Path configDir = Files.createTempDirectory("dynamicvehicles-gametest-config");
            Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
            Files.createDirectories(carsDir);
            Files.writeString(carsDir.resolve(carId + ".json"), json);
            VehicleSpecOverrides.load(configDir);
            body.run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            VehicleSpecOverrides.load(Path.of(System.getProperty("java.io.tmpdir"), "dynamicvehicles-no-such-config-dir"));
        }
    }
}
