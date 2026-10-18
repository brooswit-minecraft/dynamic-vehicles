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
 * not spin in place at a standstill.
 */
public class MotorboatEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.MOTORBOAT_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.MOTORBOAT_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.MOTORBOAT_HALF_LENGTH;
    private static final double ENGINE_ACCEL = 9.0;
    private static final double MAX_SPEED = 9.0;
    private static final double TURN_RATE_DEG = 70.0;
    private static final double DRAG = 0.92;
    private static final double FLOAT_GAIN = 0.3;
    private static final double GRAVITY = 0.08;
    private static final double FALL_DRAG = 0.98;

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
    protected void driveTick(Player rider, double dt) {
        if (level().isClientSide() && !isControlledByLocalInstance()) {
            return;
        }
        Double surfaceY = findWaterSurfaceY();
        Vec3 motion = getDeltaMovement();
        double vx = motion.x();
        double vy = motion.y();
        double vz = motion.z();
        boolean floating = surfaceY != null;
        if (floating) {
            vy += (surfaceY - getY()) * FLOAT_GAIN;
            vy *= 0.6;
        } else {
            vy = (vy - GRAVITY) * FALL_DRAG;
        }
        if (rider != null && floating) {
            double throttle = Mth.clamp(rider.zza, -1.0, 1.0);
            double steer = Mth.clamp(rider.xxa, -1.0, 1.0);
            double speed = Math.hypot(vx, vz);
            setYRot((float) (getYRot() + steer * TURN_RATE_DEG * (0.2 + Math.min(1.0, speed / 4.0)) * dt));
            double yaw = Math.toRadians(getYRot());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            vx += fx * throttle * ENGINE_ACCEL * dt;
            vz += fz * throttle * ENGINE_ACCEL * dt;
        }
        vx *= DRAG;
        vz *= DRAG;
        double speed = Math.hypot(vx, vz);
        if (speed > MAX_SPEED) {
            vx = vx / speed * MAX_SPEED;
            vz = vz / speed * MAX_SPEED;
        }
        setDeltaMovement(vx, vy, vz);
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
