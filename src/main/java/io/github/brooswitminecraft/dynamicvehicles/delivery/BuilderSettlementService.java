package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;

/**
 * Server-side Builder settlement anchor lookup.
 *
 * <p>MINECRAFT-388 review round 3, finding #1: the legacy chance-based "complete a free house"
 * mechanic this class used to drive directly has been retired entirely - see
 * {@link #attemptSettlement}. This class is kept only so the donation/passive triggers below have
 * somewhere to land, and so {@code CAMPFIRE_POS_TAG} (still written by
 * {@code BuilderCampfireHandler}) keeps a defined owner.
 */
public final class BuilderSettlementService {
    public static final String CAMPFIRE_POS_TAG = "dynamicvehicles.builderCampfire";
    private static final String PENDING_DONATIONS_TAG = "dynamicvehicles.pendingBuilderDonations";
    private static final int CAMPFIRE_SEARCH_RADIUS = 8;

    private BuilderSettlementService() {}

    public static void attemptDonationSettlement(Villager villager, int tradeTier) {
        attemptSettlement(villager, BuilderSettlementPlan.donationSettlementChance(
                villager.getVillagerData().getLevel(), tradeTier));
    }

    public static void queueDonationSettlement(Villager villager, int tradeTier) {
        int[] previous = villager.getPersistentData().getIntArray(PENDING_DONATIONS_TAG);
        int[] pending = java.util.Arrays.copyOf(previous, previous.length + 2);
        pending[previous.length] = villager.getVillagerData().getLevel();
        pending[previous.length + 1] = tradeTier;
        villager.getPersistentData().putIntArray(PENDING_DONATIONS_TAG, pending);
    }

    /** MerchantResultSlot awards XP after the trade event; retire only after the transaction finishes. */
    static void processPendingDonations(Villager villager) {
        int[] pending = villager.getPersistentData().getIntArray(PENDING_DONATIONS_TAG);
        villager.getPersistentData().remove(PENDING_DONATIONS_TAG);
        for (int i = 0; i + 1 < pending.length; i += 2) {
            attemptSettlement(villager, BuilderSettlementPlan.donationSettlementChance(pending[i], pending[i + 1]));
        }
    }

    public static void attemptPassiveSettlement(Villager villager) {
        attemptSettlement(villager, BuilderSettlementPlan.passiveSettlementChance(
                villager.getVillagerData().getLevel()));
    }

    private static void attemptSettlement(Villager villager, double settlementChance) {
        if (!(villager.level() instanceof ServerLevel level)) return;
        if (villager.getVillagerData().getProfession() != BuilderProfession.BUILDER.get()) return;
        BlockPos campfire = findCampfireAnchor(level, villager);
        if (campfire == null) return;
        // MINECRAFT-388 review round 3, finding #1: this legacy chance-based mechanic is retired
        // ENTIRELY - it must never complete a free house, regardless of whether this exact campfire
        // is already claimed as a town construction site. The round-2 fix (deferring only once a
        // campfire is already town-claimed) was not enough: a chance roll completing a full house
        // for free on an UNCLAIMED campfire, with no resource debit and no town claim at all, is
        // itself exactly the "free/unclaimed construction" the ticket's "claimed campfire only, no
        // free construction" rule forbids. All construction now flows exclusively through
        // io.github.brooswitminecraft.dynamicvehicles.town.TownConstructionService's claim-gated,
        // resource-funded path - this method deliberately does nothing beyond locating the anchor,
        // so a donation/passive trigger has somewhere to land without ever building anything here.
    }

    /**
     * A campfire placed by the mod keeps its precise marker; otherwise an existing,
     * player-placed vanilla campfire in the bounded loaded neighbourhood is eligible.
     */
    private static BlockPos findCampfireAnchor(ServerLevel level, Villager villager) {
        if (villager.getPersistentData().contains(CAMPFIRE_POS_TAG)) {
            BlockPos recorded = BlockPos.of(villager.getPersistentData().getLong(CAMPFIRE_POS_TAG));
            if (level.hasChunkAt(recorded) && level.getBlockState(recorded).is(Blocks.CAMPFIRE)) return recorded;
        }
        BlockPos origin = villager.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(origin.offset(-CAMPFIRE_SEARCH_RADIUS, -2, -CAMPFIRE_SEARCH_RADIUS),
                origin.offset(CAMPFIRE_SEARCH_RADIUS, 2, CAMPFIRE_SEARCH_RADIUS))) {
            if (!level.hasChunkAt(candidate) || !level.getBlockState(candidate).is(Blocks.CAMPFIRE)) continue;
            double distance = candidate.distSqr(origin);
            if (distance < bestDistance) {
                best = candidate.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }
}
