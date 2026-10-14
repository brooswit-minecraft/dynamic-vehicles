package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;

/** The contact-triggered creeper blast: visual/physical impulse, but never destructive. */
final class CreeperExplosions {
    static final int EXHAUST_BURST_AMOUNT = 16;

    private static final ExplosionDamageCalculator NON_DESTRUCTIVE_CALCULATOR = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
            return false;
        }

        @Override
        public float getEntityDamageAmount(Explosion explosion, Entity entity) {
            return 0.0F;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 1.0F;
        }
    };

    private CreeperExplosions() {}

    static void detonate(ServerLevel level, Creeper creeper) {
        level.explode(creeper, null, NON_DESTRUCTIVE_CALCULATOR, creeper.getX(), creeper.getY(), creeper.getZ(), 3.0F,
                false, net.minecraft.world.level.Level.ExplosionInteraction.MOB);
        if (AtmosphereCompat.usable()) {
            AtmosphereCompat.exhaust(level, BlockPos.containing(creeper.position()), EXHAUST_BURST_AMOUNT);
        }
        creeper.discard();
    }
}
