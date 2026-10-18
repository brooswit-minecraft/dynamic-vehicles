package io.github.brooswitminecraft.dynamicvehicles;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** MINECRAFT-380: places any {@link SimpleVehicleEntity} on the clicked block, the same way {@link
 * CarItem} places a {@link CarEntity}. */
public class VehicleSpawnItem<T extends Entity> extends Item {
    private final Supplier<EntityType<T>> type;
    /** Metres above the clicked surface to place the vehicle. */
    private final double lift;

    public VehicleSpawnItem(Properties properties, Supplier<EntityType<T>> type, double lift) {
        super(properties);
        this.type = type;
        this.lift = lift;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos at = context.getClickedPos().relative(context.getClickedFace());
        T vehicle = type.get().create(level);
        if (vehicle == null) {
            return InteractionResult.FAIL;
        }
        vehicle.moveTo(at.getX() + 0.5, at.getY() + lift, at.getZ() + 0.5, context.getRotation(), 0.0f);
        level.addFreshEntity(vehicle);
        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
