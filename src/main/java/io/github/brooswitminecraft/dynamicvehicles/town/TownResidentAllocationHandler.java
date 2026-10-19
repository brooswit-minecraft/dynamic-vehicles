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
            // Builder is this mod's own temporary construction profession - TownConstructionService
            // owns its ENTIRE lifecycle (claim, build, handoff) exclusively; this reconciliation pass
            // must never reassign a villager still actively working as one out from under it.
            if (villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get()) {
                continue;
            }
            RegionCoord region = TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ());
            String key = TownKeys.keyFor(level.dimension(), region);
            if (!storage.hasFoundedTown(key)) {
                continue; // only a real, already-founded town's own fixtures are ever matched
            }
            TownPlan plan = TownPlanner.generate(region, level.getSeed());
            BlockPos townCenter = new BlockPos(plan.townCenterX(), villager.blockPosition().getY(), plan.townCenterZ());
            allocateHomeIfNeeded(level, villager, townCenter);
            allocateWorkplaceIfNeeded(level, villager, townCenter);
        }
    }

    /** {@code true} when {@code villager}'s existing HOME memory (if any) still resolves to a real,
     *  currently-registered reservation in THIS dimension - i.e. nothing needs to change. A memory
     *  pointing at a different dimension, or at a position whose POI record is gone (the bed was
     *  destroyed, or never actually registered), is treated as "needs a new one". */
    private static boolean hasValidHome(ServerLevel level, Villager villager) {
        return villager.getBrain().getMemory(MemoryModuleType.HOME)
                .filter(home -> home.dimension().equals(level.dimension()))
                .map(home -> level.getPoiManager().exists(home.pos(), type -> type.is(PoiTypes.HOME)))
                .orElse(false);
    }

    static void allocateHomeIfNeeded(ServerLevel level, Villager villager, BlockPos townCenter) {
        if (hasValidHome(level, villager)) {
            return;
        }
        level.getPoiManager()
                .take(type -> type.is(PoiTypes.HOME), (type, pos) -> true, townCenter, TownConstants.RESIDENT_ALLOCATION_SEARCH_RADIUS)
                .ifPresent(bedPos -> villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), bedPos)));
    }

    /** {@code true} when {@code villager} already holds a real profession with a JOB_SITE memory
     *  that still resolves to a real, currently-registered POI record in THIS dimension. {@code NONE}
     *  never counts as "already has a workplace", regardless of its memory. */
    private static boolean hasValidWorkplace(ServerLevel level, Villager villager) {
        if (villager.getVillagerData().getProfession() == VillagerProfession.NONE) {
            return false;
        }
        return villager.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(site -> site.dimension().equals(level.dimension()))
                .map(site -> level.getPoiManager().exists(site.pos(), type -> true))
                .orElse(false);
    }

    static void allocateWorkplaceIfNeeded(ServerLevel level, Villager villager, BlockPos townCenter) {
        if (hasValidWorkplace(level, villager)) {
            return;
        }
        PoiManager poiManager = level.getPoiManager();
        // Peek (read-only) at the first vacant, assignable workstation and which profession it
        // belongs to, then acquire it by position - single-threaded server tick logic, so nothing
        // else can take it in between these two calls.
        Optional<net.minecraft.util.Tuple<BlockPos, VillagerProfession>> candidate = poiManager
                .getInRange(TownProfessionWorkstations::isAssignableWorkstationType, townCenter,
                        TownConstants.RESIDENT_ALLOCATION_SEARCH_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                .flatMap(record -> TownProfessionWorkstations.professionForType(record.getPoiType())
                        .map(profession -> new net.minecraft.util.Tuple<>(record.getPos(), profession))
                        .stream())
                .findFirst();
        candidate.ifPresent(tuple -> {
            BlockPos workstationPos = tuple.getA();
            VillagerProfession profession = tuple.getB();
            poiManager.take(type -> true, (type, pos) -> pos.equals(workstationPos), workstationPos, 1)
                    .ifPresent(acquiredPos -> {
                        villager.setVillagerData(villager.getVillagerData().setProfession(profession));
                        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), acquiredPos));
                    });
        });
    }
}
