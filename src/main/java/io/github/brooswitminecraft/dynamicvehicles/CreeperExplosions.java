package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** The contact-triggered creeper blast: visual/physical impulse, but never destructive. */
final class CreeperExplosions {
    static final int EXHAUST_BURST_AMOUNT = 16;
    private static final ThreadLocal<Creeper> DETONATING_CREEPER = new ThreadLocal<>();

    private static final ExplosionDamageCalculator NON_DESTRUCTIVE_CALCULATOR = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
            return false;
        }

        @Override
        public float getKnockbackMultiplier(Entity entity) {
            return 1.0F;
        }
    };

    private CreeperExplosions() {}

    static void detonate(ServerLevel level, Creeper creeper) {
        DamageSource source = creeper.damageSources().explosion(creeper, creeper);
        DETONATING_CREEPER.set(creeper);
        try {
            level.explode(creeper, source, NON_DESTRUCTIVE_CALCULATOR, creeper.getX(), creeper.getY(), creeper.getZ(), 3.0F,
                    false, net.minecraft.world.level.Level.ExplosionInteraction.MOB);
        } finally {
            DETONATING_CREEPER.remove();
        }
        if (AtmosphereCompat.usable()) {
            AtmosphereCompat.exhaust(level, BlockPos.containing(creeper.position()), EXHAUST_BURST_AMOUNT);
        }
        creeper.discard();
    }

    /** Preserve vanilla's impulse calculation while suppressing only this blast's applied health damage. */
    static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().getDirectEntity() == DETONATING_CREEPER.get()) {
            event.setAmount(0.0F);
        }
    }
}
