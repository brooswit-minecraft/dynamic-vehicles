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
    static final double[][] WHEELS = {{-0.8, 1.2}, {0.8, 1.2}, {-0.8, -1.2}, {0.8, -1.2}};
    static final double MASS_KG = 1200.0;
    private static final double DT = 1.0 / 20.0;
    private static final double GRAVITY = 0.08;

    private double speed;
    private double forcedSlip;
    private int forcedSlipTicks;

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

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
        double throttle = rider == null ? 0.0 : Math.max(-1.0, Math.min(1.0, rider.zza));
        double steer = rider == null ? 0.0 : Math.max(-1.0, Math.min(1.0, rider.xxa));
        boolean handbrake = rider != null && rider.isShiftKeyDown();

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
        reportSlip(slip);
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

    public double speed() {
        return speed;
    }
}
