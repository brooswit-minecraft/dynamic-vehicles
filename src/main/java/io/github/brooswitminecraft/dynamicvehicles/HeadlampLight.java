package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real light from the headlamps: one invisible vanilla light block a few blocks ahead of the car, placed only
 * into air, moved as the car drives and removed when the lamps go off, the car stops being driven, unloads or
 * is removed. Every placement is recorded in {@link LightLedger} so leftovers are swept on the next start.
 */
final class HeadlampLight {
    private static final int LEVEL = 12;
    private static final double AHEAD = 3.0;

    private HeadlampLight() {}

    /** The light block this car currently holds, or null. */
    static BlockPos update(ServerLevel level, CarEntity car, BlockPos current) {
        boolean wanted = CarConfig.HEADLAMP_LIGHT.get() && car.lightsOn() && !car.getPassengers().isEmpty();
        if (!wanted) {
            return clear(level, current);
        }
        double yaw = Math.toRadians(car.getYRot());
        BlockPos target = BlockPos.containing(car.getX() - Math.sin(yaw) * AHEAD, car.getY() + 1.2, car.getZ() + Math.cos(yaw) * AHEAD);
        if (!level.isLoaded(target)) {
            return clear(level, current);
        }
        if (!level.getBlockState(target).isAir() && !target.equals(current)) {
            target = target.above();
            if (!level.isLoaded(target) || !level.getBlockState(target).isAir()) {
                return current;
            }
        }
        if (target.equals(current)) {
            return current;
        }
        clear(level, current);
        BlockState light = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LEVEL);
        level.setBlock(target, light, net.minecraft.world.level.block.Block.UPDATE_ALL);
        LightLedger.of(level).add(target);
        return target;
    }

    /** Remove the held light block if it is still ours; always returns null. */
    static BlockPos clear(ServerLevel level, BlockPos current) {
        if (current != null) {
            if (level.isLoaded(current) && level.getBlockState(current).is(Blocks.LIGHT)) {
                level.setBlock(current, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            LightLedger.of(level).remove(current);
        }
        return null;
    }
}
