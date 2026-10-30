package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/** Bounded MINECRAFT-453 regression for paid-centre terrain feedback in production road planning. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class TownRoadCenterStabilityGameTests {
    private TownRoadCenterStabilityGameTests() {}

    @GameTest(template = "settlement_test", batch = "townRoadCenterStabilityIsolated")
    public static void paidCenterDoesNotRatchetProductionRoutesAcrossReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionCoord region = new RegionCoord(-160, -161);
        TownPlan plan = TownPlanner.generate(region, level.getSeed());
        BlockPos center = new BlockPos(plan.townCenterX(), 64, plan.townCenterZ());
        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(level.dimension(), region));
        store.credit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK * 2);
        String owner = TownStorage.roadOwner(level.dimension(), region);
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        Set<ChunkPos> forces = new HashSet<>();
        Map<BlockPos, BlockState> original = new HashMap<>();
        try {
            for (ChunkPos chunk : planningChunks(level, plan)) {
                helper.assertTrue(level.setChunkForced(chunk.x, chunk.z, true), "fixture must own planning chunk " + chunk);
                forces.add(chunk);
                level.getChunk(chunk.x, chunk.z);
            }
            helper.assertTrue(TownRoadPlanner.hasLoadedBoundaryTerrain(level, plan),
                    "fixture must load all four real shared-boundary premises before production centre replanning");
            int clearThrough = Math.max(TownTerrain.rawCanopyTop(level, center.getX(), center.getZ()), center.getY() + 5);
            snapshotColumn(helper, level, original, center.getX(), center.getZ(), center.getY() - 1, clearThrough);
            prepareTreeGapCenter(level, center, clearThrough);
            for (TownEdge edge : TownEdge.values()) {
                int[] boundary = plan.edgeConnectionAbsolute(edge);
                int neighborX = boundary[0] + (edge == TownEdge.EAST ? 1 : edge == TownEdge.WEST ? -1 : 0);
                int neighborZ = boundary[1] + (edge == TownEdge.SOUTH ? 1 : edge == TownEdge.NORTH ? -1 : 0);
                int boundaryClear = Math.max(Math.max(TownTerrain.rawCanopyTop(level, boundary[0], boundary[1]),
                        TownTerrain.rawCanopyTop(level, neighborX, neighborZ)), center.getY() + 2);
                snapshotColumn(helper, level, original, boundary[0], boundary[1], center.getY() - 1, boundaryClear);
                snapshotColumn(helper, level, original, neighborX, neighborZ, center.getY() - 1, boundaryClear);
                prepareNaturalColumn(level, boundary[0], boundary[1], center.getY() - 1, boundaryClear);
                prepareNaturalColumn(level, neighborX, neighborZ, center.getY() - 1, boundaryClear);
            }
            helper.assertTrue(TownTerrain.estimateGroundHeight(level, center.getX(), center.getZ()) == center.getY(),
                    "fixture must establish the actual tree-excluded natural centre before any paid write");

            int initialCenter = resolvedCenter(helper, level, plan, storage, "before paid write");
            helper.assertTrue(initialCenter == center.getY(), "controlled loaded edge terrain must resolve the common centre at the natural tree-excluded height");
            int beforeDebit = store.balance(TownResourceCategory.STONE);
            helper.assertTrue(TownRoadConstructionService.buildRoadCells(level,
                            List.of(new TownRoadPlanner.RoadCell(center.getX(), center.getZ(), initialCenter)), 1, storage, store) == 1,
                    "real paid writer must place the bounded centre surface");
            helper.assertTrue(storage.isCellPlaced(level.dimension(), center, owner, signature)
                            && level.getBlockState(center).is(Blocks.DIRT_PATH)
                            && beforeDebit - store.balance(TownResourceCategory.STONE) == TownConstants.ROAD_COST_PER_BLOCK,
                    "paid centre must retain exact owner/signature and debit exactly once");

            helper.assertTrue(TownTerrain.estimateGroundHeight(level, center.getX(), center.getZ()) == center.getY() + 3,
                    "paid writer must leave the bounded upper-log/air-gap shape that used to ratchet the centre");
            assertFourEndpointHeights(helper, level, plan, storage, initialCenter, "after first paid centre");
            int balanceAfterPaidCenter = store.balance(TownResourceCategory.STONE);
            helper.assertTrue(TownRoadConstructionService.buildRoadCells(level,
                            List.of(new TownRoadPlanner.RoadCell(center.getX(), center.getZ(), initialCenter)), 1, storage, store) == 0
                            && store.balance(TownResourceCategory.STONE) == balanceAfterPaidCenter,
                    "already-paid centre must not recharge through the real writer");

            TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(level.dimension(), region));
            helper.assertTrue(reloaded.isCellPlaced(level.dimension(), center, owner, signature),
                    "NBT reload must retain exact paid centre ownership/signature before production replanning");
            assertFourEndpointHeights(helper, level, plan, reloaded, initialCenter, "after NBT reload");
            helper.assertTrue(TownRoadConstructionService.buildRoadCells(level,
                            List.of(new TownRoadPlanner.RoadCell(center.getX(), center.getZ(), initialCenter)), 1, reloaded, reloadedStore) == 0
                            && reloadedStore.balance(TownResourceCategory.STONE) == balanceAfterPaidCenter,
                    "reload/replanning must not add a centre charge");
        } finally {
            Throwable failure = null;
            for (Map.Entry<BlockPos, BlockState> entry : original.entrySet()) {
                try {
                    level.setBlock(entry.getKey(), entry.getValue(), 3);
                    if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) throw new AssertionError("cleanup failed: " + entry.getKey());
                } catch (Throwable throwable) {
                    if (failure == null) failure = throwable;
                    else failure.addSuppressed(throwable);
                }
            }
            for (ChunkPos chunk : forces) {
                try {
                    level.setChunkForced(chunk.x, chunk.z, false);
                } catch (Throwable throwable) {
                    if (failure == null) failure = throwable;
                    else failure.addSuppressed(throwable);
                }
            }
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new RuntimeException("owned centre fixture cleanup failed", failure);
        }
        helper.succeed();
    }

    private static void assertFourEndpointHeights(GameTestHelper helper, ServerLevel level, TownPlan plan, TownStorage storage,
            int expectedCenter, String phase) {
        helper.assertTrue(resolvedCenter(helper, level, plan, storage, phase) == expectedCenter,
                phase + " production chain must retain the resolved common centre");
        for (TownEdge edge : TownEdge.values()) {
            List<TownRoadPlanner.RoadCell> route = TownRoadPlanner.routeForEdge(level, plan, edge, expectedCenter);
            helper.assertTrue(route.getLast().y() == expectedCenter,
                    phase + " all four production terrain-derived routes must retain the common centre height: " + edge);
        }
    }

    private static int resolvedCenter(GameTestHelper helper, ServerLevel level, TownPlan plan, TownStorage storage, String phase) {
        OptionalInt resolved = TownRoadPlanner.resolveSharedCenterHeight(level, plan,
                TownRoadConstructionService.centerTerrainHeight(level, plan, storage));
        helper.assertTrue(resolved.isPresent(), phase + " real shared-centre resolution must remain available");
        return resolved.orElseThrow();
    }

    private static void snapshotColumn(GameTestHelper helper, ServerLevel level, Map<BlockPos, BlockState> original,
            int x, int z, int fromY, int throughY) {
        for (int y = fromY; y <= throughY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            helper.assertTrue(level.getBlockEntity(pos) == null, "fixture refuses un-restorable block entity: " + pos);
            original.putIfAbsent(pos, level.getBlockState(pos));
        }
    }

    private static void prepareNaturalColumn(ServerLevel level, int x, int z, int groundY, int clearThrough) {
        for (int y = groundY + 1; y <= clearThrough; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x, groundY, z), Blocks.DIRT.defaultBlockState(), 3);
    }

    private static void prepareTreeGapCenter(ServerLevel level, BlockPos center, int clearThrough) {
        prepareNaturalColumn(level, center.getX(), center.getZ(), center.getY() - 1, clearThrough);
        for (int y = center.getY(); y <= center.getY() + 4; y++) {
            level.setBlock(new BlockPos(center.getX(), y, center.getZ()), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
    }

    private static Set<ChunkPos> planningChunks(ServerLevel level, TownPlan plan) {
        Set<ChunkPos> chunks = new HashSet<>();
        chunks.add(new ChunkPos(plan.townCenterX() >> 4, plan.townCenterZ() >> 4));
        for (TownEdge edge : TownEdge.values()) {
            int[] point = plan.edgeConnectionAbsolute(edge);
            chunks.add(new ChunkPos(point[0] >> 4, point[1] >> 4));
            chunks.add(new ChunkPos((point[0] + (edge == TownEdge.EAST ? 1 : edge == TownEdge.WEST ? -1 : 0)) >> 4,
                    (point[1] + (edge == TownEdge.SOUTH ? 1 : edge == TownEdge.NORTH ? -1 : 0)) >> 4));
        }
        return chunks;
    }
}
