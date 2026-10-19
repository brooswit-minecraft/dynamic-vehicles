package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * MINECRAFT-388: GameTests for the Minecraft-facing halves of Dynamic Towns, following
 * {@code delivery.SettlementFounderGameTests}' conventions (same flat-ground helper, same
 * freshly-constructed, never-persisted {@code TownStorage} per test so a prior run's founded-town
 * state can never make a rerun silently skip its own placement).
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownGameTests {
    private TownGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    /**
     * Exercises {@link TownFounder#tryFoundAt} directly with an explicit target matching the
     * prepared flat ground, rather than {@link TownFounder#tryFoundNear}'s full plan-driven
     * target (anywhere within a 384-block region, which cannot be relied on to land inside this
     * tiny GameTest structure's own prepared footprint — {@code TownPlannerTest} already covers
     * that choice). The region coordinate itself is irrelevant to this test other than needing to
     * be stable across both calls within it, so a fixed, GameTest-only region is used.
     */
    @GameTest(template = "settlement_test")
    public static void bootstrapPlacesExactlyOneCampfireAndOneVillager(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord region = new RegionCoord(0, 0);
        TownStorage storage = new TownStorage();

        TownFounder.tryFoundAt(helper.getLevel(), region, absoluteAnchor, storage);

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        List<Villager> villagers = helper.getLevel().getEntitiesOfClass(Villager.class, footprint);
        helper.assertTrue(villagers.size() == 1, "expected exactly one bootstrap villager, found " + villagers.size());
        helper.assertTrue(villagers.get(0).getVillagerData().getProfession() == VillagerProfession.NONE,
                "the bootstrap villager should start unemployed");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void repeatedBootstrapAttemptAtTheSameRegionDoesNotDuplicate(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord region = new RegionCoord(0, 0);
        TownStorage storage = new TownStorage();

        TownFounder.tryFoundAt(helper.getLevel(), region, absoluteAnchor, storage);
        TownFounder.tryFoundAt(helper.getLevel(), region, absoluteAnchor, storage);

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        List<Villager> villagers = helper.getLevel().getEntitiesOfClass(Villager.class, footprint);
        helper.assertTrue(villagers.size() == 1, "a repeated bootstrap attempt must not spawn a second villager, found " + villagers.size());
        helper.succeed();
    }

    /**
     * {@link TownCampfireClaims} reuses vanilla's own job-site memory as the sole claim signal: an
     * employed villager whose JOB_SITE memory is NOT this exact campfire must not count, and a
     * villager with no profession at all (e.g. the bootstrap villager itself) must not count either
     * — only an employed villager whose job site IS this cell makes it claimed.
     */
    @GameTest(template = "settlement_test")
    public static void onlyAnEmployedVillagerWithThisExactJobSiteCountsAsClaimed(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        helper.assertTrue(!TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "an empty campfire with no villager nearby must not read as claimed");

        Villager unemployed = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(unemployed);
        helper.assertTrue(!TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "an unemployed villager merely standing at the campfire must not claim it");

        unemployed.setVillagerData(unemployed.getVillagerData().setProfession(VillagerProfession.FARMER));
        BlockPos elsewhere = absoluteCampfire.offset(20, 0, 0);
        unemployed.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), elsewhere));
        helper.assertTrue(!TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "an employed villager whose job site is a DIFFERENT position must not claim this campfire");

        unemployed.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        helper.assertTrue(TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "an employed villager whose job site IS this exact campfire must claim it");
        helper.succeed();
    }

    /** A villager claiming {@code campfire} exactly, the same claim shape {@link TownCampfireClaims} reads. */
    private static Villager spawnClaimedVillager(GameTestHelper helper, BlockPos absoluteCampfire) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        return villager;
    }

    private static TownResourceStore fundStore(GameTestHelper helper, TownStorage storage, BlockPos absoluteCampfire, int amount) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
        store.credit(TownResourceCategory.STONE, amount);
        store.credit(TownResourceCategory.WOOD, amount);
        return store;
    }

    /**
     * MINECRAFT-388 review finding #1: a chest (player-built/protected content) anywhere in the
     * planned footprint must abort the WHOLE site for this tick - no block placed, no resource
     * charged - rather than being silently overwritten.
     */
    @GameTest(template = "settlement_test")
    public static void constructionAbortsTheWholeSiteWhenProtectedContentOccupiesTheFootprint(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);

        BlockPos chestCell = campfireCell.offset(1, 0, 1);
        helper.setBlock(chestCell, Blocks.CHEST);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE), "an aborted site must leave the campfire untouched");
        helper.assertTrue(helper.getBlockState(chestCell).is(Blocks.CHEST), "the chest must never be overwritten");
        helper.assertTrue(helper.getBlockState(campfireCell.offset(-2, 0, -2)).isAir(),
                "no other cell in the footprint should have been built either - the whole site must abort, not just the blocked cell");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                "an aborted site must not charge any resources at all");
        helper.succeed();
    }

    /** MINECRAFT-388 review finding #1: a second live claimed campfire inside this site's own
     *  footprint is a hard backstop - never overwritten, even though it is not "protected" in the
     *  chest sense, because another town construction site is relying on it. */
    @GameTest(template = "settlement_test")
    public static void constructionNeverOverwritesAnotherLiveClaimedCampfireInItsFootprint(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireACell = ANCHOR;
        BlockPos campfireBCell = ANCHOR.offset(1, 0, 1);
        helper.setBlock(campfireACell, Blocks.CAMPFIRE);
        helper.setBlock(campfireBCell, Blocks.CAMPFIRE);
        BlockPos absA = helper.absolutePos(campfireACell);
        BlockPos absB = helper.absolutePos(campfireBCell);
        spawnClaimedVillager(helper, absA);
        spawnClaimedVillager(helper, absB);

        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absA, 1000);

        TownConstructionService.buildAt(helper.getLevel(), absA, storage);

        helper.assertTrue(helper.getBlockState(campfireACell).is(Blocks.CAMPFIRE), "site A's own campfire must survive its own aborted build");
        helper.assertTrue(helper.getBlockState(campfireBCell).is(Blocks.CAMPFIRE), "site A's build must never overwrite B's live claimed campfire");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review finding #4: a tree trunk standing in the footprint, above the floor's
     * own Y, must be cleared and credited to the WOOD store exactly once per construction tick -
     * TownClearingYields was previously only unit-tested in isolation, never actually invoked from
     * the production construction path.
     */
    @GameTest(template = "settlement_test")
    public static void constructionClearsATreeInTheFootprintAndCreditsWoodExactlyOnce(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        // A two-tall trunk standing on the natural ground, inside the footprint, above the floor.
        BlockPos trunkBase = campfireCell.offset(1, 0, 1);
        helper.setBlock(trunkBase, Blocks.OAK_LOG);
        helper.setBlock(trunkBase.above(), Blocks.OAK_LOG);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 500);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        TownResourceCategory costCategory = kind == TownBuildingTemplates.Kind.RESIDENTIAL
                ? TownResourceCategory.WOOD : TownResourceCategory.STONE;

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(!helper.getBlockState(trunkBase).is(Blocks.OAK_LOG) && !helper.getBlockState(trunkBase.above()).is(Blocks.OAK_LOG),
                "the tree inside the footprint should have been cleared during terrain prep");
        // Clearing two logs credits two WOOD (CLEARING_WOOD_RETURN=1 per block); construction
        // itself debits CONSTRUCTION_BLOCKS_PER_TICK from exactly whichever category this site's
        // own kind costs - never both, and never more than once for the same cleared material.
        int expectedWood = 500 + 2 - (costCategory == TownResourceCategory.WOOD ? TownConstants.CONSTRUCTION_BLOCKS_PER_TICK : 0);
        int expectedStone = 500 - (costCategory == TownResourceCategory.STONE ? TownConstants.CONSTRUCTION_BLOCKS_PER_TICK : 0);
        helper.assertTrue(store.balance(TownResourceCategory.WOOD) == expectedWood,
                "expected WOOD balance " + expectedWood + " but found " + store.balance(TownResourceCategory.WOOD));
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == expectedStone,
                "expected STONE balance " + expectedStone + " but found " + store.balance(TownResourceCategory.STONE));
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review finding #6: the existing-village check must not depend on the
     * triggering player's Y. {@link TownFounder#groundLevelPosition} must return the SAME ground
     * position for a column no matter what Y the input carries - proving the fix removes the
     * exact seam ({@code tryFoundNear} building its target from the player's own Y) the review
     * flagged, independent of needing a real generated village structure to observe it with.
     */
    @GameTest(template = "settlement_test")
    public static void groundLevelPositionIgnoresTheInputColumnsOwnAltitude(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos column = ANCHOR;
        BlockPos absoluteColumn = helper.absolutePos(column);

        BlockPos fromHighAltitude = TownFounder.groundLevelPosition(helper.getLevel(), absoluteColumn.offset(0, 200, 0));
        BlockPos fromLowAltitude = TownFounder.groundLevelPosition(helper.getLevel(), absoluteColumn.offset(0, -50, 0));
        BlockPos fromGroundLevel = TownFounder.groundLevelPosition(helper.getLevel(), absoluteColumn);

        helper.assertTrue(fromHighAltitude.equals(fromLowAltitude) && fromHighAltitude.equals(fromGroundLevel),
                "the resolved ground position must be identical regardless of the input column's own Y: "
                        + fromHighAltitude + " vs " + fromLowAltitude + " vs " + fromGroundLevel);
        helper.assertTrue(fromGroundLevel.getY() == absoluteColumn.getY(),
                "over flat prepared ground the resolved Y should be the real surface, not an arbitrary altitude");
        helper.succeed();
    }

    /**
     * End-to-end regression for finding #6: bootstrapping near the SAME region from a player
     * flying high above versus one deep underground must behave identically (exactly one
     * bootstrap, not two, and not zero) - before the fix, {@code tryFoundNear} baked the
     * observing player's own Y into the 3-D existing-village check.
     */
    @GameTest(template = "settlement_test")
    public static void bootstrapIsIdenticalRegardlessOfTheObservingPlayersAltitude(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord region = new RegionCoord(0, 0);
        TownStorage storage = new TownStorage();

        // The SAME storage and target column, evaluated once as if observed from far above and
        // once from far below: before the fix, each call's own input Y leaked into the 3-D
        // existing-village check, so the two calls could in principle disagree. One shared storage
        // proves the first call's one-way claim is honored identically regardless of the second
        // call's wildly different input altitude - a true duplicate here would mean altitude
        // changed the outcome.
        TownFounder.tryFoundAt(helper.getLevel(), region, absoluteAnchor.offset(0, 200, 0), storage);
        TownFounder.tryFoundAt(helper.getLevel(), region, absoluteAnchor.offset(0, -50, 0), storage);

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        List<Villager> villagers = helper.getLevel().getEntitiesOfClass(Villager.class, footprint);
        helper.assertTrue(villagers.size() == 1,
                "bootstrap should land on the real ground surface and succeed exactly once regardless of either call's input Y, found "
                        + villagers.size());
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "the campfire must land on the real surface, not at either input altitude");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review finding #3's production half: a computed road route must actually turn
     * into placed, resource-debited blocks - {@code TownRoadPlannerTest} already covers the pure
     * geometry/height-interpolation math; this proves the road-placement CONSUMER the review found
     * entirely absent is wired up and working. A small, fully-controlled 3-cell route anchored at
     * the GameTest's own loaded footprint (rather than a seed-derived {@link TownPlan}'s town
     * centre, which cannot be relied on to land inside this tiny structure) is used, the same way
     * {@code TownFounder#tryFoundAt} already separates its core placement logic from its
     * plan-driven, unpredictable real target.
     */
    @GameTest(template = "settlement_test")
    public static void roadConstructionPlacesSurfaceBlocksAndDebitsStonePerPlacedCell(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        // A straight run along X, well longer than the per-tick budget - the budget, not the
        // route length, should be what caps how many cells get placed this call.
        List<TownRoadPlanner.RoadCell> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            cells.add(new TownRoadPlanner.RoadCell(absoluteAnchor.getX() + i, absoluteAnchor.getZ(), absoluteAnchor.getY()));
        }

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road");
        store.credit(TownResourceCategory.STONE, 1000);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, store);

        helper.assertTrue(placed > 0 && placed <= TownConstants.ROAD_BLOCKS_PER_TICK,
                "placed count should be bounded by the per-tick road budget, found " + placed);
        int actualRoadBlocks = 0;
        for (int x = -2; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                if (helper.getBlockState(ANCHOR.offset(x, 0, z)).is(Blocks.DIRT_PATH)) {
                    actualRoadBlocks++;
                }
            }
        }
        helper.assertTrue(actualRoadBlocks == placed,
                "the number of actually-placed road-surface blocks (" + actualRoadBlocks + ") must match the reported count (" + placed + ")");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 - placed * TownConstants.ROAD_COST_PER_BLOCK,
                "road construction should debit exactly 1 STONE per placed cell, found balance " + store.balance(TownResourceCategory.STONE));
        helper.succeed();
    }

    /** A protected/foreign block inside a road's path must be skipped (never bulldozed), without
     *  charging for the skipped cell. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionSkipsAProtectedCellWithoutCharging(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos chestCell = ANCHOR;
        // A single-cell route's width expands to both sides along whichever axis is NOT the
        // route's own direction (ambiguous for a lone cell, so block all three width positions
        // explicitly rather than depending on that internal tie-break).
        helper.setBlock(chestCell.offset(-1, 0, 0), Blocks.CHEST);
        helper.setBlock(chestCell, Blocks.CHEST);
        helper.setBlock(chestCell.offset(1, 0, 0), Blocks.CHEST);
        helper.setBlock(chestCell.offset(0, 0, -1), Blocks.CHEST);
        helper.setBlock(chestCell.offset(0, 0, 1), Blocks.CHEST);
        BlockPos absoluteChest = helper.absolutePos(chestCell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteChest.getX(), absoluteChest.getZ(), absoluteChest.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-blocked");
        store.credit(TownResourceCategory.STONE, 1000);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, store);

        helper.assertTrue(placed == 0, "the single protected cell must be skipped, not placed, found " + placed);
        helper.assertTrue(helper.getBlockState(chestCell).is(Blocks.CHEST), "the chest must never be overwritten by a road");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000,
                "a skipped protected cell must not be charged, found balance " + store.balance(TownResourceCategory.STONE));
        helper.succeed();
    }
}
