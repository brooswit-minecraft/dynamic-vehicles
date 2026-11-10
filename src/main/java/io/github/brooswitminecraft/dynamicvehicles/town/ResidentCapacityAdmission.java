package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.village.RegionCoord;
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
        boolean needsAnyWorkplace, String siteOwner, String planSignature, State state, String boundSiteOwner,
        UUID builderId, String boundPlanSignature, UUID anchorGenerationToken) {
    enum State {
        AWAITING_CLAIM,
        /** [MINECRAFT-455 self-admission liveness, coordinator design 51579-51586] A genuine third
         *  bound state: the beneficiary IS the builder IS the live claimant of this exact site -
         *  never advanced through the distinct-beneficiary handoff {@link State#BUILDING} owns,
         *  but carrying the SAME bound-fields/exclusivity/authority as {@link State#BUILDING} so
         *  the site is never left re-bindable to a different admission. */
        SELF_CLAIM_LINKED,
        BUILDING,
        COMPLETED,
        CANCELLED;

        boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }

        boolean bound() {
            return this == SELF_CLAIM_LINKED || this == BUILDING || this == COMPLETED;
        }

        /** {@code true} for a bound state construction may still actively advance against -
         *  {@link State#COMPLETED} is bound but has nothing left to authorize. */
        boolean activelyBound() {
            return this == SELF_CLAIM_LINKED || this == BUILDING;
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
        if (requestedHomes == 0 && requestedWorkplaces.isEmpty() && !needsAnyWorkplace) {
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

    /** Transitions this still-{@link State#AWAITING_CLAIM} admission to {@code targetState}
     *  (either {@link State#BUILDING}, the distinct-beneficiary handoff, or {@link
     *  State#SELF_CLAIM_LINKED}, the beneficiary-is-builder-is-claimant mode), reserved against
     *  exactly {@code siteOwner}/{@code concretePlanSignature}/{@code anchorToken} for {@code
     *  builderId}'s own claim - never called directly; {@link
     *  TownStorage#bindCapacityAdmissionToSite} is the sole, CAS-guarded entry point so reverse
     *  site exclusivity is always enforced alongside this transition. */
    ResidentCapacityAdmission boundTo(String siteOwner, UUID builderId, String concretePlanSignature, UUID anchorToken,
            State targetState) {
        if (targetState != State.BUILDING && targetState != State.SELF_CLAIM_LINKED) {
            throw new IllegalArgumentException("boundTo's target state must be BUILDING or SELF_CLAIM_LINKED, not " + targetState);
        }
        return new ResidentCapacityAdmission(dimension, townKey, region, beneficiaryId, requestedHomes,
                requestedWorkplaces, needsAnyWorkplace, this.siteOwner, planSignature, targetState, siteOwner, builderId,
                concretePlanSignature, anchorToken);
    }

    /** Transitions this {@link State#BUILDING} or {@link State#COMPLETED}-eligible {@link
     *  State#SELF_CLAIM_LINKED} admission to {@link State#COMPLETED} - only ever called once the
     *  recipient has actually, positively received its final handoff. */
    ResidentCapacityAdmission completedHandoff() {
        return new ResidentCapacityAdmission(dimension, townKey, region, beneficiaryId, requestedHomes,
                requestedWorkplaces, needsAnyWorkplace, siteOwner, planSignature, State.COMPLETED, boundSiteOwner, builderId,
                boundPlanSignature, anchorGenerationToken);
    }

    /** [MINECRAFT-455 self-admission liveness] Reverts a {@link State#SELF_CLAIM_LINKED}
     *  admission back to {@link State#AWAITING_CLAIM}, clearing every bound-field - only ever
     *  called by {@link TownStorage#unbindCapacityAdmission} on POSITIVE evidence the self-claim
     *  is genuinely gone, never on mere absence/unload. The physical site's own permanent
     *  reverse-site fence ({@code everBoundAdmissionSites}) is untouched by this - only the
     *  admission's own identity is freed to match a genuinely different future site/claimant. */
    ResidentCapacityAdmission unbound() {
        return new ResidentCapacityAdmission(dimension, townKey, region, beneficiaryId, requestedHomes,
                requestedWorkplaces, needsAnyWorkplace, siteOwner, planSignature, State.AWAITING_CLAIM, null, null, null,
                null);
    }
}
