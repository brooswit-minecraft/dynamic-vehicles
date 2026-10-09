package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * Thin Minecraft-facing layer (MINECRAFT-107/120): the only class in this
 * offer-generation slice that touches {@link ServerLevel}. It does nothing
 * but look up the level-gated ring/slot limits (AC1, AC5), call
 * {@link VillagePlacementService#findVillages}, and feed each confirmed
 * result into the pure {@link OfferGenerator} (AC2-4). All of the actual
 * offer decisions live in {@link OfferConfig}/{@link OfferGenerator}, which
 * is what keeps them unit-testable (AC7) despite this class not being.
 *
 * <p>Generation here is a plain, on-demand call, not something wired to a
 * tick handler: {@link VillagePlacementService#findVillages} does a
 * blocking chunk load per confirmed result, so a caller should invoke this
 * lazily (e.g. when a player opens trade with the Dispatcher) rather than
 * every tick or for every loaded Dispatcher.
 */
public final class DispatcherOfferService {

    private DispatcherOfferService() {
    }

    /**
     * @param level         the dimension to search in
     * @param origin        the Dispatcher's own position
     * @param villagerLevel the Dispatcher villager's current profession level (1 Novice .. 5 Master); pass
     *                      {@code villager.getVillagerData().getLevel()} — vanilla's own progression, never a
     *                      parallel one (AC5)
     * @param config        offer-generation tuning (AC6)
     * @return destination offers, nearest ring first, spilling into farther rings until slots are filled or the
     *         level's allowed range is exhausted (AC1); empty if nothing was confirmed within range
     */
    public static List<DestinationOffer> generateOffers(ServerLevel level, BlockPos origin, int villagerLevel, OfferConfig config) {
        int maxRing = config.maxRingForLevel(villagerLevel);
        int slots = config.slotsForLevel(villagerLevel);
        List<ConfirmedVillage> villages = VillagePlacementService.findVillages(level, origin, maxRing, slots);

        long seed = level.getSeed();
        return villages.stream()
                .map(village -> new DestinationOffer(
                        village,
                        OfferGenerator.generateTerms(seed, village.identity().region(), village.approxDistance(), config)))
                .toList();
    }
}
