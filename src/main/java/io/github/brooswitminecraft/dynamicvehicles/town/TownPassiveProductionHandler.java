package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
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
        runPassiveProductionTick(level);
    }

    /** The core per-tick work, factored out from {@link #onLevelTick} so it is directly GameTest-
     *  able without needing to construct a real {@link LevelTickEvent.Post}. */
    public static void runPassiveProductionTick(ServerLevel level) {
        // [Review round 4, finding #4's scope decision, applied consistently] Dynamic Towns is an
        // Overworld-only feature (see TownConstructionService's own gate) - passive production
        // must not credit any town in a dimension the rest of the feature never builds in.
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            return;
        }

        TownStorage storage = TownStorage.of(level.getServer());
        Map<RegionCoord, Integer> workersByRegion = new HashMap<>();
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            // [Review round 4, finding #7 / round 5, finding #6 / round 6, finding #5] A JOB_SITE
            // memory's own DIMENSION must actually match this tick's level before its position is
            // trusted, its remembered position must still hold a real block, and its region must
            // actually be founded - a stale, destroyed, or unfounded memory must never credit
            // anything just because the coordinates happen to overlap. Shared with
            // TownDonationHandler via TownRegion#liveClaimedRegion so this validation can never
            // drift between the two.
            Optional<RegionCoord> region = TownRegion.liveClaimedRegion(level, villager, storage);
            if (region.isEmpty()) {
                continue;
            }
            workersByRegion.merge(region.get(), 1, Integer::sum);
        }
        if (workersByRegion.isEmpty()) {
            return;
        }

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
