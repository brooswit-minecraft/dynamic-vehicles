package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;

/**
 * A durable construction admission for capacity requested on behalf of one resident.
 *
 * <p>This is deliberately an intent, not a POI or construction operation.  In particular, creating
 * one neither places an anchor nor reserves a bed/workstation nor debits a town.  Those effects
 * remain behind the existing construction transaction and its ownership-proven handoff.
 *
 * <p>{@code boundSiteOwner}/{@code builderId}/{@code boundPlanSignature} record that later binding
 * once it happens: the exact construction site (see {@link
 * TownStorage#siteOwner(net.minecraft.core.BlockPos)}) this admission is reserved against, the
 * temporary Builder whose claim triggered the binding, and the EXACT concrete construction plan
 * identity (kind/storehouse-variant/final-profession - {@code TownConstructionService#buildAt}'s
 * own {@code planSignature}, never this record's OWN {@code planSignature} field above, which is
 * only the ORIGINAL demand signature requested at {@code TownConstructionService#requestResidentCapacity}
 * time) that was in effect at binding. The Builder is never the beneficiary: it is tracked purely so
 * a stale/competing claimant can be told apart from the one that actually bound this exact site. The
 * concrete plan identity is tracked so a plan that changed at the same coordinate since binding
 * (e.g. a recreated site, or a mod-version zoning change) is never silently treated as the same
 * build this admission was bound against. All three are {@code null} exactly when {@link
 * State#terminal()} would be {@code false} for {@link State#AWAITING_CLAIM}/{@link
 * State#CANCELLED}, and all three are non-null for {@link State#BUILDING}/{@link State#COMPLETED}.
 *
 * <p>{@code anchorGenerationToken} [coordinator review 51271/51274, matched against NeoForge's own
 * BlockEntity/CampfireBlock/LevelChunk source] is the namespaced UUID written into the LIVE
 * campfire's own {@code BlockEntity#getPersistentData()} at bind time - ordinary block
 * removal/recreation always yields a brand-new {@code CampfireBlockEntity} with empty persistent
 * data, so this discriminates "the same continuously existing anchor, legitimately resuming" from
 * "a new anchor that merely happens to share this site's builderId/planSignature." Deliberately
 * NOT required to be non-null by THIS record's own compact constructor for a bound state (unlike
 * boundSiteOwner/builderId/boundPlanSignature, which stay strictly required): a {@code null} token
 * on an otherwise-valid BUILDING/COMPLETED record means "persisted before this field existed" - a
 * legacy record whose own site {@code TownStorage#load} must quarantine (fail-closed) rather than
 * drop the whole record, preserving both its history and its reverse-site fence. {@link
 * TownStorage#bindCapacityAdmissionToSite} - the only live-binding path - independently refuses to
 * bind with a {@code null} token, so a fresh bind is always non-null in practice.
 */
record ResidentCapacityAdmission(ResourceKey<Level> dimension, String townKey, RegionCoord region,
        UUID beneficiaryId, int requestedHomes, Map<VillagerProfession, Integer> requestedWorkplaces,
        String siteOwner, String planSignature, State state, String boundSiteOwner, UUID builderId,
        String boundPlanSignature, UUID anchorGenerationToken) {
    enum State {
        AWAITING_CLAIM,
        BUILDING,
        COMPLETED,
        CANCELLED;

        boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }

        boolean bound() {
            return this == BUILDING || this == COMPLETED;
        }
    }

    ResidentCapacityAdmission {
        if (requestedHomes < 0) {
            throw new IllegalArgumentException("requestedHomes must not be negative");
        }
        requestedWorkplaces = Map.copyOf(requestedWorkplaces);
        for (Map.Entry<VillagerProfession, Integer> entry : requestedWorkplaces.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalArgumentException("requested workplaces must have a profession and positive count");
            }
        }
        if (requestedHomes == 0 && requestedWorkplaces.isEmpty()) {
            throw new IllegalArgumentException("admission must request homes or workplaces");
        }
        if (state.bound() && (boundSiteOwner == null || boundSiteOwner.isEmpty() || builderId == null
                || boundPlanSignature == null || boundPlanSignature.isEmpty())) {
            throw new IllegalArgumentException(
                    "a BUILDING/COMPLETED admission must record its bound site, builder, and concrete plan identity");
        }
        if (!state.bound() && (boundSiteOwner != null || builderId != null || boundPlanSignature != null)) {
            throw new IllegalArgumentException("an unbound admission must not record a bound site, builder, or plan identity");
        }
    }

    /** Transitions this still-{@link State#AWAITING_CLAIM} admission to {@link State#BUILDING},
     *  reserved against exactly {@code siteOwner}/{@code concretePlanSignature}/{@code
     *  anchorToken} for {@code builderId}'s own claim - never called directly; {@link
     *  TownStorage#bindCapacityAdmissionToSite} is the sole, CAS-guarded entry point so reverse
     *  site exclusivity is always enforced alongside this transition. */
    ResidentCapacityAdmission boundTo(String siteOwner, UUID builderId, String concretePlanSignature, UUID anchorToken) {
        return new ResidentCapacityAdmission(dimension, townKey, region, beneficiaryId, requestedHomes,
                requestedWorkplaces, this.siteOwner, planSignature, State.BUILDING, siteOwner, builderId, concretePlanSignature,
                anchorToken);
    }

    /** Transitions this {@link State#BUILDING} admission to {@link State#COMPLETED} - only ever
     *  called once the beneficiary has actually, positively received its final handoff. */
    ResidentCapacityAdmission completedHandoff() {
        return new ResidentCapacityAdmission(dimension, townKey, region, beneficiaryId, requestedHomes,
                requestedWorkplaces, siteOwner, planSignature, State.COMPLETED, boundSiteOwner, builderId, boundPlanSignature,
                anchorGenerationToken);
    }
}
