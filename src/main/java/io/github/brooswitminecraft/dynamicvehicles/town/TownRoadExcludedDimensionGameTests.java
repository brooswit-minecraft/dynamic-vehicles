package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolated MINECRAFT-453 proof that the production road/construction entrypoints exclude Nether work. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class TownRoadExcludedDimensionGameTests {
    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    private TownRoadExcludedDimensionGameTests() {}

    @GameTest(template = "settlement_test", batch = "townRoadExcludedDimensionIsolated")
    public static void netherProductionEntrypointsLeaveClaimResourcesAndRoadProgressUntouched(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null && !nether.dimension().equals(Level.OVERWORLD),
                "test setup: the supported Nether level must be available and excluded from Overworld roads");

        BlockPos campfire = helper.absolutePos(ANCHOR).atY(80);
        ChunkPos chunk = new ChunkPos(campfire);
        RegionCoord region = TownRegion.fromBlockCoords(campfire.getX(), campfire.getZ());
        String townKey = TownKeys.keyFor(nether.dimension(), region);
        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor(townKey);
        storage.markTownFounded(townKey);
        store.credit(TownResourceCategory.STONE, 10_000);
        store.credit(TownResourceCategory.WOOD, 10_000);
        int stoneBefore = store.balance(TownResourceCategory.STONE);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        helper.assertTrue(!nether.getForcedChunks().contains(chunk.toLong()),
                "fixture refuses a Nether chunk already forced by another owner: " + chunk);
        helper.assertTrue(nether.getBlockEntity(campfire) == null,
                "fixture refuses a block entity it cannot restore: " + campfire);
        BlockState originalCampfire = nether.getBlockState(campfire);
        Villager[] builder = {null};
        boolean[] ownsForce = {false};
        boolean[] ownsTicket = {false};
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            Throwable failure = null;
            try {
                if (builder[0] != null && !builder[0].isRemoved()) builder[0].discard();
            } catch (Throwable throwable) {
                failure = throwable;
            }
            try {
                nether.setBlock(campfire, originalCampfire, 3);
                if (!nether.getBlockState(campfire).equals(originalCampfire)) {
                    throw new AssertionError("fixture cleanup could not restore Nether campfire cell: " + campfire);
                }
            } catch (Throwable throwable) {
                if (failure == null) failure = throwable;
                else failure.addSuppressed(throwable);
            }
            try {
                if (ownsTicket[0]) {
                    nether.getChunkSource().removeRegionTicket(TicketType.PORTAL, chunk, 2, campfire, true);
                    ownsTicket[0] = false;
                }
            } catch (Throwable throwable) {
                if (failure == null) failure = throwable;
                else failure.addSuppressed(throwable);
            }
            try {
                if (ownsForce[0]) {
                    nether.setChunkForced(chunk.x, chunk.z, false);
                    ownsForce[0] = false;
                }
            } catch (Throwable throwable) {
                if (failure == null) failure = throwable;
                else failure.addSuppressed(throwable);
            }
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new RuntimeException("owned Nether fixture cleanup failed", failure);
        };
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) { cleanup.run(); }
        });

        ownsForce[0] = nether.setChunkForced(chunk.x, chunk.z, true);
        helper.assertTrue(ownsForce[0], "fixture must acquire its own Nether force: " + chunk);
        nether.getChunkSource().addRegionTicket(TicketType.PORTAL, chunk, 2, campfire, true);
        ownsTicket[0] = true;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) nether.getChunk(chunk.x + dx, chunk.z + dz);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(nether.getChunkSource().isPositionTicking(chunk.toLong())
                                && nether.areEntitiesLoaded(chunk.toLong()),
                        "test setup: owned Nether chunk must reach entity-ticking readiness before production iteration"))
                .thenExecute(() -> {
                    nether.setBlock(campfire, Blocks.CAMPFIRE.defaultBlockState(), 3);
                    builder[0] = EntityType.VILLAGER.create(nether, null, campfire, MobSpawnType.EVENT, false, false);
                    helper.assertTrue(builder[0] != null && nether.addFreshEntity(builder[0]),
                            "test setup: owned Nether Builder must be constructible and addable");
                    builder[0].setVillagerData(builder[0].getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
                    builder[0].getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(nether.dimension(), campfire));
                    boolean visibleToProduction = false;
                    for (Entity entity : nether.getEntities().getAll()) if (entity == builder[0]) visibleToProduction = true;
                    helper.assertTrue(visibleToProduction, "test setup: exact owned Nether Builder must be visible to production iteration");
                    CompoundTag beforeRoad = storage.save(new CompoundTag(), nether.registryAccess());
                    BlockState beforeRoadWorld = nether.getBlockState(campfire);
                    ClaimSnapshot beforeRoadClaim = ClaimSnapshot.capture(builder[0]);
                    TownRoadConstructionService.RoadBuildOutcome roadOutcome =
                            TownRoadConstructionService.buildRoadsForRegionOutcome(nether, region, storage, store);
                    assertNoOp(helper, nether, storage, beforeRoad, beforeRoadWorld, beforeRoadClaim, builder[0], campfire,
                            "real road entrypoint");
                    helper.assertTrue(roadOutcome == TownRoadConstructionService.RoadBuildOutcome.EXCLUDED_DIMENSION,
                            "real road core must report its exact excluded-dimension guard, not an inferred availability no-op");

                    CompoundTag beforeConstruction = storage.save(new CompoundTag(), nether.registryAccess());
                    BlockState beforeConstructionWorld = nether.getBlockState(campfire);
                    ClaimSnapshot beforeConstructionClaim = ClaimSnapshot.capture(builder[0]);
                    TownConstructionService.ConstructionAndRoadsTickOutcome constructionOutcome =
                            TownConstructionService.runConstructionAndRoadsTickOutcome(nether, storage);
                    assertNoOp(helper, nether, storage, beforeConstruction, beforeConstructionWorld, beforeConstructionClaim,
                            builder[0], campfire, "real construction-and-roads entrypoint");
                    helper.assertTrue(constructionOutcome == TownConstructionService.ConstructionAndRoadsTickOutcome.EXCLUDED_DIMENSION,
                            "real construction core must report its exact excluded-dimension guard before claim or road eligibility");
                    helper.assertTrue(store.balance(TownResourceCategory.STONE) == stoneBefore
                                    && store.balance(TownResourceCategory.WOOD) == woodBefore,
                            "both excluded production entrypoints must retain the funded private resources");
                })
                .thenSucceed();
    }

    private static void assertNoOp(GameTestHelper helper, ServerLevel level, TownStorage storage, CompoundTag beforeStorage,
            BlockState beforeWorld, ClaimSnapshot beforeClaim, Villager builder, BlockPos campfire, String entrypoint) {
        helper.assertTrue(beforeStorage.equals(storage.save(new CompoundTag(), level.registryAccess())),
                entrypoint + " must retain the complete private TownStorage snapshot");
        helper.assertTrue(level.getBlockState(campfire).equals(beforeWorld),
                entrypoint + " must retain the complete owned campfire footprint");
        helper.assertTrue(beforeClaim.equals(ClaimSnapshot.capture(builder)),
                entrypoint + " must retain the exact owned Builder claim");
    }

    private record ClaimSnapshot(net.minecraft.world.entity.npc.VillagerProfession profession, GlobalPos jobSite) {
        static ClaimSnapshot capture(Villager builder) {
            return new ClaimSnapshot(builder.getVillagerData().getProfession(),
                    builder.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null));
        }
    }
}
