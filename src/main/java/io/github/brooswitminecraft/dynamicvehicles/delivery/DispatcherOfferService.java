package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.UUID;

/**
 * Thin Minecraft-facing layer (MINECRAFT-107/120): the only class in this
 * offer-generation slice that touches {@link ServerLevel}. It does nothing
 * but look up the level-gated ring/slot limits, call
 * {@link VillagePlacementService#findPredictedVillages}, and feed each predicted
 * result into the pure {@link OfferGenerator} (AC2-4). All of the actual
 * offer decisions live in {@link OfferConfig}/{@link OfferGenerator}, which
 * is what keeps them unit-testable (AC7) despite this class not being.
 *
 * <p>Generation here is a plain, on-demand call, not something wired to a
 * tick handler: it is invoked lazily when a player opens trade with the
 * Dispatcher, never per tick or for every loaded Dispatcher.
 */
public final class DispatcherOfferService {

    private DispatcherOfferService() {
    }

    /**
     * @param level         the dimension to search in
     * @param dispatcherId  UUID of the Dispatcher whose daily board is being generated
     * @param gameTime      current Minecraft game time; the day drives daily rotation
     * @param origin        the Dispatcher's own position
     * @param villagerLevel the Dispatcher villager's current profession level (1 Novice .. 5 Master); pass
     *                      {@code villager.getVillagerData().getLevel()} — vanilla's own progression, never a
     *                      parallel one (AC5)
     * @param config        offer-generation tuning (AC6)
     * @return deterministic daily destination offers from vanilla Village placement predictions
     */
    public static List<DestinationOffer> generateOffers(
            ServerLevel level, BlockPos origin, UUID dispatcherId, long gameTime, int villagerLevel, OfferConfig config) {
        int maxRing = config.maxRingForLevel(villagerLevel);
        int slots = config.slotsForLevel(villagerLevel);
        long orderSeed = DispatcherOfferSchedule.orderSeed(dispatcherId, DispatcherOfferSchedule.day(gameTime));
        List<PredictedVillage> villages = VillagePlacementService.findPredictedVillages(level, origin, maxRing, slots, orderSeed);

        long seed = level.getSeed();
        return villages.stream()
                .map(village -> new DestinationOffer(
                        village,
                        OfferGenerator.generateTerms(seed, village.identity().region(), village.approxDistance(), config)))
                .toList();
    }
}
