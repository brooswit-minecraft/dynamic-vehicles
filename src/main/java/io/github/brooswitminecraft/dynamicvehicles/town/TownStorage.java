package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.village.RegionCoord;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
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
// MINECRAFT-454 [stage 2 port, test seam]: not final - lets a GameTest-only subclass override
// save(CompoundTag, Provider) to deterministically force a capture failure for
// TownStorageCheckpointPendingStateGameTests, without adding a dedicated "simulate failure"
// method to production API surface. Every existing method keeps its own final-ness/behavior.
public class TownStorage extends SavedData {
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
    /** [stage 2, coordinator design 51327] Durable construction operation ledger - keyed by the
     *  caller-minted {@code operationId} itself (never regenerated), the sole recovery enumeration
     *  entry point (see {@link #pendingConstructionOperations}), never the live world. */
    private final Map<UUID, TownConstructionOperation> constructionOperations = new HashMap<>();
    /** [stage 2] Dimension-qualified sites whose own persisted construction operation record was
     *  malformed, duplicate, or conflicting on load - fenced against a NEW operation forever
     *  (within this instance's lifetime), since this mod can no longer prove the old operation's
     *  own world effects/witness are safely finished. Parallels {@link #quarantinedAdmissionSites}. */
    private final Set<String> quarantinedConstructionSites = new HashSet<>();
    /** [coordinator review 51346] Operation ids whose transition to a TERMINAL phase (SETTLED/
     *  CANCELLED) has happened in memory but has NOT yet been durably acknowledged by an
     *  orchestration caller - see {@link #markPendingTerminalAcknowledgment}/{@link
     *  #confirmTerminalAcknowledgment}. While an id is present here, every fencing/ownership query
     *  below still treats that operation as occupying its own site/chunks, so a synchronous join's
     *  own FAILURE path can never silently release a fence for a transition that might not survive
     *  a crash. Deliberately NEVER persisted (see {@link #save}/{@link #load}): after a restart,
     *  whatever phase was actually, durably written IS the truth - there is nothing left to track.
     *  Mutated ONLY by the two methods below, both called ONLY from the server thread by the
     *  orchestration layer, never from any write-completion callback. */
    private final Set<UUID> pendingTerminalAcknowledgment = new HashSet<>();
    /** [stage 2, structural-cell adapter] Durable association between a building site (keyed by
     *  {@code siteOwner}) and the exact plan/claimant/anchor generation that is authorized to place
     *  its allowlisted structural cells via the construction operation ledger - see
     *  {@link #establishOrReadAnchorAssociation}. */
    record AnchorAssociation(ResourceKey<Level> dimension, String siteOwner, String planSignature,
                              UUID claimantId, UUID anchorGenerationToken) {}
    private final Map<String, AnchorAssociation> anchorAssociations = new HashMap<>();
    /** [stage 2, structural-cell adapter] siteOwner keys whose {@link AnchorAssociation} has been
     *  recorded but whose chunk/ledger acknowledgment is not yet both confirmed - see
     *  {@link #establishOrReadAnchorAssociation}. Mirrors {@link #pendingTerminalAcknowledgment}'s
     *  own discipline: recorded BEFORE either checkpoint attempt, cleared only once both actually
     *  succeed. */
    private final Set<String> pendingAnchorAcknowledgment = new HashSet<>();
    /** Test-only fault injection for {@link #finishAnchorAcknowledgment}'s chunk-acknowledgment
     *  step - mirrors {@code TownConstructionService#testForcePlacementInterruptionAt}'s exact
     *  convention (test-only, package-private, consumed exactly once). Instance-scoped (not
     *  static) since each test constructs its own {@code TownStorage}, so there is no risk of
     *  cross-test leakage the way a static field would have. */
    boolean testForceChunkAckFailureOnce = false;
    /** Test-only fault injection for {@link #finishAnchorAcknowledgment}'s ledger-acknowledgment
     *  step - same convention as {@link #testForceChunkAckFailureOnce}. */
    boolean testForceLedgerAckFailureOnce = false;

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

    /** [coordinator review 51346, matched engine source] The real production save file for the live
     *  singleton instance {@link #of} returns, for an acknowledgment call ({@link
     *  #requestAcknowledgedCheckpoint}) against the ACTUAL live ledger rather than only a test's own
     *  scratch file. {@link MinecraftServer#getWorldPath(LevelResource)} is the one PUBLIC API that
     *  exposes the overworld's own root (this mod's {@link #of} always attaches to {@code
     *  server.overworld()}), with {@code "data/" + NAME + ".dat"} appended exactly as {@code
     *  DimensionDataStorage} itself does. */
    static File productionSaveFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(NAME + ".dat").toFile();
    }

    /** [coordinator design 51290/51291, stage 1 part A] The single ordered, acknowledged writer
     *  for this storage's own file - see {@link TownStorageDurableWriter}'s own class doc for why.
     *  Not {@code static}: each {@code TownStorage} instance (there is exactly one live at a time,
     *  via {@link #of}) owns its own revision sequence, so a fresh instance after a reload never
     *  inherits a stale one's in-flight state. */
    private final TownStorageDurableWriter durableWriter;

    /** [coordinator review 51294, stage 1 part A] The most recently STARTED checkpoint attempt's
     *  own result, as long as no newer mutation has happened since it was started - {@code null}
     *  only for a genuinely untouched instance that has never captured a single snapshot. Only ever
     *  read/written from the server thread, exactly like {@code dirty} itself. */
    private java.util.concurrent.CompletableFuture<CheckpointResult> currentAttempt;

    public TownStorage() {
        this(new TownStorageDurableWriter(net.neoforged.neoforge.common.IOUtilities::withIOWorker, TownStorageDurableWriter.AtomicByteWriter.real()));
    }

    /** [coordinator review 51294, stage 1 part A, test seam] Lets a test inject a controllable
     *  {@link TownStorageDurableWriter} instead of the real production one. Package-private; the
     *  real {@link #of} factory path and every other production caller only ever uses the no-arg
     *  constructor above. */
    TownStorage(TownStorageDurableWriter durableWriter) {
        this.durableWriter = durableWriter;
    }

    /** [coordinator review 51300, test seam] Read-only visibility into whether this storage's own
     *  durable writer still owes a retry. */
    boolean hasOutstandingDurableRetry() {
        return durableWriter.needsRetry();
    }

    /** [coordinator design 51290/51291, stage 1 part A] The caller-visible result of one checkpoint
     *  attempt via {@link #requestAcknowledgedCheckpoint} or (implicitly, retained but not directly
     *  returned) the engine's own ordinary {@link #save(java.io.File, HolderLookup.Provider)} call. */
    sealed interface CheckpointResult {
        /** Nothing needed doing: no new mutation, and no retry owed from a previous attempt. */
        record NothingToDo() implements CheckpointResult {}

        /** The snapshot could not even be captured/serialized - thrown before ever reaching the
         *  writer. {@code dirty} and any previously-owed retry are both left completely untouched. */
        record CaptureFailed(Exception cause) implements CheckpointResult {}

        /** The real, underlying write failed - {@code revision}'s bytes are NOT on disk, and
         *  {@link TownStorageDurableWriter#needsRetry()} will report {@code true} until a later
         *  success supersedes it. */
        record WriteFailed(long revision, java.io.IOException cause) implements CheckpointResult {}

        /** The synchronous submission to the writer's own ordered queue was itself rejected -
         *  BEFORE the write was even accepted. */
        record SubmissionRejected(RuntimeException cause) implements CheckpointResult {}

        /** The real, underlying write succeeded and was acknowledged by the writer. */
        record Persisted(long revision) implements CheckpointResult {}
    }

    /** Overrides the base {@link SavedData}'s own {@code save(File, Provider)} - the ONE method
     *  both ordinary (engine autosave) and explicit checkpoint calls actually invoke. Deliberately
     *  never calls {@code super.save(file, registries)}: that base implementation hands the write
     *  off and clears {@code dirty} immediately, before the write even starts, so a failure is
     *  silently indistinguishable from success - exactly what {@link TownStorageDurableWriter}
     *  exists to close for this one file. */
    private java.util.concurrent.CompletableFuture<CheckpointResult> saveAndReportOutcome(java.io.File file, HolderLookup.Provider registries) {
        boolean hasNewMutation = isDirty();
        boolean mustStartFreshAttempt = hasNewMutation || durableWriter.needsRetry();
        if (!mustStartFreshAttempt) {
            return currentAttempt != null ? currentAttempt : java.util.concurrent.CompletableFuture.completedFuture(new CheckpointResult.NothingToDo());
        }
        byte[] snapshot;
        try {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put("data", this.save(new CompoundTag(), registries));
            net.minecraft.nbt.NbtUtils.addCurrentDataVersion(wrapper);
            try (java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                net.minecraft.nbt.NbtIo.writeCompressed(wrapper, bytes);
                snapshot = bytes.toByteArray();
            }
        } catch (Exception captureFailure) {
            io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                    "TownStorage failed to capture a snapshot for a durable checkpoint - dirty/retry state left untouched so a later save retries",
                    captureFailure);
            return java.util.concurrent.CompletableFuture.completedFuture(new CheckpointResult.CaptureFailed(captureFailure));
        }
        java.util.concurrent.CompletableFuture<CheckpointResult> result = new java.util.concurrent.CompletableFuture<>();
        currentAttempt = result;
        try {
            durableWriter.enqueue(snapshot, file.toPath(), (revision, outcome) -> {
                if (outcome instanceof TownStorageDurableWriter.WriteOutcome.Failed failed) {
                    io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                            "TownStorage durable checkpoint write failed for revision {} - this storage still owes a retry with current state",
                            revision, failed.cause());
                    result.complete(new CheckpointResult.WriteFailed(revision, failed.cause()));
                } else {
                    result.complete(new CheckpointResult.Persisted(revision));
                }
            });
        } catch (RuntimeException submissionRejected) {
            io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                    "TownStorage durable checkpoint submission was rejected - dirty/retry state left untouched so a later save retries",
                    submissionRejected);
            result.complete(new CheckpointResult.SubmissionRejected(submissionRejected));
            return result;
        }
        if (hasNewMutation) {
            setDirty(false);
        }
        return result;
    }

    @Override
    public void save(java.io.File file, HolderLookup.Provider registries) {
        saveAndReportOutcome(file, registries);
    }

    /** [coordinator design 51290/51291, stage 1 part A] The caller-visible explicit checkpoint
     *  entry point - calls the EXACT SAME {@link #save(java.io.File, HolderLookup.Provider)}
     *  override an ordinary engine autosave uses, and hands the caller back the real {@link
     *  CheckpointResult} once the actual underlying write completes. */
    java.util.concurrent.CompletableFuture<CheckpointResult> requestAcknowledgedCheckpoint(java.io.File file, HolderLookup.Provider registries) {
        return saveAndReportOutcome(file, registries);
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
     *  {@link #siteOwner(BlockPos)} or {@link #roadOwner(ResourceKey, io.github.brooswitminecraft.dynamicvehicles.village.RegionCoord)})
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

    /** [coordinator review 51352, stage 2 port] {@code true} iff SOME record at {@code pos} in
     *  {@code dimension} was placed-and-paid-for by the plan identified by {@code owner} -
     *  narrower than {@link #isTownBuiltFixture} (any plan/owner) and relaxed from {@link
     *  #isCellPlaced} (which demands an exact signature match too) - for a caller (e.g. {@link
     *  TownConstructionOperationService#reconcileHandoff}) that already knows exactly which owner
     *  but not necessarily the exact signature a prior tick recorded. */
    public boolean isCellPlacedByOwner(ResourceKey<Level> dimension, BlockPos pos, String owner) {
        PlacedCell cell = placedCells.get(cellKey(dimension, pos));
        return cell != null && cell.owner().equals(owner);
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

    /** [MINECRAFT-455 self-admission liveness] Test-only, unconditional overwrite - unlike every
     *  other admission mutator, this bypasses all CAS/identity guards, exactly so a focused test
     *  can install a deliberately malformed record (e.g. a mismatched identity invariant) and
     *  assert production code quarantines it rather than trusting it. Never called from production
     *  code. */
    void testOnlyReplaceCapacityAdmission(ResidentCapacityAdmission admission) {
        capacityAdmissions.put(capacityAdmissionKey(admission.dimension(), admission.region(), admission.beneficiaryId()), admission);
        setDirty();
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

    /** Cancels only the exact beneficiary's unstarted capacity intent. */
    boolean cancelAwaitingCapacityAdmission(ResourceKey<Level> dimension, RegionCoord region, UUID beneficiaryId) {
        String key = capacityAdmissionKey(dimension, region, beneficiaryId);
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing == null || existing.state() != ResidentCapacityAdmission.State.AWAITING_CLAIM) {
            return false;
        }
        capacityAdmissions.put(key, new ResidentCapacityAdmission(existing.dimension(), existing.townKey(),
                existing.region(), existing.beneficiaryId(), existing.requestedHomes(), existing.requestedWorkplaces(),
                existing.needsAnyWorkplace(), existing.siteOwner(), existing.planSignature(),
                ResidentCapacityAdmission.State.CANCELLED, null, null, null, null));
        setDirty();
        return true;
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

    /** [MINECRAFT-455 self-admission liveness] Every currently {@code SELF_CLAIM_LINKED} admission
     *  in a dimension - a small, town-scale set, scanned by the claimant-independent reconciliation
     *  pass in {@code TownConstructionService} (the same shape {@link #pendingConstructionOperations}
     *  already supports for the orphaned-ledger-operation recovery pass). */
    List<ResidentCapacityAdmission> selfClaimLinkedAdmissions(ResourceKey<Level> dimension) {
        List<ResidentCapacityAdmission> result = new ArrayList<>();
        for (ResidentCapacityAdmission admission : capacityAdmissions.values()) {
            if (admission.dimension().equals(dimension) && admission.state() == ResidentCapacityAdmission.State.SELF_CLAIM_LINKED) {
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
        return bindCapacityAdmissionToSite(awaiting, siteOwner, builderId, concretePlanSignature, anchorTokenSupplier,
                ResidentCapacityAdmission.State.BUILDING);
    }

    /** As the four-argument overload, but binding to {@code targetState} (either {@link
     *  ResidentCapacityAdmission.State#BUILDING}, the distinct-beneficiary handoff, or {@link
     *  ResidentCapacityAdmission.State#SELF_CLAIM_LINKED}, the beneficiary-is-builder-is-claimant
     *  mode) - see {@link ResidentCapacityAdmission#boundTo} for the same CAS-guarded semantics,
     *  now parameterized rather than duplicated per target state. */
    Optional<ResidentCapacityAdmission> bindCapacityAdmissionToSite(ResidentCapacityAdmission awaiting, String siteOwner,
            UUID builderId, String concretePlanSignature, java.util.function.Supplier<Optional<UUID>> anchorTokenSupplier,
            ResidentCapacityAdmission.State targetState) {
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
        ResidentCapacityAdmission bound = existing.boundTo(siteOwner, builderId, concretePlanSignature, anchorToken.get(), targetState);
        capacityAdmissions.put(key, bound);
        everBoundAdmissionSites.add(quarantinedSiteKey(awaiting.dimension(), siteOwner));
        setDirty();
        return Optional.of(bound);
    }

    /**
     * Marks a {@code BUILDING} or {@code SELF_CLAIM_LINKED} admission {@code COMPLETED} once its
     * recipient has actually, positively received the final handoff - never on blocks-finished or
     * Builder-assigned fixtures alone. Only succeeds against the EXACT site/builder/concrete-plan/
     * anchor-token that bound it; a stale, already-resolved, or mismatched admission is left
     * untouched rather than silently rebound or double-completed.
     */
    Optional<ResidentCapacityAdmission> completeCapacityAdmission(ResidentCapacityAdmission bound) {
        String key = capacityAdmissionKey(bound.dimension(), bound.region(), bound.beneficiaryId());
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing == null || !existing.state().activelyBound()
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
     * [MINECRAFT-455 self-admission liveness] Reverts a {@code SELF_CLAIM_LINKED} admission back
     * to {@code AWAITING_CLAIM} on POSITIVE evidence its self-claim is genuinely gone - never on
     * mere absence/unload (see the caller in {@code TownConstructionService}). Only succeeds
     * against the EXACT site/builder/concrete-plan/anchor-token that linked it - a stale or
     * already-advanced reference changes nothing. The site's own permanent reverse-site fence
     * ({@link #everBoundAdmissionSites}) is deliberately NEVER cleared here - this frees the
     * ADMISSION's identity to match a genuinely different future site/claimant, never makes the
     * OLD physical site bindable again.
     */
    Optional<ResidentCapacityAdmission> unbindCapacityAdmission(ResidentCapacityAdmission linked) {
        String key = capacityAdmissionKey(linked.dimension(), linked.region(), linked.beneficiaryId());
        ResidentCapacityAdmission existing = capacityAdmissions.get(key);
        if (existing == null || existing.state() != ResidentCapacityAdmission.State.SELF_CLAIM_LINKED
                || !existing.boundSiteOwner().equals(linked.boundSiteOwner()) || !existing.builderId().equals(linked.builderId())
                || !existing.boundPlanSignature().equals(linked.boundPlanSignature())
                || !Objects.equals(existing.anchorGenerationToken(), linked.anchorGenerationToken())) {
            return Optional.empty();
        }
        ResidentCapacityAdmission reverted = existing.unbound();
        capacityAdmissions.put(key, reverted);
        setDirty();
        return Optional.of(reverted);
    }

    /** [MINECRAFT-455 self-admission liveness] Quarantines {@code siteOwner} at runtime - the same
     *  fail-closed fence {@code load} already applies to a malformed persisted bound record,
     *  reused here for a live {@code SELF_CLAIM_LINKED} record whose identity invariant
     *  (beneficiary == builder == live claimant) fails: never trusted to skip the distinct-
     *  beneficiary/active-Builder guards it would otherwise bypass. */
    void quarantineAdmissionSite(ResourceKey<Level> dimension, String siteOwner) {
        quarantinedAdmissionSites.add(quarantinedSiteKey(dimension, siteOwner));
        setDirty();
    }

    /** [MINECRAFT-455 self-admission liveness] The inverse of {@link #siteOwner(BlockPos)} - the
     *  encoding is a stable, already-persisted identity, so no new field is needed to recover the
     *  anchor position a {@code SELF_CLAIM_LINKED} admission's own {@code boundSiteOwner} names. */
    public static Optional<BlockPos> siteOwnerPos(String siteOwner) {
        if (siteOwner == null || !siteOwner.startsWith("site:")) {
            return Optional.empty();
        }
        String[] parts = siteOwner.substring("site:".length()).split(",", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
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
    public static String roadOwner(ResourceKey<Level> dimension, io.github.brooswitminecraft.dynamicvehicles.village.RegionCoord region) {
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

    /** [stage 2] The durable intent record for one construction operation, if it exists. */
    Optional<TownConstructionOperation> constructionOperation(UUID operationId) {
        return Optional.ofNullable(constructionOperations.get(operationId));
    }

    /**
     * [stage 2, coordinator design 51327 / review 51330] Reserves one new construction operation,
     * atomically, with its FULL escrow debited at this exact call - BEFORE any world effect exists.
     *
     * <p>Idempotent on {@code operationId}: a retry carrying the EXACT SAME content (see {@link
     * TownConstructionOperation#sameContentAs}) returns the existing record unchanged, charging
     * nothing a second time. A retry carrying the SAME id but DIFFERENT content is REJECTED outright.
     *
     * <p>Fenced on {@code siteOwner}: a DIFFERENT {@code operationId} cannot reserve against a site
     * that already has a non-terminal operation, or a quarantined one - see {@link
     * #isSiteFencedByUnsettledOperation}.
     *
     * <p>ALSO fenced on chunk overlap, independent of {@code siteOwner} - see {@link
     * #isChunkOwnedByAnotherUnsettledOperation}.
     *
     * <p>All-or-nothing escrow: every requested category must independently {@link
     * TownResourceStore#canAfford} before ANY category is debited; a failed afford check (or either
     * fence above) debits nothing.
     */
    Optional<TownConstructionOperation> reserveConstructionOperation(UUID operationId, ResourceKey<Level> dimension,
            String townKey, String siteOwner, UUID anchorGenerationToken, UUID builderId, Optional<UUID> beneficiaryId,
            List<TownConstructionOperation.CellEffect> effects, Map<TownResourceCategory, Integer> reservedCost) {
        TownConstructionOperation requested = new TownConstructionOperation(operationId, dimension, townKey, siteOwner,
                anchorGenerationToken, builderId, beneficiaryId, effects, reservedCost, TownConstructionOperation.Phase.RESERVED);
        TownConstructionOperation existing = constructionOperations.get(operationId);
        if (existing != null) {
            return existing.sameContentAs(requested) ? Optional.of(existing) : Optional.empty();
        }
        if (isSiteFencedByUnsettledOperation(dimension, siteOwner)) {
            return Optional.empty();
        }
        for (ChunkPos chunkPos : requested.affectedChunks()) {
            // A brand-new operation can never exempt itself - it does not exist in the ledger yet.
            if (isChunkOwnedByAnotherUnsettledOperation(dimension, chunkPos, Optional.empty())) {
                return Optional.empty();
            }
        }
        TownResourceStore store = storeFor(townKey);
        for (Map.Entry<TownResourceCategory, Integer> entry : reservedCost.entrySet()) {
            if (!store.canAfford(entry.getKey(), entry.getValue())) {
                return Optional.empty();
            }
        }
        for (Map.Entry<TownResourceCategory, Integer> entry : reservedCost.entrySet()) {
            if (!store.tryDebit(entry.getKey(), entry.getValue())) {
                throw new IllegalStateException(
                        "construction operation escrow debit failed after every category already passed canAfford - "
                                + "impossible on a single server-thread-only caller with no concurrent mutation");
            }
        }
        constructionOperations.put(operationId, requested);
        setDirty();
        return Optional.of(requested);
    }

    /** Transitions {@code RESERVED -> WORLD_APPLIED} once this operation's world effects have
     *  actually been applied - idempotent ({@code WORLD_APPLIED} stays {@code WORLD_APPLIED});
     *  rejects an unknown or terminal operation. */
    Optional<TownConstructionOperation> markWorldApplied(UUID operationId) {
        TownConstructionOperation existing = constructionOperations.get(operationId);
        if (existing == null || existing.isTerminal()) {
            return Optional.empty();
        }
        if (existing.phase() == TownConstructionOperation.Phase.WORLD_APPLIED) {
            return Optional.of(existing);
        }
        TownConstructionOperation applied = existing.withPhase(TownConstructionOperation.Phase.WORLD_APPLIED);
        constructionOperations.put(operationId, applied);
        setDirty();
        return Optional.of(applied);
    }

    /** Idempotent current-balance settlement: records that this operation's effects are durably
     *  committed. NEVER debits again - escrow was already spent at reservation time. Requires
     *  {@code WORLD_APPLIED} (an operation whose effects were never actually applied cannot settle);
     *  a repeat call once already {@code SETTLED} is a safe no-op. */
    Optional<TownConstructionOperation> settleConstructionOperation(UUID operationId) {
        TownConstructionOperation existing = constructionOperations.get(operationId);
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.phase() == TownConstructionOperation.Phase.SETTLED) {
            return Optional.of(existing);
        }
        if (existing.phase() != TownConstructionOperation.Phase.WORLD_APPLIED) {
            return Optional.empty();
        }
        TownConstructionOperation settled = existing.withPhase(TownConstructionOperation.Phase.SETTLED);
        constructionOperations.put(operationId, settled);
        setDirty();
        return Optional.of(settled);
    }

    /** Cancels a not-yet-settled operation, refunding its FULL reserved escrow exactly once -
     *  idempotent ({@code CANCELLED} stays {@code CANCELLED}, refunding nothing further); rejects
     *  an operation that has already {@code SETTLED}. */
    Optional<TownConstructionOperation> cancelConstructionOperation(UUID operationId) {
        TownConstructionOperation existing = constructionOperations.get(operationId);
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.phase() == TownConstructionOperation.Phase.CANCELLED) {
            return Optional.of(existing);
        }
        if (existing.phase() == TownConstructionOperation.Phase.SETTLED) {
            return Optional.empty();
        }
        TownResourceStore store = storeFor(existing.townKey());
        for (Map.Entry<TownResourceCategory, Integer> entry : existing.reservedCost().entrySet()) {
            store.credit(entry.getKey(), entry.getValue());
        }
        TownConstructionOperation cancelled = existing.withPhase(TownConstructionOperation.Phase.CANCELLED);
        constructionOperations.put(operationId, cancelled);
        setDirty();
        return Optional.of(cancelled);
    }

    /** [stage 2, anchor-independent recovery] Every non-terminal, non-quarantined operation for a
     *  dimension - the SOLE recovery enumeration entry point. A small, town-scale set. */
    List<TownConstructionOperation> pendingConstructionOperations(ResourceKey<Level> dimension) {
        List<TownConstructionOperation> result = new ArrayList<>();
        for (TownConstructionOperation operation : constructionOperations.values()) {
            if (operation.dimension().equals(dimension) && operation.isUnsettled()
                    && !quarantinedConstructionSites.contains(quarantinedSiteKey(dimension, operation.siteOwner()))) {
                result.add(operation);
            }
        }
        return result;
    }

    /** {@code true} while ANY non-terminal operation already claims this exact site, OR the site
     *  was quarantined on load - the overlap fence stage 2 requires. */
    boolean isSiteFencedByUnsettledOperation(ResourceKey<Level> dimension, String siteOwner) {
        if (quarantinedConstructionSites.contains(quarantinedSiteKey(dimension, siteOwner))) {
            return true;
        }
        for (TownConstructionOperation operation : constructionOperations.values()) {
            if (operation.dimension().equals(dimension) && operation.siteOwner().equals(siteOwner) && stillOccupiesItsSite(operation)) {
                return true;
            }
        }
        return false;
    }

    /** [coordinator review 51346] {@code true} while {@code operation} still occupies its own
     *  site/chunks for fencing purposes - either genuinely non-terminal, OR terminal but its own
     *  transition has not yet been durably acknowledged. */
    private boolean stillOccupiesItsSite(TownConstructionOperation operation) {
        return operation.isUnsettled() || pendingTerminalAcknowledgment.contains(operation.operationId());
    }

    /** [coordinator review 51346] Marks {@code operationId}'s most recent terminal transition
     *  (SETTLED/CANCELLED) as not yet durably acknowledged - called by the orchestration layer
     *  BEFORE it calls {@link #settleConstructionOperation}/{@link #cancelConstructionOperation}.
     *  Idempotent; a no-op if already marked. */
    void markPendingTerminalAcknowledgment(UUID operationId) {
        pendingTerminalAcknowledgment.add(operationId);
    }

    /** [coordinator review 51346] Clears {@code operationId}'s pending-acknowledgment mark - called
     *  ONLY after a real {@link #requestAcknowledgedCheckpoint} of THIS ledger reports {@link
     *  CheckpointResult.Persisted}. Idempotent; a no-op if already clear or never marked. */
    void confirmTerminalAcknowledgment(UUID operationId) {
        pendingTerminalAcknowledgment.remove(operationId);
    }

    /** [coordinator review 51348/51351] Read-only check for the orchestration layer's own retry
     *  entry point - a terminal-but-unacknowledged operation must remain discoverable, never
     *  stranded with no reachable retry path. */
    boolean isPendingTerminalAcknowledgment(UUID operationId) {
        return pendingTerminalAcknowledgment.contains(operationId);
    }

    /** [coordinator review 51348/51351] Every operation in {@code dimension} whose terminal
     *  transition is pending acknowledgment - the enumeration half of the retry entry point above. */
    List<TownConstructionOperation> pendingTerminalAcknowledgments(ResourceKey<Level> dimension) {
        List<TownConstructionOperation> result = new ArrayList<>();
        for (UUID operationId : pendingTerminalAcknowledgment) {
            TownConstructionOperation operation = constructionOperations.get(operationId);
            if (operation != null && operation.dimension().equals(dimension)) {
                result.add(operation);
            }
        }
        return result;
    }

    /** [coordinator review 51336/51341] The ONE authoritative chunk-ownership decision: {@code
     *  true} iff some unsettled operation OTHER than {@code exemptOperationId} already claims
     *  {@code chunkPos} in {@code dimension} as one of its own {@link
     *  TownConstructionOperation#affectedChunks()} - independent of {@code siteOwner}. */
    boolean isChunkOwnedByAnotherUnsettledOperation(ResourceKey<Level> dimension, ChunkPos chunkPos, Optional<UUID> exemptOperationId) {
        for (TownConstructionOperation operation : constructionOperations.values()) {
            if (operation.dimension().equals(dimension) && stillOccupiesItsSite(operation) && operation.affectedChunks().contains(chunkPos)
                    && !operation.operationId().equals(exemptOperationId.orElse(null))) {
                return true;
            }
        }
        return false;
    }

    /** [coordinator review 51341] The guarded, operation-linked checkpoint entry point - every
     *  production caller checkpointing a chunk on an operation's behalf MUST go through this, never
     *  call {@link TownStorageChunkCheckpoint#checkpointLoadedChunkForOperation} directly. */
    Optional<TownStorageChunkCheckpoint.CheckpointResult> checkpointChunkForOperation(ServerLevel level, ChunkPos chunkPos, UUID operationId) {
        if (isChunkOwnedByAnotherUnsettledOperation(level.dimension(), chunkPos, Optional.of(operationId))) {
            return Optional.empty();
        }
        return Optional.of(TownStorageChunkCheckpoint.checkpointLoadedChunkForOperation(level, chunkPos, operationId));
    }

    /** [coordinator review 51341] The guarded, standalone checkpoint entry point - the SAME
     *  ownership decision as {@link #checkpointChunkForOperation}, but exempting no operation. */
    Optional<TownStorageChunkCheckpoint.CheckpointResult> checkpointChunkIfUnclaimed(ServerLevel level, ChunkPos chunkPos) {
        if (isChunkOwnedByAnotherUnsettledOperation(level.dimension(), chunkPos, Optional.empty())) {
            return Optional.empty();
        }
        return Optional.of(TownStorageChunkCheckpoint.checkpointLoadedChunk(level, chunkPos));
    }

    /** [stage 2] Every {@code SETTLED} construction operation recorded for this exact dimension and
     *  siteOwner - used by {@code TownConstructionService#buildAt}'s pre-preflight backfill
     *  reconstruction (never the live world diff) to restore {@link #placedCells} entries a prior
     *  tick's own {@code markCellPlaced} call may not have survived to run. */
    List<TownConstructionOperation> settledOperationsForSite(ResourceKey<Level> dimension, String siteOwner) {
        List<TownConstructionOperation> result = new ArrayList<>();
        for (TownConstructionOperation operation : constructionOperations.values()) {
            if (operation.dimension().equals(dimension) && operation.siteOwner().equals(siteOwner)
                    && operation.phase() == TownConstructionOperation.Phase.SETTLED) {
                result.add(operation);
            }
        }
        return result;
    }

    /** [stage 2] The currently authorized {@link AnchorAssociation} for {@code siteOwner}, or
     *  {@code null} if none has ever been established. Plain getter - see
     *  {@link #establishOrReadAnchorAssociation}. */
    AnchorAssociation anchorAssociationFor(String siteOwner) {
        return anchorAssociations.get(siteOwner);
    }

    /** [stage 2, test seam] Read-only visibility into whether {@code siteOwner}'s {@link
     *  AnchorAssociation} is still awaiting its own chunk/ledger acknowledgment - mirrors {@link
     *  #isPendingTerminalAcknowledgment}'s exact shape for the construction-operation ledger. */
    boolean isPendingAnchorAcknowledgment(String siteOwner) {
        return pendingAnchorAcknowledgment.contains(siteOwner);
    }

    /** [stage 2] {@code true} iff a {@link PlacedCell} exists at {@code pos} in {@code dimension}
     *  whose owner/signature pair differs from the given ({@code owner}, {@code signature}) pair -
     *  i.e. some OTHER plan's own paid record already occupies this exact cell. Used by the
     *  backfill reconstruction to fail closed (never overwrite) rather than silently adopting a
     *  position another plan's own record already claims. */
    boolean hasConflictingPlacedRecord(ResourceKey<Level> dimension, BlockPos pos, String owner, String signature) {
        PlacedCell cell = placedCells.get(cellKey(dimension, pos));
        return cell != null && !(cell.owner().equals(owner) && cell.signature().equals(signature));
    }

    /**
     * [stage 2, structural-cell adapter] Establishes (the first time) or re-reads (every later
     * call) the durable association between {@code siteOwner} and the exact plan/claimant/anchor
     * generation authorized to place its allowlisted structural cells via the construction
     * operation ledger. Fails closed (returns {@link Optional#empty()}, never adopts/retags) on any
     * mismatch against an existing association, a missing/mismatched live anchor marker, or either
     * acknowledgment failing. Idempotent and safe to call every tick: a fully acknowledged existing
     * association is simply re-read; a still-pending one retries ONLY its own acknowledgment, never
     * minting a second live marker.
     */
    Optional<UUID> establishOrReadAnchorAssociation(ServerLevel level, File ledgerFile, HolderLookup.Provider registries,
            BlockPos campfire, ResourceKey<Level> dimension, String siteOwner, String planSignature, UUID claimantId) {
        AnchorAssociation existing = anchorAssociations.get(siteOwner);
        if (existing != null) {
            if (!existing.dimension().equals(dimension) || !existing.planSignature().equals(planSignature)
                    || !existing.claimantId().equals(claimantId)) {
                return Optional.empty();
            }
            if (!TownConstructionService.liveAnchorGenerationTokenMatches(level, campfire, existing.anchorGenerationToken())) {
                return Optional.empty();
            }
            if (pendingAnchorAcknowledgment.contains(siteOwner)) {
                // [structural-cell adapter correction] A retry must force a FRESH capture+write,
                // never rely on whatever `dirty` happens to read right now - an unrelated ordinary
                // autosave (or, as here, a fresh instance straight off a load()) can leave `dirty`
                // false even though THIS ledger file has never actually durably recorded this
                // pending association. Without this, requestAcknowledgedCheckpoint would report
                // NothingToDo (never Persisted), and a genuinely pending retry could never
                // succeed - setDirty() here is what makes the retry a REAL, not merely attempted,
                // acknowledgment.
                setDirty();
                return finishAnchorAcknowledgment(level, ledgerFile, registries, campfire, siteOwner, existing.anchorGenerationToken());
            }
            return Optional.of(existing.anchorGenerationToken());
        }
        Optional<UUID> fresh = TownConstructionService.writeFreshAnchorGenerationToken(level, campfire);
        if (fresh.isEmpty()) {
            return Optional.empty();
        }
        anchorAssociations.put(siteOwner, new AnchorAssociation(dimension, siteOwner, planSignature, claimantId, fresh.get()));
        pendingAnchorAcknowledgment.add(siteOwner);
        setDirty();
        return finishAnchorAcknowledgment(level, ledgerFile, registries, campfire, siteOwner, fresh.get());
    }

    private Optional<UUID> finishAnchorAcknowledgment(ServerLevel level, File ledgerFile, HolderLookup.Provider registries,
            BlockPos campfire, String siteOwner, UUID token) {
        if (testForceChunkAckFailureOnce) {
            testForceChunkAckFailureOnce = false;
            return Optional.empty();
        }
        Optional<TownStorageChunkCheckpoint.CheckpointResult> chunkAck = checkpointChunkIfUnclaimed(level, new ChunkPos(campfire));
        if (chunkAck.isEmpty() || !(chunkAck.get() instanceof TownStorageChunkCheckpoint.CheckpointResult.Checkpointed)) {
            return Optional.empty();
        }
        if (testForceLedgerAckFailureOnce) {
            testForceLedgerAckFailureOnce = false;
            return Optional.empty();
        }
        CheckpointResult ledgerAck = requestAcknowledgedCheckpoint(ledgerFile, registries).join();
        if (!(ledgerAck instanceof CheckpointResult.Persisted)) {
            return Optional.empty();
        }
        pendingAnchorAcknowledgment.remove(siteOwner);
        setDirty();
        return Optional.of(token);
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
            entry.putBoolean("NeedsAnyWorkplace", admission.needsAnyWorkplace());
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

        ListTag constructionOps = new ListTag();
        for (TownConstructionOperation operation : constructionOperations.values()) {
            constructionOps.add(writeConstructionOperation(operation));
        }
        tag.put("ConstructionOperations", constructionOps);

        ListTag quarantinedConstructionSiteList = new ListTag();
        for (String key : quarantinedConstructionSites) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", key);
            quarantinedConstructionSiteList.add(entryTag);
        }
        tag.put("QuarantinedConstructionSites", quarantinedConstructionSiteList);

        ListTag anchorAssociationList = new ListTag();
        for (Map.Entry<String, AnchorAssociation> entry : anchorAssociations.entrySet()) {
            AnchorAssociation association = entry.getValue();
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", entry.getKey());
            entryTag.putString("Dimension", association.dimension().location().toString());
            entryTag.putString("SiteOwner", association.siteOwner());
            entryTag.putString("PlanSignature", association.planSignature());
            entryTag.putUUID("ClaimantId", association.claimantId());
            entryTag.putUUID("AnchorGenerationToken", association.anchorGenerationToken());
            anchorAssociationList.add(entryTag);
        }
        tag.put("AnchorAssociations", anchorAssociationList);

        ListTag pendingAnchorAckList = new ListTag();
        for (String key : pendingAnchorAcknowledgment) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("Key", key);
            pendingAnchorAckList.add(entryTag);
        }
        tag.put("PendingAnchorAcknowledgment", pendingAnchorAckList);
        return tag;
    }

    /** [stage 2] Exact before/after effect record serialization - every field below is required
     *  for a faithful round-trip; a load-time failure to parse ANY of them quarantines the whole
     *  operation's site rather than silently dropping just that cell. */
    private static CompoundTag writeConstructionOperation(TownConstructionOperation operation) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("OperationId", operation.operationId());
        entry.putString("Dimension", operation.dimension().location().toString());
        entry.putString("TownKey", operation.townKey());
        entry.putString("SiteOwner", operation.siteOwner());
        entry.putUUID("AnchorGenerationToken", operation.anchorGenerationToken());
        entry.putUUID("Builder", operation.builderId());
        operation.beneficiaryId().ifPresent(id -> entry.putUUID("Beneficiary", id));
        ListTag effects = new ListTag();
        for (TownConstructionOperation.CellEffect effect : operation.effects()) {
            CompoundTag effectTag = new CompoundTag();
            effectTag.putInt("X", effect.pos().getX());
            effectTag.putInt("Y", effect.pos().getY());
            effectTag.putInt("Z", effect.pos().getZ());
            effectTag.put("Before", net.minecraft.nbt.NbtUtils.writeBlockState(effect.before()));
            effectTag.put("After", net.minecraft.nbt.NbtUtils.writeBlockState(effect.after()));
            effectTag.put("BeforeBE", writeBlockEntitySnapshot(effect.beforeBlockEntity()));
            effectTag.put("AfterBE", writeBlockEntitySnapshot(effect.afterBlockEntity()));
            effectTag.putString("Signature", effect.signature());
            effects.add(effectTag);
        }
        entry.put("Effects", effects);
        CompoundTag cost = new CompoundTag();
        for (Map.Entry<TownResourceCategory, Integer> costEntry : operation.reservedCost().entrySet()) {
            cost.putInt(costEntry.getKey().name(), costEntry.getValue());
        }
        entry.put("ReservedCost", cost);
        entry.putString("Phase", operation.phase().name());
        return entry;
    }

    private static CompoundTag writeBlockEntitySnapshot(TownConstructionOperation.BlockEntitySnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        if (snapshot instanceof TownConstructionOperation.BlockEntitySnapshot.None) {
            tag.putString("Kind", "None");
        } else if (snapshot instanceof TownConstructionOperation.BlockEntitySnapshot.Captured captured) {
            tag.putString("Kind", "Captured");
            tag.put("Nbt", captured.nbt());
        } else if (snapshot instanceof TownConstructionOperation.BlockEntitySnapshot.Unsupported unsupported) {
            tag.putString("Kind", "Unsupported");
            tag.putString("Type", unsupported.blockEntityType());
        } else {
            throw new IllegalStateException("unknown BlockEntitySnapshot: " + snapshot);
        }
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
                        entry.getInt("Homes"), workplaces, entry.getBoolean("NeedsAnyWorkplace"), entry.getString("SiteOwner"),
                        entry.getString("PlanSignature"), ResidentCapacityAdmission.State.valueOf(entry.getString("State")),
                        boundSiteOwner, builderId, boundPlanSignature, anchorGenerationToken);
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
                } else if ("SELF_CLAIM_LINKED".equals(entry.getString("State")) || "BUILDING".equals(entry.getString("State"))
                        || "COMPLETED".equals(entry.getString("State"))) {
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

        // [stage 2, coordinator design 51327 / review 51330] A malformed, duplicate, or
        // site-conflicting construction operation record must never be silently dropped without
        // fencing the site it names - this mod can no longer prove whether that site's own world
        // effects/witness are finished, so a NEW operation must never be allowed to reserve there.
        Set<UUID> seenOperationIds = new HashSet<>();
        ListTag constructionOps = tag.getList("ConstructionOperations", Tag.TAG_COMPOUND);
        for (int i = 0; i < constructionOps.size(); i++) {
            CompoundTag entry = constructionOps.getCompound(i);
            ResourceLocation dimensionIdForQuarantine = ResourceLocation.tryParse(entry.getString("Dimension"));
            String siteOwnerForQuarantine = entry.getString("SiteOwner");
            try {
                if (!entry.hasUUID("OperationId")) {
                    throw new IllegalArgumentException("construction operation missing its own id");
                }
                UUID operationId = entry.getUUID("OperationId");
                if (!seenOperationIds.add(operationId)) {
                    TownConstructionOperation firstOccurrence = storage.constructionOperations.get(operationId);
                    if (firstOccurrence != null) {
                        storage.quarantinedConstructionSites.add(
                                quarantinedSiteKey(firstOccurrence.dimension(), firstOccurrence.siteOwner()));
                    }
                    throw new IllegalArgumentException("duplicate construction operation id: " + operationId);
                }
                if (dimensionIdForQuarantine == null) {
                    throw new IllegalArgumentException("invalid construction operation dimension");
                }
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionIdForQuarantine);
                String townKey = entry.getString("TownKey");
                String siteOwner = entry.getString("SiteOwner");
                if (townKey.isEmpty() || siteOwner.isEmpty() || !entry.hasUUID("AnchorGenerationToken") || !entry.hasUUID("Builder")) {
                    throw new IllegalArgumentException("invalid construction operation identity");
                }
                UUID anchorGenerationToken = entry.getUUID("AnchorGenerationToken");
                UUID builderId = entry.getUUID("Builder");
                Optional<UUID> beneficiaryId;
                if (!entry.contains("Beneficiary")) {
                    beneficiaryId = Optional.empty();
                } else if (entry.hasUUID("Beneficiary")) {
                    beneficiaryId = Optional.of(entry.getUUID("Beneficiary"));
                } else {
                    throw new IllegalArgumentException("construction operation has a malformed Beneficiary field");
                }
                ListTag effectsTag = entry.getList("Effects", Tag.TAG_COMPOUND);
                if (effectsTag.isEmpty()) {
                    throw new IllegalArgumentException("construction operation has no recorded cell effects");
                }
                List<TownConstructionOperation.CellEffect> effects = new ArrayList<>();
                net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blockGetter = registries.lookupOrThrow(Registries.BLOCK);
                for (int j = 0; j < effectsTag.size(); j++) {
                    CompoundTag effectTag = effectsTag.getCompound(j);
                    if (!effectTag.contains("X", Tag.TAG_INT) || !effectTag.contains("Y", Tag.TAG_INT)
                            || !effectTag.contains("Z", Tag.TAG_INT)) {
                        throw new IllegalArgumentException("construction operation effect is missing its own position");
                    }
                    if (!effectTag.contains("Signature", Tag.TAG_STRING)) {
                        throw new IllegalArgumentException("construction operation effect is missing its own signature");
                    }
                    BlockPos pos = new BlockPos(effectTag.getInt("X"), effectTag.getInt("Y"), effectTag.getInt("Z"));
                    BlockState before = readValidatedBlockState(blockGetter, effectTag, "Before");
                    BlockState after = readValidatedBlockState(blockGetter, effectTag, "After");
                    TownConstructionOperation.BlockEntitySnapshot beforeBe = readBlockEntitySnapshot(effectTag, "BeforeBE");
                    TownConstructionOperation.BlockEntitySnapshot afterBe = readBlockEntitySnapshot(effectTag, "AfterBE");
                    effects.add(new TownConstructionOperation.CellEffect(pos, before, after, beforeBe, afterBe, effectTag.getString("Signature")));
                }
                if (!entry.contains("ReservedCost", Tag.TAG_COMPOUND)) {
                    throw new IllegalArgumentException("construction operation is missing its own ReservedCost compound");
                }
                CompoundTag costTag = entry.getCompound("ReservedCost");
                Map<TownResourceCategory, Integer> cost = new HashMap<>();
                for (TownResourceCategory category : TownResourceCategory.values()) {
                    if (costTag.contains(category.name())) {
                        if (!costTag.contains(category.name(), Tag.TAG_INT)) {
                            throw new IllegalArgumentException("construction operation reserved cost has the wrong type for " + category);
                        }
                        cost.put(category, costTag.getInt(category.name()));
                    }
                }
                TownConstructionOperation.Phase phase = TownConstructionOperation.Phase.valueOf(entry.getString("Phase"));
                TownConstructionOperation operation = new TownConstructionOperation(operationId, dimension, townKey, siteOwner,
                        anchorGenerationToken, builderId, beneficiaryId, effects, cost, phase);
                storage.constructionOperations.put(operationId, operation);
            } catch (IllegalArgumentException ignored) {
                if (dimensionIdForQuarantine != null && !siteOwnerForQuarantine.isEmpty()) {
                    ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionIdForQuarantine);
                    storage.quarantinedConstructionSites.add(quarantinedSiteKey(dimension, siteOwnerForQuarantine));
                } else {
                    io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.LOGGER.error(
                            "Dynamic Towns dropped a construction operation with an unreadable Dimension/SiteOwner during load - "
                                    + "its own site could NOT be quarantined and remains unfenced");
                }
            }
        }
        // Two different (non-terminal) operations both claiming the SAME site cannot both be
        // real - detected only after every individually-valid entry above is loaded.
        Map<String, Integer> unsettledSiteCounts = new HashMap<>();
        for (TownConstructionOperation operation : storage.constructionOperations.values()) {
            if (operation.isUnsettled()) {
                unsettledSiteCounts.merge(quarantinedSiteKey(operation.dimension(), operation.siteOwner()), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> count : unsettledSiteCounts.entrySet()) {
            if (count.getValue() > 1) {
                storage.quarantinedConstructionSites.add(count.getKey());
            }
        }
        // [coordinator review 51341] Distinct-SITE shared-CHUNK conflicts: two DIFFERENT siteOwners'
        // unsettled operations overlapping on the same chunk cannot both be trusted either. Every
        // operation sharing in such a conflict has its OWN site quarantined.
        Map<ChunkPos, Set<UUID>> unsettledOperationIdsByChunk = new HashMap<>();
        for (TownConstructionOperation operation : storage.constructionOperations.values()) {
            if (operation.isUnsettled()) {
                for (ChunkPos chunkPos : operation.affectedChunks()) {
                    unsettledOperationIdsByChunk.computeIfAbsent(chunkPos, key -> new HashSet<>()).add(operation.operationId());
                }
            }
        }
        for (Map.Entry<ChunkPos, Set<UUID>> chunkOwners : unsettledOperationIdsByChunk.entrySet()) {
            if (chunkOwners.getValue().size() > 1) {
                for (UUID conflictingOperationId : chunkOwners.getValue()) {
                    TownConstructionOperation conflicting = storage.constructionOperations.get(conflictingOperationId);
                    storage.quarantinedConstructionSites.add(quarantinedSiteKey(conflicting.dimension(), conflicting.siteOwner()));
                }
            }
        }
        ListTag quarantinedConstructionSiteList = tag.getList("QuarantinedConstructionSites", Tag.TAG_COMPOUND);
        for (int i = 0; i < quarantinedConstructionSiteList.size(); i++) {
            storage.quarantinedConstructionSites.add(quarantinedConstructionSiteList.getCompound(i).getString("Key"));
        }

        // [stage 2, structural-cell adapter] AnchorAssociations/PendingAnchorAcknowledgment -
        // deliberately best-effort/fail-closed, same shape as every other optional record above: a
        // malformed entry is simply dropped (never a quarantine target itself - the construction
        // operation ledger/admission-binding fences above already cover site safety), which causes
        // establishOrReadAnchorAssociation to correctly treat that siteOwner as never-established.
        ListTag anchorAssociationList = tag.getList("AnchorAssociations", Tag.TAG_COMPOUND);
        for (int i = 0; i < anchorAssociationList.size(); i++) {
            try {
                CompoundTag entry = anchorAssociationList.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(entry.getString("Dimension"));
                if (id == null) {
                    throw new IllegalArgumentException("bad anchor association dimension");
                }
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
                String key = entry.getString("Key");
                String siteOwner = entry.getString("SiteOwner");
                String planSignature = entry.getString("PlanSignature");
                if (key.isEmpty() || siteOwner.isEmpty() || !entry.hasUUID("ClaimantId") || !entry.hasUUID("AnchorGenerationToken")) {
                    throw new IllegalArgumentException("invalid anchor association");
                }
                storage.anchorAssociations.put(key, new AnchorAssociation(dimension, siteOwner, planSignature,
                        entry.getUUID("ClaimantId"), entry.getUUID("AnchorGenerationToken")));
            } catch (IllegalArgumentException ignored) { }
        }
        ListTag pendingAnchorAckList = tag.getList("PendingAnchorAcknowledgment", Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingAnchorAckList.size(); i++) {
            storage.pendingAnchorAcknowledgment.add(pendingAnchorAckList.getCompound(i).getString("Key"));
        }
        return storage;
    }

    /** [coordinator review 51336/51341] Decodes {@code parentTag.get(key)} as a {@link
     *  BlockState}, validating the FULL serialized state via a canonical round-trip check against
     *  the ORIGINAL stored compound - catches an unrecognized block id, and any unknown/invalid
     *  property name or value, in one shot. */
    private static BlockState readValidatedBlockState(net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> blockGetter,
            CompoundTag parentTag, String key) {
        if (!parentTag.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("construction operation effect is missing its own " + key + " block state");
        }
        CompoundTag stateTag = parentTag.getCompound(key);
        if (!stateTag.contains("Name", Tag.TAG_STRING) || stateTag.getString("Name").isEmpty()) {
            throw new IllegalArgumentException("construction operation effect's " + key + " block state is missing its own block id");
        }
        BlockState decoded = net.minecraft.nbt.NbtUtils.readBlockState(blockGetter, stateTag);
        CompoundTag canonical = net.minecraft.nbt.NbtUtils.writeBlockState(decoded);
        if (!canonical.equals(stateTag)) {
            throw new IllegalArgumentException("construction operation effect's " + key + " block state does not round-trip exactly - "
                    + "recorded " + stateTag + " but its own canonical serialization is " + canonical
                    + " (unrecognized block id, or an unknown/invalid property)");
        }
        return decoded;
    }

    /** [coordinator review 51336] Decodes {@code parentTag.get(key)} as a {@link
     *  TownConstructionOperation.BlockEntitySnapshot}, validating every field's own presence/type
     *  BEFORE decoding. */
    private static TownConstructionOperation.BlockEntitySnapshot readBlockEntitySnapshot(CompoundTag parentTag, String key) {
        if (!parentTag.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("construction operation effect is missing its own " + key + " block entity snapshot");
        }
        CompoundTag tag = parentTag.getCompound(key);
        if (!tag.contains("Kind", Tag.TAG_STRING)) {
            throw new IllegalArgumentException("block entity snapshot is missing its own Kind");
        }
        String kind = tag.getString("Kind");
        if ("Captured".equals(kind)) {
            if (!tag.contains("Nbt", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("captured block entity snapshot is missing its own Nbt payload");
            }
            return new TownConstructionOperation.BlockEntitySnapshot.Captured(tag.getCompound("Nbt"));
        } else if ("Unsupported".equals(kind)) {
            if (!tag.contains("Type", Tag.TAG_STRING) || tag.getString("Type").isEmpty()) {
                throw new IllegalArgumentException("unsupported block entity snapshot is missing its own Type");
            }
            return new TownConstructionOperation.BlockEntitySnapshot.Unsupported(tag.getString("Type"));
        } else if ("None".equals(kind)) {
            return new TownConstructionOperation.BlockEntitySnapshot.None();
        }
        throw new IllegalArgumentException("invalid block entity snapshot kind: " + kind);
    }
}
