package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Periodically gives eligible, unclaimed villagers one safe campfire workstation. */
public final class BuilderCampfireHandler {
    private static final int EVALUATE_INTERVAL_TICKS = 100;
    private BuilderCampfireHandler() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % EVALUATE_INTERVAL_TICKS != 0) return;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Villager villager) tryCreateCampfire(level, villager);
        }
    }

    private static void tryCreateCampfire(ServerLevel level, Villager villager) {
        BlockPos pos = villager.blockPosition();
        boolean unemployed = villager.getVillagerData().getProfession() == VillagerProfession.NONE;
        boolean bedless = !villager.getBrain().hasMemoryValue(MemoryModuleType.HOME);
        boolean darkNight = level.isNight() && level.getBrightness(LightLayer.SKY, pos) <= 7;
        boolean skyVisible = level.canSeeSky(pos.above());
        boolean alreadyRecorded = villager.getPersistentData().contains(BuilderSettlementService.CAMPFIRE_POS_TAG);
        if (!BuilderSettlementPlan.shouldCreateCampfire(unemployed, bedless, darkNight, skyVisible, alreadyRecorded)
                || !level.isEmptyBlock(pos)) return;
        AABB occupied = new AABB(pos);
        if (level.getEntities((Entity) null, occupied).stream().anyMatch(entity -> entity != villager)) return;
        level.setBlock(pos, Blocks.CAMPFIRE.defaultBlockState(), 3);
        villager.getPersistentData().putLong(BuilderSettlementService.CAMPFIRE_POS_TAG, pos.asLong());
    }
}
