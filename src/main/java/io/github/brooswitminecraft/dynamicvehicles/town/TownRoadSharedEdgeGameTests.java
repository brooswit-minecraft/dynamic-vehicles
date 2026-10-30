package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Separately reviewable MINECRAFT-453 generated-road/shared-edge coverage. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class TownRoadSharedEdgeGameTests {
    private TownRoadSharedEdgeGameTests() {}

    @GameTest(template = "settlement_test", batch = "townRoadSharedEdgeIsolated")
    public static void generatedNegativeSharedEdgeUsesExactOwnersSignaturesAndDebits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RegionCoord west = new RegionCoord(-120, -121); // independent of the template's random absolute origin
        RegionCoord east = TownRegion.neighbor(west, TownEdge.EAST);
        TownPlan westPlan = TownPlanner.generate(west, level.getSeed());
        TownPlan eastPlan = TownPlanner.generate(east, level.getSeed());
        int[] westEdge = westPlan.edgeConnectionAbsolute(TownEdge.EAST);
        int[] eastEdge = eastPlan.edgeConnectionAbsolute(TownEdge.WEST);
        ChunkPos westChunk = new ChunkPos(new BlockPos(westEdge[0], level.getMinBuildHeight(), westEdge[1]));
        ChunkPos eastChunk = new ChunkPos(new BlockPos(eastEdge[0], level.getMinBuildHeight(), eastEdge[1]));
        TownStorage storage = new TownStorage();
        TownResourceStore store = new TownResourceStore();
        store.credit(TownResourceCategory.STONE, 2 * TownConstants.ROAD_COST_PER_BLOCK);
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        Map<BlockPos, BlockState> original = new HashMap<>();
        boolean ownsWestForce = false;
        boolean ownsEastForce = false;
        try {
            ownsWestForce = level.setChunkForced(westChunk.x, westChunk.z, true);
            ownsEastForce = level.setChunkForced(eastChunk.x, eastChunk.z, true);
            helper.assertTrue(ownsWestForce && ownsEastForce, "fixture must acquire both negative shared-edge chunks");
            helper.assertTrue(westEdge[0] < 0 && westEdge[1] < 0 && eastEdge[0] < 0 && eastEdge[1] < 0,
                    "fixture regions must be genuinely negative regardless of template origin");
            int priorWestTop = TownTerrain.estimateGroundHeight(level, westEdge[0], westEdge[1]);
            int priorEastTop = TownTerrain.estimateGroundHeight(level, eastEdge[0], eastEdge[1]);
            int controlledBase = Math.max(priorWestTop, priorEastTop);
            // Establish actual, deliberately unequal natural tops rather than planting below
            // an unknown higher column.  The one-block low side still makes the real writer fill
            // bounded support; nothing is prefilled at its planned road Y.
            int westGround = controlledBase;
            int eastGround = controlledBase + 1;
            int clearThrough = Math.max(Math.max(TownTerrain.rawCanopyTop(level, westEdge[0], westEdge[1]),
                    TownTerrain.rawCanopyTop(level, eastEdge[0], eastEdge[1])), eastGround + 3);
            snapshotColumn(helper, level, original, westEdge[0], westEdge[1], westGround, clearThrough);
            snapshotColumn(helper, level, original, eastEdge[0], eastEdge[1], westGround, clearThrough);
            prepareControlledNaturalTop(level, westEdge[0], westEdge[1], westGround, clearThrough);
            prepareControlledNaturalTop(level, eastEdge[0], eastEdge[1], eastGround, clearThrough);
            helper.assertTrue(TownTerrain.estimateGroundHeight(level, westEdge[0], westEdge[1]) == westGround + 1
                            && TownTerrain.estimateGroundHeight(level, eastEdge[0], eastEdge[1]) == eastGround + 1,
                    "fixture must independently prove its controlled unequal natural tops before planning");
            int westBoundaryHeight = TownRoadPlanner.edgeHeightFor(level, westPlan, TownEdge.EAST);
            int eastBoundaryHeight = TownRoadPlanner.edgeHeightFor(level, eastPlan, TownEdge.WEST);
            helper.assertTrue(westBoundaryHeight == eastGround + 1 && eastBoundaryHeight == eastGround + 1,
                    "real opposite-direction planning must choose the higher known loaded natural boundary height");
            TownRoadPlanner.RoadCell westBoundary = TownRoadPlanner.routeForEdge(level, westPlan, TownEdge.EAST, westBoundaryHeight).get(0);
            TownRoadPlanner.RoadCell eastBoundary = TownRoadPlanner.routeForEdge(level, eastPlan, TownEdge.WEST, eastBoundaryHeight).get(0);
            BlockPos westPos = new BlockPos(westBoundary.x(), westBoundary.y(), westBoundary.z());
            BlockPos eastPos = new BlockPos(eastBoundary.x(), eastBoundary.y(), eastBoundary.z());
            helper.assertTrue(westBoundary.x() + 1 == eastBoundary.x() && westBoundary.z() == eastBoundary.z(),
                    "opposite plans must agree on the offset and use adjacent boundary columns");
            helper.assertTrue(westBoundary.y() == eastBoundary.y(), "opposite plans must agree on terrain-derived boundary height");
            helper.assertTrue(original.containsKey(westPos.below()) && original.containsKey(westPos)
                            && original.containsKey(westPos.above()) && original.containsKey(westPos.above(2))
                            && original.containsKey(eastPos.below()) && original.containsKey(eastPos)
                            && original.containsKey(eastPos.above()) && original.containsKey(eastPos.above(2)),
                    "full boundary snapshot must precede every preparation and construction write");
            helper.assertTrue(TownRoadConstructionService.buildRoadCells(level, List.of(westBoundary), 1, storage, store) == 1,
                    "real construction must place the west generated boundary cell");
            helper.assertTrue(storage.isCellPlaced(level.dimension(), westPos, TownStorage.roadOwner(level.dimension(), west), signature),
                    "west cell must retain its exact production owner/signature");
            helper.assertTrue(TownRoadPlanner.edgeHeightFor(level, westPlan, TownEdge.EAST) == westBoundaryHeight
                            && TownRoadPlanner.edgeHeightFor(level, eastPlan, TownEdge.WEST) == eastBoundaryHeight,
                    "first paid surface must not ratchet either shared-edge planning height");
            helper.assertTrue(TownRoadConstructionService.buildRoadCells(level, List.of(eastBoundary), 1, storage, store) == 1,
                    "real construction must place the east generated boundary cell");
            helper.assertTrue(storage.isCellPlaced(level.dimension(), eastPos, TownStorage.roadOwner(level.dimension(), east), signature),
                    "east cell must retain its exact production owner/signature");
            helper.assertTrue(store.balance(TownResourceCategory.STONE) == 0, "two shared-edge cells must debit exactly two road costs");
            helper.assertTrue(TownRoadPlanner.edgeHeightFor(level, westPlan, TownEdge.EAST) == westBoundaryHeight
                            && TownRoadPlanner.edgeHeightFor(level, eastPlan, TownEdge.WEST) == eastBoundaryHeight,
                    "both paid surfaces must leave shared-edge height stable for replanning");
            TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            helper.assertTrue(reloaded.isCellPlaced(level.dimension(), westPos, TownStorage.roadOwner(level.dimension(), west), signature)
                            && reloaded.isCellPlaced(level.dimension(), eastPos, TownStorage.roadOwner(level.dimension(), east), signature),
                    "reload must preserve both paid owners/signatures before replanning");
            helper.assertTrue(TownRoadPlanner.edgeHeightFor(level, westPlan, TownEdge.EAST) == westBoundaryHeight
                            && TownRoadPlanner.edgeHeightFor(level, eastPlan, TownEdge.WEST) == eastBoundaryHeight,
                    "reload/replanning must not change the stable shared-edge height");
        } finally {
            try {
                for (Map.Entry<BlockPos, BlockState> entry : original.entrySet()) {
                    level.setBlock(entry.getKey(), entry.getValue(), 3);
                    if (!level.getBlockState(entry.getKey()).equals(entry.getValue())) throw new AssertionError("cleanup failed: " + entry.getKey());
                }
            } finally {
                try {
                    if (ownsEastForce) level.setChunkForced(eastChunk.x, eastChunk.z, false);
                } finally {
                    if (ownsWestForce) level.setChunkForced(westChunk.x, westChunk.z, false);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test", batch = "townRoadSharedEdgeUnavailableIsolated")
    public static void oneUnloadedSharedBoundaryDefersWithoutLoading(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A different batch/region from the two-sided fixture because this test waits on ordinary
        // GameTest ticks for ticket readiness and must not overlap its owned boundary columns.
        RegionCoord west = new RegionCoord(-140, -141);
        TownPlan westPlan = TownPlanner.generate(west, level.getSeed());
        int[] westEdge = westPlan.edgeConnectionAbsolute(TownEdge.EAST);
        ChunkPos westChunk = new ChunkPos(new BlockPos(westEdge[0], level.getMinBuildHeight(), westEdge[1]));
        BlockPos westProbe = new BlockPos(westEdge[0], level.getMinBuildHeight(), westEdge[1]);
        BlockPos eastProbe = westProbe.east();
        boolean[] ownsWestTicket = {false};
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            if (ownsWestTicket[0]) {
                level.getChunkSource().removeRegionTicket(TicketType.PORTAL, westChunk, 0, westProbe, false);
                ownsWestTicket[0] = false;
            }
        };
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) { cleanup.run(); }
        });
        // A distance-zero PORTAL ticket is a minimal FULL (level 33) owned west ticket.  Unlike
        // a forced ENTITY_TICKING level-31 ticket, its adjacent propagation is level 34 and does
        // not retain the east boundary at FULL/hasChunkAt availability.
        level.getChunkSource().addRegionTicket(TicketType.PORTAL, westChunk, 0, westProbe, false);
        ownsWestTicket[0] = true;
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.hasChunkAt(westProbe) && !level.hasChunkAt(eastProbe),
                        "fixture requires actual available west and unavailable east boundary terrain before planning"))
                .thenExecute(() -> {
                    helper.assertTrue(level.hasChunkAt(westProbe) && !level.hasChunkAt(eastProbe),
                            "planner precondition must observe west available and east unavailable");
                    try {
                        TownRoadPlanner.edgeHeightFor(level, westPlan, TownEdge.EAST);
                        throw new AssertionError("planner must defer instead of loading a missing shared-boundary neighbor");
                    } catch (IllegalStateException expected) {
                        helper.assertTrue(expected.getMessage().contains("defer road planning"),
                                "unloaded neighbor must take the explicit no-extra-load deferral path");
                    }
                    helper.assertTrue(level.hasChunkAt(westProbe) && !level.hasChunkAt(eastProbe),
                            "planner deferral must retain west and must not implicitly load east");
                })
                .thenSucceed();
    }

    private static void snapshotColumn(GameTestHelper helper, ServerLevel level, Map<BlockPos, BlockState> original,
            int x, int z, int fromY, int throughY) {
        for (int y = fromY; y <= throughY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            helper.assertTrue(level.getBlockEntity(pos) == null, "fixture refuses un-restorable block entity: " + pos);
            original.put(pos, level.getBlockState(pos));
        }
    }

    private static void prepareControlledNaturalTop(ServerLevel level, int x, int z, int groundY, int clearThrough) {
        for (int y = groundY + 1; y <= clearThrough; y++) {
            level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(new BlockPos(x, groundY, z), Blocks.DIRT.defaultBlockState(), 3);
    }
}
