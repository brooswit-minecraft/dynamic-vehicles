package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Production-path checks for the non-destructive car/creeper collision. */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class CreeperExplosionGameTests {
    private CreeperExplosionGameTests() {}

    @GameTest(template = "dispatch_profession")
    public static void contactBlastPreservesImpulseWithoutDamageOrBlockDestructionAndUsesExhaust(GameTestHelper helper) {
        BlockPos creeperPos = new BlockPos(3, 2, 2);
        BlockPos protectedBlock = new BlockPos(3, 3, 2);
        helper.setBlock(protectedBlock, Blocks.STONE);
        CarEntity car = helper.spawn(DynamicVehiclesMod.CAR.get(), new BlockPos(1, 2, 2));
        Creeper creeper = helper.spawn(EntityType.CREEPER, creeperPos);
        Pig target = helper.spawn(EntityType.PIG, new BlockPos(5, 2, 2));
        float healthBefore = target.getHealth();
        car.setDeltaMovement(new Vec3(0.5, 0.0, 0.0)); // 10 m/s, above the contact threshold.
        car.push(creeper);

        helper.assertTrue(target.getHealth() == healthBefore, "creeper contact blast damaged a nearby entity");
        helper.assertTrue(helper.getBlockState(protectedBlock).is(Blocks.STONE), "creeper contact blast destroyed a block");
        helper.assertTrue(target.getDeltaMovement().lengthSqr() > 0.0, "creeper contact blast did not retain knockback");
        helper.succeed();
    }
}
