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

    /** A real {@code rideHeight} override must derive a {@code restLength} that makes the car's actual
     * spring-equilibrium resting height equal the requested value (review fix 48903; a naive ratio-scaled
     * restLength does not), observed through a real {@link CarEntity}'s own resolved {@link VehicleSpec}
     * -- not by calling {@code resolve} directly -- so this exercises the exact constructor path
     * {@code CarEntity} uses in production. */
    @GameTest(template = "settlement_test")
    public static void aRideHeightOverrideDerivesARestLengthForThatActualRestingHeight(GameTestHelper helper) {
        double requestedRideHeight = VehicleSpec.ROCK_CRAWLER.rideHeight() / 2.0;
        withLoadedOverride(VehicleIds.ROCK_CRAWLER, "{\"rideHeight\": " + requestedRideHeight + "}", () -> {
            CarEntity crawler = helper.spawn(DynamicVehiclesMod.ROCK_CRAWLER.get(), new BlockPos(5, 3, 5));
            double expectedRestLength = WheelMath.restLengthForRideHeight(requestedRideHeight,
                    VehicleSpec.ROCK_CRAWLER.mounts()[0][1], VehicleSpec.ROCK_CRAWLER.massKg(),
                    VehicleSpec.ROCK_CRAWLER.springRate(), VehicleSpec.ROCK_CRAWLER.mounts().length);
            helper.assertTrue(Math.abs(crawler.spec().rideHeight() - requestedRideHeight) < 1e-9,
                    "spawned rock crawler did not receive the overridden rideHeight");
            helper.assertTrue(Math.abs(crawler.spec().restLength() - expectedRestLength) < 1e-9,
                    "restLength was not derived from the actual spring-equilibrium physics: expected "
                            + expectedRestLength + " got " + crawler.spec().restLength());
            // MINECRAFT-443 review fix (comment 48876): CarRenderer must never draw from spec.rideHeight()
            // (always stock on a logical client) -- authoritativeRideHeight() is the synced value it
            // actually uses, and on this same logical server it must already equal the override.
            helper.assertTrue(Math.abs(crawler.authoritativeRideHeight() - requestedRideHeight) < 1e-5,
                    "authoritativeRideHeight() did not reflect the rideHeight override: expected "
                            + requestedRideHeight + " got " + crawler.authoritativeRideHeight());
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-443 review fix (comment 48812, gap 2): a per-car config override to
     * {@code lsdLockPercent}/etc. must NOT retroactively change a car that was already saved before the
     * override existed -- {@code CarEntity} always writes {@code Tuning*} NBT tags on every save (the
     * pre-existing per-entity player-tuning persistence, {@link CarEntity#setVehicleTuning}) and always
     * restores from them when present, never from the spec's current defaults. This pins that documented
     * precedence as a regression: the OLD saved tag keeps winning over a NEWER override, while a
     * brand-new car (no saved tag yet) DOES pick up that same override.
     */
    @GameTest(template = "settlement_test")
    public static void aSavedCarsTuningTagsOutliveALaterConfigOverride(GameTestHelper helper) {
        // No override loaded yet: this car's tuning is the plain stock default, and saving it bakes that
        // stock value into NBT tags exactly as it would for any car saved before this ticket existed.
        CarEntity savedBeforeOverride = helper.spawn(DynamicVehiclesMod.ROCK_CRAWLER.get(), new BlockPos(5, 3, 5));
        double stockLsdLock = VehicleSpec.ROCK_CRAWLER.vehicleTuning().lsdLockPercent();
        helper.assertTrue(savedBeforeOverride.vehicleTuning().lsdLockPercent() == stockLsdLock,
                "test setup assumption failed: spawned car did not start at the stock LSD lock");
        net.minecraft.nbt.CompoundTag savedTag = new net.minecraft.nbt.CompoundTag();
        savedBeforeOverride.addAdditionalSaveData(savedTag);

        double overriddenLsdLock = Math.min(1.0, stockLsdLock + 0.2);
        withLoadedOverride(VehicleIds.ROCK_CRAWLER, "{\"lsdLockPercent\": " + overriddenLsdLock + "}", () -> {
            // A brand-new car (no saved tag) DOES pick up the override, through the exact same
            // CarEntity constructor path the identity/rideHeight tests above exercise.
            CarEntity spawnedAfterOverride = helper.spawn(DynamicVehiclesMod.ROCK_CRAWLER.get(), new BlockPos(5, 3, 9));
            helper.assertTrue(Math.abs(spawnedAfterOverride.vehicleTuning().lsdLockPercent() - overriddenLsdLock) < 1e-9,
                    "a newly-spawned car did not pick up the per-car config override");

            // The car saved BEFORE the override existed must reload at its own saved (stock) value, not
            // the new override -- restoring its saved tag is exactly what a real server restart does.
            savedBeforeOverride.readAdditionalSaveData(savedTag);
            helper.assertTrue(savedBeforeOverride.vehicleTuning().lsdLockPercent() == stockLsdLock,
                    "a previously-saved car's LSD lock changed to the new override instead of keeping its saved value: expected "
                            + stockLsdLock + " got " + savedBeforeOverride.vehicleTuning().lsdLockPercent());
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-443 review fix (comment 48853): proves the actual production lifecycle wiring, not just
     * the loader function in isolation. Every other test in this class calls {@link
     * VehicleSpecOverrides#load} directly, which the review correctly pointed out cannot catch a
     * misregistered listener, the wrong event, or the wrong config path -- those bugs would leave every
     * other test here green. This test instead:
     * <ol>
     * <li>writes a fixture file into the REAL {@code FMLPaths.CONFIGDIR} (not a temp directory);</li>
     * <li>posts a real {@code ServerAboutToStartEvent} to the real {@code NeoForge.EVENT_BUS} --
     * {@code DynamicVehiclesMod}'s constructor already subscribed its production listener to exactly this
     * event type, on this exact bus, when the mod loaded; posting here invokes that same listener, not a
     * copy of it;</li>
     * <li>spawns a brand-new {@link CarEntity} and confirms it reflects the fixture.</li>
     * </ol>
     * A saved car's {@link VehicleSpec} is resolved through this exact same constructor path (a car is
     * always {@code new CarEntity(type, level)} first, whether freshly placed or deserialized from a
     * save; {@code readAdditionalSaveData} only restores position/health/tuning-tag state afterward, never
     * re-derives {@code spec}) -- so this also covers "before saved-car construction": there is no
     * separate code path for saved construction that this could miss.
     *
     * <p>What this does NOT prove: that {@code ServerAboutToStartEvent} fires before level loading in a
     * REAL server boot sequence -- that ordering is NeoForge/vanilla's own, verified by reading {@code
     * ServerAboutToStartEvent}'s own javadoc ("Called before the server begins loading anything") rather
     * than re-testable from this mod's code. What was testable and worth testing is that THIS mod's
     * listener is actually registered to THAT event, actually reads the real config path, and actually
     * reaches new {@code CarEntity} construction -- which a removed/misregistered/wrong-path listener
     * would now fail.
     */
    @GameTest(template = "settlement_test")
    public static void theRealRegisteredStartupListenerLoadsFromTheRealConfigDirBeforeNewCarConstruction(
            GameTestHelper helper) {
        java.nio.file.Path realConfigDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        java.nio.file.Path realCarsDir = realConfigDir.resolve("dynamicvehicles").resolve("cars");
        java.nio.file.Path fixtureFile = realCarsDir.resolve(VehicleIds.BUS + ".json");
        // A value nothing else in this suite uses for the bus, so a stale table from an earlier test
        // (which only ever touches truck/rock_crawler ids) cannot be mistaken for this fixture working.
        double fixtureRollingScale = 0.42;
        // MINECRAFT-443 review fix (comment 48876): this test mutates the REAL config dir and the REAL
        // override table (that is the whole point -- it is what proves the production wiring), so it
        // must leave both exactly as it found them, not just "empty"/"no fixture": a pre-existing
        // bus.json (however unlikely in a fresh gametest run, but not impossible on a reused one) is
        // read back and restored byte-for-byte rather than deleted, and the table is restored by
        // re-running the real loader against the real dir in whatever state that leaves it -- never
        // wiped to "nothing loaded", which would be a regression for any other car's real fixture.
        boolean fixtureExistedBefore = Files.isRegularFile(fixtureFile);
        // MINECRAFT-443 review fix (comment 48891): a genuine byte snapshot (not a String decode/encode
        // round trip, which is not actually "byte-for-byte" for an arbitrary pre-existing file) --
        // read BEFORE this method writes anything, so a read failure here aborts before any mutation.
        byte[] previousFixtureBytes = fixtureExistedBefore ? readBytesOrFail(fixtureFile) : null;
        RuntimeException testFailure = null;
        try {
            if (!fixtureExistedBefore) {
                Files.createDirectories(realCarsDir);
            }
            Files.writeString(fixtureFile, "{\"rollingScale\": " + fixtureRollingScale + "}");

            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.server.ServerAboutToStartEvent(helper.getLevel().getServer()));

            CarEntity bus = helper.spawn(DynamicVehiclesMod.BUS.get(), new BlockPos(5, 3, 5));
            helper.assertTrue(Math.abs(bus.spec().rollingScale() - fixtureRollingScale) < 1e-9,
                    "posting the real ServerAboutToStartEvent through the real registered listener did not "
                            + "load the fixture from the real config dir before this new CarEntity was constructed: "
                            + "expected rollingScale " + fixtureRollingScale + " got " + bus.spec().rollingScale());
        } catch (IOException e) {
            testFailure = new RuntimeException(e);
        } catch (RuntimeException e) {
            // Covers a failed helper.assertTrue above: caught here (rather than left to propagate past
            // this method) only so restoration below still runs and a restoration failure can be attached
            // to THIS exception as suppressed, instead of a finally-block throw silently replacing it.
            testFailure = e;
        }

        // MINECRAFT-443 review fix (comment 48891): a failed restoration must fail this GameTest, not be
        // swallowed -- a silently-kept 0.42 fixture overwriting whatever an operator's real bus.json held
        // is exactly the destructive-fixture risk the review flagged. The table is reloaded from the real
        // dir ONLY after restoration is confirmed to have actually succeeded, never unconditionally.
        try {
            if (fixtureExistedBefore) {
                Files.write(fixtureFile, previousFixtureBytes);
            } else {
                Files.delete(fixtureFile);
            }
        } catch (IOException restoreFailure) {
            RuntimeException wrapped = new RuntimeException(
                    "failed to restore " + fixtureFile + " to its pre-test state; it may still hold this "
                            + "test's 0.42 fixture instead of its real prior content", restoreFailure);
            if (testFailure != null) {
                testFailure.addSuppressed(wrapped);
                throw testFailure;
            }
            throw wrapped;
        }
        VehicleSpecOverrides.load(realConfigDir);
        if (testFailure != null) {
            throw testFailure;
        }
        helper.succeed();
    }

    /** Config must reach a real entity independently per vehicle type, including reconstruction from NBT. */
    @GameTest(template = "settlement_test")
    public static void computerAssistConfigReachesNewAndReloadedCarsWithoutLeakingToTruck(GameTestHelper helper) {
        withLoadedOverride(VehicleIds.CAR, "{\"computer\":{\"tractionControlLevel\":0.75,\"absLevel\":0.5}}", () -> {
            CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(5, 3, 5));
            helper.assertTrue(car.spec().computerTuning().tractionControlLevel() == 0.75,
                    "real car constructor did not consume configured TC authority");
            helper.assertTrue(car.spec().computerTuning().absLevel() == 0.5,
                    "real car constructor did not consume configured ABS authority");
            CarEntity truck = helper.spawn(DynamicVehiclesMod.TRUCK.get(), new BlockPos(5, 3, 9));
            helper.assertTrue(truck.spec().computerTuning().equals(VehicleSpec.TRUCK.computerTuning()),
                    "car-specific assist configuration leaked to truck");
            net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
            car.addAdditionalSaveData(saved);
            CarEntity restored = new CarEntity(DynamicVehiclesMod.CAR.get(), helper.getLevel());
            restored.readAdditionalSaveData(saved);
            helper.assertTrue(restored.spec().computerTuning().equals(car.spec().computerTuning()),
                    "NBT reconstruction lost the configured assist authority");
            helper.succeed();
        });
    }

    private static byte[] readBytesOrFail(java.nio.file.Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException("failed to snapshot pre-existing " + file + " before this test "
                    + "overwrites it; refusing to proceed without a restorable backup", e);
        }
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
