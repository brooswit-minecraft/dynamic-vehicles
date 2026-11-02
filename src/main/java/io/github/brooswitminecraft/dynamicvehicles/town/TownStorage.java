package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.npc.VillagerProfession;

/**
 * Server-wide persistence for Dynamic Towns: one {@link TownResourceStore} per town, plus the
 * one-way "bootstrap already happened" claim per town, keyed identically to
 * {@code delivery.SettlementFounder#settlementKey} ({@code dimension:regionX:regionZ}). Attached to
 * the overworld's own data storage, the same place {@code delivery.DeliveryContractStorage} keeps
 * its cross-dimension, per-server data — this is server-wide state, not tied to whichever
 * dimension happens to be loaded.
 *
 * <p>[Review round 4, finding #2 / round 5, finding #2] Also persists exactly which world cells
 * Dynamic Towns itself has actually placed-and-paid-for ({@link #placedCells}) — the real
 * "construction progress" state the ticket's acceptance criteria require, and the single source of
 * truth {@link TownConstructionService} and {@link TownRoadConstructionService} now use to decide
 * "is this cell already done" instead of diffing the live world against the plan. A foreign block
 * that merely happens to match the planned material (player-placed, naturally generated, or left
 * over from some other mod) is never recorded here, so it can never be silently adopted as "already
 * paid for" the way live-world diffing used to let it.
 *
 * <p>Each recorded cell is scoped to the exact plan that claims it (a building site's own campfire
 * anchor, or a region's road plan) via an opaque {@code owner} key, never merely dimension+position.
 * Two different plans can legitimately claim the same coordinate over a town's lifetime (a road
 * cell later re-planned as part of a building's footprint, or vice versa); without an owner, the
 * SECOND plan would read the FIRST plan's already-paid progress as its own free, pre-paid cell the
 * instant the live block also happened to match its own planned material.
 *
 * <p>[Review round 7, finding #1] Beyond the owner, each record also carries an opaque
 * {@code signature} (expected block + cost category + template/plan identity, see
 * {@link TownConstructionService}'s own signature construction) - the SAME owner (the campfire
 * never moves) can still see a genuinely different plan over a site's lifetime, e.g. after the
 * mod's own template/zoning logic changes between versions, or (in a future multi-stage plan) a
 * site is deliberately replanned. A cell paid for under the OLD plan's signature must never be
 * silently treated as already-paid progress for a NEW, different plan at the same position.
 *
 * <p>[Review follow-up] Every mutator below calls {@link #setDirty()} itself. A real server save
 * only ever writes a {@link SavedData} whose own {@code isDirty()} is true - a mutator that forgot
 * to flag it (several callers used to do this externally, inconsistently, instead) would have its
 * change silently survive only until the next server restart, with no error anywhere to reveal it.
 * Keeping this self-contained means a caller can never get it wrong by omission.
 */
public final class TownStorage extends SavedData {
    private static final String NAME = "dynamicvehicles_towns";

    /** An owner's claim on one persisted cell: WHO placed it (see {@link #siteOwner(BlockPos)} /
     *  {@link #roadOwner}), WHAT, exactly, was placed and paid for there (an opaque signature
     *  capturing the expected block, cost category, and template/plan identity) - see
     *  {@link #isCellPlaced(ResourceKey, BlockPos, String, String)} - and, when the placed state is
     *  itself a villager fixture, the id of the POI type it registered (see
     *  {@link #isTownBuiltFixture}). {@code fixturePoi} is empty for every other cell (walls, floors,
     *  roofs, doors, road surface, a bed's foot half) and for any record saved before this field
     *  existed. */
    private record PlacedCell(String owner, String signature, String fixturePoi) {}

    private final Set<String> foundedTowns = new HashSet<>();
    private final Map<String, TownResourceStore> stores = new HashMap<>();
    private final Map<String, PlacedCell> placedCells = new HashMap<>();
    private final Map<String, BlockPos> pendingCampfires = new HashMap<>();
    private final Map<String, Integer> siteFloors = new HashMap<>();
    private final Set<String> blockedCells = new HashSet<>();
    /** A building terrain transaction whose rollback cannot be verified.  Unlike a blocked cell,
     * this survives later terrain/heightmap changes that could otherwise hide the exact support
     * position from the next preparation scan. */
    private final Set<String> blockedTerrainSites = new HashSet<>();
    private final Map<String, UUID> reservationOwners = new HashMap<>();
    /** Durable, beneficiary-keyed capacity admissions.  They are intents only; no POI ticket or
     * resource debit is represented here. */
    private final Map<String, ResidentCapacityAdmission> capacityAdmissions = new HashMap<>();
    /** [coordinator review, comment 51266] Dimension-qualified construction sites whose own
     *  persisted admission-binding state is ambiguous or unrecoverable - a malformed BUILDING/
     *  COMPLETED record that lost its bound-site/builder fence while the world/progress survived, or
     *  two different admissions both claiming the same site. Never guessed at; every effect at a
     *  quarantined site is blocked until this is resolved out of band. Best-effort: this can only
     *  fence a site whose own {@code Dimension}/{@code BoundSiteOwner} fields are themselves still
     *  readable (see {@link #load}'s own quarantine-on-catch); a record so corrupted that even those
     *  are lost is simply dropped, with no site identity left to quarantine - callers must never read
     *  an EMPTY quarantine set as proof every malformed record was safely fenced. */
    private final Set<String> quarantinedAdmissionSites = new HashSet<>();
    /** [coordinator review 51268, P1] Every dimension-qualified site EVER bound (BUILDING or
     *  COMPLETED) to ANY admission - permanent, and deliberately independent of the beneficiary-
     *  keyed {@link ResidentCapacityAdmission} record itself. {@link #createOrReuseCapacityAdmission}
     *  legitimately replaces a TERMINAL admission at the SAME beneficiary identity with a fresh,
     *  unbound one for a later, unrelated request - which would otherwise erase the old record's own
     *  {@code boundSiteOwner} fence and let a since-overwritten site be silently rebound to a
     *  completely different admission. Once a site is ever bound, it is bound forever: it can never
     *  again satisfy {@link #bindCapacityAdmissionToSite}, regardless of what happens to the
     *  beneficiary record that originally bound it. This does not block ordinary claimant-only
     *  construction at the same coordinate - only a NEW admission ever binding there again. */
    private final Set<String> everBoundAdmissionSites = new HashSet<>();
    /** Narrow, server-owned identity for donation boxes.  This is deliberately not a general
     * container ownership system: it records only the box placed at this exact world cell. */
    private final Map<String, DonationBoxBinding> donationBoxes = new HashMap<>();
    private final Map<String, Long> donationBoxGenerations = new HashMap<>();
    private final Map<String, DonationOperation> donationOperations = new HashMap<>();

    public record DonationBoxBinding(ResourceKey<Level> dimension, BlockPos pos, String townKey, UUID boxId, long generation) {}
    public record DonationPayload(String itemId, int count, TownResourceCategory category, int credit) {
        public DonationPayload {
            if (itemId == null || itemId.isEmpty() || count <= 0 || credit <= 0) {
                throw new IllegalArgumentException("invalid donation payload");
            }
        }
    }
    public enum DonationOperationState { RESERVED, APPLIED, ABORTED, COMPLETED }
    public record DonationOperation(DonationBoxBinding binding, long sequence, DonationPayload payload,
                                    DonationOperationState state) {}

    public static TownStorage of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(TownStorage::new, TownStorage::load, null), NAME);
    }

    public boolean hasFoundedTown(String key) {
        return foundedTowns.contains(key);
    }

    public void markTownFounded(String key) {
        foundedTowns.add(key);
        setDirty();
    }

    /** Returns {@code townKey}'s resource store, creating a fresh all-zero one (and marking this
     *  storage dirty for having done so) the first time it is ever asked for. Still safe for
     *  READ-ONLY use ({@code balance}/{@code canAfford}/{@code snapshot}) - {@link TownResourceStore}
     *  itself has no reference back to this owning {@code TownStorage} and so cannot mark it dirty
     *  on its own, which is exactly why every actual BALANCE mutation must instead go through
     *  {@link #creditStore}/{@link #tryDebitStore} below, never through this store's own
     *  {@code credit}/{@code tryDebit} directly. */
    public TownResourceStore storeFor(String townKey) {
        return stores.computeIfAbsent(townKey, k -> {
            setDirty();
            return new TownResourceStore();
        });
    }

    /**
     * [Review round 21 follow-up] The sole path for crediting a town's resource balance - marks
     * this storage dirty itself, since the returned {@link TownResourceStore} from {@link #storeFor}
     * has no way to do so on its own. A caller that credited the raw store directly (the previous
     * pattern, relying on each caller to separately remember {@code setDirty()}) could silently
     * leave a real donation/production credit in memory only, lost on the next server restart.
     */
    public void creditStore(String townKey, TownResourceCategory category, int amount) {
        storeFor(townKey).credit(category, amount);
        setDirty();
    }

    /** The sole path for debiting a town's resource balance (see {@link TownResourceStore#tryDebit}
     *  for the no-partial-charge contract) - marks this storage dirty only when the debit actually
     *  changed the balance; a declined attempt leaves nothing new to persist. */
    public boolean tryDebitStore(String townKey, TownResourceCategory category, int amount) {
        boolean debited = storeFor(townKey).tryDebit(category, amount);
        if (debited) {
            setDirty();
        }
        return debited;
    }

    /** Allocates a replacement-safe donation-box identity only for an already founded town. */
    public Optional<DonationBoxBinding> bindDonationBox(ResourceKey<Level> dimension, BlockPos pos) {
        String townKey = TownKeys.keyFor(dimension, TownRegion.fromBlockCoords(pos.getX(), pos.getZ()));
        if (!hasFoundedTown(townKey)) return Optional.empty();
        String key = cellKey(dimension, pos);
        long generation = donationBoxGenerations.getOrDefault(key, 0L) + 1L;
        donationBoxGenerations.put(key, generation);
        DonationBoxBinding binding = new DonationBoxBinding(dimension, pos.immutable(), townKey, UUID.randomUUID(), generation);
        donationBoxes.put(key, binding);
        setDirty();
        return Optional.of(binding);
    }

    public boolean isActiveDonationBox(DonationBoxBinding binding) {
        return binding != null && binding.equals(donationBoxes.get(cellKey(binding.dimension(), binding.pos())));
    }

    public Optional<DonationBoxBinding> activeDonationBoxAt(ResourceKey<Level> dimension, BlockPos pos) {
        return Optional.ofNullable(donationBoxes.get(cellKey(dimension, pos)));
    }

    /** Retires only the live binding; donationBoxGenerations deliberately remains as the
     * replacement high-water, so a later box at this cell cannot reuse receipts. */
    public boolean retireDonationBox(DonationBoxBinding binding) {
        if (!isActiveDonationBox(binding) || activeDonationOperation(binding).isPresent()) return false;
        donationBoxes.remove(cellKey(binding.dimension(), binding.pos()));
        setDirty();
        return true;
    }

    public Optional<DonationOperation> activeDonationOperation(DonationBoxBinding binding) {
        DonationOperation operation = binding == null ? null : donationOperations.get(donationOperationKey(binding));
        return operation == null || operation.state() == DonationOperationState.COMPLETED || operation.state() == DonationOperationState.ABORTED
                ? Optional.empty() : Optional.of(operation);
    }

    /** Includes COMPLETED/ABORTED for load reconciliation; callers deciding new intake must use
     * activeDonationOperation instead. */
    Optional<DonationOperation> donationOperationReceipt(DonationBoxBinding binding) {
        return binding == null ? Optional.empty() : Optional.ofNullable(donationOperations.get(donationOperationKey(binding)));
    }

    /** Storage, never a block-entity snapshot, allocates the next sequence before escrow changes. */
    public Optional<DonationOperation> reserveDonationOperation(DonationBoxBinding binding, DonationPayload payload) {
        if (!isActiveDonationBox(binding)) return Optional.empty();
        String key = donationOperationKey(binding);
        DonationOperation existing = donationOperations.get(key);
        if (existing != null && (existing.state() == DonationOperationState.RESERVED || existing.state() == DonationOperationState.APPLIED)) {
            return Optional.empty();
        }
        long sequence = existing == null ? 1L : existing.sequence() + 1L;
        DonationOperation operation = new DonationOperation(binding, sequence, payload, DonationOperationState.RESERVED);
        donationOperations.put(key, operation);
        setDirty();
        return Optional.of(operation);
    }

    /** Credits exactly one currently reserved payload.  A same-sequence different payload is never a replay. */
    public boolean applyDonationReceipt(DonationOperation operation) {
        DonationOperation current = operation == null ? null : donationOperations.get(donationOperationKey(operation.binding()));
        // State is intentionally excluded from receipt identity: a replay of the original
        // RESERVED request after storage has persisted APPLIED is the normal retry case.
        if (current == null || !sameDonationReceipt(current, operation) || !isActiveDonationBox(operation.binding())) return false;
        if (current.state() == DonationOperationState.APPLIED) return true;
        if (current.state() != DonationOperationState.RESERVED) return false;
        creditStore(operation.binding().townKey(), operation.payload().category(), operation.payload().credit());
        donationOperations.put(donationOperationKey(operation.binding()),
                new DonationOperation(operation.binding(), operation.sequence(), operation.payload(), DonationOperationState.APPLIED));
        setDirty();
        return true;
    }

    private static boolean sameDonationReceipt(DonationOperation left, DonationOperation right) {
        return left.binding().equals(right.binding()) && DonationBoxReceiptReducer.matches(
                new DonationBoxReceiptReducer.Pending(left.sequence(), reducerPayload(left.payload()),
                        left.state() == DonationOperationState.APPLIED), right.sequence(), reducerPayload(right.payload()));
    }

    private static DonationBoxReceiptReducer.Payload reducerPayload(DonationPayload payload) {
        return new DonationBoxReceiptReducer.Payload(payload.itemId(), payload.count(), payload.category(), payload.credit());
    }

    /** Ends the one active recovery operation.  Its sequence remains in the binding's high-water. */
    public boolean completeDonationOperation(DonationOperation operation) {
        DonationOperation current = operation == null ? null : donationOperations.get(donationOperationKey(operation.binding()));
        if (current == null || current.sequence() != operation.sequence() || !current.payload().equals(operation.payload())
                || current.state() != DonationOperationState.APPLIED) return false;
        donationOperations.put(donationOperationKey(operation.binding()),
                new DonationOperation(current.binding(), current.sequence(), current.payload(), DonationOperationState.COMPLETED));
        setDirty();
        return true;
    }

    public boolean abortDonationOperation(DonationOperation operation) {
        DonationOperation current = operation == null ? null : donationOperations.get(donationOperationKey(operation.binding()));
        if (current == null || !current.equals(operation) || current.state() != DonationOperationState.RESERVED) return false;
        donationOperations.put(donationOperationKey(operation.binding()),
                new DonationOperation(current.binding(), current.sequence(), current.payload(), DonationOperationState.ABORTED));
        setDirty();
        return true;
    }

    private static String donationOperationKey(DonationBoxBinding binding) {
        return cellKey(binding.dimension(), binding.pos()) + "#" + binding.boxId() + "#" + binding.generation();
    }

    /** {@code true} iff Dynamic Towns' plan identified by {@code owner} (e.g.
     *  {@link #siteOwner(BlockPos)} or {@link #roadOwner(ResourceKey, io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord)})
     *  has already placed-and-paid-for {@code pos} in {@code dimension} UNDER THE EXACT SAME
     *  {@code signature} (expected block/cost/template identity) — never merely "the live block
     *  happens to match the plan", never merely "SOME plan already claimed this cell" (a different
     *  owner's progress never counts as this owner's), and never merely "this owner claimed SOME
     *  cell here before" (the SAME owner's progress under a now-superseded plan/template never
     *  counts as prepaid for a genuinely different current plan at the same position). */
    public boolean isCellPlaced(ResourceKey<Level> dimension, BlockPos pos, String owner, String signature) {
        PlacedCell cell = placedCells.get(cellKey(dimension, pos));
        return cell != null && cell.owner().equals(owner) && cell.signature().equals(signature);
    }

    /** {@code true} iff some Dynamic Towns plan (any owner, any signature) placed-and-paid-for a
     *  villager fixture at {@code pos} in {@code dimension}, and {@code liveState} is still that same
     *  fixture - for callers that need to know "is this bed/workstation one of the town's own
     *  fixtures" (see {@code TownResidentAllocationHandler}'s candidate filtering, round 19 finding
     *  #2), unlike {@link #isCellPlaced} which also demands an exact owner+signature match against
     *  one specific plan. A same-region vanilla- or player-placed bed/workstation was never recorded
     *  here, so it can never be mistaken for a town's own fixture just because it happens to sit in
     *  that town's region.
     *
     * <p>[Review round 25, P2] A record alone is not enough: it proves only that the town once paid
     * for SOMETHING at this coordinate. A player who replaces a paid wall, floor or road cell (or the
     * foot half of a town bed) with their own bed or workstation leaves that historical record in
     * place, but the town never placed a fixture there, so it is never eligible. Likewise a town
     * workstation swapped for a different one no longer registers the POI type the town placed.
     * Identity is the POI type rather than the exact block state, so the town's own fixture stays
     * eligible through ordinary state changes (a bed being slept in, a furnace lit, a cauldron
     * filled). */
    public boolean isTownBuiltFixture(ResourceKey<Level> dimension, BlockPos pos, BlockState liveState) {
        PlacedCell cell = placedCells.get(cellKey(dimension, pos));
        return cell != null && !cell.fixturePoi().isEmpty() && cell.fixturePoi().equals(fixturePoiOf(liveState));
    }

    /**
     * [Review round 20, finding #1] {@code PoiManager} itself tracks only a free-ticket COUNT per
     * position - never which entity holds one - so "this POI's ticket is occupied" can never by
     * itself prove "occupied BY villagerId". This is Dynamic Towns' own, separate record of exactly
     * which villager a reservation THIS MOD granted actually belongs to, kept only for positions this
     * mod itself has reserved (see {@link TownResidentAllocationHandler}/{@code TownConstructionService}
     * callers) - a reservation this mod never tracked at all (pre-existing/vanilla-origin) is simply
     * absent here, NOT evidence of anyone's ownership either way.
     *
     * <p>{@code true} only when a DIFFERENT villager is positively, currently on record as this
     * reservation's owner - the one case this mod can actually prove makes reusing or releasing
     * {@code pos} on {@code villagerId}'s behalf unsafe. An absent record (never tracked, or already
     * cleared) deliberately returns {@code false}: there is no positive evidence it belongs to
     * anyone else, so treating it as still safe to validate/release preserves this mod's existing,
     * already-reviewed leak-prevention behavior for every reservation that predates this tracking.
     */
    public boolean isReservedByADifferentVillager(ResourceKey<Level> dimension, BlockPos pos, UUID villagerId) {
        UUID owner = reservationOwners.get(cellKey(dimension, pos));
        return owner != null && !owner.equals(villagerId);
    }

    /**
     * [Review round 24] {@code true} only when {@code villagerId} is POSITIVELY, currently on
     * record as {@code pos}'s reservation owner - the one thing this mod can actually prove safe to
     * act on. Unlike {@link #isReservedByADifferentVillager}, an ABSENT record returns {@code false}
     * here too (not just when a different villager owns it): releasing a reservation requires real
     * proof it is {@code villagerId}'s own, not merely the absence of proof it is someone else's -
     * see {@code TownResidentAllocationHandler#releaseOldReservation}'s own doc for why those two
     * are not the same thing, and why conflating them was itself a past review finding.
     */
    public boolean isReservedBySelf(ResourceKey<Level> dimension, BlockPos pos, UUID villagerId) {
        return villagerId.equals(reservationOwners.get(cellKey(dimension, pos)));
    }

    /** Records that {@code villagerId} is now the one this mod granted the reservation at
     *  {@code pos} to - called exactly once, right after this mod's own code actually secures a new
     *  ticket there, so a later stale-memory check against a DIFFERENT villager has something real
     *  to compare against. */
    public void setReservationOwner(ResourceKey<Level> dimension, BlockPos pos, UUID villagerId) {
        reservationOwners.put(cellKey(dimension, pos), villagerId);
        setDirty();
    }

    /** Clears {@code pos}'s ownership record - called once its actual ticket is released, so a
     *  future occupant never gets read as still "owned" by whoever held it before. */
    public void clearReservationOwner(ResourceKey<Level> dimension, BlockPos pos) {
        reservationOwners.remove(cellKey(dimension, pos));
        setDirty();
    }

    Optional<ResidentCapacityAdmission> capacityAdmissionFor(ResourceKey<Level> dimension, RegionCoord region,
            UUID beneficiaryId) {
        return Optional.ofNullable(capacityAdmissions.get(capacityAdmissionKey(dimension, region, beneficiaryId)));
    }

    /** Creates one admission atomically for the durable beneficiary identity.  A later allocation
     * tick must reuse a nonterminal admission rather than manufacture another anchor, debit, or
     * reservation. */
    ResidentCapacityAdmission createOrReuseCapacityAdmission(ResidentCapacityAdmission admission) {
        String key = capacityAdmissionKey(admission.dimension(), admission.region(), admission.beneficiaryId());
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing != null && !existing.state().terminal()) {
            return existing;
        }
        capacityAdmissions.put(key, admission);
        setDirty();
        return admission;
    }

    /**
     * Reconciles only an unbound capacity intent at its existing durable beneficiary identity.
     * {@link ResidentCapacityAdmission.State#AWAITING_CLAIM} has not placed an anchor, selected a
     * builder, debited resources, or reserved a POI, so replacing its now-stale requested profile
     * cannot abandon started work or create a second site. BUILDING is deliberately not replaceable:
     * a changed beneficiary need must fail closed until the existing construction lifecycle resolves
     * that in-flight transaction.
     */
    Optional<ResidentCapacityAdmission> replaceAwaitingCapacityAdmission(ResidentCapacityAdmission admission) {
        String key = capacityAdmissionKey(admission.dimension(), admission.region(), admission.beneficiaryId());
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing == null || existing.state() != ResidentCapacityAdmission.State.AWAITING_CLAIM) {
            return Optional.empty();
        }
        capacityAdmissions.put(key, admission);
        setDirty();
        return Optional.of(admission);
    }

    static String capacityAdmissionKey(ResourceKey<Level> dimension, RegionCoord region, UUID beneficiaryId) {
        return dimension.location() + "@" + region.x() + "," + region.z() + ":" + beneficiaryId;
    }

    int capacityAdmissionCount() {
        return capacityAdmissions.size();
    }

    /** Every still-{@link ResidentCapacityAdmission.State#AWAITING_CLAIM} admission for a
     *  dimension/region - a small, town-scale set, safe for a construction site to scan when
     *  weighing whether an unbound admission's actual need matches what this exact site offers. */
    List<ResidentCapacityAdmission> awaitingCapacityAdmissionsFor(ResourceKey<Level> dimension, RegionCoord region) {
        List<ResidentCapacityAdmission> result = new ArrayList<>();
        for (ResidentCapacityAdmission admission : capacityAdmissions.values()) {
            if (admission.dimension().equals(dimension) && admission.region().equals(region)
                    && admission.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM) {
                result.add(admission);
            }
        }
        return result;
    }

    /** The admission (if any) already bound - {@link ResidentCapacityAdmission.State#BUILDING} or
     *  {@link ResidentCapacityAdmission.State#COMPLETED} - to exactly this construction site.
     *  Reverse site exclusivity: a site can never be bound to two different admissions. Never
     *  matches a {@link #isAdmissionSiteQuarantined} site - its own binding is unresolvable and a
     *  caller must never read it as either validly bound or safely unbound. */
    Optional<ResidentCapacityAdmission> capacityAdmissionBoundToSite(ResourceKey<Level> dimension, String siteOwner) {
        if (isAdmissionSiteQuarantined(dimension, siteOwner)) {
            return Optional.empty();
        }
        for (ResidentCapacityAdmission admission : capacityAdmissions.values()) {
            if (admission.dimension().equals(dimension) && siteOwner.equals(admission.boundSiteOwner())) {
                return Optional.of(admission);
            }
        }
        return Optional.empty();
    }

    /** [coordinator review, comment 51266] {@code true} when this exact dimension-qualified site's
     *  own admission-binding state is ambiguous or unrecoverable - see {@link
     *  #quarantinedAdmissionSites}. A caller must block every construction effect here rather than
     *  guess which (if any) persisted record is the real one. */
    boolean isAdmissionSiteQuarantined(ResourceKey<Level> dimension, String siteOwner) {
        return quarantinedAdmissionSites.contains(quarantinedSiteKey(dimension, siteOwner));
    }

    /** [coordinator review 51268, P1] {@code true} once {@code siteOwner} has EVER been bound to an
     *  admission - see {@link #everBoundAdmissionSites}. Permanent: survives the original binding's
     *  own beneficiary record later being replaced by an unrelated request. */
    boolean hasSiteEverBeenBoundToAnAdmission(ResourceKey<Level> dimension, String siteOwner) {
        return everBoundAdmissionSites.contains(quarantinedSiteKey(dimension, siteOwner));
    }

    private static String quarantinedSiteKey(ResourceKey<Level> dimension, String siteOwner) {
        return dimension.location() + "@" + siteOwner;
    }

    /**
     * Binds exactly one still-unbound admission to {@code siteOwner} under the EXACT concrete
     * construction plan identity {@code concretePlanSignature} in effect right now, atomically:
     * fails (returns empty, changing nothing) if the admission has since left {@code
     * AWAITING_CLAIM} (e.g. a concurrent bind to a different site, or a stale caller reference), if
     * some OTHER admission is already bound to this exact site, if the site is quarantined, or if
     * this site has EVER been bound before (see {@link #everBoundAdmissionSites} - permanent,
     * independent of whether that original binding's own beneficiary record still exists).
     *
     * <p>[coordinator review 51276] {@code anchorTokenSupplier} is called ONLY once every one of
     * those other eligibility conditions has already been confirmed, as the LAST step before
     * commit - never earlier. A denied bind (quarantine, everBound, a stale candidate reference)
     * must never write to - and so never overwrite - a live anchor's own pre-existing marker for a
     * binding that was never actually going to commit anyway. The supplier itself returning empty
     * (no live anchor to carry a token at all) is likewise treated as a plain denial: nothing is
     * recorded without a live anchor to actually carry its token. This is the sole CAS-guarded
     * entry point into {@link ResidentCapacityAdmission#boundTo}, so reverse site exclusivity can
     * never be bypassed, including by a later unrelated request overwriting a terminal admission at
     * the same beneficiary identity.
     */
    Optional<ResidentCapacityAdmission> bindCapacityAdmissionToSite(ResidentCapacityAdmission awaiting, String siteOwner,
            UUID builderId, String concretePlanSignature, java.util.function.Supplier<Optional<UUID>> anchorTokenSupplier) {
        if (isAdmissionSiteQuarantined(awaiting.dimension(), siteOwner)
                || hasSiteEverBeenBoundToAnAdmission(awaiting.dimension(), siteOwner)) {
            return Optional.empty();
        }
        String key = capacityAdmissionKey(awaiting.dimension(), awaiting.region(), awaiting.beneficiaryId());
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing == null || existing.state() != ResidentCapacityAdmission.State.AWAITING_CLAIM
                || capacityAdmissionBoundToSite(awaiting.dimension(), siteOwner).isPresent()) {
            return Optional.empty();
        }
        Optional<UUID> anchorToken = anchorTokenSupplier.get();
        if (anchorToken.isEmpty()) {
            return Optional.empty();
        }
        ResidentCapacityAdmission bound = existing.boundTo(siteOwner, builderId, concretePlanSignature, anchorToken.get());
        capacityAdmissions.put(key, bound);
        everBoundAdmissionSites.add(quarantinedSiteKey(awaiting.dimension(), siteOwner));
        setDirty();
        return Optional.of(bound);
    }

    /**
     * Marks a {@code BUILDING} admission {@code COMPLETED} once its beneficiary has actually,
     * positively received the final handoff - never on blocks-finished or Builder-assigned fixtures
     * alone. Only succeeds against the EXACT site/builder/concrete-plan/anchor-token that bound it; a
     * stale, already-resolved, or mismatched admission is left untouched rather than silently
     * rebound or double-completed.
     */
    Optional<ResidentCapacityAdmission> completeCapacityAdmission(ResidentCapacityAdmission bound) {
        String key = capacityAdmissionKey(bound.dimension(), bound.region(), bound.beneficiaryId());
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing == null || existing.state() != ResidentCapacityAdmission.State.BUILDING
                || !existing.boundSiteOwner().equals(bound.boundSiteOwner()) || !existing.builderId().equals(bound.builderId())
                || !existing.boundPlanSignature().equals(bound.boundPlanSignature())
                || !Objects.equals(existing.anchorGenerationToken(), bound.anchorGenerationToken())) {
            return Optional.empty();
        }
        ResidentCapacityAdmission completed = existing.completedHandoff();
        capacityAdmissions.put(key, completed);
        setDirty();
        return Optional.of(completed);
    }

    /**
     * [Review round 25] Reconciles {@code pos}'s ownership record against a LIVE observation that
     * {@code claimantId}'s own memory currently points at it. A record naming a DIFFERENT villager
     * is a historical fact the world has since contradicted (vanilla released and re-granted the
     * ticket outside this mod's bookkeeping, or one of the two memories is stale), and this mod can
     * no longer prove which of them really holds the ticket - so the record is dropped, leaving the
     * reservation unresolved. Unresolved reservations are preserved, never released (see
     * {@link #isReservedBySelf}), so this can only ever turn a would-be release into a kept ticket,
     * never the reverse. The observed claimant is deliberately NOT recorded as the new owner: an
     * observation is not proof of ownership either (round 24).
     *
     * @return {@code true} if a contradicted record was dropped
     */
    public boolean reconcileObservedClaim(ResourceKey<Level> dimension, BlockPos pos, UUID claimantId) {
        String key = cellKey(dimension, pos);
        UUID owner = reservationOwners.get(key);
        if (owner == null || owner.equals(claimantId)) {
            return false;
        }
        reservationOwners.remove(key);
        setDirty();
        return true;
    }

    /** Records that {@code pos} in {@code dimension} has now actually been placed and paid for by
     *  the plan identified by {@code owner}, under exactly {@code signature}, as {@code placedState}
     *  — the durable "construction progress" record a reload must not lose. Overwrites whichever
     *  plan (if any) previously claimed this exact cell: the world itself is the final arbiter of
     *  "what is here now", and only one plan can own a given cell's paid progress at a time. */
    public void markCellPlaced(ResourceKey<Level> dimension, BlockPos pos, String owner, String signature, BlockState placedState) {
        placedCells.put(cellKey(dimension, pos), new PlacedCell(owner, signature, fixturePoiOf(placedState)));
        setDirty();
    }

    /**
     * [Review round 10, finding #1] {@code true} iff {@code pos} in {@code dimension} was
     * permanently taken out of construction after a mutation AND its own rollback both failed (see
     * {@link #markCellBlocked}) - a durable, reload-surviving hold, never merely a local variable
     * that forgets the failure the instant this tick's call returns. A cell left in a genuinely
     * unknown, partially-mutated state after both the forward write and its rollback failed must
     * never be silently retried: a later tick could re-clear/re-charge/re-credit a cell this mod
     * cannot actually verify the true state of, risking a double charge or double salvage credit for
     * work this mod is no longer sure it ever actually paid for cleanly.
     */
    public boolean isCellBlocked(ResourceKey<Level> dimension, BlockPos pos) {
        return blockedCells.contains(cellKey(dimension, pos));
    }

    /** Permanently (durably, across ticks and reloads) takes {@code pos} in {@code dimension} out
     *  of construction - see {@link #isCellBlocked}. There is no automatic unblock: once both a
     *  mutation and its own rollback have failed, this mod can no longer trust what is actually
     *  there, so the only safe response is to never touch this exact cell again. */
    public void markCellBlocked(ResourceKey<Level> dimension, BlockPos pos) {
        blockedCells.add(cellKey(dimension, pos));
        setDirty();
    }

    public boolean isTerrainSiteBlocked(ResourceKey<Level> dimension, BlockPos campfire) {
        return blockedTerrainSites.contains(cellKey(dimension, campfire));
    }

    public void markTerrainSiteBlocked(ResourceKey<Level> dimension, BlockPos campfire) {
        blockedTerrainSites.add(cellKey(dimension, campfire));
        setDirty();
    }

    /**
     * The floor Y a building site's own {@code owner} (see {@link #siteOwner(BlockPos)}) committed
     * to the FIRST time it was ever estimated, reused on every later tick rather than re-derived from
     * the live world each time. Without this, once terrain prep clears a trunk the campfire was
     * standing on, the NEXT tick's fresh estimate would no longer see that (now-removed) trunk
     * material to walk down through, snapping the estimate straight back up to the campfire's own
     * elevated Y mid-build and silently replanning the whole site around an inconsistent floor.
     */
    public Optional<Integer> floorFor(String owner) {
        return Optional.ofNullable(siteFloors.get(owner));
    }

    public void setFloor(String owner, int floorY) {
        siteFloors.put(owner, floorY);
        setDirty();
    }

    /** Called once a site is fully built and its claimant released - the floor commitment has
     *  served its purpose and need not be retained forever. */
    public void clearFloor(String owner) {
        siteFloors.remove(owner);
        setDirty();
    }

    /**
     * [Review round 5, finding #8] The exact campfire position a PRIOR {@code TownFounder} bootstrap
     * attempt for {@code key} already placed, whose companion villager spawn (or whose own orphan
     * cleanup) did not confirm. A retry must reuse this exact persisted position rather than
     * re-deriving a fresh one via the heightmap: the still-standing orphan campfire itself now reads
     * as "the ground" there, so a fresh heightmap query would land one block ABOVE it and place a
     * second, duplicate campfire.
     */
    public Optional<BlockPos> pendingCampfireFor(String key) {
        return Optional.ofNullable(pendingCampfires.get(key));
    }

    public void setPendingCampfire(String key, BlockPos pos) {
        pendingCampfires.put(key, pos);
        setDirty();
    }

    public void clearPendingCampfire(String key) {
        pendingCampfires.remove(key);
        setDirty();
    }

    /** The stable owner key for a building site, anchored on its own original campfire position -
     *  stable across the whole build even once that cell's own block is no longer a campfire. */
    public static String siteOwner(BlockPos campfire) {
        return "site:" + campfire.getX() + "," + campfire.getY() + "," + campfire.getZ();
    }

    /** The stable owner key for a region's road plan. */
    public static String roadOwner(ResourceKey<Level> dimension, io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord region) {
        return "road:" + TownKeys.keyFor(dimension, region);
    }

    private static String cellKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** {@code true} when {@code planned} is a villager fixture and {@code live} is still that same
     *  fixture - the identity {@link #isTownBuiltFixture} checks, for a caller that already knows
     *  exactly which state its own plan placed. */
    static boolean isSameFixture(BlockState planned, BlockState live) {
        String fixture = fixturePoiOf(planned);
        return !fixture.isEmpty() && fixture.equals(fixturePoiOf(live));
    }

    /** The id of the POI type {@code state} registers (a bed's HEAD half, a workstation), or
     *  {@code ""} when it registers none. */
    private static String fixturePoiOf(BlockState state) {
        return PoiTypes.forState(state).flatMap(Holder::unwrapKey).map(key -> key.location().toString()).orElse("");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag founded = new ListTag();
        for (String key : foundedTowns) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Key", key);
            founded.add(entry);
        }
        tag.put("FoundedTowns", founded);

        ListTag storeList = new ListTag();
        for (Map.Entry<String, TownResourceStore> entry : stores.entrySet()) {
            CompoundTag storeTag = new CompoundTag();
            storeTag.putString("Key", entry.getKey());
            for (Map.Entry<TownResourceCategory, Integer> balance : entry.getValue().snapshot().entrySet()) {
                storeTag.putInt(balance.getKey().name(), balance.getValue());
            }
            storeList.add(storeTag);
        }
        tag.put("Stores", storeList);

        ListTag donationBindings = new ListTag();
        for (Map.Entry<String, DonationBoxBinding> entry : donationBoxes.entrySet()) {
            DonationBoxBinding binding = entry.getValue();
            CompoundTag value = new CompoundTag();
            value.putString("Key", entry.getKey());
            value.putString("Dimension", binding.dimension().location().toString());
            value.putInt("X", binding.pos().getX()); value.putInt("Y", binding.pos().getY()); value.putInt("Z", binding.pos().getZ());
            value.putString("Town", binding.townKey()); value.putUUID("Box", binding.boxId()); value.putLong("Generation", binding.generation());
            value.putLong("HighWater", donationBoxGenerations.getOrDefault(entry.getKey(), binding.generation()));
            donationBindings.add(value);
        }
        tag.put("DonationBoxes", donationBindings);
        ListTag donationOps = new ListTag();
        for (DonationOperation operation : donationOperations.values()) {
            CompoundTag value = new CompoundTag();
            DonationBoxBinding binding = operation.binding();
            value.putString("Dimension", binding.dimension().location().toString());
            value.putInt("X", binding.pos().getX()); value.putInt("Y", binding.pos().getY()); value.putInt("Z", binding.pos().getZ());
            value.putString("Town", binding.townKey()); value.putUUID("Box", binding.boxId()); value.putLong("Generation", binding.generation());
            value.putLong("Sequence", operation.sequence()); value.putString("Item", operation.payload().itemId());
            value.putInt("Count", operation.payload().count()); value.putString("Category", operation.payload().category().name());
            value.putInt("Credit", operation.payload().credit()); value.putString("State", operation.state().name());
            donationOps.add(value);
        }
        tag.put("DonationOperations", donationOps);

        ListTag placedList = new ListTag();
        for (Map.Entry<String, PlacedCell> entry : placedCells.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putString("Owner", entry.getValue().owner());
            entryTag.putString("Signature", entry.getValue().signature());
            entryTag.putString("Fixture", entry.getValue().fixturePoi());
            placedList.add(entryTag);
        }
        tag.put("PlacedCells", placedList);

        ListTag pendingList = new ListTag();
        for (Map.Entry<String, BlockPos> entry : pendingCampfires.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putInt("X", entry.getValue().getX());
            entryTag.putInt("Y", entry.getValue().getY());
            entryTag.putInt("Z", entry.getValue().getZ());
            pendingList.add(entryTag);
        }
        tag.put("PendingCampfires", pendingList);

        ListTag floorList = new ListTag();
        for (Map.Entry<String, Integer> entry : siteFloors.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putInt("FloorY", entry.getValue());
            floorList.add(entryTag);
        }
        tag.put("SiteFloors", floorList);

        ListTag blockedList = new ListTag();
        for (String key : blockedCells) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", key);
            blockedList.add(entryTag);
        }
        tag.put("BlockedCells", blockedList);

        ListTag blockedTerrainSiteList = new ListTag();
        for (String key : blockedTerrainSites) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", key);
            blockedTerrainSiteList.add(entryTag);
        }
        tag.put("BlockedTerrainSites", blockedTerrainSiteList);

        ListTag reservationOwnerList = new ListTag();
        for (Map.Entry<String, UUID> entry : reservationOwners.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putUUID("Owner", entry.getValue());
            reservationOwnerList.add(entryTag);
        }
        tag.put("ReservationOwners", reservationOwnerList);

        ListTag admissions = new ListTag();
        for (ResidentCapacityAdmission admission : capacityAdmissions.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Dimension", admission.dimension().location().toString());
            entry.putString("TownKey", admission.townKey());
            entry.putInt("RegionX", admission.region().x());
            entry.putInt("RegionZ", admission.region().z());
            entry.putUUID("Beneficiary", admission.beneficiaryId());
            entry.putInt("Homes", admission.requestedHomes());
            entry.putString("SiteOwner", admission.siteOwner());
            entry.putString("PlanSignature", admission.planSignature());
            entry.putString("State", admission.state().name());
            if (admission.boundSiteOwner() != null) {
                entry.putString("BoundSiteOwner", admission.boundSiteOwner());
            }
            if (admission.builderId() != null) {
                entry.putUUID("Builder", admission.builderId());
            }
            if (admission.boundPlanSignature() != null) {
                entry.putString("BoundPlanSignature", admission.boundPlanSignature());
            }
            if (admission.anchorGenerationToken() != null) {
                entry.putUUID("AnchorGenerationToken", admission.anchorGenerationToken());
            }
            ListTag workplaces = new ListTag();
            for (Map.Entry<VillagerProfession, Integer> workplace : admission.requestedWorkplaces().entrySet()) {
                CompoundTag requested = new CompoundTag();
                requested.putString("Profession", BuiltInRegistries.VILLAGER_PROFESSION.getKey(workplace.getKey()).toString());
                requested.putInt("Count", workplace.getValue());
                workplaces.add(requested);
            }
            entry.put("Workplaces", workplaces);
            admissions.add(entry);
        }
        tag.put("ResidentCapacityAdmissions", admissions);

        ListTag quarantinedSiteList = new ListTag();
        for (String key : quarantinedAdmissionSites) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", key);
            quarantinedSiteList.add(entryTag);
        }
        tag.put("QuarantinedAdmissionSites", quarantinedSiteList);

        ListTag everBoundSiteList = new ListTag();
        for (String key : everBoundAdmissionSites) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", key);
            everBoundSiteList.add(entryTag);
        }
        tag.put("EverBoundAdmissionSites", everBoundSiteList);
        return tag;
    }

    /** Package-private (not {@code private}) purely so a GameTest can round-trip
     *  {@code save}+{@code load} directly to prove persisted state (e.g. {@link #blockedCells})
     *  genuinely survives a reload, not merely a same-session in-memory check. */
    static TownStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        TownStorage storage = new TownStorage();
        ListTag founded = tag.getList("FoundedTowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < founded.size(); i++) {
            storage.foundedTowns.add(founded.getCompound(i).getString("Key"));
        }
        ListTag storeList = tag.getList("Stores", Tag.TAG_COMPOUND);
        for (int i = 0; i < storeList.size(); i++) {
            CompoundTag storeTag = storeList.getCompound(i);
            TownResourceStore store = new TownResourceStore();
            Map<TownResourceCategory, Integer> snapshot = new HashMap<>();
            for (TownResourceCategory category : TownResourceCategory.values()) {
                if (storeTag.contains(category.name())) {
                    snapshot.put(category, storeTag.getInt(category.name()));
                }
            }
            store.loadFrom(snapshot);
            storage.stores.put(storeTag.getString("Key"), store);
        }
        ListTag donationBindings = tag.getList("DonationBoxes", Tag.TAG_COMPOUND);
        for (int i = 0; i < donationBindings.size(); i++) {
            try {
                CompoundTag value = donationBindings.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(value.getString("Dimension"));
                if (id == null) throw new IllegalArgumentException("bad donation dimension");
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
                BlockPos pos = new BlockPos(value.getInt("X"), value.getInt("Y"), value.getInt("Z"));
                DonationBoxBinding binding = new DonationBoxBinding(dimension, pos, value.getString("Town"), value.getUUID("Box"), value.getLong("Generation"));
                String key = value.getString("Key");
                if (key.isEmpty() || binding.generation() <= 0) throw new IllegalArgumentException("bad donation binding");
                storage.donationBoxes.put(key, binding);
                storage.donationBoxGenerations.put(key, Math.max(binding.generation(), value.getLong("HighWater")));
            } catch (IllegalArgumentException ignored) { }
        }
        ListTag donationOps = tag.getList("DonationOperations", Tag.TAG_COMPOUND);
        for (int i = 0; i < donationOps.size(); i++) {
            try {
                CompoundTag value = donationOps.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(value.getString("Dimension"));
                if (id == null) throw new IllegalArgumentException("bad donation dimension");
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
                DonationBoxBinding binding = new DonationBoxBinding(dimension,
                        new BlockPos(value.getInt("X"), value.getInt("Y"), value.getInt("Z")), value.getString("Town"),
                        value.getUUID("Box"), value.getLong("Generation"));
                DonationPayload payload = new DonationPayload(value.getString("Item"), value.getInt("Count"),
                        TownResourceCategory.valueOf(value.getString("Category")), value.getInt("Credit"));
                DonationOperation operation = new DonationOperation(binding, value.getLong("Sequence"), payload,
                        DonationOperationState.valueOf(value.getString("State")));
                if (operation.sequence() <= 0 || !storage.isActiveDonationBox(binding)) throw new IllegalArgumentException("orphan donation operation");
                storage.donationOperations.put(donationOperationKey(binding), operation);
            } catch (IllegalArgumentException ignored) { }
        }
        ListTag placedList = tag.getList("PlacedCells", Tag.TAG_COMPOUND);
        for (int i = 0; i < placedList.size(); i++) {
            CompoundTag entryTag = placedList.getCompound(i);
            // getString returns "" for a record saved before "Fixture" existed: that cell is never a
            // candidate for allocation (see isTownBuiltFixture) until a plan places it again.
            storage.placedCells.put(entryTag.getString("Key"),
                    new PlacedCell(entryTag.getString("Owner"), entryTag.getString("Signature"), entryTag.getString("Fixture")));
        }
        ListTag pendingList = tag.getList("PendingCampfires", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            CompoundTag entryTag = pendingList.getCompound(i);
            storage.pendingCampfires.put(entryTag.getString("Key"),
                    new BlockPos(entryTag.getInt("X"), entryTag.getInt("Y"), entryTag.getInt("Z")));
        }
        ListTag floorList = tag.getList("SiteFloors", Tag.TAG_COMPOUND);
        for (int i = 0; i < floorList.size(); i++) {
            CompoundTag entryTag = floorList.getCompound(i);
            storage.siteFloors.put(entryTag.getString("Key"), entryTag.getInt("FloorY"));
        }
        ListTag blockedList = tag.getList("BlockedCells", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockedList.size(); i++) {
            storage.blockedCells.add(blockedList.getCompound(i).getString("Key"));
        }
        ListTag blockedTerrainSiteList = tag.getList("BlockedTerrainSites", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockedTerrainSiteList.size(); i++) {
            storage.blockedTerrainSites.add(blockedTerrainSiteList.getCompound(i).getString("Key"));
        }
        ListTag reservationOwnerList = tag.getList("ReservationOwners", Tag.TAG_COMPOUND);
        for (int i = 0; i < reservationOwnerList.size(); i++) {
            CompoundTag entryTag = reservationOwnerList.getCompound(i);
            storage.reservationOwners.put(entryTag.getString("Key"), entryTag.getUUID("Owner"));
        }
        ListTag admissions = tag.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
        for (int i = 0; i < admissions.size(); i++) {
            CompoundTag entry = admissions.getCompound(i);
            // [coordinator review, comment 51266] Parsed OUTSIDE the validation try below: even a
            // malformed/incomplete record (e.g. a BUILDING entry that lost its Builder or
            // BoundPlanSignature) must still quarantine the site it names, so a construction site
            // whose own progress/POI state survives in the world is never silently read as unbound
            // and re-bound to something else.
            ResourceLocation dimensionIdForQuarantine = ResourceLocation.tryParse(entry.getString("Dimension"));
            String boundSiteOwnerForQuarantine = entry.contains("BoundSiteOwner") ? entry.getString("BoundSiteOwner") : "";
            try {
                Map<VillagerProfession, Integer> workplaces = new HashMap<>();
                ListTag requestedWorkplaces = entry.getList("Workplaces", Tag.TAG_COMPOUND);
                for (int j = 0; j < requestedWorkplaces.size(); j++) {
                    CompoundTag requested = requestedWorkplaces.getCompound(j);
                    ResourceLocation professionId = ResourceLocation.tryParse(requested.getString("Profession"));
                    VillagerProfession profession = professionId == null ? null
                            : BuiltInRegistries.VILLAGER_PROFESSION.get(professionId);
                    if (profession == null || requested.getInt("Count") <= 0) {
                        throw new IllegalArgumentException("invalid persisted capacity workplace");
                    }
                    workplaces.put(profession, requested.getInt("Count"));
                }
                if (dimensionIdForQuarantine == null) {
                    throw new IllegalArgumentException("invalid persisted capacity dimension");
                }
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionIdForQuarantine);
                String boundSiteOwner = entry.contains("BoundSiteOwner") ? entry.getString("BoundSiteOwner") : null;
                UUID builderId = entry.hasUUID("Builder") ? entry.getUUID("Builder") : null;
                String boundPlanSignature = entry.contains("BoundPlanSignature") ? entry.getString("BoundPlanSignature") : null;
                // [coordinator review 51271/51274] Deliberately nullable even for a bound state -
                // see ResidentCapacityAdmission#anchorGenerationToken's own doc. A null here on a
                // BUILDING/COMPLETED record means "saved before this field existed"; handled as an
                // explicit legacy-quarantine case below, never as permission to trust an unverifiable
                // binding.
                UUID anchorGenerationToken = entry.hasUUID("AnchorGenerationToken") ? entry.getUUID("AnchorGenerationToken") : null;
                ResidentCapacityAdmission admission = new ResidentCapacityAdmission(dimension, entry.getString("TownKey"),
                        new RegionCoord(entry.getInt("RegionX"), entry.getInt("RegionZ")), entry.getUUID("Beneficiary"),
                        entry.getInt("Homes"), workplaces, entry.getString("SiteOwner"), entry.getString("PlanSignature"),
                        ResidentCapacityAdmission.State.valueOf(entry.getString("State")), boundSiteOwner, builderId,
                        boundPlanSignature, anchorGenerationToken);
                storage.capacityAdmissions.put(capacityAdmissionKey(dimension, admission.region(), admission.beneficiaryId()), admission);
                if (admission.state().bound() && admission.anchorGenerationToken() == null) {
                    // [coordinator review 51271/51274] Legacy BUILDING/COMPLETED record predating
                    // the generation token: there is no live anchor marker to verify it against at
                    // all, so it fails closed - quarantined, exactly like an ambiguous/malformed
                    // record - while the record ITSELF (history) and its reverse-site fence (via the
                    // defensive reconstruction pass below) both stay intact, never dropped.
                    storage.quarantinedAdmissionSites.add(quarantinedSiteKey(dimension, admission.boundSiteOwner()));
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed or obsolete optional admission must fail closed: it is never treated
                // as permission to create a new anchor, debit, or POI reservation during load. If it
                // named a real site, that site is quarantined too - preserving world/progress state
                // while blocking the construction fence this record was supposed to establish.
                if (dimensionIdForQuarantine != null && !boundSiteOwnerForQuarantine.isEmpty()) {
                    ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionIdForQuarantine);
                    storage.quarantinedAdmissionSites.add(quarantinedSiteKey(dimension, boundSiteOwnerForQuarantine));
                } else if ("BUILDING".equals(entry.getString("State")) || "COMPLETED".equals(entry.getString("State"))) {
                    // [coordinator review 51268, P1] This record's own State says it WAS bound, but
                    // either its Dimension or its BoundSiteOwner is itself unreadable - there is no
                    // site identity left to quarantine at all. Best-effort fencing, documented on
                    // quarantinedAdmissionSites above, stops here: logged loudly (never silent) so
                    // this is never mistaken for "every malformed record was safely fenced."
                    io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                            "Dynamic Towns dropped a {} capacity admission with an unreadable Dimension/BoundSiteOwner during "
                                    + "load - its own construction site could NOT be quarantined and remains unfenced",
                            entry.getString("State"));
                }
            }
        }
        // [coordinator review, comment 51266] Two different admissions both claiming the SAME
        // bound site cannot both be real - this is ambiguous ownership a plain HashMap lookup would
        // otherwise silently resolve to whichever happens to iterate first. Detected only after
        // every individually-valid entry above is loaded, so a sound single entry is never
        // penalized for an unrelated malformed one.
        Map<String, Integer> boundSiteCounts = new HashMap<>();
        for (ResidentCapacityAdmission admission : storage.capacityAdmissions.values()) {
            if (admission.boundSiteOwner() != null) {
                boundSiteCounts.merge(quarantinedSiteKey(admission.dimension(), admission.boundSiteOwner()), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> count : boundSiteCounts.entrySet()) {
            if (count.getValue() > 1) {
                storage.quarantinedAdmissionSites.add(count.getKey());
            }
        }
        ListTag quarantinedSiteList = tag.getList("QuarantinedAdmissionSites", Tag.TAG_COMPOUND);
        for (int i = 0; i < quarantinedSiteList.size(); i++) {
            storage.quarantinedAdmissionSites.add(quarantinedSiteList.getCompound(i).getString("Key"));
        }
        // [coordinator review 51268, P1] Every currently-loaded BUILDING/COMPLETED admission's own
        // bound site was, by definition, bound at least once - reconstructed defensively here in
        // addition to the persisted list below, so an older save written before this set existed
        // still starts with every site its OWN still-loaded admissions already prove was bound.
        for (ResidentCapacityAdmission admission : storage.capacityAdmissions.values()) {
            if (admission.boundSiteOwner() != null) {
                storage.everBoundAdmissionSites.add(quarantinedSiteKey(admission.dimension(), admission.boundSiteOwner()));
            }
        }
        ListTag everBoundSiteList = tag.getList("EverBoundAdmissionSites", Tag.TAG_COMPOUND);
        for (int i = 0; i < everBoundSiteList.size(); i++) {
            storage.everBoundAdmissionSites.add(everBoundSiteList.getCompound(i).getString("Key"));
        }
        return storage;
    }
}
