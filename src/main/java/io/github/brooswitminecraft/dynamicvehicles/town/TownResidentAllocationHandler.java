package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Optional;

/**
 * MINECRAFT-388/455: every villager in a founded town - not only the one claimant whose own
 * construction site handed it off (see {@link TownConstructionService}) - needs reachable
 * residential accommodation, and an unemployed one should be able to pick up any of the town's own
 * already-built, vacant workplaces. This is deliberately a SEPARATE, periodic reconciliation pass,
 * not folded into the construction handoff: a villager that merely wanders into a founded town
 * (never claimed a campfire at all) still needs this exact same matching, and "is there a vacant
 * bed/workstation somewhere in town right now" is an ongoing condition - it can only resolve as the
 * town's own fixtures get built, villagers die/move/unload, or existing claims lapse - not a
 * one-time event a single construction site's own completion could ever fully capture.
 *
 * <p>Deliberately reuses {@link PoiManager} as the SOLE source of truth for both "which fixtures
 * exist" and "which are vacant" - the exact same real reservation ledger
 * {@link TownConstructionService}'s own single-claimant handoff already reserves against - rather
 * than inventing a second, separately-persisted town registry that could drift out of sync with it.
 * Vanilla's own chunk-level POI persistence already survives reload/unload without this mod tracking
 * anything extra.
 */
public final class TownResidentAllocationHandler {
    private TownResidentAllocationHandler() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % TownConstants.RESIDENT_ALLOCATION_INTERVAL_TICKS != 0) {
            return;
        }
        runResidentAllocationTick(level);
    }

    /** The core per-tick work, factored out from {@link #onLevelTick} so it is directly GameTest-
     *  able without needing to construct a real {@link LevelTickEvent.Post}. Uses the real,
     *  server-wide {@link TownStorage} - production always goes through this overload. */
    public static void runResidentAllocationTick(ServerLevel level) {
        runResidentAllocationTick(level, TownStorage.of(level.getServer()));
    }

    /** As {@link #runResidentAllocationTick(ServerLevel)}, but against an explicit
     *  {@link TownStorage} - exposed so a GameTest can drive this deterministically against an
     *  isolated, never-persisted instance, exactly like {@link TownConstructionService#buildAt}
     *  already does, rather than the real server-wide storage every concurrently-running GameTest
     *  shares (a documented source of cross-test flakiness in this codebase's own history). */
    public static void runResidentAllocationTick(ServerLevel level, TownStorage storage) {
        // [Review round 4, finding #4's scope decision, applied consistently] Dynamic Towns is an
        // Overworld-only feature - this reconciliation must never credit/allocate anything for a
        // villager in a dimension the rest of the feature never builds in.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager villager) || !villager.isAlive()) {
                continue;
            }
            // [Review round 25] Every loaded villager - founded town or not - is a live
            // observation that can contradict a stale ownership record. Reconciling here (and on
            // unload, see onEntityLeaveLevel) is what keeps a record from outliving the moment the
            // villager who really took the ticket over stops being visible to the live scan.
            reconcileObservedClaims(villager, storage);
            // [Review round 19, finding #3] The founded-town check must come FIRST: a Builder
            // standing anywhere in the loaded Overworld that happens to have lost its claim is NOT
            // this reconciliation's business unless it is actually standing in some town's own
            // already-founded region. Checking this before any Builder mutation is what stops a
            // non-town Builder (one whose claim lifecycle belongs to a region Dynamic Towns has
            // never founded at all) from ever being touched here.
            RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
            String key = TownKeys.keyFor(level.dimension(), region);
            if (!storage.hasFoundedTown(key)) {
                continue; // only a real, already-founded town's own fixtures are ever matched
            }
            // [Review round 18, finding #3 / round 19, finding #3] Builder is this mod's own
            // temporary construction profession, and TownConstructionService owns an ACTIVELY
            // CLAIMED Builder's entire lifecycle (claim, build, handoff) exclusively - but "is
            // Builder" alone is not "is actively claimed": a Builder whose campfire was destroyed or
            // whose claim otherwise lapsed must never be permanently excluded from reconciliation
            // just because its profession field still happens to say Builder. An orphaned one is
            // released to NONE right here so it falls straight through into the SAME normal
            // reconciliation as any other unemployed villager, rather than being silently re-claimed
            // through this generic path (which owns no campfire-relocation or claim-validation logic
            // of its own - that stays exclusively TownConstructionService's). The stale claim memory
            // itself is released through its own actual level (possibly a different dimension from
            // this one) before being erased, never merely dropped.
            if (villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get()) {
                if (hasActiveBuilderClaim(level, villager)) {
                    continue;
                }
                releaseOldReservation(level, storage, villager, villager.getBrain().getMemory(MemoryModuleType.JOB_SITE),
                        Optional.empty());
                villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
                villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));
            }
            TownPlan plan = TownPlanner.generate(region, level.getSeed());
            BlockPos townCenter = new BlockPos(plan.townCenterX(), villager.blockPosition().getY(), plan.townCenterZ());
            allocateHomeIfNeeded(level, villager, townCenter, region, storage);
            allocateWorkplaceIfNeeded(level, villager, townCenter, region, storage);
        }
    }

    /** {@code true} when {@code villager} is a Builder whose {@code JOB_SITE} memory still points at
     *  a real, physically-present {@code CAMPFIRE} block in THIS dimension - i.e. the same liveness
     *  check {@link TownCampfireClaims} itself relies on, checked directly against this villager's
     *  own memory rather than one specific known campfire position. */
    private static boolean hasActiveBuilderClaim(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .map(site -> level.getBlockState(site.pos()).is(Blocks.CAMPFIRE))
                .orElse(false);
    }

    /** {@code true} when {@code villager}'s existing HOME memory (if any) still resolves to a real,
     *  currently-registered, CURRENTLY HELD reservation in THIS dimension, belonging to the
     *  villager's OWN CURRENT {@code region} - i.e. nothing needs to change. A memory pointing at a
     *  different dimension, at a position whose POI record is gone, at one outside this villager's
     *  current region (round 19, finding #1: a resident that has physically moved to a different
     *  founded town must not keep reading its OLD town's still-intact fixture as valid forever), at
     *  one whose ticket is not actually held at all (an unticketed memory - the reservation was
     *  released by something else without the memory itself being updated), or at one this mod's own
     *  records show is now actually held by a DIFFERENT villager (round 20, finding #1 - occupancy
     *  alone never proves it is still THIS villager's) is treated as "needs a new one".
     *
     * <p>[Review round 24: reverts round 22's backfill] Deliberately does NOT write anything to the
     * ownership registry, even when every check here passes. Backfilling "no evidence this belongs
     * to someone else" into a durable "this belongs to {@code villager}" record was unsound:
     * ABSENCE of observed ownership is never PROOF of this villager's ownership - a stale A whose
     * memory happens to point at untracked, unloaded B's real reservation would pass every one of
     * these same checks, and the backfill would then durably (even across save/reload) misattribute
     * B's reservation to A, which a later release could act on. An unresolved reservation (no
     * tracked owner, no currently-loaded claimant either way) is therefore left exactly as
     * unresolved as it was found - good enough to leave this villager's memory alone for now
     * (see the return value below), but never promoted to a positive ownership claim. */
    private static boolean hasValidHome(ServerLevel level, Villager villager, RegionCoord region, TownStorage storage) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension().equals(level.dimension()))
                .filter(home -> inRegion(home.pos(), region))
                .filter(home -> !storage.isReservedByADifferentVillager(level.dimension(), home.pos(), villager.getUUID()))
                .filter(home -> !isCurrentlyClaimedByAnotherVillager(level, home.pos(), villager))
                .map(home -> level.getPoiManager().exists(home.pos(), type -> type.is(PoiTypes.HOME))
                        && isTicketHeld(level, home.pos()))
                .orElse(false);
    }

    static void allocateHomeIfNeeded(ServerLevel level, Villager villager, BlockPos townCenter, RegionCoord region,
            TownStorage storage) {
        if (hasValidHome(level, villager, region, storage)) {
            return;
        }
        Optional<GlobalPos> oldHome = villager.getBrain().getMemory(MemoryModuleType.HOME);
        level.getPoiManager()
                .take(type -> type.is(PoiTypes.HOME),
                        (type, pos) -> inRegion(pos, region) && isTownBuiltFixture(level, storage, pos),
                        townCenter, TownConstants.RESIDENT_ALLOCATION_SEARCH_RADIUS)
                .ifPresent(bedPos -> {
                    GlobalPos newHome = GlobalPos.of(level.dimension(), bedPos);
                    // [Review round 18, finding #2 / round 20, finding #1] Only now that the new home
                    // is actually secured does the old one (if any - stale, wrong-dimension, or
                    // simply superseded) get released, resolved against ITS OWN actual level and
                    // against this mod's OWN ownership record - never silently overwritten
                    // (permanently leaking whatever reservation it still held), and never released
                    // when it is the exact SAME reservation just re-acquired (round 20, finding #2:
                    // reacquiring the sole eligible fixture must never immediately free it again).
                    releaseOldReservation(level, storage, villager, oldHome, Optional.of(newHome));
                    storage.setReservationOwner(level.dimension(), bedPos, villager.getUUID());
                    villager.getBrain().setMemory(MemoryModuleType.HOME, newHome);
                });
    }

    /** {@code true} when {@code villager} already holds a real profession whose {@code JOB_SITE}
     *  memory resolves to a POI record in THIS dimension, in the villager's OWN CURRENT
     *  {@code region} (round 19, finding #1: a relocated resident must not keep its old town's
     *  workstation forever), whose TYPE actually matches that profession - not merely "some POI
     *  exists there" (round 18, finding #2) - whose ticket is actually held, not merely present
     *  (round 19, finding #1: an unticketed memory is not really "employed" there), and that this
     *  mod's own records do not show as now belonging to a DIFFERENT villager (round 20, finding
     *  #1). {@code NONE} never counts as "already has a workplace", regardless of its memory. */
    private static boolean hasValidWorkplace(ServerLevel level, Villager villager, RegionCoord region, TownStorage storage) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE) {
            return false;
        }
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .filter(site -> inRegion(site.pos(), region))
                .filter(site -> !storage.isReservedByADifferentVillager(level.dimension(), site.pos(), villager.getUUID()))
                .filter(site -> !isCurrentlyClaimedByAnotherVillager(level, site.pos(), villager))
                .map(site -> level.getPoiManager().getType(site.pos())
                        .map(type -> profession.heldJobSite().test(type))
                        .orElse(false)
                        && isTicketHeld(level, site.pos()))
                .orElse(false);
    }

    static void allocateWorkplaceIfNeeded(ServerLevel level, Villager villager, BlockPos townCenter, RegionCoord region,
            TownStorage storage) {
        if (hasValidWorkplace(level, villager, region, storage)) {
            return;
        }
        PoiManager poiManager = level.getPoiManager();
        VillagerProfession existingProfession = villager.getVillagerData().getProfession();
        Optional<BlockPos> workstationPos;
        VillagerProfession assignedProfession;
        if (existingProfession != VillagerProfession.NONE) {
            // [Review round 18, finding #2] A villager that already holds a REAL profession (its
            // current workstation was simply lost/invalidated) must have ITS OWN SAME profession's
            // workstation searched for first - never silently reassigned to whatever different
            // profession's vacancy happens to be found first.
            workstationPos = poiManager.take(existingProfession.heldJobSite(),
                    (type, pos) -> inRegion(pos, region) && isTownBuiltFixture(level, storage, pos),
                    townCenter, TownConstants.RESIDENT_ALLOCATION_SEARCH_RADIUS);
            assignedProfession = existingProfession;
        } else {
            // Genuinely unemployed: pick up any one vacant, assignable, TOWN-OWNED workstation (round
            // 19, finding #2: a same-region vanilla- or player-placed workstation must never be
            // eligible just because it happens to sit in the right region) and whichever real
            // vanilla profession it belongs to.
            Optional<net.minecraft.util.Tuple<BlockPos, VillagerProfession>> candidate = poiManager
                    .getInRange(TownProfessionWorkstations::isAssignableWorkstationType, townCenter,
                            TownConstants.RESIDENT_ALLOCATION_SEARCH_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                    .filter(record -> inRegion(record.getPos(), region)
                            && isTownBuiltFixture(level, storage, record.getPos()))
                    .flatMap(record -> TownProfessionWorkstations.professionForType(record.getPoiType())
                            .map(profession -> new net.minecraft.util.Tuple<>(record.getPos(), profession))
                            .stream())
                    .findFirst();
            if (candidate.isEmpty()) {
                return;
            }
            BlockPos candidatePos = candidate.get().getA();
            assignedProfession = candidate.get().getB();
            workstationPos = poiManager.take(type -> true, (type, pos) -> pos.equals(candidatePos), candidatePos, 1);
        }
        VillagerProfession finalAssignedProfession = assignedProfession;
        Optional<GlobalPos> oldJobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        workstationPos.ifPresent(acquiredPos -> {
            GlobalPos newJobSite = GlobalPos.of(level.dimension(), acquiredPos);
            // [Review round 18, finding #2 / round 20, findings #1-2] Only now that the new
            // workstation is actually secured does the old job-site reservation (if any) get
            // released, resolved against ITS OWN actual level, this mod's own ownership record, and
            // never against the exact SAME reservation just re-acquired.
            releaseOldReservation(level, storage, villager, oldJobSite, Optional.of(newJobSite));
            storage.setReservationOwner(level.dimension(), acquiredPos, villager.getUUID());
            villager.setVillagerData(villager.getVillagerData().setProfession(finalAssignedProfession));
            villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, newJobSite);
        });
    }

    /** {@code true} when the POI record at {@code pos} (if any) is actually OCCUPIED - i.e. fewer
     *  free tickets remain than the type's own maximum - rather than merely existing. A HOME/JOB_SITE
     *  memory pointing at a real, correctly-typed fixture whose ticket was nonetheless released by
     *  something else (round 19, finding #1) is not really still "this villager's own" reservation,
     *  regardless of what the memory itself still says. */
    private static boolean isTicketHeld(ServerLevel level, BlockPos pos) {
        return level.getPoiManager().getType(pos)
                .map(type -> level.getPoiManager().getFreeTickets(pos) < type.value().maxTickets())
                .orElse(false);
    }

    /** {@code true} when {@code pos} falls within exactly {@code region} - the whole-region-diagonal
     *  search radius in {@link TownConstants#RESIDENT_ALLOCATION_SEARCH_RADIUS} can reach well past a
     *  single region's own edge; this is what actually excludes a neighbouring region's content from
     *  ever being matched (round 18, finding #1). */
    private static boolean inRegion(BlockPos pos, RegionCoord region) {
        return TownRegion.fromBlockCoords(pos.getX(), pos.getZ()).equals(region);
    }

    /** {@code true} when what stands at {@code pos} right now is still the fixture a town plan
     *  placed and paid for there - not merely a coordinate the town once paid for. A player's
     *  bed/workstation on an old paid wall, floor or road cell is therefore never a candidate
     *  (review round 25, P2); see {@link TownStorage#isTownBuiltFixture}. */
    private static boolean isTownBuiltFixture(ServerLevel level, TownStorage storage, BlockPos pos) {
        return storage.isTownBuiltFixture(level.dimension(), pos, level.getBlockState(pos));
    }

    /** Releases a previously-held ticket, if any, resolved against ITS OWN actual {@link ServerLevel}
     *  (via {@link net.minecraft.server.MinecraftServer#getLevel}) - a same-position release call
     *  made against the WRONG level's {@link PoiManager} would silently do nothing, permanently
     *  leaking a cross-dimension reservation with no memory left to ever release it from.
     *
     * <p>[Review round 24: requires POSITIVE proof, not merely absence of counter-evidence] Only
     * ever releases when this mod's own registry POSITIVELY records {@code villager} itself as the
     * current owner. Earlier rounds released whenever ownership was merely "not known to be
     * someone else's" (registry silent) AND "not currently claimed live" - but silence is not
     * proof: an untracked, currently-unloaded real owner is invisible to BOTH of those checks at
     * once, so a stale villager's memory could still steal its reservation. Preferring to PRESERVE
     * an unresolved reservation (never releasing it without positive proof) over risking exactly
     * that theft is the deliberate, documented trade-off here - at the cost of a genuine leak if the
     * old reservation really was this villager's own but was never tracked.
     *
     * <p>[Review round 25] The registry's "self" record is a HISTORICAL fact (true at the moment it
     * was written), not a live one - it can go stale: if the physical ticket is later released and
     * re-acquired by some vanilla villager entirely outside this mod's own bookkeeping (the registry
     * is never told), the registry still says {@code villager}, even though a real OTHER villager
     * now genuinely, currently holds it. Positive registry proof is therefore necessary but NOT
     * sufficient - the live scan is restored here as an additional veto: even a "self"-recorded
     * reservation is never released if some OTHER currently-loaded villager's own live memory also
     * claims it right now, since that is definitive, un-stale proof the registry's history no longer
     * matches reality. The same-reservation guard (never releasing the exact position just
     * re-acquired this same call) is kept regardless, since that is a pure identity check, not an
     * ownership inference. The ownership record itself is cleared on an actual release, so a later
     * occupant is never read as still "owned" by whoever held it before. */
    private static void releaseOldReservation(ServerLevel level, TownStorage storage, Villager villager,
            Optional<GlobalPos> old, Optional<GlobalPos> newReservation) {
        old.ifPresent(globalPos -> {
            if (newReservation.map(globalPos::equals).orElse(false)) {
                return;
            }
            ServerLevel oldLevel = globalPos.dimension().equals(level.dimension())
                    ? level : level.getServer().getLevel(globalPos.dimension());
            if (oldLevel == null) {
                // That dimension no longer exists/isn't loaded - there is no PoiManager left to
                // release anything from; nothing further can be done safely.
                return;
            }
            if (!storage.isReservedBySelf(globalPos.dimension(), globalPos.pos(), villager.getUUID())) {
                return; // unresolved (or positively someone else's) - preserve, never release
            }
            if (isCurrentlyClaimedByAnotherVillager(oldLevel, globalPos.pos(), villager)) {
                // The registry's history no longer matches reality - someone else genuinely holds
                // it now. Drop the contradicted record too, so it cannot authorize a release later
                // once that other villager unloads and the live scan can no longer see it.
                storage.clearReservationOwner(globalPos.dimension(), globalPos.pos());
                return;
            }
            if (oldLevel.getPoiManager().exists(globalPos.pos(), type -> true)) {
                oldLevel.getPoiManager().release(globalPos.pos());
            }
            storage.clearReservationOwner(globalPos.dimension(), globalPos.pos());
        });
    }

    /** {@code true} when some OTHER alive villager in {@code level} currently has {@code pos} as
     *  its OWN {@code HOME} or {@code JOB_SITE} memory right now - the live, always-correct
     *  complement to {@link TownStorage}'s own (necessarily incomplete) ownership record: a
     *  reservation someone else's actual, current memory still references is never safe to
     *  validate for a DIFFERENT villager, nor to release out from under its real occupant, no
     *  matter what this mod's own bookkeeping does or doesn't know about it. */
    static boolean isCurrentlyClaimedByAnotherVillager(ServerLevel level, BlockPos pos, Villager self) {
        for (Entity entity : level.getEntities().getAll()) {
            if (!(entity instanceof Villager other) || !other.isAlive() || other.getUUID().equals(self.getUUID())) {
                continue;
            }
            boolean homeMatches = other.getBrain().getMemory(MemoryModuleType.HOME)
                    .map(gp -> gp.dimension().equals(level.dimension()) && gp.pos().equals(pos)).orElse(false);
            boolean jobSiteMatches = other.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                    .map(gp -> gp.dimension().equals(level.dimension()) && gp.pos().equals(pos)).orElse(false);
            if (homeMatches || jobSiteMatches) {
                return true;
            }
        }
        return false;
    }

    /**
     * [Review round 25] The live-scan veto in {@link #releaseOldReservation} only protects a villager
     * that is still loaded. Tracked A is granted P; vanilla later re-grants P's ticket to B outside
     * this mod's bookkeeping; then B unloads before any reconciliation looks at it. From then on the
     * registry still says "A", the live scan cannot see B, and A relocating would release B's ticket.
     *
     * <p>{@link ServerLevel}'s tracking-end callback fires this event at the exact moment a villager
     * stops being visible to {@code level.getEntities()} (chunk unload, dimension change, death or
     * discard alike), so reconciling here - while B's memory can still be read - drops the
     * contradicted "A" record before it can ever authorize that release. The reservation is then
     * unresolved, and unresolved reservations are preserved. Nothing is recorded for B either: its
     * memory is an observation, not proof of ownership.
     */
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Villager villager) {
            reconcileObservedClaims(villager, TownStorage.of(level.getServer()));
        }
    }

    /** Drops every ownership record that {@code villager}'s own current {@code HOME}/{@code JOB_SITE}
     *  memory contradicts - see {@link TownStorage#reconcileObservedClaim}. */
    static void reconcileObservedClaims(Villager villager, TownStorage storage) {
        villager.getBrain().getMemory(MemoryModuleType.HOME)
                .ifPresent(home -> storage.reconcileObservedClaim(home.dimension(), home.pos(), villager.getUUID()));
        villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .ifPresent(site -> storage.reconcileObservedClaim(site.dimension(), site.pos(), villager.getUUID()));
    }
}
