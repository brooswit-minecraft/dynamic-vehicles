package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-380: a rotor-powered helicopter. {@code rider.zza} is forward/back thrust, {@code rider.xxa}
 * yaws the nose, and the pilot's own look pitch ({@code rider.getXRot()}) sets a bounded climb/dive rate
 * &mdash; levelling out hovers, exactly the arcade-helicopter convention (nose up to climb, nose down to
 * dive) that needs no new key bindings beyond the mouse the player already steers their view with. The
 * movement model itself lives in {@link ChopperPhysics}, a pure function this class just wires
 * Minecraft's own per-tick input/output onto.
 */
public class ChopperEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.CHOPPER_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.CHOPPER_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.CHOPPER_HALF_LENGTH;

    public ChopperEntity(EntityType<? extends ChopperEntity> type, Level level) {
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
        return new Vec3(0.0, HALF_HEIGHT + 0.1, 0.1);
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
        ChopperPhysics.Result result = ChopperPhysics.step(motion.x(), motion.y(), motion.z(), getYRot(),
                throttle, steer, pitch, rider != null);
        setYRot((float) result.yawDeg());
        setDeltaMovement(result.vx(), result.vy(), result.vz());
        move(MoverType.SELF, getDeltaMovement());
        if (onGround() && getDeltaMovement().y() < 0.0) {
            setDeltaMovement(getDeltaMovement().x(), 0.0, getDeltaMovement().z());
        }
    }
}
