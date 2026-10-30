package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Isolated MINECRAFT-453 proof of the normal generated-road production binding. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class TownRoadProductionBindingGameTests {
    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    private TownRoadProductionBindingGameTests() {}

    @GameTest(template = "settlement_test", batch = "townRoadProductionBindingIsolated")
    public static void releasedBuilderDispatchesGeneratedPaidRoadPrefixAcrossReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        prepareFlatGround(helper);
        BlockPos campfire = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ());
        String townKey = TownKeys.keyFor(level.dimension(), region);
        TownResourceStore store = storage.storeFor(townKey);
        store.credit(TownResourceCategory.STONE, 100_000);
        store.credit(TownResourceCategory.WOOD, 100_000);
        Villager builder = EntityType.VILLAGER.create(level, null, campfire, MobSpawnType.EVENT, false, false);
        level.addFreshEntity(builder);
        builder.setVillagerData(builder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        builder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), campfire));

        ServerPlayer player = null;
        Set<ChunkPos> ownedForces = new HashSet<>();
        Map<BlockPos, BlockState> original = new HashMap<>();
        try {
            for (int attempt = 0; attempt < 500 && builder.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); attempt++) {
                TownConstructionService.buildAt(level, campfire, storage);
            }
            helper.assertTrue(!level.getBlockState(campfire).is(Blocks.CAMPFIRE),
                    "fixture requires real completion to replace the sole campfire before roads run");
            helper.assertTrue(builder.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                    "fixture requires the real Builder to be released before roads run");
            helper.assertTrue(!TownConstructionService.findClaimedCampfireSites(level).containsKey(campfire),
                    "released sole site must no longer remain in the production claim scan");

            TownPlan plan = TownPlanner.generate(region, level.getSeed());
            for (ChunkPos chunk : requiredPlanningChunks(level, plan)) {
                helper.assertTrue(level.setChunkForced(chunk.x, chunk.z, true),
                        "fixture must acquire each generated-road planning chunk itself: " + chunk);
                ownedForces.add(chunk);
                level.getChunk(chunk.x, chunk.z);
            }
            helper.assertTrue(TownRoadPlanner.hasLoadedBoundaryTerrain(level, plan),
                    "all real shared-edge terrain premises must be loaded before the production tick");
            List<TownRoadPlanner.RoadCell> major = generatedNorthMajor(level, plan);
            List<BlockPos> prefix = firstMajorFootprint(major);
            snapshotAndPreparePrefix(helper, level, original, prefix);
            // Preparation supplies natural support and headroom WITHOUT raising the planned
            // surface.  Re-derive from the live world before the production binding; if this
            // changes, the fixture must fail rather than silently assert stale coordinates.
            helper.assertTrue(prefix.equals(firstMajorFootprint(generatedNorthMajor(level, plan))),
                    "controlled natural preparation must preserve the generated prefix before the production tick");

            storage.markTownFounded(townKey);
            player = GameTestMockPlayers.create(helper);
            player.teleportTo(campfire.getX() + 0.5, campfire.getY(), campfire.getZ() + 0.5);
            int balanceBefore = store.balance(TownResourceCategory.STONE);
            helper.assertTrue(TownConstructionService.findClaimedCampfireSites(level).isEmpty(),
                    "sole-site fixture must refuse any foreign construction claim before the production tick");
            helper.assertTrue(prefix.equals(firstMajorFootprint(generatedNorthMajor(level, plan))),
                    "the first production assertion must use a freshly derived live-terrain prefix");
            TownConstructionService.runConstructionAndRoadsTick(level, storage);
            assertPaidPrefix(helper, level, storage, prefix.subList(0, TownConstants.ROAD_BLOCKS_PER_TICK),
                    balanceBefore, store.balance(TownResourceCategory.STONE));

            TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            TownResourceStore reloadedStore = reloaded.storeFor(townKey);
            int beforeReloadTick = reloadedStore.balance(TownResourceCategory.STONE);
            helper.assertTrue(TownConstructionService.findClaimedCampfireSites(level).isEmpty(),
                    "sole-site fixture must refuse any foreign construction claim before the reload production tick");
            helper.assertTrue(prefix.equals(firstMajorFootprint(generatedNorthMajor(level, plan))),
                    "reload production assertion must use a freshly derived stable live-terrain prefix");
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded);
            helper.assertTrue(beforeReloadTick - reloadedStore.balance(TownResourceCategory.STONE)
                            == TownConstants.ROAD_BLOCKS_PER_TICK,
                    "the same production binding must make a new bounded generated-road prefix after NBT reload");
            for (BlockPos oldCell : prefix.subList(0, TownConstants.ROAD_BLOCKS_PER_TICK)) {
                helper.assertTrue(level.getBlockState(oldCell).is(Blocks.DIRT_PATH),
                        "reload continuation must retain each already-paid live road surface: " + oldCell);
                helper.assertTrue(reloaded.isCellPlaced(level.dimension(), oldCell, owner(level, oldCell), signature()),
                        "reload continuation must retain each already-paid generated cell without recharging it: " + oldCell);
            }
            for (BlockPos newCell : prefix.subList(TownConstants.ROAD_BLOCKS_PER_TICK,
                    TownConstants.ROAD_BLOCKS_PER_TICK * 2)) {
                helper.assertTrue(level.getBlockState(newCell).is(Blocks.DIRT_PATH),
                        "reload continuation must place each new live road surface: " + newCell);
                helper.assertTrue(reloaded.isCellPlaced(level.dimension(), newCell, owner(level, newCell), signature()),
                        "reload continuation must record each new generated paid cell with exact owner/signature: " + newCell);
            }
        } finally {
            cleanupOwnedFixture(level, original, ownedForces, player, builder);
        }
        helper.succeed();
    }

    private static Set<ChunkPos> requiredPlanningChunks(ServerLevel level, TownPlan plan) {
        Set<ChunkPos> chunks = new HashSet<>();
        chunks.add(new ChunkPos(plan.townCenterX() >> 4, plan.townCenterZ() >> 4));
        for (TownEdge edge : TownEdge.values()) {
            int[] p = plan.edgeConnectionAbsolute(edge);
            chunks.add(new ChunkPos(p[0] >> 4, p[1] >> 4));
            chunks.add(new ChunkPos((p[0] + (edge == TownEdge.EAST ? 1 : edge == TownEdge.WEST ? -1 : 0)) >> 4,
                    (p[1] + (edge == TownEdge.SOUTH ? 1 : edge == TownEdge.NORTH ? -1 : 0)) >> 4));
        }
        return chunks;
    }

    private static List<BlockPos> firstMajorFootprint(List<TownRoadPlanner.RoadCell> route) {
        TownRoadPlanner.RoadCell first = route.getFirst();
        TownRoadPlanner.RoadCell second = route.get(1);
        boolean runsAlongX = first.z() == second.z();
        List<BlockPos> cells = new ArrayList<>();
        for (int routeIndex = 0; routeIndex < 2; routeIndex++) {
            TownRoadPlanner.RoadCell cell = route.get(routeIndex);
            for (int offset = -2; offset <= 2; offset++) {
                cells.add(new BlockPos(runsAlongX ? cell.x() : cell.x() + offset, cell.y(), runsAlongX ? cell.z() + offset : cell.z()));
            }
        }
        return cells;
    }

    private static List<TownRoadPlanner.RoadCell> generatedNorthMajor(ServerLevel level, TownPlan plan) {
        int centerHeight = TownRoadPlanner.resolveSharedCenterHeight(level, plan,
                TownTerrain.estimateGroundHeight(level, plan.townCenterX(), plan.townCenterZ())).orElseThrow();
        return TownRoadPlanner.routeForEdge(level, plan, TownEdge.NORTH, centerHeight);
    }

    private static void snapshotAndPreparePrefix(GameTestHelper helper, ServerLevel level, Map<BlockPos, BlockState> original,
            List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            for (int y = cell.getY() - TownConstants.ROAD_SUPPORT_FILL_MAX_DEPTH; y <= cell.getY() + 2; y++) {
                BlockPos pos = new BlockPos(cell.getX(), y, cell.getZ());
                helper.assertTrue(level.getBlockEntity(pos) == null, "fixture refuses an un-restorable block entity: " + pos);
                original.putIfAbsent(pos, level.getBlockState(pos));
            }
            level.setBlock(cell.below(), Blocks.DIRT.defaultBlockState(), 3);
            // The surface position must remain air over the known natural support.  Writing DIRT
            // here would make the live heightmap one block taller and invalidate the route just
            // derived from real terrain.
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void assertPaidPrefix(GameTestHelper helper, ServerLevel level, TownStorage storage, List<BlockPos> cells,
            int before, int after) {
        helper.assertTrue(before - after == TownConstants.ROAD_BLOCKS_PER_TICK,
                "first production tick must debit exactly the generated bounded prefix");
        for (BlockPos cell : cells) {
            helper.assertTrue(level.getBlockState(cell).is(Blocks.DIRT_PATH), "generated prefix must place a live road surface: " + cell);
            helper.assertTrue(storage.isCellPlaced(level.dimension(), cell, owner(level, cell), signature()),
                    "generated prefix must persist exact owner/signature: " + cell);
        }
    }

    private static String owner(ServerLevel level, BlockPos pos) {
        return TownStorage.roadOwner(level.dimension(), TownRegion.fromBlockCoords(pos.getX(), pos.getZ()));
    }

    private static String signature() {
        return "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
    }

    /** Attempt every owned cleanup action even when an earlier restoration reports a failure. */
    private static void cleanupOwnedFixture(ServerLevel level, Map<BlockPos, BlockState> original,
            Set<ChunkPos> ownedForces, ServerPlayer player, Villager builder) {
        Throwable failure = null;
        for (Map.Entry<BlockPos, BlockState> entry : original.entrySet()) {
            try {
                level.setBlock(entry.getKey(), entry.getValue(), 3);
                if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                    throw new AssertionError("fixture cleanup could not restore generated-road footprint: " + entry.getKey());
                }
            } catch (Throwable throwable) {
                if (failure == null) failure = throwable;
                else failure.addSuppressed(throwable);
            }
        }
        for (ChunkPos chunk : ownedForces) {
            try {
                level.setChunkForced(chunk.x, chunk.z, false);
            } catch (Throwable throwable) {
                if (failure == null) failure = throwable;
                else failure.addSuppressed(throwable);
            }
        }
        try {
            if (player != null) level.getServer().getPlayerList().remove(player);
        } catch (Throwable throwable) {
            if (failure == null) failure = throwable;
            else failure.addSuppressed(throwable);
        }
        try {
            builder.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        } catch (Throwable throwable) {
            if (failure == null) failure = throwable;
            else failure.addSuppressed(throwable);
        }
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new RuntimeException("owned fixture cleanup failed", failure);
    }

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) for (int y = 0; y <= 12; y++) {
            helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
        }
    }
}
