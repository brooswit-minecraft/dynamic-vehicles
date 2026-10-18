package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-380: a rotor-powered helicopter. {@code rider.zza} is forward/back thrust, {@code rider.xxa}
 * yaws the nose, and the pilot's own look pitch ({@code rider.getXRot()}) sets climb/dive rate &mdash;
 * levelling out hovers, exactly the arcade-helicopter convention (nose up to climb, nose down to dive) that
 * needs no new key bindings beyond the mouse the player already steers their view with.
 */
public class ChopperEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.CHOPPER_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.CHOPPER_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.CHOPPER_HALF_LENGTH;
    private static final double FORWARD_ACCEL = 10.0;
    private static final double MAX_SPEED = 14.0;
    private static final double TURN_RATE_DEG = 60.0;
    private static final double CLIMB_RATE = 6.0;
    private static final double DRAG = 0.94;
    private static final double IDLE_FALL = 0.05;

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
    protected void driveTick(Player rider, double dt) {
        if (level().isClientSide() && !isControlledByLocalInstance()) {
            return;
        }
        Vec3 motion = getDeltaMovement();
        double vx = motion.x();
        double vy = motion.y();
        double vz = motion.z();
        if (rider != null) {
            double throttle = Mth.clamp(rider.zza, -1.0, 1.0);
            double steer = Mth.clamp(rider.xxa, -1.0, 1.0);
            double pitch = rider.getXRot();
            setYRot((float) (getYRot() + steer * TURN_RATE_DEG * dt));
            double yaw = Math.toRadians(getYRot());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            vx += fx * throttle * FORWARD_ACCEL * dt;
            vz += fz * throttle * FORWARD_ACCEL * dt;
            vy += -Math.sin(Math.toRadians(pitch)) * CLIMB_RATE * dt;
        } else {
            vy -= IDLE_FALL * dt * 20.0;
        }
        vx *= DRAG;
        vz *= DRAG;
        vy *= DRAG;
        double speed = Math.hypot(vx, vz);
        if (speed > MAX_SPEED) {
            vx = vx / speed * MAX_SPEED;
            vz = vz / speed * MAX_SPEED;
        }
        setDeltaMovement(vx, vy, vz);
        move(MoverType.SELF, getDeltaMovement());
        if (onGround() && vy < 0.0) {
            setDeltaMovement(getDeltaMovement().x(), 0.0, getDeltaMovement().z());
        }
    }
}
