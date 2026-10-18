package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-380: a fixed-wing biplane. On the ground it taxis ({@code rider.zza} throttle, {@code
 * rider.xxa} rudder), picking up speed until it clears {@code BiplanePhysics.TAKEOFF_SPEED}; above that
 * speed the pilot's own look pitch ({@code rider.getXRot()}) sets a bounded climb/dive rate, the same
 * convention {@link ChopperEntity} uses, and falling below {@code BiplanePhysics.STALL_SPEED} while
 * airborne lets a bounded gravity sink take over (a stall) instead of holding altitude for free. The
 * movement model itself lives in {@link BiplanePhysics}, a pure function this class just wires
 * Minecraft's own per-tick input/output (including {@link #onGround()}) onto.
 */
public class BiplaneEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.BIPLANE_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.BIPLANE_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.BIPLANE_HALF_LENGTH;

    public BiplaneEntity(EntityType<? extends BiplaneEntity> type, Level level) {
        super(type, level);
    }

    public static double halfWidth() {
        return HALF_WIDTH;
    }

    public static double halfHeight() {
        return HALF_HEIGHT;
    }

    public static double halfLength() {
        return HALF_LENGTH;
    }

    @Override
    protected Vec3 seatOffset() {
        return new Vec3(0.0, HALF_HEIGHT + 0.2, 0.3);
    }

    @Override
    protected void driveTick(Player rider) {
        if (level().isClientSide() && !isControlledByLocalInstance()) {
            return;
        }
        Vec3 motion = getDeltaMovement();
        double throttle = rider == null ? 0.0 : Mth.clamp(rider.zza, -1.0, 1.0);
        double steer = rider == null ? 0.0 : Mth.clamp(rider.xxa, -1.0, 1.0);
        double pitch = rider == null ? 0.0 : rider.getXRot();
        BiplanePhysics.Result result = BiplanePhysics.step(motion.x(), motion.y(), motion.z(), getYRot(),
                throttle, steer, pitch, rider != null, onGround());
        setYRot((float) result.yawDeg());
        setDeltaMovement(result.vx(), result.vy(), result.vz());
        move(MoverType.SELF, getDeltaMovement());
        if (onGround() && getDeltaMovement().y() < 0.0) {
            setDeltaMovement(getDeltaMovement().x(), 0.0, getDeltaMovement().z());
        }
    }
}
