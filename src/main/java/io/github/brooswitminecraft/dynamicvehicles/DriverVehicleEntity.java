package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;

/** Shared input/view contract for vehicles. No physics, synced-data IDs, or persistence move here.
 * Cars and motorboats opt in; aircraft retain their existing keyboard/pitch controls. */
public abstract class DriverVehicleEntity extends Entity {
    protected DriverVehicleEntity(EntityType<? extends DriverVehicleEntity> type, Level level) {
        super(type, level);
    }

    public boolean supportsWheelMovement() { return false; }

    /** The same eligibility gate used by wheel mounting/calibration and movement input. */
    public static boolean acceptsWheelMovement(Entity mount) {
        return mount instanceof DriverVehicleEntity vehicle && vehicle.supportsWheelMovement();
    }
    public boolean isRigidBody() { return false; }
    public Quaternionf renderOrientation(float partialTick) { return new Quaternionf(); }
}
