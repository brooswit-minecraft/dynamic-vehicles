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
    private final org.joml.Quaternionf previousOrientation = new org.joml.Quaternionf();
    private final org.joml.Quaternionf currentOrientation = new org.joml.Quaternionf();

    private double speed;
    private org.joml.Quaternionf savedOrientation;
    /** The Sable rigid body, as Object so this class never loads Sable types (see SableCompat). */
    private Object sableBody;
    private double forcedSlip;
    private double forcedThrottle;
    private double forcedSteer;
    private int forcedDriveTicks;
    private int forcedSlipTicks;

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ORIENTATION, new org.joml.Quaternionf());
        builder.define(DATA_RIGID_BODY, false);
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_ORIENTATION.equals(key) && level().isClientSide()) {
            previousOrientation.set(currentOrientation);
            currentOrientation.set(entityData.get(DATA_ORIENTATION));
        }
    }

    /** Server: publish the body's orientation to clients. */
    void publishOrientation(org.joml.Quaternionf orientation) {
        entityData.set(DATA_RIGID_BODY, true);
        entityData.set(DATA_ORIENTATION, orientation);
    }

    /** Whether this car is driven by a rigid body, so the renderer should draw its full orientation. */
    public boolean isRigidBody() {
        return entityData.get(DATA_RIGID_BODY);
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
        org.joml.Quaternionf q = sableBody != null ? SableCompat.orientation(sableBody) : savedOrientation;
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
        return new Vec3(0.0, 0.55, -0.1);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
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
        reportSlip(slip);
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
        if (forcedDriveTicks > 0) {
            forcedDriveTicks--;
            throttle = forcedThrottle;
            steer = forcedSteer;
        }
        SableCompat.tick(sableBody, this, throttle, steer, handbrake, DT);
        SableCompat.syncEntity(sableBody, this);
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
