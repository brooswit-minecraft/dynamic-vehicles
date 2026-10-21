package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-380: shared one-seat-vehicle plumbing for {@link MotorboatEntity}, {@link ChopperEntity} and
 * {@link BiplaneEntity} &mdash; registration, entry/exit, the single driver seat and the per-tick hook
 * every one of them drives through. Unlike {@link CarEntity}, these three carry no Sable rigid body and no
 * wheel physics: each subclass implements its own small, deterministic movement model directly in
 * {@link #driveTick}, run identically on the server and on the controlling client (the same split vanilla's
 * own {@code Boat} uses), so no custom network payload is needed to move them &mdash; position, rotation
 * and passengers already sync through the entity's standard tracking, the same as every other non-living
 * rideable entity.
 */
public abstract class SimpleVehicleEntity extends DriverVehicleEntity {

    protected SimpleVehicleEntity(EntityType<? extends SimpleVehicleEntity> type, Level level) {
        super(type, level);
    }

    /** Local seat offset from the entity origin (bottom centre of the box): x right, y up, z forward. */
    protected abstract Vec3 seatOffset();

    /** Server and client: advance this vehicle's own movement model by exactly one game tick (there is no
     * continuous {@code dt} here -- every constant in {@code MotorboatPhysics}/{@code ChopperPhysics}/
     * {@code BiplanePhysics} is already expressed in Minecraft's own native per-tick units, the same
     * convention {@code Entity#getDeltaMovement}/{@code Entity#move} use), given the current driver (null
     * if unmanned). Implementations read {@code rider.zza}/{@code rider.xxa} for throttle/steer and
     * {@code rider.getXRot()} for pitch-based climb/dive, exactly as {@link CarEntity} already reads
     * {@code rider.zza}/{@code rider.xxa} for its own steering. */
    protected abstract void driveTick(Player rider);

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No extra synced state: position/rotation already sync through the entity's standard tracking.
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // No extra persisted state beyond the entity's own position/rotation, which vanilla already saves.
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // No extra persisted state beyond the entity's own position/rotation, which vanilla already saves.
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
        Entity first = getFirstPassenger();
        return first instanceof LivingEntity living ? living : null;
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        Vec3 seat = seatOffset();
        return new Vec3(seat.x() * scale, seat.y() * scale, seat.z() * scale);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        LivingEntity controller = getControllingPassenger();
        Player rider = controller instanceof Player player ? player : null;
        driveTick(rider);
    }
}
