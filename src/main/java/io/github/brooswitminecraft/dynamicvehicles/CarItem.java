package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Places a car or truck on the clicked block. */
public class CarItem extends Item {
    private final java.util.function.Supplier<net.minecraft.world.entity.EntityType<CarEntity>> type;
    /** Metres above the clicked surface to place the vehicle, so a tall one starts near its ride height. */
    private final double lift;

    public CarItem(Properties properties, java.util.function.Supplier<net.minecraft.world.entity.EntityType<CarEntity>> type, double lift) {
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
        CarEntity car = type.get().create(level);
        if (car == null) {
            return InteractionResult.FAIL;
        }
        car.moveTo(at.getX() + 0.5, at.getY() + lift, at.getZ() + 0.5, context.getRotation(), 0.0f);
        level.addFreshEntity(car);
        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
