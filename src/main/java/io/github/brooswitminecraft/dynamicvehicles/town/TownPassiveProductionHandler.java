package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Small, ongoing passive resource trickle from town activity, independent of donations
 * (MINECRAFT-388: "passive resource production from town activity"). Every
 * {@link TownConstants#PASSIVE_PRODUCTION_INTERVAL_TICKS} (one Minecraft minute), each town credits
 * {@link TownConstants#PASSIVE_PRODUCTION_PER_WORKER} of EVERY category per claimed, employed
 * villager currently working inside it, capped at {@link TownConstants#PASSIVE_PRODUCTION_WORKER_CAP}
 * workers so an unusually large town cannot runaway-produce. Crediting every category equally
 * (rather than splitting by each worker's own profession) is a deliberate simplification, documented
 * in the PR's "Design choices made" section — profession-specific trickle is left as an easy later
 * refinement, not invented here.
 */
public final class TownPassiveProductionHandler {
    private TownPassiveProductionHandler() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % TownConstants.PASSIVE_PRODUCTION_INTERVAL_TICKS != 0) {
            return;
        }

        Map<RegionCoord, Integer> workersByRegion = new HashMap<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager villager) || villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
                continue;
            }
            Optional<GlobalPos> jobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
            if (jobSite.isEmpty()) {
                continue;
            }
            RegionCoord region = TownRegion.fromBlockCoords(jobSite.get().pos().getX(), jobSite.get().pos().getZ());
            workersByRegion.merge(region, 1, Integer::sum);
        }
        if (workersByRegion.isEmpty()) {
            return;
        }

        TownStorage storage = TownStorage.of(level.getServer());
        for (Map.Entry<RegionCoord, Integer> entry : workersByRegion.entrySet()) {
            int workers = Math.min(entry.getValue(), TownConstants.PASSIVE_PRODUCTION_WORKER_CAP);
            int credit = workers * TownConstants.PASSIVE_PRODUCTION_PER_WORKER;
            if (credit <= 0) {
                continue;
            }
            TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), entry.getKey()));
            for (TownResourceCategory category : TownResourceCategory.values()) {
                store.credit(category, credit);
            }
            storage.setDirty();
        }
    }
}
