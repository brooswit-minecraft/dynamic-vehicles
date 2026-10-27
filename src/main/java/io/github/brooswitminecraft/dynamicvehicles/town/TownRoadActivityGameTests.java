package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Focused MINECRAFT-453 activity lifecycle coverage kept out of TownGameTests' shared surface. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class TownRoadActivityGameTests {
    private TownRoadActivityGameTests() {}

    @GameTest(template = "settlement_test")
    public static void evictedRoadCorridorResumesOnlyUnpaidCellsWithoutAdoptingForeignOrProtectedBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(5, 2, 5));
        RegionCoord fixtureRegion = TownRegion.fromBlockCoords(anchor.getX(), anchor.getZ());
        RegionCoord town = new RegionCoord(fixtureRegion.x() + 120, fixtureRegion.z() + 120);
        String townKey = TownKeys.keyFor(level.dimension(), town);
        BlockPos start = new BlockPos(TownRegion.minBlockX(town) + 24, 8, TownRegion.minBlockZ(town) + 24);
        ChunkPos corridorChunk = new ChunkPos(start);
        TownStorage storage = new TownStorage();
        storage.markTownFounded(townKey);
        TownResourceStore store = storage.storeFor(townKey);
        store.credit(TownResourceCategory.STONE, 1000);
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        List<TownRoadPlanner.RoadCell> route = List.of(
                new TownRoadPlanner.RoadCell(start.getX(), start.getZ(), start.getY()),
                new TownRoadPlanner.RoadCell(start.getX() + 1, start.getZ(), start.getY()),
                new TownRoadPlanner.RoadCell(start.getX() + 2, start.getZ(), start.getY()),
                new TownRoadPlanner.RoadCell(start.getX() + 3, start.getZ(), start.getY()));
        BlockPos foreign = start.offset(1, 0, 1);
        BlockPos protectedChest = start.offset(2, 0, -1);
        ServerPlayer player = null;
        try {
            helper.assertTrue(level.setChunkForced(corridorChunk.x, corridorChunk.z, true), "fixture must force the remote corridor");
            for (int x = start.getX(); x <= start.getX() + 3; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, start.getY(), z);
                level.setBlock(cell.below(), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(cell, Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(foreign, Blocks.DIRT_PATH.defaultBlockState(), 3);
            level.setBlock(protectedChest, Blocks.CHEST.defaultBlockState(), 3);

            player = GameTestMockPlayers.create(helper);
            player.teleportTo(TownRegion.minBlockX(town) + 300.5, anchor.getY(), TownRegion.minBlockZ(town) + 300.5);
            TownConstructionService.RoadWork work = (workLevel, selected, selectedStorage, selectedStore) -> {
                helper.assertTrue(selected.equals(town), "only the fixture town may dispatch road work");
                return TownRoadConstructionService.buildRoadCells(workLevel, route, selectedStorage, selectedStore) > 0;
            };
            TownConstructionService.runConstructionAndRoadsTick(level, storage, work);
            int partialDebit = 1000 - store.balance(TownResourceCategory.STONE);
            helper.assertTrue(partialDebit == TownConstants.ROAD_BLOCKS_PER_TICK, "first activity tick must create a strict bounded paid prefix");
            helper.assertTrue(!storage.isCellPlaced(level.dimension(), foreign, owner(level, foreign), signature), "foreign path must not become paid progress before eviction");
            helper.assertTrue(!storage.isCellPlaced(level.dimension(), protectedChest, owner(level, protectedChest), signature), "protected chest must not become paid progress before eviction");

            helper.assertTrue(level.setChunkForced(corridorChunk.x, corridorChunk.z, false), "fixture must release the corridor force before eviction");
            for (int tick = 0; tick < 8 && level.hasChunkAt(start); tick++) level.getChunkSource().tick(() -> true, true);
            helper.assertTrue(!level.hasChunkAt(start), "released remote corridor must actually evict before reactivation");
            TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            TownResourceStore reloadedStore = reloaded.storeFor(townKey);
            int beforeAbsentTick = reloadedStore.balance(TownResourceCategory.STONE);
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == beforeAbsentTick, "evicted corridor must not debit while absent");

            helper.assertTrue(level.setChunkForced(corridorChunk.x, corridorChunk.z, true), "fixture must reactivate the evicted corridor");
            for (int tick = 0; tick < 8; tick++) {
                int before = reloadedStore.balance(TownResourceCategory.STONE);
                TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
                if (before == reloadedStore.balance(TownResourceCategory.STONE)) break;
            }
            helper.assertTrue(level.getBlockState(foreign).is(Blocks.DIRT_PATH) && !reloaded.isCellPlaced(level.dimension(), foreign, owner(level, foreign), signature), "reactivation must not adopt foreign path");
            helper.assertTrue(level.getBlockState(protectedChest).is(Blocks.CHEST) && !reloaded.isCellPlaced(level.dimension(), protectedChest, owner(level, protectedChest), signature), "reactivation must preserve protected chest");
            int finalBalance = reloadedStore.balance(TownResourceCategory.STONE);
            helper.assertTrue(1000 - finalBalance == 10, "only ten mutable cells in the four-by-three corridor may ever be charged");
            for (int x = start.getX(); x <= start.getX() + 3; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, start.getY(), z);
                if (!cell.equals(foreign) && !cell.equals(protectedChest)) helper.assertTrue(reloaded.isCellPlaced(level.dimension(), cell, owner(level, cell), signature), "every paid cell must retain exact owner/signature after eviction: " + cell);
            }
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == finalBalance, "funded completed route must not double-charge on reactivation retry");
        } finally {
            level.setChunkForced(corridorChunk.x, corridorChunk.z, false);
            if (player != null) level.getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    private static String owner(ServerLevel level, BlockPos pos) {
        return TownStorage.roadOwner(level.dimension(), TownRegion.fromBlockCoords(pos.getX(), pos.getZ()));
    }
}
