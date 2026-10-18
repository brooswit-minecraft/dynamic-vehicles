package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * "Claimed" reuses vanilla's own job-site claim exactly, rather than inventing a second notion of
 * ownership: a workstation (here, a campfire — the same {@code BuilderPoiTypes.CAMPFIRE} POI type
 * Builder already works at) is claimed when some employed villager's brain holds it as its
 * {@link MemoryModuleType#JOB_SITE} memory, which vanilla only ever sets once that villager's
 * profession has actually acquired the POI there. A campfire nobody has walked up and taken a job
 * at — including one the mod itself just placed as a bare bootstrap or passive campsite — reports
 * unclaimed, exactly matching MINECRAFT-388's "an unclaimed campfire alone must never trigger
 * construction".
 */
public final class TownCampfireClaims {
    private TownCampfireClaims() {}

    public static boolean isClaimed(ServerLevel level, BlockPos campfirePos) {
        AABB search = new AABB(campfirePos).inflate(TownConstants.CAMPFIRE_CLAIM_SEARCH_RADIUS);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, search)) {
            if (villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                continue;
            }
            Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isPresent() && jobSite.get().pos().equals(campfirePos)) {
                return true;
            }
        }
        return false;
    }
}
