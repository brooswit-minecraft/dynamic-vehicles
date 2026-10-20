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
 * Per-car config files (MINECRAFT-443, wing fields MINECRAFT-446, engine/clutch coupling MINECRAFT-444):
 * {@code config/dynamicvehicles/cars/<id>.json} overrides a subset of a stock {@link VehicleSpec}'s
 * already-implemented tuning fields &mdash; ride height, damping rate, anti-roll scale, rolling-resistance
 * scale, handbrake-strength scale, the five {@link VehicleSpec.VehicleTuning} dials (LSD lock, front/rear
 * torque split, torque-vectoring gain, camber, toe), (MINECRAFT-446) the {@code wing} section's front/
 * rear downforce coefficients and drag coefficient, and (MINECRAFT-444) the four
 * {@link VehicleSpec.DrivetrainCoupling} engine/clutch coupling dials (engine inertia, peak torque, engine
 * braking torque, clutch torque capacity). Everything else about a car (geometry, mass, seats, drive force, top speed, tire/downforce
 * tuning) is not file-configurable by this ticket; see {@code docs/per-car-config.md} for the full
 * rationale and schema.
 *
 * <p><b>The five {@code VehicleTuning} fields only affect a car the first time it is ever saved.</b> They
 * are also this mod's existing, pre-this-ticket per-ENTITY player tuning dials ({@link
 * CarEntity#setVehicleTuning}): {@code CarEntity#addAdditionalSaveData} unconditionally writes {@code
 * Tuning*} NBT tags on every save (whether or not a player ever customized them), and {@code
 * readAdditionalSaveData} always restores from those tags when present, never from the spec's current
 * defaults. So a config file's LSD/split/vectoring/camber/toe values apply to a newly-spawned car (which
 * has no saved tags yet) but NOT retroactively to a car that was ever saved before the file existed or
 * changed &mdash; that car's own saved values keep winning, by the same rule that already makes a
 * player's own in-game retune persist across restarts. This is not a bug this ticket introduces or fixes;
 * it is the pre-existing persistence contract, now documented against this new config path (see {@code
 * VehicleSpecOverridesGameTests#aSavedCarsTuningTagsOutliveALaterConfigOverride} for the regression that
 * pins it). {@code rideHeight}/{@code dampingRate}/{@code antiRollScale}/{@code rollingScale}/{@code
 * handbrakeScale} carry no such per-entity persistence and so do not have this limitation.
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
 * <p>Overriding {@code rideHeight} also derives a new {@code restLength} ({@link
 * WheelMath#restLengthForRideHeight}, from the car's own spring-equilibrium physics, not a simple ratio
 * scale of the stock value), so the car's ACTUAL static resting height ends up equal to the requested
 * {@code rideHeight} -- not merely some other height, floating or sunk relative to what {@link CarRenderer}
 * draws from {@link CarEntity#authoritativeRideHeight()}.
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
                    resolved.put(id, applyOverride(id, stock, parsed));
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

    /**
     * MINECRAFT-443 review fix (comment 48829, "client/lifecycle"): the side-aware decision {@code
     * CarEntity}'s constructor actually makes, extracted here (a plain {@code boolean} plus two immutable
     * values, no {@code Level}) rather than as a method on {@code CarEntity} itself, specifically so it is
     * directly, headlessly unit-testable -- a test referencing {@code CarEntity} at all requires its
     * {@code net.minecraft.world.entity.Entity} superclass to be on the classpath, which this plain
     * {@code test} task's classpath deliberately does not have (see this class's own top javadoc for why
     * every other Minecraft-adjacent dependency here is kept off that classpath too).
     *
     * <p>A logical client ({@code clientSide = true}, including a singleplayer world's own client side)
     * always receives {@code stock}, ignoring whatever {@link #load} populated {@link #OVERRIDES} with; a
     * logical server (including that same singleplayer world's integrated server) consults {@link
     * #resolve}. See this class's own top javadoc for why that isolation matters beyond "physics are
     * server-decided anyway".
     */
    static VehicleSpec resolveForSide(boolean clientSide, String id, VehicleSpec stock) {
        return clientSide ? stock : resolve(id, stock);
    }

    /** Builds the effective spec: every field from {@code stock} except the ones {@code file} actually
     * set, which are clamped into {@link VehicleSpecLimits}'/{@link VehicleTuningLimits}' safe range
     * (falling back to {@code stock}'s own value for that one field if the supplied number is non-finite). */
    private static VehicleSpec applyOverride(String id, VehicleSpec stock, VehicleCarConfigFile file) {
        double requestedRideHeight = file.rideHeight == null ? stock.rideHeight()
                : VehicleSpecLimits.clamp(file.rideHeight, VehicleSpecLimits.MIN_RIDE_HEIGHT,
                        VehicleSpecLimits.MAX_RIDE_HEIGHT, stock.rideHeight());
        // MINECRAFT-443 review fix (comment 48913): VehicleSpecLimits' rideHeight range is car-agnostic,
        // but a value inside that range can still be unusable for THIS car's own geometry -- too low for
        // its own mount depth or body half-height, which WheelMath.restLengthForRideHeight (see its own
        // javadoc) turns into a negative/degenerate restLength rather than a merely-extreme one. Falling
        // back the WHOLE rideHeight/restLength pair to stock (not just clamping harder) for a car whose
        // geometry cannot support the requested value keeps this failure mode identical to every other
        // "reject the whole file" fallback in this loader.
        boolean usable = WheelMath.isUsableRideHeight(requestedRideHeight, stock.halfY(), stock.mounts()[0][1]);
        if (!usable && file.rideHeight != null) {
            LOGGER.warn("dynamicvehicles: '{}' rideHeight {} is not usable for this car's own geometry "
                    + "(halfY {}, mount height {}); keeping its stock rideHeight/restLength",
                    id, requestedRideHeight, stock.halfY(), stock.mounts()[0][1]);
        }
        double rideHeight = usable ? requestedRideHeight : stock.rideHeight();
        // MINECRAFT-443 review fix (comment 48903): a naive ratio-scaled restLength does NOT make the car
        // actually rest at the requested rideHeight -- restLength, mount height and static sag are not
        // related by a common ratio across this roster (confirmed: proportional scaling put a requested
        // 1.8 m CAR override's real resting height at 1.55 m, a 0.25 m sink under the renderer's drawn
        // wheels). WheelMath.restLengthForRideHeight derives restLength from the actual spring-equilibrium
        // physics instead, so the real resting height equals the requested one by construction -- only
        // ever reached once isUsableRideHeight above has already ruled out a negative/degenerate result.
        double restLength = rideHeight == stock.rideHeight() ? stock.restLength()
                : WheelMath.restLengthForRideHeight(rideHeight, stock.mounts()[0][1], stock.massKg(),
                        stock.springRate(), stock.mounts().length);
        double dampingRate = file.dampingRate == null ? stock.dampingRate()
                : VehicleSpecLimits.clamp(file.dampingRate, VehicleSpecLimits.MIN_DAMPING_RATE,
                        VehicleSpecLimits.MAX_DAMPING_RATE, stock.dampingRate());
        double antiRollScale = file.antiRollScale == null ? stock.antiRollScale()
                : VehicleSpecLimits.clamp(file.antiRollScale, VehicleSpecLimits.MIN_ANTI_ROLL_SCALE,
                        VehicleSpecLimits.MAX_ANTI_ROLL_SCALE, stock.antiRollScale());
        double rollingScale = file.rollingScale == null ? stock.rollingScale()
                : VehicleSpecLimits.clamp(file.rollingScale, VehicleSpecLimits.MIN_ROLLING_SCALE,
                        VehicleSpecLimits.MAX_ROLLING_SCALE, stock.rollingScale());
        double handbrakeScale = file.handbrakeScale == null ? stock.handbrakeScale()
                : VehicleSpecLimits.clamp(file.handbrakeScale, VehicleSpecLimits.MIN_HANDBRAKE_SCALE,
                        VehicleSpecLimits.MAX_HANDBRAKE_SCALE, stock.handbrakeScale());

        // Wing downforce/drag (MINECRAFT-446): file.wing itself (not just its individual fields) can be
        // absent -- an omitted "wing" key entirely -- which must behave exactly like one present with every
        // field absent, i.e. every wing field keeps this car's stock value.
        VehicleCarConfigFile.Wing wing = file.wing;
        Double frontDownforceField = wing == null ? null : wing.frontDownforceCoeff;
        Double rearDownforceField = wing == null ? null : wing.rearDownforceCoeff;
        Double dragField = wing == null ? null : wing.dragCoeff;
        double frontWingDownforceCoeff = frontDownforceField == null ? stock.frontWingDownforceCoeff()
                : VehicleSpecLimits.clamp(frontDownforceField, VehicleSpecLimits.MIN_WING_DOWNFORCE_COEFF,
                        VehicleSpecLimits.MAX_WING_DOWNFORCE_COEFF, stock.frontWingDownforceCoeff());
        double rearWingDownforceCoeff = rearDownforceField == null ? stock.rearWingDownforceCoeff()
                : VehicleSpecLimits.clamp(rearDownforceField, VehicleSpecLimits.MIN_WING_DOWNFORCE_COEFF,
                        VehicleSpecLimits.MAX_WING_DOWNFORCE_COEFF, stock.rearWingDownforceCoeff());
        double wingDragCoeff = dragField == null ? stock.wingDragCoeff()
                : VehicleSpecLimits.clamp(dragField, VehicleSpecLimits.MIN_WING_DRAG_COEFF,
                        VehicleSpecLimits.MAX_WING_DRAG_COEFF, stock.wingDragCoeff());

        VehicleSpec.VehicleTuning stockTuning = stock.vehicleTuning();
        VehicleSpec.VehicleTuning requestedTuning = new VehicleSpec.VehicleTuning(
                file.lsdLockPercent == null ? stockTuning.lsdLockPercent() : file.lsdLockPercent,
                file.frontTorqueSplit == null ? stockTuning.frontTorqueSplit() : file.frontTorqueSplit,
                file.vectoringGain == null ? stockTuning.vectoringGain() : file.vectoringGain,
                file.camberDeg == null ? stockTuning.camberDeg() : file.camberDeg,
                file.toeDeg == null ? stockTuning.toeDeg() : file.toeDeg);
        VehicleSpec.VehicleTuning vehicleTuning = VehicleTuningLimits.clamp(requestedTuning, stockTuning);

        VehicleSpec.SuspensionTuning suspensionTuning = applyTireKit(file.tireKit, stock, dampingRate);
        VehicleSpec.SteeringTuning steeringTuning = applySteering(file.tireKit, stock);
        double tirePressureBar = file.tireKit == null || file.tireKit.tirePressureBar == null
                ? stock.tirePressureBar()
                : VehicleSpecLimits.clamp(file.tireKit.tirePressureBar, VehicleSpecLimits.MIN_TIRE_PRESSURE_BAR,
                        VehicleSpecLimits.MAX_TIRE_PRESSURE_BAR, stock.tirePressureBar());

        VehicleSpec.PitchTuning pitchTuning = applyPitch(file.tireKit, stock);

        // Engine/clutch coupling (MINECRAFT-444): same per-field contract as every field above -- absent
        // keeps this car's stock value, non-finite falls back to it, finite-but-extreme is clamped.
        VehicleSpec.DrivetrainCoupling stockCoupling = stock.drivetrainCoupling();
        VehicleSpec.DrivetrainCoupling drivetrainCoupling = new VehicleSpec.DrivetrainCoupling(
                file.engineInertia == null ? stockCoupling.engineInertia()
                        : VehicleSpecLimits.clamp(file.engineInertia, VehicleSpecLimits.MIN_ENGINE_INERTIA,
                                VehicleSpecLimits.MAX_ENGINE_INERTIA, stockCoupling.engineInertia()),
                file.enginePeakTorque == null ? stockCoupling.enginePeakTorque()
                        : VehicleSpecLimits.clamp(file.enginePeakTorque, VehicleSpecLimits.MIN_ENGINE_PEAK_TORQUE,
                                VehicleSpecLimits.MAX_ENGINE_PEAK_TORQUE, stockCoupling.enginePeakTorque()),
                file.engineBrakingTorque == null ? stockCoupling.engineBrakingTorque()
                        : VehicleSpecLimits.clamp(file.engineBrakingTorque, VehicleSpecLimits.MIN_ENGINE_BRAKING_TORQUE,
                                VehicleSpecLimits.MAX_ENGINE_BRAKING_TORQUE, stockCoupling.engineBrakingTorque()),
                file.clutchTorqueCapacity == null ? stockCoupling.clutchTorqueCapacity()
                        : VehicleSpecLimits.clamp(file.clutchTorqueCapacity, VehicleSpecLimits.MIN_CLUTCH_TORQUE_CAPACITY,
                                VehicleSpecLimits.MAX_CLUTCH_TORQUE_CAPACITY, stockCoupling.clutchTorqueCapacity()));

        VehicleSpec.DiffuserTuning diffuserTuning = applyDiffuserOverride(stock.diffuserTuning(), file.diffuser);

        return new VehicleSpec(stock.halfX(), stock.halfY(), stock.halfZ(),
                stock.mounts(), stock.wheelRadius(), stock.wheelWidth(),
                rideHeight, restLength,
                stock.massKg(), stock.springRate(), dampingRate, stock.maxSpringForce(),
                stock.wheelbase(), stock.maxSpeed(),
                stock.forceScale(), stock.enginePitch(),
                stock.seatY(), stock.seatZ(),
                stock.looseGrip(), rollingScale,
                stock.tireTuning(), stock.downforceGripPerSpeed(), antiRollScale,
                stock.seats(), vehicleTuning, handbrakeScale, suspensionTuning, steeringTuning,
                tirePressureBar, pitchTuning,
                frontWingDownforceCoeff, rearWingDownforceCoeff, wingDragCoeff, drivetrainCoupling, diffuserTuning);
    }

    /** Builds this car's effective {@link VehicleSpec.DiffuserTuning} (MINECRAFT-447): {@code stock}
     * unchanged if the file has no {@code "diffuser"} key at all, otherwise {@code stock}'s own value for
     * every field the nested object did not itself set, clamped into {@link DiffuserTuningLimits}' safe
     * range the same way every other field in this loader is. Diffuser tuning carries no per-entity saved
     * state (unlike the five {@code VehicleTuning} fields), so there is no saved-car precedence question
     * here: it always reflects whatever was loaded at the last server start, same as {@code rideHeight}/
     * {@code dampingRate}/{@code antiRollScale}/{@code rollingScale}/{@code handbrakeScale}. */
    private static VehicleSpec.DiffuserTuning applyDiffuserOverride(VehicleSpec.DiffuserTuning stock,
            VehicleCarConfigFile.Diffuser file) {
        if (file == null) {
            return stock;
        }
        VehicleSpec.DiffuserTuning requested = new VehicleSpec.DiffuserTuning(
                file.rollSelfRightingEnabled == null ? stock.rollSelfRightingEnabled() : file.rollSelfRightingEnabled,
                file.rollSelfRightingMaxTorque == null ? stock.rollSelfRightingMaxTorque() : file.rollSelfRightingMaxTorque,
                file.rollSelfRightingGain == null ? stock.rollSelfRightingGain() : file.rollSelfRightingGain,
                file.rollSelfRightingDamping == null ? stock.rollSelfRightingDamping() : file.rollSelfRightingDamping,
                file.pitchLandingEnabled == null ? stock.pitchLandingEnabled() : file.pitchLandingEnabled,
                file.pitchLandingMaxTorque == null ? stock.pitchLandingMaxTorque() : file.pitchLandingMaxTorque,
                file.pitchLandingHorizonSeconds == null ? stock.pitchLandingHorizonSeconds() : file.pitchLandingHorizonSeconds,
                file.pitchLandingGain == null ? stock.pitchLandingGain() : file.pitchLandingGain,
                file.pitchLandingDamping == null ? stock.pitchLandingDamping() : file.pitchLandingDamping,
                file.headingAlignmentEnabled == null ? stock.headingAlignmentEnabled() : file.headingAlignmentEnabled,
                file.headingAlignmentMaxTorque == null ? stock.headingAlignmentMaxTorque() : file.headingAlignmentMaxTorque,
                file.headingAlignmentGain == null ? stock.headingAlignmentGain() : file.headingAlignmentGain,
                file.headingAlignmentDamping == null ? stock.headingAlignmentDamping() : file.headingAlignmentDamping,
                file.headingAlignmentSteerSuppression == null ? stock.headingAlignmentSteerSuppression() : file.headingAlignmentSteerSuppression,
                file.trajectoryAlignmentEnabled == null ? stock.trajectoryAlignmentEnabled() : file.trajectoryAlignmentEnabled,
                file.trajectoryAlignmentMaxTorque == null ? stock.trajectoryAlignmentMaxTorque() : file.trajectoryAlignmentMaxTorque,
                file.trajectoryAlignmentGain == null ? stock.trajectoryAlignmentGain() : file.trajectoryAlignmentGain,
                file.trajectoryAlignmentDamping == null ? stock.trajectoryAlignmentDamping() : file.trajectoryAlignmentDamping,
                file.trajectoryAlignmentMinSpeed == null ? stock.trajectoryAlignmentMinSpeed() : file.trajectoryAlignmentMinSpeed);
        return DiffuserTuningLimits.clamp(requested, stock);
    }

    /** MINECRAFT-445: the {@code tireKit} section's anti-dive/anti-squat fractions, clamped like every
     * other field here. A section that mentions neither keeps this car's stock {@link
     * VehicleSpec.PitchTuning}, instance identity included. */
    private static VehicleSpec.PitchTuning applyPitch(VehicleCarConfigFile.TireKit tireKit, VehicleSpec stock) {
        if (tireKit == null || (tireKit.antiDive == null && tireKit.antiSquat == null)) {
            return stock.pitchTuning();
        }
        VehicleSpec.PitchTuning stockPitch = stock.pitchTuning();
        double antiDive = tireKit.antiDive == null ? stockPitch.antiDive()
                : VehicleSpecLimits.clamp(tireKit.antiDive, VehicleSpecLimits.MIN_PITCH_RESIST,
                        VehicleSpecLimits.MAX_PITCH_RESIST, stockPitch.antiDive());
        double antiSquat = tireKit.antiSquat == null ? stockPitch.antiSquat()
                : VehicleSpecLimits.clamp(tireKit.antiSquat, VehicleSpecLimits.MIN_PITCH_RESIST,
                        VehicleSpecLimits.MAX_PITCH_RESIST, stockPitch.antiSquat());
        return new VehicleSpec.PitchTuning(antiDive, antiSquat);
    }

    /**
     * MINECRAFT-445: the {@code tireKit} section's steering geometry (today, rear-wheel steering), clamped
     * into {@link VehicleSpecLimits}' range exactly like every other field here. An absent {@code tireKit}
     * section, or one that does not mention {@code rearSteerRatio}, keeps this car's stock {@link
     * VehicleSpec.SteeringTuning} -- object identity included, so a car with no steering override is the
     * same instance it always was.
     */
    private static VehicleSpec.SteeringTuning applySteering(VehicleCarConfigFile.TireKit tireKit,
            VehicleSpec stock) {
        if (tireKit == null || tireKit.rearSteerRatio == null) {
            return stock.steeringTuning();
        }
        return new VehicleSpec.SteeringTuning(VehicleSpecLimits.clamp(tireKit.rearSteerRatio,
                VehicleSpecLimits.MIN_REAR_STEER_RATIO, VehicleSpecLimits.MAX_REAR_STEER_RATIO,
                stock.steeringTuning().rearSteerRatio()));
    }

    /**
     * MINECRAFT-445: the {@code tireKit} section's own fields, clamped into {@link VehicleSpecLimits}' safe
     * range exactly like every other field in this loader (falling back to the stock car's own value for a
     * non-finite number). An absent {@code tireKit} section (the common case today: no vehicle's stock
     * {@link VehicleSpec} has opted into two-rate damping or a bump stop) keeps the car's stock {@link
     * VehicleSpec.SuspensionTuning} unchanged, object identity included.
     *
     * @param tireKit the file's own {@code tireKit} section, or {@code null} if absent
     * @param stock this car's stock spec, read for its own {@code suspensionTuning} as the fallback for any
     *        field {@code tireKit} does not set
     * @param dampingRate THIS override's own resolved {@code dampingRate} (already clamped above) -- not
     *        necessarily {@code stock.dampingRate()}, since a file can override {@code dampingRate} and
     *        {@code tireKit} in the same request. A {@code tireKit} field left unset falls back to this
     *        resolved {@code dampingRate}, not the stock one, so e.g. a file that only sets {@code
     *        dampingRate} (no {@code tireKit} at all) still gets {@code fastCompressionDamping}/{@code
     *        slowReboundDamping}/{@code fastReboundDamping} all equal to its own new {@code dampingRate},
     *        exactly the single-rate behaviour {@link VehicleSpec.SuspensionTuning#singleRate} documents.
     */
    private static VehicleSpec.SuspensionTuning applyTireKit(VehicleCarConfigFile.TireKit tireKit,
            VehicleSpec stock, double dampingRate) {
        if (tireKit == null) {
            return dampingRate == stock.dampingRate() ? stock.suspensionTuning()
                    : VehicleSpec.SuspensionTuning.singleRate(dampingRate);
        }
        VehicleSpec.SuspensionTuning stockTuning = dampingRate == stock.dampingRate() ? stock.suspensionTuning()
                : VehicleSpec.SuspensionTuning.singleRate(dampingRate);
        double fastCompressionDamping = tireKit.compressionDampingFast == null ? stockTuning.fastCompressionDamping()
                : VehicleSpecLimits.clamp(tireKit.compressionDampingFast, VehicleSpecLimits.MIN_TIRE_KIT_DAMPING,
                        VehicleSpecLimits.MAX_TIRE_KIT_DAMPING, stockTuning.fastCompressionDamping());
        double slowReboundDamping = tireKit.reboundDampingSlow == null ? stockTuning.slowReboundDamping()
                : VehicleSpecLimits.clamp(tireKit.reboundDampingSlow, VehicleSpecLimits.MIN_TIRE_KIT_DAMPING,
                        VehicleSpecLimits.MAX_TIRE_KIT_DAMPING, stockTuning.slowReboundDamping());
        double fastReboundDamping = tireKit.reboundDampingFast == null ? stockTuning.fastReboundDamping()
                : VehicleSpecLimits.clamp(tireKit.reboundDampingFast, VehicleSpecLimits.MIN_TIRE_KIT_DAMPING,
                        VehicleSpecLimits.MAX_TIRE_KIT_DAMPING, stockTuning.fastReboundDamping());
        double dampingKneeSpeed = tireKit.dampingKneeSpeed == null ? stockTuning.dampingKneeSpeed()
                : VehicleSpecLimits.clamp(tireKit.dampingKneeSpeed, VehicleSpecLimits.MIN_DAMPING_KNEE_SPEED,
                        VehicleSpecLimits.MAX_DAMPING_KNEE_SPEED, stockTuning.dampingKneeSpeed());
        double bumpStopTravel = tireKit.bumpStopTravel == null ? stockTuning.bumpStopTravel()
                : VehicleSpecLimits.clamp(tireKit.bumpStopTravel, VehicleSpecLimits.MIN_BUMP_STOP_TRAVEL,
                        VehicleSpecLimits.MAX_BUMP_STOP_TRAVEL, stockTuning.bumpStopTravel());
        double bumpStopRate = tireKit.bumpStopRate == null ? stockTuning.bumpStopRate()
                : VehicleSpecLimits.clamp(tireKit.bumpStopRate, VehicleSpecLimits.MIN_BUMP_STOP_RATE,
                        VehicleSpecLimits.MAX_BUMP_STOP_RATE, stockTuning.bumpStopRate());
        return new VehicleSpec.SuspensionTuning(fastCompressionDamping, slowReboundDamping, fastReboundDamping,
                dampingKneeSpeed, bumpStopTravel, bumpStopRate);    }
}
