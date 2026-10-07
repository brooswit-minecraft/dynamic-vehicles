package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The first vehicle: a 4-wheel car. Server-authoritative. The rider's
 * forward/strafe input drives {@link CarPhysics}; each tick every wheel
 * reports its slip to Dynamic Terrain for the block beneath it, and the car
 * never modifies terrain itself.
 */
public class CarEntity extends Entity {
    /** Wheel offsets from the car centre: x = right, z = forward, in metres. */
    static final double[][] WHEELS = {{-0.8, 1.2}, {0.8, 1.2}, {-0.8, -1.2}, {0.8, -1.2}}; // x, z; same as CarGeometry.MOUNTS
    static final double MASS_KG = 1200.0;
    private static final double DT = 1.0 / 20.0;
    private static final double GRAVITY = 0.08;

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
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_THROTTLE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_SLIP =
            net.minecraft.network.syncher.SynchedEntityData.defineId(CarEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    /** Client: horizontal speed in m/s from the movement actually shown, and the sound loops for this car. */
    private double clientSpeed;
    private Object clientSounds;
    private double lastTickSpeed;
    private int impactCooldown;
    private boolean wasHonking;

    private double speed;
    private org.joml.Quaternionf savedOrientation;
    /** The Sable rigid body, as Object so this class never loads Sable types (see SableCompat). */
    private Object sableBody;
    private double forcedSlip;
    private double forcedThrottle;
    private double forcedSteer;
    private int forcedDriveTicks;
    private int forcedSlipTicks;

    private final VehicleSpec spec;

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        this.spec = DynamicVehiclesMod.TRUCK.isBound() && type == DynamicVehiclesMod.TRUCK.get() ? VehicleSpec.TRUCK
                : DynamicVehiclesMod.TROPHY.isBound() && type == DynamicVehiclesMod.TROPHY.get() ? VehicleSpec.TROPHY : VehicleSpec.CAR;
    }

    /** This vehicle's shape and drivetrain: the car's or the truck's. */
    public VehicleSpec spec() {
        return spec;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ORIENTATION, new org.joml.Quaternionf());
        builder.define(DATA_RIGID_BODY, false);
        builder.define(DATA_THROTTLE, 0.0f);
        builder.define(DATA_LIGHTS, (byte) 0);
        builder.define(DATA_SLIP, 0.0f);
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
        return next == 0 ? "auto" : next == 1 ? "on" : "off";
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

    public double clientThrottle() {
        return entityData.get(DATA_THROTTLE);
    }

    public double clientSlip() {
        return entityData.get(DATA_SLIP);
    }

    /** Server: publish what the sound system needs to hear on every client. */
    void publishSoundState(double throttle, double slipSpeed) {
        float t = (float) Math.max(0.0, Math.min(1.0, throttle));
        float s = (float) CarSoundMath.slipLevel(slipSpeed);
        if (Math.abs(entityData.get(DATA_THROTTLE) - t) > 0.05f) {
            entityData.set(DATA_THROTTLE, t);
        }
        if (Math.abs(entityData.get(DATA_SLIP) - s) > 0.05f) {
            entityData.set(DATA_SLIP, s);
        }
    }

    /** Server: honk once each time space goes down (space is also the handbrake). */
    private void honkOnPress(boolean pressed) {
        if (pressed && !wasHonking) {
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.HORN.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
        }
        wasHonking = pressed;
    }

    /** Server: play an impact when the car has just lost a lot of speed at once. */
    void checkImpact(double currentSpeed) {
        if (impactCooldown > 0) {
            impactCooldown--;
        }
        int severity = CarSoundMath.impactSeverity(lastTickSpeed - currentSpeed);
        lastTickSpeed = currentSpeed;
        if (severity > 0 && impactCooldown == 0) {
            impactCooldown = 10;
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.impact(severity),
                    net.minecraft.sounds.SoundSource.NEUTRAL, (float) CarSoundMath.impactVolume(severity),
                    0.9f + random.nextFloat() * 0.2f);
        }
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (!level().isClientSide() && getPassengers().size() == 1) {
            level().playSound(null, getX(), getY() + 0.5, getZ(), ModSounds.ENGINE_START.get(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.9f, 1.0f);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
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
        if (tag.contains("OrientationW")) {
            savedOrientation = new org.joml.Quaternionf(tag.getFloat("OrientationX"), tag.getFloat("OrientationY"),
                    tag.getFloat("OrientationZ"), tag.getFloat("OrientationW"));
        }
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
        if (player.isSecondaryUseActive() || !getPassengers().isEmpty()) {
            return InteractionResult.PASS;
        }
        if (!level().isClientSide()) {
            player.startRiding(this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide());
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
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

    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof LivingEntity rider ? rider : null;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float scale) {
        return new Vec3(0.0, spec.seatY(), spec.seatZ());
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            consumeClientStates();
            clientSpeed = Math.hypot(getX() - xo, getZ() - zo) * 20.0;
            if (clientSounds == null) {
                clientSounds = CarSoundsClient.start(this);
            }
            return;
        }
        LivingEntity rider = getControllingPassenger();
        if (level() instanceof net.minecraft.server.level.ServerLevel serverLevel && SableCompat.usable()) {
            tickSable(serverLevel, rider);
            return;
        }
        double throttle = rider == null ? 0.0 : Math.max(-1.0, Math.min(1.0, rider.zza));
        double steer = rider == null ? 0.0 : Math.max(-1.0, Math.min(1.0, rider.xxa));
        // Sneak is vanilla's dismount key, so the handbrake is the jump key (space).
        boolean handbrake = rider != null && rider.jumping;
        honkOnPress(handbrake);

        // Minecraft yaw 0 faces +Z; the physics heading is the entity's yaw in radians.
        CarPhysics.Step step = CarPhysics.step(new CarPhysics.State(speed, Math.toRadians(getYRot())),
                throttle, steer, handbrake, DT);
        speed = step.state().speed();
        setYRot((float) Math.toDegrees(step.state().heading()));
        double yaw = Math.toRadians(getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        double vy = onGround() ? -0.01 : getDeltaMovement().y - GRAVITY;
        setDeltaMovement(forward.x * speed * DT, vy, forward.z * speed * DT);
        move(MoverType.SELF, getDeltaMovement());
        if (horizontalCollision) {
            speed *= 0.3;
        }
        double slip = step.slipSpeed();
        if (forcedSlipTicks > 0) {
            forcedSlipTicks--;
            slip = Math.max(slip, forcedSlip);
        }
        if (tickCount % 20 == 0 && Math.abs(speed) > 0.1) {
            DynamicVehiclesMod.LOGGER.debug("car {} speed {} m/s at {} {} {}", getUUID(), String.format("%.2f", speed),
                    String.format("%.1f", getX()), String.format("%.1f", getY()), String.format("%.1f", getZ()));
        }
        reportSlip(CarConfig.WEAR_ENABLED.get() ? CarEffectsMath.wearSlip(throttle, speed, slip, CarConfig.WEAR_STRENGTH.get()) : slip);
        publishSoundState(Math.abs(throttle), slip);
        emitEffects(throttle, speed, slip);
        checkImpact(Math.abs(speed));
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
        boolean handbrake = rider != null && rider.jumping;
        honkOnPress(handbrake);
        if (forcedDriveTicks > 0) {
            forcedDriveTicks--;
            throttle = forcedThrottle;
            steer = forcedSteer;
        }
        SableCompat.tick(sableBody, this, throttle, steer, handbrake, DT);
        SableCompat.syncEntity(sableBody, this);
        publishSoundState(Math.abs(throttle), SableCompat.slip(sableBody) + (handbrake || (throttle < 0 && SableCompat.speed(sableBody) > 8.0) ? 2.0 : 0.0));
        emitEffects(throttle, SableCompat.speed(sableBody), SableCompat.slip(sableBody));
        checkImpact(SableCompat.speed(sableBody));
        if (tickCount % 20 == 0) {
            DynamicVehiclesMod.LOGGER.debug("car {} {}", getUUID(), SableCompat.describe(sableBody));
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
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

    /** Every wheel reports the same slip for the block under it; terrain decides what it does. */
    private void reportSlip(double slipSpeed) {
        if (slipSpeed <= 0 || !onGround()) {
            return;
        }
        double yaw = Math.toRadians(getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        for (double[] wheel : WHEELS) {
            double x = getX() + wheel[0] * cos - wheel[1] * sin;
            double z = getZ() + wheel[0] * sin + wheel[1] * cos;
            BlockPos under = BlockPos.containing(x, getY() - 0.05, z);
            SlipReporter.report(level(), under, slipSpeed, MASS_KG / WHEELS.length);
        }
    }

    /** Debug: report at least this slip every tick for a while, to exercise terrain wear without driving. */
    public void forceSlip(double slipSpeed, int ticks) {
        forcedSlip = slipSpeed;
        forcedSlipTicks = ticks;
    }

    /** Debug: drive as if a rider held these inputs, for a while. */
    public void forceDrive(double throttle, double steer, int ticks) {
        forcedThrottle = throttle;
        forcedSteer = steer;
        forcedDriveTicks = ticks;
    }

    public double speed() {
        return speed;
    }
}
