package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

import java.util.Optional;

/**
 * Dynamic Towns' own fixed-size grid over {@code delivery.RegionCoord} cells: one {@code RegionCoord}
 * is one town, per MINECRAFT-388. See {@link TownConstants#REGION_SIZE_BLOCKS} for why a new fixed
 * size was needed here rather than reusing a size already defined on {@code RegionCoord} (there
 * isn't one — flagged as an open decision in the PR description).
 */
public final class TownRegion {
    private TownRegion() {}

    /** The region containing the given block coordinates, correct for negative inputs. */
    public static RegionCoord fromBlockCoords(int blockX, int blockZ) {
        return new RegionCoord(Math.floorDiv(blockX, TownConstants.REGION_SIZE_BLOCKS),
                Math.floorDiv(blockZ, TownConstants.REGION_SIZE_BLOCKS));
    }

    /** The minimum (north-west) block corner of a region. */
    public static int minBlockX(RegionCoord region) {
        return region.x() * TownConstants.REGION_SIZE_BLOCKS;
    }

    public static int minBlockZ(RegionCoord region) {
        return region.z() * TownConstants.REGION_SIZE_BLOCKS;
    }

    /**
     * [Review round 5, finding #6] The region a currently-employed villager is ACTIVELY
     * claimed/working in right now, shared by every handler that credits a town off a villager's
     * job site ({@link TownDonationHandler}, {@link TownPassiveProductionHandler}) so the same
     * validation can never drift between them. Empty for an unemployed villager, one with no
     * JOB_SITE memory at all, or - critically - one whose JOB_SITE memory's own dimension does not
     * match {@code level}: a villager cannot be actively working a job site in a dimension it is
     * not currently in, so such a memory is stale/foreign and must never credit a region merely
     * because its coordinates happen to overlap.
     */
    public static Optional<RegionCoord> liveClaimedRegion(ServerLevel level, Villager villager) {
        if (villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
            return Optional.empty();
        }
        Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty() || !jobSite.get().dimension().equals(level.dimension())) {
            return Optional.empty();
        }
        return Optional.of(fromBlockCoords(jobSite.get().pos().getX(), jobSite.get().pos().getZ()));
    }

    /** The region immediately adjacent to {@code region} in {@code edge}'s direction. */
    public static RegionCoord neighbor(RegionCoord region, TownEdge edge) {
        return switch (edge) {
            case NORTH -> new RegionCoord(region.x(), region.z() - 1);
            case SOUTH -> new RegionCoord(region.x(), region.z() + 1);
            case EAST -> new RegionCoord(region.x() + 1, region.z());
            case WEST -> new RegionCoord(region.x() - 1, region.z());
        };
    }
}
