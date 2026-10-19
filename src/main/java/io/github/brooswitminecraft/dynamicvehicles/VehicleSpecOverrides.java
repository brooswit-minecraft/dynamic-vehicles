package io.github.brooswitminecraft.dynamicvehicles;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-car config files (MINECRAFT-443): {@code config/dynamicvehicles/cars/<id>.json} overrides a subset
 * of a stock {@link VehicleSpec}'s already-implemented tuning fields &mdash; ride height, damping rate,
 * anti-roll scale, rolling-resistance scale, and the five {@link VehicleSpec.VehicleTuning} dials (LSD
 * lock, front/rear torque split, torque-vectoring gain, camber, toe). Everything else about a car
 * (geometry, mass, seats, drive force, top speed, tire/downforce tuning) is not file-configurable by this
 * ticket; see {@code docs/per-car-config.md} for the full rationale and schema.
 *
 * <p><b>Loaded once, at server start</b> ({@link #load}, called from a {@code ServerAboutToStartEvent}
 * listener, before any level loads) &mdash; not re-read while the server runs. A file edited while the
 * server is up takes effect on the next start. This is narrower than {@code CarConfig}'s own existing
 * {@code toml} values, most of which NeoForge's {@code ModConfigSpec} already re-reads live on every
 * {@code .get()} call (an edited file takes effect without a restart) &mdash; this loader's start-only
 * behaviour is a deliberate, new scope boundary unique to this per-car format (see MINECRAFT-443's ticket
 * discussion), not a claim that every other config value in this mod already behaved this way.
 *
 * <p><b>Server-authoritative, not synced</b>: {@link CarEntity} only consults {@link #resolve} on its
 * logical-server side ({@code !level.isClientSide()}, which includes a singleplayer world's own
 * integrated server); a logical client always uses the plain stock spec, both because physics are
 * server-decided and reach it through ordinary entity-state sync regardless, and because this table is a
 * single process-global map that must never leak a previous singleplayer session's overrides into a
 * later remote-server connection made from the same client process.
 *
 * <p>Overriding {@code rideHeight} also scales {@code restLength} by the same ratio, so the suspension's
 * own rest geometry moves with it instead of leaving the car floating at its old height or a spring that
 * cannot reach the new one.
 *
 * <p>Missing file, a missing field within a file, or a file that fails to parse at all each fall back to
 * the car's stock default: missing/absent fields keep that field's stock value; a file that throws any
 * parse exception is rejected in its entirety (logged once, car runs stock) rather than guessing which
 * half of a corrupt file to trust.
 */
final class VehicleSpecOverrides {
    // Plain slf4j, not com.mojang.logging.LogUtils (which this class's own unit tests would then need the
    // real Minecraft/NeoForge runtime jar on their classpath just to class-load) -- Minecraft's log4j2
    // binding picks this logger up exactly the same as every LogUtils.getLogger() call elsewhere in the
    // mod, since LogUtils itself is nothing more than LoggerFactory.getLogger with a fixed name.
    private static final Logger LOGGER = LoggerFactory.getLogger("dynamicvehicles/VehicleSpecOverrides");
    private static final Gson GSON = new Gson();

    /** Populated by {@link #load}; empty (so every {@link #resolve} falls through to the stock default)
     * until then, which is also exactly what every unit test that never calls {@link #load} observes. */
    private static final Map<String, VehicleSpec> OVERRIDES = new ConcurrentHashMap<>();

    private VehicleSpecOverrides() {}

    /** The stock {@link VehicleSpec} for every car id this ticket migrates, keyed exactly as the entity
     * registry names them (see {@code DynamicVehiclesMod}'s {@code ENTITIES.register} calls) &mdash; the
     * set {@link #load} looks for a {@code <id>.json} for. */
    private static final Map<String, VehicleSpec> STOCK_DEFAULTS = Map.ofEntries(
            Map.entry(VehicleIds.CAR, VehicleSpec.CAR),
            Map.entry(VehicleIds.TRUCK, VehicleSpec.TRUCK),
            Map.entry(VehicleIds.TROPHY_TRUCK, VehicleSpec.TROPHY),
            Map.entry(VehicleIds.DRIFT_CAR, VehicleSpec.DRIFT),
            Map.entry("muscle_car", VehicleSpec.MUSCLE),
            Map.entry("rock_crawler", VehicleSpec.ROCK_CRAWLER),
            Map.entry("monster_truck", VehicleSpec.MONSTER_TRUCK),
            Map.entry("indy_car", VehicleSpec.INDY),
            Map.entry("bus", VehicleSpec.BUS),
            Map.entry("cargo_truck", VehicleSpec.CARGO_TRUCK));

    /**
     * Reads {@code <configDir>/dynamicvehicles/cars/<id>.json} for every known car id and replaces the
     * current override table. Call once, at server start. Never throws: any per-file problem (missing
     * file, unreadable file, malformed JSON) is logged and that one car simply keeps its stock default;
     * one bad file never affects the other cars.
     */
    static void load(Path configDir) {
        Map<String, VehicleSpec> resolved = new ConcurrentHashMap<>();
        Path carsDir = configDir.resolve("dynamicvehicles").resolve("cars");
        for (Map.Entry<String, VehicleSpec> entry : STOCK_DEFAULTS.entrySet()) {
            String id = entry.getKey();
            VehicleSpec stock = entry.getValue();
            Path file = carsDir.resolve(id + ".json");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String json = Files.readString(file);
                VehicleCarConfigFile parsed = GSON.fromJson(json, VehicleCarConfigFile.class);
                if (parsed != null) {
                    resolved.put(id, applyOverride(stock, parsed));
                    LOGGER.info("dynamicvehicles: loaded per-car config override for '{}'", id);
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("dynamicvehicles: failed to load {} ({}); '{}' keeps its stock defaults",
                        file, e.getMessage(), id);
            }
        }
        OVERRIDES.clear();
        OVERRIDES.putAll(resolved);
    }

    /** The spec to actually use for car id {@code id}: its loaded-and-validated override if {@link #load}
     * found and parsed one, otherwise exactly {@code stockDefault} (the same object, unchanged). */
    static VehicleSpec resolve(String id, VehicleSpec stockDefault) {
        return OVERRIDES.getOrDefault(id, stockDefault);
    }

    /** Builds the effective spec: every field from {@code stock} except the ones {@code file} actually
     * set, which are clamped into {@link VehicleSpecLimits}'/{@link VehicleTuningLimits}' safe range
     * (falling back to {@code stock}'s own value for that one field if the supplied number is non-finite). */
    private static VehicleSpec applyOverride(VehicleSpec stock, VehicleCarConfigFile file) {
        double rideHeight = file.rideHeight == null ? stock.rideHeight()
                : VehicleSpecLimits.clamp(file.rideHeight, VehicleSpecLimits.MIN_RIDE_HEIGHT,
                        VehicleSpecLimits.MAX_RIDE_HEIGHT, stock.rideHeight());
        // A rideHeight override must move the suspension's own rest geometry with it, or the car either
        // floats at its old visual height (wheelCentreY only subtracts rideHeight, restLength is untouched)
        // or the spring runs out of travel before reaching the new ride height. Scale restLength by the
        // same ratio rideHeight itself just moved by, which keeps every car's existing ride-height/travel
        // relationship (e.g. the rock crawler's restLength deliberately exceeding its rideHeight) intact
        // under a uniform suspension lift/drop instead of reinventing a per-car travel curve.
        double restLength = rideHeight == stock.rideHeight() ? stock.restLength()
                : stock.restLength() * (rideHeight / stock.rideHeight());
        double dampingRate = file.dampingRate == null ? stock.dampingRate()
                : VehicleSpecLimits.clamp(file.dampingRate, VehicleSpecLimits.MIN_DAMPING_RATE,
                        VehicleSpecLimits.MAX_DAMPING_RATE, stock.dampingRate());
        double antiRollScale = file.antiRollScale == null ? stock.antiRollScale()
                : VehicleSpecLimits.clamp(file.antiRollScale, VehicleSpecLimits.MIN_ANTI_ROLL_SCALE,
                        VehicleSpecLimits.MAX_ANTI_ROLL_SCALE, stock.antiRollScale());
        double rollingScale = file.rollingScale == null ? stock.rollingScale()
                : VehicleSpecLimits.clamp(file.rollingScale, VehicleSpecLimits.MIN_ROLLING_SCALE,
                        VehicleSpecLimits.MAX_ROLLING_SCALE, stock.rollingScale());

        VehicleSpec.VehicleTuning stockTuning = stock.vehicleTuning();
        VehicleSpec.VehicleTuning requestedTuning = new VehicleSpec.VehicleTuning(
                file.lsdLockPercent == null ? stockTuning.lsdLockPercent() : file.lsdLockPercent,
                file.frontTorqueSplit == null ? stockTuning.frontTorqueSplit() : file.frontTorqueSplit,
                file.vectoringGain == null ? stockTuning.vectoringGain() : file.vectoringGain,
                file.camberDeg == null ? stockTuning.camberDeg() : file.camberDeg,
                file.toeDeg == null ? stockTuning.toeDeg() : file.toeDeg);
        VehicleSpec.VehicleTuning vehicleTuning = VehicleTuningLimits.clamp(requestedTuning, stockTuning);

        return new VehicleSpec(stock.halfX(), stock.halfY(), stock.halfZ(),
                stock.mounts(), stock.wheelRadius(), stock.wheelWidth(),
                rideHeight, restLength,
                stock.massKg(), stock.springRate(), dampingRate, stock.maxSpringForce(),
                stock.wheelbase(), stock.maxSpeed(),
                stock.forceScale(), stock.enginePitch(),
                stock.seatY(), stock.seatZ(),
                stock.looseGrip(), rollingScale,
                stock.tireTuning(), stock.downforceGripPerSpeed(), antiRollScale,
                stock.seats(), vehicleTuning);
    }
}
