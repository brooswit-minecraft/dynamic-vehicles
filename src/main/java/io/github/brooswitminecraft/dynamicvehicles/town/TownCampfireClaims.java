package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

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
 *
 * <p>[Review round 2, finding #1] The claim is the villager's persistent {@code JOB_SITE} brain
 * memory itself, checked against both position AND dimension - NEVER the claiming villager's
 * current spatial distance from the campfire. A vanilla worker wanders well away from its own
 * workstation as a routine part of its schedule; that must never read as "lost its job" the way a
 * proximity radius would. This scans every loaded villager in the level (the same unbounded shape
 * {@code TownConstructionService#findClaimedCampfireSites} already uses for its own per-tick scan)
 * rather than restricting the search to a small AABB around the campfire.
 */
public final class TownCampfireClaims {
    private TownCampfireClaims() {}

    public static boolean isClaimed(ServerLevel level, BlockPos campfirePos) {
        return findClaimant(level, campfirePos).isPresent();
    }

    /**
     * The villager, if any, whose {@code JOB_SITE} memory IS this exact campfire (position and
     * dimension both matching) - not merely nearby. Exposed so a caller (construction) can keep
     * reusing the SAME discovered claimant across an entire site's build rather than re-running
     * this level-wide scan before every single block placement.
     */
    public static Optional<Villager> findClaimant(ServerLevel level, BlockPos campfirePos) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            if (isClaimedBy(villager, level, campfirePos)) {
                return Optional.of(villager);
            }
        }
        return Optional.empty();
    }

    /** {@code true} when {@code villager} is employed and its {@code JOB_SITE} memory IS exactly
     *  {@code campfirePos} in {@code level}'s own dimension - the single source of truth this
     *  whole class defers to, independent of the villager's current physical position. */
    public static boolean isClaimedBy(Villager villager, ServerLevel level, BlockPos campfirePos) {
        if (villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
            return false;
        }
        Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        return jobSite.isPresent()
                && jobSite.get().dimension().equals(level.dimension())
                && jobSite.get().pos().equals(campfirePos);
    }
}
