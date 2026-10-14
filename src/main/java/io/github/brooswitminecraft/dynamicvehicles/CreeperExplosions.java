package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The contact-triggered creeper blast: visual/physical impulse, but never destructive. */
final class CreeperExplosions {
    static final int EXHAUST_BURST_AMOUNT = 16;
    private static final double BLAST_RADIUS = 6.0;

    private static final ExplosionDamageCalculator NON_DESTRUCTIVE_CALCULATOR = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state, float power) {
            return false;
        }

        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }
    };

    private CreeperExplosions() {}

    static void detonate(ServerLevel level, Creeper creeper) {
        Vec3 center = creeper.position();
        level.explode(creeper, null, NON_DESTRUCTIVE_CALCULATOR, center.x, center.y, center.z, 3.0F,
                false, net.minecraft.world.level.Level.ExplosionInteraction.MOB);
        applyImpulse(level, creeper, center);
        if (AtmosphereCompat.usable()) {
            AtmosphereCompat.exhaust(level, BlockPos.containing(creeper.position()), EXHAUST_BURST_AMOUNT);
        }
        creeper.discard();
    }

    /** Apply the blast's physics separately so zero damage never suppresses the impulse. */
    private static void applyImpulse(ServerLevel level, Creeper creeper, Vec3 center) {
        for (Entity entity : level.getEntities(creeper, new AABB(center, center).inflate(BLAST_RADIUS))) {
            Vec3 offset = entity.position().subtract(center);
            double distance = offset.length();
            if (distance > 0.0 && distance < BLAST_RADIUS) {
                entity.push(offset.scale((1.0 - distance / BLAST_RADIUS) / distance));
            }
        }
    }
}
