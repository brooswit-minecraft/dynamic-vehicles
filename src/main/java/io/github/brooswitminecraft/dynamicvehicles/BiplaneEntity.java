package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * MINECRAFT-380: a fixed-wing biplane. On the ground it taxis ({@code rider.zza} throttle, {@code
 * rider.xxa} rudder), picking up speed until it clears {@link #TAKEOFF_SPEED}; above that speed the
 * pilot's own look pitch ({@code rider.getXRot()}) controls climb/dive the same convention {@link
 * ChopperEntity} uses, and falling back below {@link #STALL_SPEED} while airborne lets gravity take over
 * (a stall) instead of holding altitude for free.
 */
public class BiplaneEntity extends SimpleVehicleEntity {
    private static final double HALF_WIDTH = VehicleGeometry.BIPLANE_HALF_WIDTH;
    private static final double HALF_HEIGHT = VehicleGeometry.BIPLANE_HALF_HEIGHT;
    private static final double HALF_LENGTH = VehicleGeometry.BIPLANE_HALF_LENGTH;
    private static final double THRUST_ACCEL = 8.0;
    private static final double MAX_SPEED = 22.0;
    private static final double GROUND_TURN_RATE_DEG = 35.0;
    private static final double AIR_TURN_RATE_DEG = 45.0;
    private static final double CLIMB_RATE = 5.0;
    private static final double TAKEOFF_SPEED = 7.0;
    private static final double STALL_SPEED = 4.0;
    private static final double GRAVITY = 0.07;
    private static final double DRAG = 0.985;

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
    protected void driveTick(Player rider, double dt) {
        if (level().isClientSide() && !isControlledByLocalInstance()) {
            return;
        }
        Vec3 motion = getDeltaMovement();
        double vx = motion.x();
        double vy = motion.y();
        double vz = motion.z();
        double speed = Math.hypot(vx, vz);
        boolean grounded = onGround();
        if (rider != null) {
            double throttle = Mth.clamp(rider.zza, -1.0, 1.0);
            double steer = Mth.clamp(rider.xxa, -1.0, 1.0);
            double turnRate = grounded ? GROUND_TURN_RATE_DEG : AIR_TURN_RATE_DEG * Math.min(1.0, speed / TAKEOFF_SPEED);
            setYRot((float) (getYRot() + steer * turnRate * dt));
            double yaw = Math.toRadians(getYRot());
            double fx = -Math.sin(yaw);
            double fz = Math.cos(yaw);
            vx += fx * throttle * THRUST_ACCEL * dt;
            vz += fz * throttle * THRUST_ACCEL * dt;
            if (!grounded && speed >= TAKEOFF_SPEED) {
                double pitch = rider.getXRot();
                vy += -Math.sin(Math.toRadians(pitch)) * CLIMB_RATE * dt;
            }
        }
        if (grounded) {
            vy = Math.max(vy, 0.0);
        } else if (speed < STALL_SPEED) {
            vy -= GRAVITY * dt * 20.0;
        }
        vx *= DRAG;
        vz *= DRAG;
        double newSpeed = Math.hypot(vx, vz);
        if (newSpeed > MAX_SPEED) {
            vx = vx / newSpeed * MAX_SPEED;
            vz = vz / newSpeed * MAX_SPEED;
        }
        setDeltaMovement(vx, vy, vz);
        move(MoverType.SELF, getDeltaMovement());
        if (onGround() && getDeltaMovement().y() < 0.0) {
            setDeltaMovement(getDeltaMovement().x(), 0.0, getDeltaMovement().z());
        }
    }
}
