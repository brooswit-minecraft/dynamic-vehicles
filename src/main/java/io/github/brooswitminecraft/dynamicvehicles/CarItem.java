package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Places a car on the clicked block. */
public class CarItem extends Item {
    public CarItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos at = context.getClickedPos().relative(context.getClickedFace());
        CarEntity car = DynamicVehiclesMod.CAR.get().create(level);
        if (car == null) {
            return InteractionResult.FAIL;
        }
        car.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, context.getRotation(), 0.0f);
        level.addFreshEntity(car);
        if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
