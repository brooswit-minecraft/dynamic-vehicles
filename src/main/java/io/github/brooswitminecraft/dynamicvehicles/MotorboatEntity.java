package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-380: a watercraft. Floats to the surface of whatever water column it is over (or settles under
 * gravity when there is none nearby, e.g. beached or in the air after a fall), steers like a tiller &mdash;
 * {@code rider.zza} is the throttle, {@code rider.xxa} the rudder, scaled by current speed so the boat does
 * not spin in place at a standstill. The movement model itself lives in {@link MotorboatPhysics}, a pure
 * function this class just wires Minecraft's own per-tick input/output onto.
 */
public class MotorboatEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.MOTORBOAT_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.MOTORBOAT_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.MOTORBOAT_HALF_LENGTH;

    public MotorboatEntity(EntityType<? extends MotorboatEntity> type, Level level) {
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
        return new Vec3(0.0, HALF_HEIGHT + 0.1, 0.2);
    }

    @Override
    protected void driveTick(Player rider) {
        if (level().isClientSide() && !isControlledByLocalInstance()) {
            return;
        }
        Double surfaceY = findWaterSurfaceY();
        Vec3 motion = getDeltaMovement();
        double throttle = rider == null ? 0.0 : Mth.clamp(rider.zza, -1.0, 1.0);
        double steer = rider == null ? 0.0 : Mth.clamp(rider.xxa, -1.0, 1.0);
        boolean floating = surfaceY != null;
        double surfaceDelta = floating ? surfaceY - getY() : 0.0;
        MotorboatPhysics.Result result = MotorboatPhysics.step(motion.x(), motion.y(), motion.z(), getYRot(),
                throttle, steer, rider != null, floating, surfaceDelta);
        setYRot((float) result.yawDeg());
        setDeltaMovement(result.vx(), result.vy(), result.vz());
        move(MoverType.SELF, getDeltaMovement());
    }

    /** Topmost water block's surface in this boat's own column, searched a few blocks either side of its
     * current position; {@code null} when there is no water nearby (beached, or airborne after a fall). */
    private Double findWaterSurfaceY() {
        BlockPos base = BlockPos.containing(getX(), getY() + 1.0, getZ());
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos probe = base.offset(0, dy, 0);
            if (level().getFluidState(probe).is(FluidTags.WATER)) {
                return probe.getY() + 1.0;
            }
        }
        return null;
    }
}
