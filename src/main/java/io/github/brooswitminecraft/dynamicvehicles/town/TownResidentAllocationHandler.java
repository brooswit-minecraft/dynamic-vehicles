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
                releaseOldReservation(level, villager.getBrain().getMemory(MemoryModuleType.JOB_SITE));
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
     *  founded town must not keep reading its OLD town's still-intact fixture as valid forever), or
     *  at one whose ticket is not actually held at all (an unticketed memory - the reservation was
     *  released by something else without the memory itself being updated) is treated as "needs a
     *  new one". */
    private static boolean hasValidHome(ServerLevel level, Villager villager, RegionCoord region) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension().equals(level.dimension()))
                .filter(home -> inRegion(home.pos(), region))
                .map(home -> level.getPoiManager().exists(home.pos(), type -> type.is(PoiTypes.HOME))
                        && isTicketHeld(level, home.pos()))
                .orElse(false);
    }

    static void allocateHomeIfNeeded(ServerLevel level, Villager villager, BlockPos townCenter, RegionCoord region,
            TownStorage storage) {
        if (hasValidHome(level, villager, region)) {
            return;
        }
        Optional<GlobalPos> oldHome = villager.getBrain().getMemory(MemoryModuleType.HOME);
        level.getPoiManager()
                .take(type -> type.is(PoiTypes.HOME),
                        (type, pos) -> inRegion(pos, region) && storage.isAnyCellPlaced(level.dimension(), pos),
                        townCenter, TownConstants.RESIDENT_ALLOCATION_SEARCH_RADIUS)
                .ifPresent(bedPos -> {
                    // [Review round 18, finding #2] Only now that the new home is actually secured
                    // does the old one (if any - stale, wrong-dimension, or simply superseded) get
                    // released, resolved against ITS OWN actual level - never silently overwritten,
                    // permanently leaking whatever reservation it still held.
                    releaseOldReservation(level, oldHome);
                    villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedPos));
                });
    }

    /** {@code true} when {@code villager} already holds a real profession whose {@code JOB_SITE}
     *  memory resolves to a POI record in THIS dimension, in the villager's OWN CURRENT
     *  {@code region} (round 19, finding #1: a relocated resident must not keep its old town's
     *  workstation forever), whose TYPE actually matches that profession - not merely "some POI
     *  exists there" (round 18, finding #2) - and whose ticket is actually held, not merely present
     *  (round 19, finding #1: an unticketed memory is not really "employed" there). {@code NONE}
     *  never counts as "already has a workplace", regardless of its memory. */
    private static boolean hasValidWorkplace(ServerLevel level, Villager villager, RegionCoord region) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE) {
            return false;
        }
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .filter(site -> inRegion(site.pos(), region))
                .map(site -> level.getPoiManager().getType(site.pos())
                        .map(type -> profession.heldJobSite().test(type))
                        .orElse(false)
                        && isTicketHeld(level, site.pos()))
                .orElse(false);
    }

    static void allocateWorkplaceIfNeeded(ServerLevel level, Villager villager, BlockPos townCenter, RegionCoord region,
            TownStorage storage) {
        if (hasValidWorkplace(level, villager, region)) {
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
                    (type, pos) -> inRegion(pos, region) && storage.isAnyCellPlaced(level.dimension(), pos),
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
                            && storage.isAnyCellPlaced(level.dimension(), record.getPos()))
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
            // [Review round 18, finding #2] Only now that the new workstation is actually secured
            // does the old job-site reservation (if any) get released, resolved against ITS OWN
            // actual level - never silently overwritten, permanently leaking whatever it still held.
            releaseOldReservation(level, oldJobSite);
            villager.setVillagerData(villager.getVillagerData().setProfession(finalAssignedProfession));
            villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), acquiredPos));
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

    /** Releases a previously-held ticket, if any, resolved against ITS OWN actual {@link ServerLevel}
     *  (via {@link net.minecraft.server.MinecraftServer#getLevel}) - a same-position release call
     *  made against the WRONG level's {@link PoiManager} would silently do nothing, permanently
     *  leaking a cross-dimension reservation with no memory left to ever release it from. */
    private static void releaseOldReservation(ServerLevel level, Optional<GlobalPos> old) {
        old.ifPresent(globalPos -> {
            ServerLevel oldLevel = globalPos.dimension().equals(level.dimension())
                    ? level : level.getServer().getLevel(globalPos.dimension());
            if (oldLevel != null && oldLevel.getPoiManager().exists(globalPos.pos(), type -> true)) {
                oldLevel.getPoiManager().release(globalPos.pos());
            }
            // oldLevel == null: that dimension no longer exists/isn't loaded - there is no
            // PoiManager left to release anything from; nothing further can be done safely.
        });
    }
}
