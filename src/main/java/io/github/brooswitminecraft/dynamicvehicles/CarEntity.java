package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The first vehicle: a 4-wheel car. Server-authoritative. The rider's
 * forward/strafe input drives a Sable rigid body ({@link SableCarBody}); each
 * tick every wheel reports its slip to Dynamic Terrain for the block beneath
 * it, and the car never modifies terrain itself.
 */
public class CarEntity extends Entity {
    /** Wheel offsets from the car centre: x = right, z = forward, in metres. */
    static final double MASS_KG = 1200.0;
    private static final int WHEEL_COUNT = 4;
    private static final double DT = 1.0 / 20.0;

    /** The body's orientation, synced every tick so clients can draw pitch and roll. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<org.joml.Quaternionf> DATA_ORIENTATION =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.QUATERNION);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> DATA_RIGID_BODY =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    // Client side: the last two orientations received, to interpolate between ticks.
    // Client side: received movement and orientation states wait here and are consumed one per tick, so uneven
    // packet arrival (two updates in one tick, none in the next) does not show up as stutter.
    private final java.util.ArrayDeque<double[]> movementQueue = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<org.joml.Quaternionf> orientationQueue = new java.util.ArrayDeque<>();
    private final double[] stepSamples = new double[20];
    private int stepIndex;
    private int arrivalsSinceTick;
    private int zeroArrivalTicks;
    private int multiArrivalTicks;
    private int movementStarved;
    private int orientationStarved;
    private final org.joml.Quaternionf previousOrientation = new org.joml.Quaternionf();
    private final org.joml.Quaternionf currentOrientation = new org.joml.Quaternionf();

    /** Headlight mode: 0 auto (on at night and in rain), 1 on, 2 off. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> DATA_LIGHTS =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    /** MINECRAFT-394: the radio's currently selected station URL, or "" for Off. Every client connects
     * directly to this URL itself and plays it locally, positioned on this car; the server never touches
     * the audio. See {@link #cycleRadio()} for how this is chosen. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<String> DATA_RADIO_URL =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_THROTTLE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_SLIP =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    /** MINECRAFT-401: engine RPM fraction 0..1 between {@link DrivetrainMath#IDLE_RPM} and
     * {@link DrivetrainMath#LIMITER_RPM}, so the client's drivetrain-derived engine sound hears actual
     * manual/automatic shifts instead of only road speed. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_RPM =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    /** Per-wheel suspension travel, steer angle and spin rate (MINECRAFT-122), packed by {@link WheelSync}. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<CompoundTag> DATA_WHEELS =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.COMPOUND_TAG);
    /** MINECRAFT-443 review fix (comment 48876): this car's AUTHORITATIVE (server-resolved, override-
     * aware) ride height, synced so {@link CarRenderer} draws wheels/body at the height the server's
     * physics actually used -- never the client's own {@link #spec}, which {@link
     * VehicleSpecOverrides#resolveForSide} always keeps at the plain stock value (a logical client never
     * reads the per-car config override table at all, by design: see that method's own javadoc for why).
     * Without this, a rideHeight override made a car's wheels visually float or sink by exactly the
     * override's delta, since the renderer would draw the stock wheel offset under a body the server
     * actually suspended at a different height. Static for this car's whole lifetime (set once, here,
     * never updated per-tick), unlike every other {@code DATA_*} field in this class. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_RIDE_HEIGHT =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    // Client side: the last two received per-wheel frames, for renderOrientation-style interpolation.
    // Zero-length default (no wheels known yet) rather than a guessed wheel count, so an empty tag
    // decodes to a frame the renderer can tell apart from "not synced yet" if it ever needs to.
    private float[] previousWheels = new float[0];
    private float[] currentWheels = new float[0];
    /** Client: horizontal speed in m/s from the movement actually shown, and the sound loops for this car. */
    private double clientSpeed;
    /** Client: smoothed 0..1 "rev" that chases throttle input (see {@link CarSoundMath#nextRev}), so the
     * engine sound reads throttle immediately instead of waiting on speed to catch up. */
    private double clientRev;
    private Object clientSounds;
    /** Client: this car's radio audio, started/stopped as the synced station and on/off state change; see
     * {@link CarRadioClient}. */
    private Object clientRadio;
    /** Server: which station is selected -- {@link RadioStationIndex#OFF} or an index into the live
     * {@code CarConfig.RADIO_STATIONS} list, as of the last {@link #cycleRadio()} call. */
    private int radioIndex = RadioStationIndex.OFF;
    private double lastTickSpeed;
    private int impactCooldown;
    private boolean wasHonking;
    /** Wheel shifter-paddle state (MINECRAFT-167), reported by {@link WheelPaddlesPayload}; independent of jump. */
    private boolean wheelHonk;
    private boolean wheelHandbrake;
    /** Explicit drivetrain gear (MINECRAFT-228): which direction the throttle may drive the car from a
     * standstill. Starts in the default forward gear so a driver who never touches the new reverse input
     * sees no change. */
    private CarPhysics.Gear gear = CarPhysics.Gear.DEFAULT_FORWARD;
    /** Server: unit horizontal direction of recent travel, and the headlamp light block this car holds. */
    private net.minecraft.world.phys.Vec3 heading;
    private net.minecraft.core.BlockPos lampBlock;

    /** Server: remaining health, 0..{@link CarHealth#MAX}; see {@link CarHealth} for the thresholds. */
    private float health = CarHealth.MAX;

    private org.joml.Quaternionf savedOrientation;
    /** The Sable rigid body, as Object so this class never loads Sable types (see SableCompat). */
    private Object sableBody;
    private double forcedThrottle;
    private double forcedSteer;
    private int forcedDriveTicks;

    private final VehicleSpec spec;
    /** MINECRAFT-443: this car's registry id (see {@link VehicleIds}), kept alongside {@link #spec}
     * precisely because {@code spec} is no longer always {@code ==} one of {@link VehicleSpec}'s stock
     * constants once a per-car config override exists for it. {@link CarRenderer} and this class's own
     * model/collision-privilege branches ({@code hitBlockAhead}) must key off this id, never off spec
     * identity &mdash; keying off identity meant an override file for the truck (even an empty one) made
     * {@code spec == VehicleSpec.TRUCK} silently false, which both picked the generic renderer fallback
     * model and (more seriously) granted the truck's deliberately-narrow vegetation-only collision
     * privilege to every other block type instead. */
    private final String vehicleId;
    /** Per-entity tuning, initialized from the immutable vehicle type then retained in this entity's NBT. */
    private VehicleSpec.VehicleTuning vehicleTuning;
    /** MINECRAFT-447: {@code null} (the normal case) means "use {@code spec.diffuserTuning()}" -- every
     * existing car, unchanged. Non-null only for a GameTest exercising a non-identity Diffuser KIT tuning
     * on a real, otherwise-stock entity without touching that car's own immutable {@link VehicleSpec}
     * constant (which {@code spec} is keyed to by entity type, at construction, and must stay exactly as
     * every other vehicle's stock identity) -- not NBT-persisted, not settable through any command, and not
     * a second product-facing config path: see {@link #setDiffuserTuningForTest}. */
    private VehicleSpec.DiffuserTuning diffuserTuningOverride;
    /** Which seat each current passenger sits in (MINECRAFT-172); seat 0 is always the driver. */
    private final SeatAssignment<Entity> seats;
    /** Saved UUID-to-seat assignments, consumed as vanilla reconstructs passenger entities on load. */
    private final java.util.Map<java.util.UUID, Integer> savedSeatAssignments = new java.util.HashMap<>();
    /** A departing passenger's own dismount point, frozen by {@link #removePassenger} for the
     * {@link #getDismountLocationForPassenger} lookup that follows it moments later (see that method's
     * javadoc) -- entries are consumed (removed) as soon as that lookup reads them. */
    private final java.util.Map<Entity, Vec3> pendingDismounts = new java.util.HashMap<>();
    /** How long this vehicle has been stopped, for auto-dismounting a boarded mob (MINECRAFT-211). */
    private final StoppedTimer stoppedTimer = new StoppedTimer();
    /** Whether this vehicle is currently too fast to carry a mob safely (MINECRAFT-249). */
    private final CrashEjectionLatch crashEjectionLatch = new CrashEjectionLatch();

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        this.vehicleId = vehicleIdFor(type);
        // MINECRAFT-443: the side-aware decision itself lives in VehicleSpecOverrides.resolveForSide
        // (a pure function, not here), specifically so it is unit-testable without this class's own
        // net.minecraft.world.entity.Entity superclass needing to load -- see that method's own javadoc.
        this.spec = VehicleSpecOverrides.resolveForSide(level.isClientSide(), this.vehicleId, stockSpecFor(this.vehicleId));
        this.vehicleTuning = spec.vehicleTuning();
        this.seats = new SeatAssignment<>(spec.seatCount());
        // MINECRAFT-443: each side's own local best guess at construction time (the server's is
        // authoritative; a logical client's is only its own stock spec, same as #spec always is) -- the
        // server's own value then overwrites the client's guess via the ordinary entity-data sync that
        // follows, which is what CarRenderer actually ends up drawing from (see DATA_RIDE_HEIGHT's own
        // javadoc for why #spec itself is never safe for a client to draw suspension height from).
        entityData.set(DATA_RIDE_HEIGHT, (float) spec.rideHeight());
    }

    private static String vehicleIdFor(EntityType<? extends CarEntity> type) {
        return DynamicVehiclesMod.TRUCK.isBound() && type == DynamicVehiclesMod.TRUCK.get() ? VehicleIds.TRUCK
                : DynamicVehiclesMod.TROPHY.isBound() && type == DynamicVehiclesMod.TROPHY.get() ? VehicleIds.TROPHY_TRUCK
                : DynamicVehiclesMod.DRIFT.isBound() && type == DynamicVehiclesMod.DRIFT.get() ? VehicleIds.DRIFT_CAR
                : DynamicVehiclesMod.MUSCLE.isBound() && type == DynamicVehiclesMod.MUSCLE.get() ? VehicleIds.MUSCLE_CAR
                : DynamicVehiclesMod.ROCK_CRAWLER.isBound() && type == DynamicVehiclesMod.ROCK_CRAWLER.get() ? VehicleIds.ROCK_CRAWLER
                : DynamicVehiclesMod.MONSTER_TRUCK.isBound() && type == DynamicVehiclesMod.MONSTER_TRUCK.get() ? VehicleIds.MONSTER_TRUCK
                : DynamicVehiclesMod.INDY.isBound() && type == DynamicVehiclesMod.INDY.get() ? VehicleIds.INDY_CAR
                : DynamicVehiclesMod.BUS.isBound() && type == DynamicVehiclesMod.BUS.get() ? VehicleIds.BUS
                : DynamicVehiclesMod.CARGO_TRUCK.isBound() && type == DynamicVehiclesMod.CARGO_TRUCK.get() ? VehicleIds.CARGO_TRUCK
                : VehicleIds.CAR;
    }

    private static VehicleSpec stockSpecFor(String vehicleId) {
        return switch (vehicleId) {
            case VehicleIds.TRUCK -> VehicleSpec.TRUCK;
            case VehicleIds.TROPHY_TRUCK -> VehicleSpec.TROPHY;
            case VehicleIds.DRIFT_CAR -> VehicleSpec.DRIFT;
            case VehicleIds.MUSCLE_CAR -> VehicleSpec.MUSCLE;
            case VehicleIds.ROCK_CRAWLER -> VehicleSpec.ROCK_CRAWLER;
            case VehicleIds.MONSTER_TRUCK -> VehicleSpec.MONSTER_TRUCK;
            case VehicleIds.INDY_CAR -> VehicleSpec.INDY;
            case VehicleIds.BUS -> VehicleSpec.BUS;
            case VehicleIds.CARGO_TRUCK -> VehicleSpec.CARGO_TRUCK;
            default -> VehicleSpec.CAR;
        };
    }

    /** This car's registry id (see {@link VehicleIds}) -- the stable key {@link CarRenderer} and this
     * class's own model/collision branches must dispatch on instead of {@link #spec}'s identity, which a
     * per-car config override can legitimately change without changing which car this is. */
    String vehicleId() {
        return vehicleId;
    }

    /** This vehicle's shape and drivetrain: the car's or the truck's. */
    public VehicleSpec spec() {
        return spec;
    }

    /** The current, per-instance tuning used by the server-side wheel physics. */
    VehicleSpec.VehicleTuning vehicleTuning() {
        return vehicleTuning;
    }

    /** The Diffuser KIT tuning {@link SableCarBody#tick} actually reads (MINECRAFT-447): this car's own
     * stock {@code spec.diffuserTuning()} unless a GameTest has called {@link #setDiffuserTuningForTest},
     * exactly the way {@link #vehicleTuning()} always reads this entity's own field rather than the spec's. */
    VehicleSpec.DiffuserTuning diffuserTuning() {
        return diffuserTuningOverride != null ? diffuserTuningOverride : spec.diffuserTuning();
    }

    /** GameTest-only hook (MINECRAFT-447): lets a production-path test exercise a non-identity Diffuser KIT
     * tuning on a real, ticking entity without changing any existing car's stock {@link VehicleSpec}
     * constant. Deliberately package-private (no command, no NBT persistence, no player-facing path) --
     * see {@link #diffuserTuningOverride}'s own javadoc for why this is not a second config mechanism. */
    void setDiffuserTuningForTest(VehicleSpec.DiffuserTuning tuning) {
        this.diffuserTuningOverride = tuning;
    }

    /** GameTest-only hook (MINECRAFT-447 review): seeds heading alignment's own frozen target directly, so
     * a direction-only regression can use a clean single-axis setup (spawned airborne, no prior grounded
     * tick, no tumble) instead of needing to first land and then relaunch the car. Requires {@code
     * sableBody} to already exist (call after the entity's first real tick). See {@code SableCarBody
     * #setLastGroundedYawDegForTest}'s own javadoc for why this is not a second config mechanism. */
    void setLastGroundedYawDegForTest(double yawDeg) {
        SableCompat.setLastGroundedYawDegForTest(sableBody, yawDeg);
    }

    /** GameTest-only hook (MINECRAFT-447 review): directly adds real horizontal (or any) velocity to this
     * car's own Sable body (starts at zero), so a direction-only trajectory-alignment regression can
     * satisfy {@code trajectoryAlignmentMinSpeed} without a grounded driving phase. Requires {@code
     * sableBody} to already exist. See {@code SableCarBody#addLinearVelocityForTest}'s own javadoc for why
     * this is not a second config mechanism. */
    void addLinearVelocityForTest(double x, double y, double z) {
        SableCompat.addLinearVelocityForTest(sableBody, new org.joml.Vector3d(x, y, z));
    }

    /** GameTest-only accessor (MINECRAFT-447): this entity's current Sable body orientation (including
     * roll and pitch), or {@code null} before its physics body exists (before the first real tick).
     * {@link #renderOrientation} exposes the client-interpolated version of this same quantity; nothing
     * server-side exposed the live value before this ticket, since every other production consumer
     * ({@code SableCarBody#syncEntity}/{@code setYRot}) only needed yaw. */
    org.joml.Quaternionf currentOrientationForTest() {
        return sableBody != null ? SableCompat.orientation(sableBody) : null;
    }

    /** GameTest-only accessor (MINECRAFT-447): whether any wheel was touching ground on the last real tick.
     * Deliberately NOT {@link #onGround()} -- that vanilla flag is never updated for this entity, since its
     * position is set directly from the Sable body rather than through vanilla's own move/collision path
     * that normally maintains it (confirmed directly: a GameTest polling {@code onGround()} never saw it
     * become true long after the car had visibly landed and settled). */
    boolean isTouchingGroundForTest() {
        return sableBody != null && SableCompat.touching(sableBody);
    }

    /**
     * The Sable rigid body handle, as the opaque {@code Object} {@link SableCompat} already takes --
     * package-private so a same-package production-path {@code @GameTest} (MINECRAFT-450, review on
     * PR #181) can read real per-wheel telemetry (speed/slip/wheelSpinRates/wheelTravel) straight off the
     * body {@code SableCarBody.tick} itself just wrote, without duplicating any of that composition. Null
     * until the first server tick creates it (see {@code tickSable}).
     */
    Object sableBody() {
        return sableBody;
    }

    /**
     * Applies one driver-configurable tuning dial to this particular entity. Values are always clamped before
     * they can reach physics, including values received through a command or loaded from old/corrupt save data.
     *
     * @return whether {@code setting} was a supported dial
     */
    boolean setVehicleTuning(String setting, double value) {
        double lsd = vehicleTuning.lsdLockPercent();
        double split = vehicleTuning.frontTorqueSplit();
        double vectoring = vehicleTuning.vectoringGain();
        double camber = vehicleTuning.camberDeg();
        double toe = vehicleTuning.toeDeg();
        switch (setting) {
            case "lsd_lock_percent" -> lsd = value;
            case "front_torque_split" -> split = value;
            case "vectoring_gain" -> vectoring = value;
            case "camber_deg" -> camber = value;
            case "toe_deg" -> toe = value;
            default -> {
                return false;
            }
        }
        vehicleTuning = VehicleTuningLimits.clamp(new VehicleSpec.VehicleTuning(lsd, split, vectoring, camber, toe),
                spec.vehicleTuning());
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ORIENTATION, new org.joml.Quaternionf());
        builder.define(DATA_RIGID_BODY, false);
        builder.define(DATA_THROTTLE, 0.0f);
        builder.define(DATA_LIGHTS, (byte) 0);
        builder.define(DATA_RADIO_URL, "");
        builder.define(DATA_SLIP, 0.0f);
        builder.define(DATA_RPM, 0.0f);
        builder.define(DATA_WHEELS, new CompoundTag());
        builder.define(DATA_RIDE_HEIGHT, 0.0f);
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_ORIENTATION.equals(key) && level().isClientSide()) {
            orientationQueue.add(new org.joml.Quaternionf(entityData.get(DATA_ORIENTATION)));
            while (orientationQueue.size() > 6) {
                orientationQueue.poll();
            }
        }
        if (DATA_WHEELS.equals(key) && level().isClientSide()) {
            previousWheels = currentWheels;
            currentWheels = unpackWheels(entityData.get(DATA_WHEELS));
        }
    }

    /** Server: publish the body's orientation to clients. */
    void publishOrientation(org.joml.Quaternionf orientation) {
        entityData.set(DATA_RIGID_BODY, true);
        entityData.set(DATA_ORIENTATION, orientation);
    }

    /** Server: auto, then on, then off, then auto again; returns the new mode's name. */
    String cycleLights() {
        byte next = (byte) ((entityData.get(DATA_LIGHTS) + 1) % 3);
        entityData.set(DATA_LIGHTS, next);
        // A switch click, higher when the lamps are switched on explicitly, lower for off.
        level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.LEVER_CLICK,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, next == 1 ? 1.3F : next == 2 ? 0.8F : 1.0F);
        return next == 0 ? "auto" : next == 1 ? "on" : "off";
    }

    /**
     * Server: cycles the radio -- Off, then every station in {@code CarConfig.RADIO_STATIONS} in order,
     * then back to Off (MINECRAFT-394) -- and publishes the newly selected URL ("" for Off) so every
     * nearby client, not just the driver, hears the same station change. Returns a label for the driver's
     * HUD message. Only meaningful to call while this car has a controlling passenger; see
     * {@link RadioTogglePayload#handle} for that gate.
     */
    String cycleRadio() {
        java.util.List<? extends String> stations = CarConfig.RADIO_STATIONS.get();
        radioIndex = RadioStationIndex.next(radioIndex, stations.size());
        entityData.set(DATA_RADIO_URL, RadioStationIndex.isOff(radioIndex) ? "" : stations.get(radioIndex));
        level().playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.LEVER_CLICK,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F, RadioStationIndex.isOff(radioIndex) ? 0.8F : 1.2F);
        return RadioStationIndex.label(radioIndex, stations.size());
    }

    /** The currently selected station's streaming URL, or "" while Off. Synced to every client. */
    public String radioUrl() {
        return entityData.get(DATA_RADIO_URL);
    }

    /** Whether the radio should be audible right now: a station is selected AND the vehicle is driven
     * (MINECRAFT-394) -- mirrors {@code CarSoundsClient}'s own engine-on check, so the radio falls silent
     * the instant the driver leaves exactly like the engine does, never left as an orphaned stream. */
    public boolean radioOn() {
        return !radioUrl().isEmpty() && getControllingPassenger() != null;
    }

    /** Whether the headlights are lit: on, or auto and dark (night or rain) in the car's world. */
    public boolean lightsOn() {
        byte mode = entityData.get(DATA_LIGHTS);
        if (mode == 1) {
            return true;
        }
        if (mode == 2) {
            return false;
        }
        long time = level().getDayTime() % 24000L;
        return HeadlightMath.isDark(time, level().getRainLevel(1.0f));
    }

    public double clientSpeed() {
        return clientSpeed;
    }

    public double clientRev() {
        return clientRev;
    }

    public double clientThrottle() {
        return entityData.get(DATA_THROTTLE);
    }

    public double clientSlip() {
        return entityData.get(DATA_SLIP);
    }

    /** Server test support only (MINECRAFT-401 production-path regressions): the gear ratio and automatic
     * gear index the drivetrain actually engaged on the last real tick, or 0.0/-1 before the physics body
     * exists. Package-private -- not part of the client-facing API. */
    double debugGearRatio() {
        return sableBody == null ? 0.0 : SableCompat.gearRatio(sableBody);
    }

    int debugAutomaticGearIndex() {
        return sableBody == null ? -1 : SableCompat.automaticGearIndex(sableBody);
    }

    /** Server test support only (MINECRAFT-447 steep-surface regression): R16's own last-applied torque. */
    double debugDiffuserRollTorque() {
        return sableBody == null ? 0.0 : SableCompat.lastDiffuserRollTorque(sableBody);
    }

    /** Server-authoritative speed, m/s -- unlike {@link #clientSpeed()}, which is only ever updated
     * inside this entity's client-side tick branch and so always reads 0 from a server-side test. */
    double debugSpeed() {
        return sableBody == null ? 0.0 : SableCompat.speed(sableBody);
    }

    /** Server-authoritative RAW tire slip speed, m/s -- the exact value {@link SlipReporter}/erosion gate
     * on (&gt; 0.3). Unlike this, {@link #clientSlip()} is the NORMALIZED 0..1 audio signal
     * ({@link CarSoundMath#slipLevel}, {@code slipSpeed / 4.0} clamped) plus a handbrake/reverse-at-speed
     * bonus for the skid sound alone -- comparing it against the erosion threshold is a unit mismatch
     * (MINECRAFT-401 review, round 2). */
    double debugSlipSpeed() {
        return sableBody == null ? 0.0 : SableCompat.slip(sableBody);
    }

    /** Server test support only (MINECRAFT-401 review, round 4): the REAL (slipSpeed, loadKg, Result) the
     * production tick last actually passed to and got back from {@code SlipReporter.report} -- observing
     * the real production call, not a value a test recomputes itself from a peak reading and an assumed
     * load. */
    double debugLastErosionReportSlipSpeed() {
        return sableBody == null ? 0.0 : SableCompat.lastErosionReportSlipSpeed(sableBody);
    }

    double debugLastErosionReportLoadKg() {
        return sableBody == null ? 0.0 : SableCompat.lastErosionReportLoadKg(sableBody);
    }

    /** Server test support only (MINECRAFT-401 review, round 5): the engine RPM {@link DrivetrainMath}
     * actually computed this tick, bypassing {@code DATA_RPM}'s own 0.02 network-sync gate in
     * {@link #publishSoundState} -- that gate is a bandwidth optimisation for client-facing audio, and
     * deliberately lets small per-tick drift accumulate before sending a single larger update, so reading
     * through it (as {@link #clientEngineRpmFraction()} does) cannot distinguish a real on/off reset from
     * an ordinary gradual change that the gate simply hadn't flushed yet. A caller checking gradualness of
     * the underlying physics must read this instead. */
    double debugEngineRpm() {
        return sableBody == null ? DrivetrainMath.IDLE_RPM : SableCompat.engineRpm(sableBody);
    }

    /** Diagnostic only (MINECRAFT-401 review, round 5 ledge-test investigation): whether any wheel had
     * a positive suspension force as of the end of the last tick -- distinguishes "genuinely airborne"
     * from "still grounded" when a fall isn't showing up as expected in velocity. */
    boolean debugTouching() {
        return sableBody != null && SableCompat.touching(sableBody);
    }

    /** Server test support only (MINECRAFT-401 review, round 5): each wheel's own real contact-patch
     * speed (m/s) and applied longitudinal force (N) as of the last tick it was grounded. */
    double[] debugWheelContactSpeeds() {
        return sableBody == null ? new double[0] : SableCompat.wheelContactSpeeds(sableBody);
    }

    /** Server test support only (MINECRAFT-401 review, round 5, second pass): each wheel's own spin rate
     * (rad/s) read straight from SableCarBody, bypassing {@link #wheelSpin(int, float)}'s client-sync
     * interpolation buffer -- that buffer is published once per tick but read back through its own
     * previous/current double-buffer, which (like {@code DATA_RPM}'s own gate) cannot be assumed to reflect
     * THIS tick's value the instant after it was set; a caller checking this tick's own physics state
     * against this tick's own engine RPM must read both the same way, not mix a direct read with a
     * sync-buffered one. */
    double[] debugWheelSpins() {
        return sableBody == null ? new double[0] : SableCompat.wheelSpinRates(sableBody);
    }

    double[] debugWheelForces() {
        return sableBody == null ? new double[0] : SableCompat.wheelForces(sableBody);
    }

    /** Review, round 8: JUST the recovery reaction's own contribution to {@link #debugWheelForces()} (0
     * when a wheel is not recovering this sub-step) - lets a test check the reaction's own
     * momentum/Newton's-third-law identity against the real production application directly, not a
     * reproduction of the formula that computed it. */
    double[] debugWheelReactionForces() {
        return sableBody == null ? new double[0] : SableCompat.wheelReactionForces(sableBody);
    }

    /** Review, round 9: each wheel's own lateral tire force this tick, so a test can check the friction
     * circle's combined (hypot of longitudinal and lateral) ceiling instead of longitudinal alone. */
    double[] debugWheelLateralForces() {
        return sableBody == null ? new double[0] : SableCompat.wheelLateralForces(sableBody);
    }

    /** Review, round 10: each wheel's own actual mu*normalForce circle this tick, so a test can check the
     * delivered force against the REAL per-tick budget instead of a fixed global worst-case ceiling. */
    double[] debugWheelFrictionLimits() {
        return sableBody == null ? new double[0] : SableCompat.wheelFrictionLimits(sableBody);
    }

    io.github.brooswitminecraft.dynamicterrain.Erosion.Result debugLastErosionResult() {
        return sableBody == null ? io.github.brooswitminecraft.dynamicterrain.Erosion.Result.SKIPPED : SableCompat.lastErosionResult(sableBody);
    }

    /** Client: engine RPM fraction 0..1 between idle and the limiter, for the drivetrain-derived engine
     * sound (MINECRAFT-401). */
    public double clientEngineRpmFraction() {
        return entityData.get(DATA_RPM);
    }

    /** Server: publish every wheel's suspension travel (m), steer angle (rad) and spin rate (rad/s). */
    void publishWheelState(double[] travel, double[] steerAngle, double[] spin) {
        entityData.set(DATA_WHEELS, packWheels(WheelSync.encode(travel, steerAngle, spin)));
    }

    private static CompoundTag packWheels(float[] packed) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (float value : packed) {
            list.add(FloatTag.valueOf(value));
        }
        tag.put("wheels", list);
        return tag;
    }

    private static float[] unpackWheels(CompoundTag tag) {
        ListTag list = tag.getList("wheels", Tag.TAG_FLOAT);
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = ((FloatTag) list.get(i)).getAsFloat();
        }
        return out;
    }

    /** Client: this wheel's travel (m), steer angle (rad) and spin rate (rad/s), interpolated for rendering. */
    public float wheelTravel(int wheel, float partialTick) {
        return wheelField(wheel, partialTick, 0);
    }

    public float wheelSteerAngle(int wheel, float partialTick) {
        return wheelField(wheel, partialTick, 1);
    }

    public float wheelSpin(int wheel, float partialTick) {
        return wheelField(wheel, partialTick, 2);
    }

    /** Client: how many wheels the last received frame carries, or 0 before the first sync. */
    public int syncedWheelCount() {
        return WheelSync.wheelCount(currentWheels);
    }

    /** MINECRAFT-443: this car's AUTHORITATIVE ride height -- what {@link CarRenderer} must draw the body
     * and wheels at, never {@link #spec}'s own {@code rideHeight()} (see {@link #DATA_RIDE_HEIGHT}'s own
     * javadoc for why). On the server this is already {@code spec.rideHeight()}; on a client it starts at
     * its own local stock guess and is corrected by the ordinary entity-data sync within a tick or two of
     * tracking the entity. */
    public float authoritativeRideHeight() {
        return entityData.get(DATA_RIDE_HEIGHT);
    }

    private float wheelField(int wheel, float partialTick, int field) {
        if (wheel >= syncedWheelCount()) {
            return 0.0f;
        }
        float[] frame = WheelSync.lerp(previousWheels, currentWheels, partialTick);
        return switch (field) {
            case 0 -> WheelSync.travel(frame, wheel);
            case 1 -> WheelSync.steerAngle(frame, wheel);
            default -> WheelSync.spin(frame, wheel);
        };
    }

    /** Server: publish what the sound system needs to hear on every client. */
    void publishSoundState(double throttle, double slipSpeed, double engineRpm) {
        float t = (float) Math.max(0.0, Math.min(1.0, throttle));
        float s = (float) CarSoundMath.slipLevel(slipSpeed);
        float r = (float) Math.max(0.0, Math.min(1.0,
                (engineRpm - DrivetrainMath.IDLE_RPM) / (DrivetrainMath.LIMITER_RPM - DrivetrainMath.IDLE_RPM)));
        if (Math.abs(entityData.get(DATA_THROTTLE) - t) > 0.05f) {
            entityData.set(DATA_THROTTLE, t);
        }
        if (Math.abs(entityData.get(DATA_SLIP) - s) > 0.05f) {
            entityData.set(DATA_SLIP, s);
        }
        if (Math.abs(entityData.get(DATA_RPM) - r) > 0.02f) {
            entityData.set(DATA_RPM, r);
        }
    }

    /** Server: honk once each time the honk signal goes down (jump when no wheel is active, else the honk paddle). */
    private void honkOnPress(boolean pressed) {
        if (pressed && !wasHonking) {
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.HORN.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
        }
        wasHonking = pressed;
    }

    /**
     * Server: the rider's wheel reported its shifter-paddle state (MINECRAFT-167). Honk and handbrake are
     * independent signals here, same as they are in {@link #tick()} -- a wheel-active client already forces
     * its own jump key to do nothing, so these two booleans are the only source of honk/handbrake while riding
     * with a wheel.
     */
    void setWheelPaddles(boolean honk, boolean handbrake) {
        wheelHonk = honk;
        wheelHandbrake = handbrake;
    }

    /**
     * Server: the rider pressed the explicit reverse-gear input (MINECRAFT-228: keyboard or gamepad, sent
     * by {@link GearTogglePayload}). Toggles between reverse and the default forward gear -- keyboard and
     * gamepad never need to pick a specific manual gear or automatic, only whether they're reversing --
     * and never anything the brake alone can trigger: see {@link CarPhysics.Gear} for the invariant this
     * protects.
     */
    String toggleGear() {
        gear = gear == CarPhysics.Gear.REVERSE ? CarPhysics.Gear.DEFAULT_FORWARD : CarPhysics.Gear.REVERSE;
        return gear == CarPhysics.Gear.REVERSE ? "reverse" : "forward";
    }

    /** Server: selects one state from the explicit MINECRAFT-228 gear interface. */
    String selectGear(CarPhysics.Gear selected) {
        gear = selected;
        return selected == CarPhysics.Gear.REVERSE ? "reverse"
                : selected == CarPhysics.Gear.NEUTRAL ? "neutral"
                : selected == CarPhysics.Gear.AUTOMATIC ? "automatic"
                : selected.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Server: play an impact when the car has just lost a lot of speed at once.
     *
     * <p>MINECRAFT-249 (review round 2): a real crash shows up here as a sudden speed LOSS, which {@link
     * CrashEjectionLatch} (a speed-SPIKE threshold) can never catch on its own, so this also ejects every
     * mob passenger on exactly the tick {@link ImpactEjectionTrigger} says the impact itself fires --
     * reusing {@link #impactCooldown} both as the existing sound/block-break gate (unchanged) and as the
     * re-board latch, so an ejected mob cannot immediately re-board while the cooldown is still running
     * (see {@link #canAddPassenger}/{@link #push}).
     */
    void checkImpact(double currentSpeed) {
        if (impactCooldown > 0) {
            impactCooldown--;
        }
        int severity = CarSoundMath.impactSeverity(lastTickSpeed - currentSpeed);
        double speedBefore = lastTickSpeed;
        lastTickSpeed = currentSpeed;
        damage(CarHealth.crashDamage(speedBefore - currentSpeed));
        if (ImpactEjectionTrigger.shouldEject(severity, impactCooldown)) {
            impactCooldown = 10;
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.impact(severity),
                    net.minecraft.sounds.SoundSource.NEUTRAL, (float) CarSoundMath.impactVolume(severity),
                    0.9f + random.nextFloat() * 0.2f);
            hitBlockAhead(speedBefore);
            ejectAllMobPassengers();
        }
    }

    /** A collision reports like one wheel taking the hit (a quarter of the car), at a capped slip speed. */
    private void reportImpact(net.minecraft.server.level.ServerLevel serverLevel, net.minecraft.core.BlockPos pos, double speedBefore) {
        SlipReporter.report(serverLevel, pos, ImpactRules.impactSlip(speedBefore), MASS_KG / WHEEL_COUNT);
    }

    /** Server: a hard hit may break the block ahead (soft and fast is likely), otherwise it erodes it. At most one block. */
    private void hitBlockAhead(double speedBefore) {
        if (!CarConfig.COLLISION_BREAKING.get() || heading == null || !(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        double reach = spec.halfZ() + 0.6;
        for (double dy : new double[] {0.4, 1.0}) {
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(getX() + heading.x * reach, getY() + dy, getZ() + heading.z * reach);
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            boolean breakable = !state.hasBlockEntity() && state.getDestroySpeed(serverLevel, pos) >= 0.0;
            boolean bamboo = state.is(BlockTags.BAMBOO_BLOCKS);
            boolean dirtOrSand = state.is(BlockTags.DIRT) || state.is(BlockTags.SAND);
            boolean glass = state.getBlock() instanceof TransparentBlock || state.getBlock() instanceof StainedGlassPaneBlock;
            ImpactRules.BlockImpactRoute route = ImpactRules.blockImpactRoute(
                    VehicleModel.forId(vehicleId) == VehicleModel.TRUCK, dirtOrSand, bamboo, breakable);

            // Dynamic Terrain is the sole authority for loose terrain; it decides resistance and whether
            // material shifts downhill or degrades. This is selected before the truck-only return below,
            // so truck impacts cannot suppress dirt/sand terrain routing.
            if (route == ImpactRules.BlockImpactRoute.TERRAIN_EROSION) {
                reportImpact(serverLevel, pos, speedBefore);
                return;
            }

            // The truck has a deliberately narrow vegetation interaction. It never gains the generic
            // block-breaking privilege merely by being heavier than a car.
            if (VehicleModel.forId(vehicleId) == VehicleModel.TRUCK) {
                if (route == ImpactRules.BlockImpactRoute.TRUCK_BAMBOO) {
                    serverLevel.destroyBlock(pos, true, this);
                }
                return;
            }

            if (!ImpactRules.glassMayBreak(glass, state.isCollisionShapeFullBlock(serverLevel, pos))) {
                return;
            }
            double chance = state.hasBlockEntity() ? 0.0
                    : CollisionMath.breakChance(speedBefore, state.getDestroySpeed(serverLevel, pos),
                            CarConfig.COLLISION_MIN_SPEED.get(), CarConfig.COLLISION_MAX_HARDNESS.get());
            if (chance > 0.0 && random.nextDouble() < chance) {
                serverLevel.destroyBlock(pos, true, this);
            } else {
                reportImpact(serverLevel, pos, speedBefore);
            }
            return;
        }
    }

    @Override
    public void onRemovedFromLevel() {
        // Runs for every way out of the level: killed, discarded, or unloaded with its chunk.
        if (level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            lampBlock = HeadlampLight.clear(serverLevel, lampBlock);
        }
        super.onRemovedFromLevel();
    }

    @Override
    protected void addPassenger(Entity passenger) {
        // Assign the seat before super's own call, so any attachment-point lookup from this point on
        // (positionRider runs on the very next tick) sees this passenger's own seat, never seat 0's default.
        pendingDismounts.remove(passenger);
        // MINECRAFT-211: a non-player (an auto-boarding mob) never takes seat 0, the driver's, even if it
        // is empty -- only a player may ever drive.
        Integer savedSeat = savedSeatAssignments.remove(passenger.getUUID());
        if (savedSeat == null || !seats.restore(passenger, savedSeat)) {
            seats.add(passenger, !(passenger instanceof Player));
        }
        super.addPassenger(passenger);
        if (!level().isClientSide() && getPassengers().size() == 1) {
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.ENGINE_START.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.9f, 1.0f);
        }
    }

    /**
     * MINECRAFT-172: {@code LivingEntity.stopRiding()} calls {@code super.stopRiding()} (which reaches this
     * method) BEFORE it asks {@link #getDismountLocationForPassenger}, so by the time that call lands,
     * {@code seats} must already have forgotten this passenger (for {@link #getControllingPassenger} and the
     * next {@link #canAddPassenger} check to be instantly correct) -- but the SEAT that's leaving still has
     * to be known for the dismount lookup that is about to happen. So this freezes that seat's dismount
     * point into {@link #pendingDismounts} first, then clears {@code seats} as normal.
     */
    @Override
    protected void removePassenger(Entity passenger) {
        if (spec.seatCount() > 1) {
            int index = seats.indexOf(passenger);
            if (index >= 0) {
                pendingDismounts.put(passenger, worldDismountPoint(spec.seats().get(index)));
            }
        }
        seats.remove(passenger);
        super.removePassenger(passenger);
        if (!level().isClientSide() && getPassengers().isEmpty()) {
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.ENGINE_STOP.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.9f, 1.0f);
        }
    }

    /** Whether this car is driven by a rigid body, so the renderer should draw its full orientation. */
    public boolean isRigidBody() {
        return entityData.get(DATA_RIGID_BODY);
    }

    /** Client: heading (Minecraft yaw, degrees) of the orientation currently shown. */
    public float heading() {
        org.joml.Vector3f forward = currentOrientation.transform(new org.joml.Vector3f(0, 0, 1));
        return (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    /** Client: take the next buffered movement and orientation, one per tick (two when the buffer runs long). */
    private void consumeClientStates() {
        if (!isRigidBody()) {
            return;
        }
        double[] move = nextState(movementQueue, movementStarved);
        movementStarved = move == null && !movementQueue.isEmpty() ? movementStarved + 1 : 0;
        if (move != null) {
            setPos(move[0], move[1], move[2]);
        }
        if (arrivalsSinceTick == 0) {
            zeroArrivalTicks++;
        } else if (arrivalsSinceTick > 1) {
            multiArrivalTicks++;
        }
        arrivalsSinceTick = 0;
        // Debug measurement: how even is the movement the camera and renderer see, tick to tick?
        double step = Math.hypot(getX() - xo, getZ() - zo);
        stepSamples[stepIndex++ % stepSamples.length] = step;
        if (tickCount % 20 == 0 && stepIndex >= stepSamples.length) {
            double mean = 0;
            for (double s : stepSamples) {
                mean += s;
            }
            mean /= stepSamples.length;
            double dev = 0;
            for (double s : stepSamples) {
                dev = Math.max(dev, Math.abs(s - mean));
            }
            DynamicVehiclesMod.LOGGER.debug("SMOOTH mean step {} m/tick, worst deviation {} m; raw arrivals: {} ticks with none, {} ticks with 2+ (since last report)", String.format("%.3f", mean), String.format("%.3f", dev), zeroArrivalTicks, multiArrivalTicks);
            zeroArrivalTicks = 0;
            multiArrivalTicks = 0;
        }
        org.joml.Quaternionf q = nextState(orientationQueue, orientationStarved);
        orientationStarved = q == null && !orientationQueue.isEmpty() ? orientationStarved + 1 : 0;
        previousOrientation.set(currentOrientation);
        if (q != null) {
            currentOrientation.set(q);
        }
    }

    /**
     * Keep about one state in hand: use one per tick while two or more are waiting, skip ahead when the
     * queue runs long, and release a lone state only after a tick of waiting so a late packet does not stall us.
     */
    private static <T> T nextState(java.util.ArrayDeque<T> queue, int starved) {
        if (queue.size() >= 4) {
            queue.poll();
            return queue.poll();
        }
        if (queue.size() >= 2 || (queue.size() == 1 && starved >= 1)) {
            return queue.poll();
        }
        return null;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        if (level().isClientSide() && isRigidBody()) {
            arrivalsSinceTick++;
            movementQueue.add(new double[] {x, y, z});
            while (movementQueue.size() > 6) {
                movementQueue.poll();
            }
            return;
        }
        super.lerpTo(x, y, z, yRot, xRot, steps);
    }

    /** Client: the orientation to draw, interpolated between the last two updates. */
    public org.joml.Quaternionf renderOrientation(float partialTick) {
        return previousOrientation.nlerp(currentOrientation, partialTick, new org.joml.Quaternionf());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        OrientationNbt.Orientation orientation = OrientationNbt.read(tag.contains("OrientationW"),
                tag.getFloat("OrientationX"), tag.getFloat("OrientationY"), tag.getFloat("OrientationZ"), tag.getFloat("OrientationW"));
        if (orientation != null) {
            savedOrientation = new org.joml.Quaternionf(orientation.x(), orientation.y(), orientation.z(), orientation.w());
        }
        health = tag.contains("VehicleHealth", Tag.TAG_FLOAT)
                ? Math.max(0.0f, Math.min(CarHealth.MAX, tag.getFloat("VehicleHealth"))) : CarHealth.MAX;
        // MINECRAFT-394: re-clamp against the CURRENT station list, not whatever list produced this saved
        // index -- an operator who shortened CarConfig.RADIO_STATIONS between server starts must never
        // reload a car pointed at a station index that no longer exists.
        int savedRadioIndex = tag.contains("RadioIndex", Tag.TAG_INT) ? tag.getInt("RadioIndex") : RadioStationIndex.OFF;
        radioIndex = RadioStationIndex.clamp(savedRadioIndex, CarConfig.RADIO_STATIONS.get().size());
        entityData.set(DATA_RADIO_URL, RadioStationIndex.isOff(radioIndex) ? "" : CarConfig.RADIO_STATIONS.get().get(radioIndex));
        savedSeatAssignments.clear();
        savedSeatAssignments.putAll(SeatPersistence.read(tag, spec.seatCount()));
        VehicleSpec.VehicleTuning defaults = spec.vehicleTuning();
        vehicleTuning = VehicleTuningLimits.clamp(new VehicleSpec.VehicleTuning(
                tag.contains("TuningLsdLock", Tag.TAG_DOUBLE) ? tag.getDouble("TuningLsdLock") : defaults.lsdLockPercent(),
                tag.contains("TuningFrontSplit", Tag.TAG_DOUBLE) ? tag.getDouble("TuningFrontSplit") : defaults.frontTorqueSplit(),
                tag.contains("TuningVectoring", Tag.TAG_DOUBLE) ? tag.getDouble("TuningVectoring") : defaults.vectoringGain(),
                tag.contains("TuningCamber", Tag.TAG_DOUBLE) ? tag.getDouble("TuningCamber") : defaults.camberDeg(),
                tag.contains("TuningToe", Tag.TAG_DOUBLE) ? tag.getDouble("TuningToe") : defaults.toeDeg()), defaults);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // The body's full orientation, so a car resting on a slope reloads tilted instead of snapping upright.
        org.joml.Quaternionf q = savedOrientation;
        if (sableBody != null) {
            try {
                q = SableCompat.orientation(sableBody);
            } catch (RuntimeException gone) {
                // Sable has already released the physics body (chunk unload); keep the last known orientation.
            }
        }
        if (q != null) {
            tag.putFloat("OrientationX", q.x);
            tag.putFloat("OrientationY", q.y);
            tag.putFloat("OrientationZ", q.z);
            tag.putFloat("OrientationW", q.w);
        }
        tag.putFloat("VehicleHealth", health);
        tag.putInt("RadioIndex", radioIndex);
        tag.putDouble("TuningLsdLock", vehicleTuning.lsdLockPercent());
        tag.putDouble("TuningFrontSplit", vehicleTuning.frontTorqueSplit());
        tag.putDouble("TuningVectoring", vehicleTuning.vectoringGain());
        tag.putDouble("TuningCamber", vehicleTuning.camberDeg());
        tag.putDouble("TuningToe", vehicleTuning.toeDeg());
        java.util.List<SeatAssignments.Assignment> assignments = new java.util.ArrayList<>();
        for (int index = 0; index < seats.seatCount(); index++) {
            Entity passenger = seats.at(index);
            if (passenger != null) {
                assignments.add(new SeatAssignments.Assignment(passenger.getUUID(), index));
            }
        }
        SeatPersistence.write(tag, assignments);
    }

    /** The orientation to restore when the body is recreated after a reload, or null to derive it from the yaw. */
    org.joml.Quaternionf savedOrientation() {
        return savedOrientation;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // MINECRAFT-172: a free seat, not an empty car -- for a one-seat spec those are the same thing
        // (today's behavior, unchanged), but a multi-seat spec must let a rider board while others already
        // are. canAddPassenger (speed gate included) still has the final say inside player.startRiding.
        if (player.isSecondaryUseActive() || VehicleSeating.firstFreeSeat(seats.occupied()) < 0) {
            return InteractionResult.PASS;
        }
        if (CarHealth.wrecked(health)) {
            if (!level().isClientSide()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("This vehicle is too damaged to enter."), true);
            }
            return InteractionResult.sidedSuccess(level().isClientSide());
        }
        if (!level().isClientSide()) {
            player.startRiding(this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    /**
     * MINECRAFT-211: on contact with an eligible mob (an adult, non-leashed, non-hostile cow, today),
     * board it into this car -- piggybacking on vanilla's own per-tick entity-collision push, which already
     * calls this for every nearby entity, rather than scanning loaded mobs ourselves. {@code startRiding}
     * re-checks {@link #canAddPassenger}, which is the actual seat/eligibility gate; the
     * {@link MobBoardingRules} check here is just to avoid calling it for mobs that can never board.
     */
    @Override
    public void push(Entity entity) {
        super.push(entity);
        if (!level().isClientSide() && entity instanceof Creeper creeper
                && ImpactRules.creeperExplosionEligible(horizontalSpeed(), CarConfig.COLLISION_MIN_SPEED.get(), impactCooldown > 0)) {
            impactCooldown = 10;
            CreeperExplosions.detonate((net.minecraft.server.level.ServerLevel) level(), creeper);
        }
        if (!level().isClientSide() && entity instanceof Enemy && entity instanceof LivingEntity victim
                && !(entity instanceof Creeper) && !hasPassenger(entity)) {
            double hitSpeed = horizontalSpeed();
            if (ImpactRules.hostileMobHitEligible(hitSpeed)) {
                double yaw = Math.toRadians(getYRot());
                double fx = -Math.sin(yaw);
                double fz = Math.cos(yaw);
                // Fling the mob out of the car's path toward whichever side of the car it is more on.
                int side = ImpactRules.lateralSide(fx, fz, victim.getX() - getX(), victim.getZ() - getZ());
                double strength = ImpactRules.hostileMobKnockback(hitSpeed);
                if (victim.hurt(damageSources().generic(), ImpactRules.hostileMobHitDamage(hitSpeed))) {
                    victim.setDeltaMovement(victim.getDeltaMovement().add(-fz * side * strength, 0.25, fx * side * strength));
                    victim.hurtMarked = true;
                }
            }
        }
        if (!level().isClientSide() && entity instanceof Mob mob && !crashEjectionLatch.isLatched()
                && impactCooldown == 0
                && MobBoardingRules.canAutoBoard(mob, spec, stoppedTimer.isLatched())) {
            mob.startRiding(this);
        }
    }

    /**
     * MINECRAFT-172: capacity- and (for multi-seat specs only) speed-gated. A one-seat spec takes the exact
     * same path it always has ({@code seats.occupied()} has one slot, and {@link VehicleSeating#canBoard}
     * never speed-gates a single-slot array), so today's behavior is unchanged byte-for-byte.
     *
     * MINECRAFT-211: a {@link Mob} passenger is gated separately -- by {@link MobBoardingRules} and the
     * first free NON-driver seat -- rather than {@link VehicleSeating#canBoard}'s speed limit, since the
     * whole point of contact-triggered boarding is a moving bus driving into a mob; that speed gate exists
     * only to stop a player hopping onto a moving vehicle, and must keep doing only that.
     * {@code MobBoardingRules.canAutoBoard} also refuses while {@link #stoppedTimer} is latched, so a mob
     * that just auto-dismounted (or any mob touching an already-long-stopped bus) cannot immediately
     * re-board.
     */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (CarHealth.wrecked(health)) {
            return false;
        }
        if (passenger instanceof Mob mob) {
            return !crashEjectionLatch.isLatched() && impactCooldown == 0
                    && MobBoardingRules.canAutoBoard(mob, spec, stoppedTimer.isLatched())
                    && VehicleSeating.firstFreeNonDriverSeat(seats.occupied()) >= 0;
        }
        double horizontalSpeed = Math.hypot(getDeltaMovement().x, getDeltaMovement().z);
        return VehicleSeating.canBoard(seats.occupied(), horizontalSpeed);
    }

    /**
     * The car is server-authoritative, so no client may claim control of it. By default the rider's own
     * client counts as the local controller: it then ignores the server's position updates for the car and
     * sends its own, unmoved, vehicle position back with ServerboundMoveVehiclePacket, which the server
     * accepts and which snaps the car back every tick. That made the car refuse to drive.
     */
    @Override
    public boolean isControlledByLocalInstance() {
        return false;
    }

    /** Climb one-block steps, as a car on rough terrain must; smoothing makes most steps smaller. */
    @Override
    public float maxUpStep() {
        return 1.0f;
    }

    /** MINECRAFT-172: always seat 0, never whichever passenger happens to be first in the entity's own
     * passenger list -- so a passenger who boards after the driver can never end up "controlling" the car
     * just because of list order. */
    @Override
    public LivingEntity getControllingPassenger() {
        return seats.driver() instanceof LivingEntity driver ? driver : null;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        int index = seats.indexOf(passenger);
        VehicleSpec.Seat seat = spec.seats().get(index < 0 ? 0 : index);
        return new Vec3(seat.x(), seat.y(), seat.z());
    }

    /**
     * MINECRAFT-172: a one-seat spec keeps vanilla's own dismount search untouched (super), exactly as
     * before this ticket. A multi-seat spec instead lands the rider clear of the body at that seat's own
     * dismount point (one per seat); exiting is never speed-gated, at any seat, so a rider can never be
     * trapped. The point itself was already computed and frozen by {@link #removePassenger} (see its
     * javadoc for why) -- this just reads it, falling back to the driver's own point in the unlikely case
     * nothing was frozen (passenger was never one of our tracked seats to begin with).
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        if (spec.seatCount() <= 1) {
            return super.getDismountLocationForPassenger(passenger);
        }
        Vec3 frozen = pendingDismounts.remove(passenger);
        return frozen != null ? frozen : worldDismountPoint(spec.driverSeat());
    }

    /** World-space point {@code seat}'s own dismount offset lands at, given this car's current position and heading. */
    private Vec3 worldDismountPoint(VehicleSpec.Seat seat) {
        double[] local = VehicleSeating.dismountOffset(seat, spec.halfX());
        double yaw = Math.toRadians(getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double worldX = getX() + local[0] * cos - local[2] * sin;
        double worldZ = getZ() + local[0] * sin + local[2] * cos;
        return new Vec3(worldX, getY() + local[1], worldZ);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            consumeClientStates();
            clientSpeed = Math.hypot(getX() - xo, getZ() - zo) * 20.0;
            clientRev = CarSoundMath.nextRev(clientRev, clientThrottle(), DT);
            if (clientSounds == null) {
                clientSounds = CarSoundsClient.start(this);
            }
            clientRadio = CarRadioClient.tick(this, clientRadio);
            SkidMarksClient.tick(this);
            return;
        }
        double travelled = Math.hypot(getX() - xo, getZ() - zo);
        if (travelled > 0.05) {
            heading = new net.minecraft.world.phys.Vec3((getX() - xo) / travelled, 0.0, (getZ() - zo) / travelled);
        }
        if (tickCount % 2 == 0 && level() instanceof net.minecraft.server.level.ServerLevel lampLevel) {
            lampBlock = HeadlampLight.update(lampLevel, this, lampBlock);
        }
        LivingEntity rider = getControllingPassenger();
        if (rider == null) {
            // No one is driving: a wheel paddle from a previous rider must never linger (MINECRAFT-167),
            // and the next driver always starts in forward gear rather than inheriting the last one's.
            wheelHonk = false;
            wheelHandbrake = false;
            gear = CarPhysics.Gear.DEFAULT_FORWARD;
        }
        if (level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            tickSable(serverLevel, rider);
            if (!checkCrashEjection()) {
                checkAutoDismount();
            }
            tickHealth();
            return;
        }
        if (!checkCrashEjection()) {
            checkAutoDismount();
        }
    }

    /** Explosions (creepers, TNT, another car blowing up) damage the car; the explosion itself comes next tick via {@link #tickHealth}. */
    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
            damage(amount);
            return true;
        }
        return super.hurt(source, amount);
    }

    /** Server: take damage after the Sable body has been initialized. */
    private void damage(float amount) {
        if (amount <= 0.0f || sableBody == null || isRemoved() || level().isClientSide()) {
            return;
        }
        health = Math.max(0.0f, health - amount);
    }

    /** Server test hook: current health, 0..{@link CarHealth#MAX}. */
    float health() {
        return health;
    }

    /** Server: smoke, fire, flip and wreck handling for this tick, then explosion if health ran out. */
    private void tickHealth() {
        if (isRemoved() || !(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        if (sableBody != null) {
            try {
                org.joml.Vector3f up = SableCompat.orientation(sableBody).transform(new org.joml.Vector3f(0, 1, 0));
                if (CarHealth.flipped(up.y)) {
                    damage(CarHealth.flippedDamage(DT));
                }
            } catch (RuntimeException gone) {
                // Sable has released the body; no orientation to judge this tick.
            }
        }
        if (isInWater() && isOnFire()) {
            // The game's established extinguishing rule: water puts the fire out. Clearing it here
            // (MINECRAFT-385), rather than only skipping this tick's damage below, stops a leftover
            // fire-tick count from reigniting burning damage the instant the vehicle leaves the water.
            clearFire();
        }
        // MINECRAFT-385: lava contact and external ignition (a flame arrow, a fire charge, standing in
        // fire) both reach the car as vanilla's own isOnFire() -- lava ignites it via Entity's own
        // baseTick()/lavaHurt() before this tick's tickHealth() runs, so lava contact and "is on fire"
        // are the same damage path here, including from full health, not only the low-health
        // self-burning CarHealth.burning() already covered damage/explosion/wreck below. Reusing that
        // single damage() call for both keeps low-health self-burning and external ignition from ever
        // stacking into double damage on the same tick; vanilla's own lava hurt() call is swallowed by
        // this class's hurt() override (non-explosion sources fall through to Entity.hurt, a no-op), so
        // it never adds a second hit on top of this one.
        boolean selfBurning = CarHealth.burning(health);
        boolean burning = (selfBurning || isOnFire()) && !isInWater();
        if (burning) {
            damage(CarHealth.burnDamage(DT));
            if (selfBurning) {
                // Only the intentional low-health self-burn keeps re-arming its own fire display
                // indefinitely (unchanged from before this ticket). A merely-ignited healthy vehicle
                // must NOT get this refresh (review, PR #166): vanilla's own fire-tick countdown, left
                // alone, already clears isOnFire() -- and with it this damage -- once a short external
                // ignition (a flame arrow, a fire charge) runs out, instead of the car burning forever.
                setRemainingFireTicks(40);
            }
            if (tickCount % 20 == 0) {
                for (Entity passenger : getPassengers()) {
                    passenger.igniteForSeconds(2.0f);
                }
            }
        }
        if (CarHealth.smoking(health) && tickCount % 3 == 0) {
            serverLevel.sendParticles(burning ? net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE
                    : net.minecraft.core.particles.ParticleTypes.SMOKE,
                    getX(), getY() + 1.0, getZ(), burning ? 4 : 2, 0.4, 0.2, 0.4, 0.02);
        }
        if (health <= 0.0f) {
            ejectPassengers();
            serverLevel.explode(this, getX(), getY() + 0.5, getZ(), 4.0f, Level.ExplosionInteraction.TNT);
            discard();
            return;
        }
        if (CarHealth.wrecked(health) && !getPassengers().isEmpty()) {
            ejectPassengers();
        }
    }

    /**
     * MINECRAFT-211: once this vehicle has been stopped (see {@link StoppedTimer}) long enough, any
     * auto-boarded mob passenger dismounts on its own -- {@link StoppedTimer#tick} fires {@code true}
     * only once per stop, so this loop runs once, not every tick a mob remains dismounted and touching
     * the vehicle; {@link #canAddPassenger}/{@link #push} independently refuse to re-board it anyway
     * while {@link StoppedTimer#isLatched} stays set. A player is never affected -- a player always exits
     * through the vanilla sneak key regardless of speed, exactly as before this ticket.
     */
    private void checkAutoDismount() {
        double horizontalSpeed = Math.hypot(getDeltaMovement().x, getDeltaMovement().z);
        if (!stoppedTimer.tick(horizontalSpeed)) {
            return;
        }
        for (Entity passenger : java.util.List.copyOf(getPassengers())) {
            if (passenger instanceof Mob mob) {
                mob.stopRiding();
            }
        }
    }

    /**
     * MINECRAFT-249: once this vehicle's speed crosses {@link CrashEjectionLatch#CRASH_EJECT_SPEED_THRESHOLD},
     * every mob passenger is ejected to a safe nearby position immediately, in place of (never alongside)
     * the normal stop-based {@link #checkAutoDismount} for that tick. Returns whether the vehicle is
     * currently latched too-fast, so the caller knows whether to run the normal check at all. The latch
     * (see {@link CrashEjectionLatch}) also blocks {@link #canAddPassenger} from letting any mob board while
     * the vehicle stays this fast, so ejection never churns with contact-triggered boarding.
     *
     * <p>The latch only fires on the rising edge, but an ejection can fail (every candidate position is
     * blocked, so the mob deliberately stays mounted rather than being placed in a block). While latched
     * and a mob is still aboard, the bounded safe-position search is therefore retried every tick, so a
     * mob never stays on a too-fast vehicle once a safe spot opens up.
     */
    private boolean checkCrashEjection() {
        double horizontalSpeed = Math.hypot(getDeltaMovement().x, getDeltaMovement().z);
        boolean risingEdge = crashEjectionLatch.tick(horizontalSpeed);
        if (MobEjection.shouldAttemptCrashEjection(risingEdge, crashEjectionLatch.isLatched(), hasMobPassenger())) {
            ejectAllMobPassengers();
        }
        return crashEjectionLatch.isLatched();
    }

    private boolean hasMobPassenger() {
        for (Entity passenger : getPassengers()) {
            if (passenger instanceof Mob) {
                return true;
            }
        }
        return false;
    }

    /**
     * MINECRAFT-249: ejects every MOB passenger (never a player -- player dismount is untouched by this
     * ticket) to a safe nearby position, found by {@link SafeEjectPlacement} against this level's own
     * collision. Called for a crash/fast-movement speed spike ({@link #checkCrashEjection}), a real
     * impact ({@link #checkImpact}), and this vehicle being actually destroyed ({@link #remove}), so a
     * mob is never left riding a vehicle that no longer exists or that just crashed.
     */
    private void ejectAllMobPassengers() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        for (Entity passenger : java.util.List.copyOf(getPassengers())) {
            if (passenger instanceof Mob mob) {
                ejectMob(serverLevel, mob);
            }
        }
    }

    private void ejectMob(net.minecraft.server.level.ServerLevel serverLevel, Mob mob) {
        double originX = getX();
        double originY = getY();
        double originZ = getZ();
        MobEjection.tryEjectAtSafePosition(originX, originY, originZ,
                candidate -> isFreeOfBlocks(serverLevel, mob, candidate), mob::stopRiding,
                candidate -> mob.setPos(candidate[0], candidate[1], candidate[2]));
    }

    /** Whether {@code mob}'s own bounding box, moved to {@code pos}, collides with no block in {@code serverLevel}. */
    private boolean isFreeOfBlocks(net.minecraft.server.level.ServerLevel serverLevel, Mob mob, double[] pos) {
        net.minecraft.world.phys.AABB box = mob.getBoundingBox().move(pos[0] - mob.getX(), pos[1] - mob.getY(), pos[2] - mob.getZ());
        return serverLevel.noCollision(mob, box);
    }

    private void tickSable(net.minecraft.server.level.ServerLevel serverLevel, LivingEntity rider) {
        if (sableBody == null) {
            sableBody = SableCompat.create(serverLevel, this);
            if (sableBody == null) {
                return;
            }
        }
        double throttle = rider == null ? 0.0 : Math.max(-1.0, Math.min(1.0, rider.zza));
        double steer = rider == null ? 0.0 : Math.max(-1.0, Math.min(1.0, rider.xxa));
        boolean jumpPressed = rider != null && rider.jumping;
        WheelMapping.Paddles paddles = WheelMapping.resolve(jumpPressed, new WheelMapping.Paddles(wheelHonk, wheelHandbrake));
        boolean handbrake = paddles.handbrake();
        honkOnPress(paddles.honk());
        if (forcedDriveTicks > 0) {
            forcedDriveTicks--;
            throttle = forcedThrottle;
            steer = forcedSteer;
        }
        try {
            SableCompat.tick(sableBody, this, throttle, steer, handbrake, gear, DT);
            SableCompat.syncEntity(sableBody, this);
            publishWheelState(SableCompat.wheelTravel(sableBody), SableCompat.wheelSteerAngles(sableBody), SableCompat.wheelSpinRates(sableBody));
            publishSoundState(Math.abs(throttle), SableCompat.slip(sableBody) + (handbrake || (throttle < 0 && SableCompat.speed(sableBody) > 8.0) ? 2.0 : 0.0),
                    SableCompat.engineRpm(sableBody));
            emitEffects(throttle, SableCompat.speed(sableBody), SableCompat.slip(sableBody));
            checkImpact(SableCompat.speed(sableBody));
            if (tickCount % 20 == 0) {
                DynamicVehiclesMod.LOGGER.debug("car {} {}", getUUID(), SableCompat.describe(sableBody, gear, throttle));
            }
        } catch (RuntimeException dead) {
            // Sable released the physics body under us (its handle is null). Drop it so the next tick builds a fresh one
            // from the entity's position, instead of crashing the server.
            DynamicVehiclesMod.LOGGER.warn("car {} lost its Sable body ({}); rebuilding", getUUID(), dead.toString());
            try {
                SableCompat.remove(sableBody);
            } catch (RuntimeException ignored) {
                // already gone
            }
            sableBody = null;
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        // MINECRAFT-249: a vehicle actually DESTROYED (killed or discarded, never simply unloaded or changing
        // dimension -- MINECRAFT-203 needs a parked bus to keep its seated mob across those) must eject its
        // mob passengers first, while this entity's position is still valid.
        if (reason.shouldDestroy()) {
            ejectAllMobPassengers();
        }
        if (level().isClientSide()) {
            SkidMarksClient.forget(this);
            CarRadioClient.stop(clientRadio);
            clientRadio = null;
        }
        if (sableBody != null) {
            SableCompat.remove(sableBody);
            sableBody = null;
        }
        super.remove(reason);
    }

    /** Server: exhaust while on the throttle and moving, dust at speed or sliding, both from behind the car, at most one of each per interval. */
    private void emitEffects(double throttle, double speed, double slip) {
        if (!CarConfig.SMOKE_ENABLED.get() || !AtmosphereCompat.usable() || tickCount % CarConfig.SMOKE_INTERVAL_TICKS.get() != 0
                || !(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        double strength = CarConfig.SMOKE_STRENGTH.get();
        double yaw = Math.toRadians(getYRot());
        double back = spec().halfZ() + 0.3;
        BlockPos rear = BlockPos.containing(getX() + Math.sin(yaw) * back, getY() + 0.5, getZ() - Math.cos(yaw) * back);
        int exhaust = CarEffectsMath.exhaustAmount(throttle, speed, strength);
        if (exhaust > 0) {
            AtmosphereCompat.exhaust(serverLevel, rear, exhaust);
        }
        int dust = onGround() ? CarEffectsMath.dustAmount(speed, slip, strength) : 0;
        if (dust > 0) {
            AtmosphereCompat.dust(serverLevel, rear.below(), dust);
        }
    }

    /** Debug: drive as if a rider held these inputs, for a while. */
    public void forceDrive(double throttle, double steer, int ticks) {
        forcedThrottle = throttle;
        forcedSteer = steer;
        forcedDriveTicks = ticks;
    }

    /** Current server-authoritative horizontal speed, including Sable-synchronised vehicles. */
    private double horizontalSpeed() {
        Vec3 motion = getDeltaMovement();
        return Math.hypot(motion.x, motion.z) * 20.0;
    }
}
