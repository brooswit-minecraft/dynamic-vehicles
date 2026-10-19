package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
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

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

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

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(placed == 0, "the single protected cell must be skipped, not placed, found " + placed);
        helper.assertTrue(helper.getBlockState(chestCell).is(Blocks.CHEST), "the chest must never be overwritten by a road");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000,
                "a skipped protected cell must not be charged, found balance " + store.balance(TownResourceCategory.STONE));
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------
    // Review round 2 regressions
    // ---------------------------------------------------------------------------------------

    /** Review round 2, finding #1: the claim must be the villager's real JOB_SITE memory, NEVER
     *  spatial proximity - an employed villager far outside the old 2-block search radius (here,
     *  diagonally opposite corners of the prepared footprint, ~9.9 blocks apart, while still
     *  leaving room for the campfire's own 5-wide construction footprint to fit) must still read
     *  as claimed, and construction must still progress for it. */
    @GameTest(template = "settlement_test")
    public static void claimSurvivesTheClaimingVillagerWanderingFarFromTheCampfire(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = new BlockPos(7, 2, 7);
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        BlockPos farCorner = helper.absolutePos(new BlockPos(0, 2, 0));
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, farCorner, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(VillagerProfession.FARMER));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        helper.assertTrue(TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "a villager 500+ blocks from its own claimed campfire must still read as claimed - this is never spatial proximity");

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);
        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        boolean anyBlockPlaced = !helper.getBlockState(campfireCell.offset(-2, 0, -2)).isAir()
                || !helper.getBlockState(campfireCell.offset(2, 0, 2)).isAir();
        helper.assertTrue(anyBlockPlaced, "construction must still progress for a site whose claimant is far away");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) < 1000 || store.balance(TownResourceCategory.WOOD) < 1000,
                "construction progressing should have debited some resource category");
        helper.succeed();
    }

    /** Review round 2, finding #1: a JOB_SITE memory pointing at the right position but the WRONG
     *  dimension must never count as a claim on this level's own campfire. */
    @GameTest(template = "settlement_test")
    public static void claimRequiresTheSameDimensionNotJustTheSamePosition(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));

        // The exact same (x, y, z) as the real campfire, but tagged as a DIFFERENT dimension.
        ResourceKey<Level> wrongDimension = helper.getLevel().dimension().equals(Level.NETHER) ? Level.END : Level.NETHER;
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(wrongDimension, absoluteCampfire));

        helper.assertTrue(!TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "a JOB_SITE memory in a different dimension must never claim this level's own campfire, even at the identical position");
        helper.succeed();
    }

    /**
     * Review round 2, finding #2: the building's floor must come from
     * {@link TownTerrain#estimateGroundHeight} (the real, tree-excluded ground), never the
     * campfire's own current Y - here the campfire sits directly atop a 3-tall log trunk, so its
     * own Y is well above the true ground, exactly the "campfire created on a trunk" case the
     * finding describes.
     */
    @GameTest(template = "settlement_test")
    public static void constructionFloorIsTheTreeExcludedGroundNotTheCampfiresOwnElevatedY(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        // A 3-tall trunk directly under the campfire's own column; the true ground (ignoring the
        // trunk) is the flat dirt top shared by the rest of the footprint.
        helper.setBlock(campfireCell, Blocks.OAK_LOG);
        helper.setBlock(campfireCell.above(), Blocks.OAK_LOG);
        helper.setBlock(campfireCell.above(2), Blocks.OAK_LOG);
        BlockPos elevatedCampfireCell = campfireCell.above(3);
        helper.setBlock(elevatedCampfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(elevatedCampfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        // The true ground (ignoring the trunk) is at campfireCell's own original Y - the floor
        // plane must land there, not up at the campfire's own elevated Y (campfireCell.above(3)).
        BlockPos farFloorCorner = campfireCell.offset(-2, 0, -2);
        helper.assertTrue(helper.getBlockState(farFloorCorner).is(TownBuildingTemplates.wallBlockFor(kind)),
                "the floor must be built at the tree-excluded ground height, found " + helper.getBlockState(farFloorCorner));
        BlockPos oldBuggyFloorCorner = elevatedCampfireCell.offset(-2, 0, -2);
        helper.assertTrue(!helper.getBlockState(oldBuggyFloorCorner).is(TownBuildingTemplates.wallBlockFor(kind)),
                "the floor must NOT be anchored to the campfire's own elevated Y");
        helper.succeed();
    }

    /** Review round 2, finding #7: a residential structure must include an actual functional bed
     *  (HEAD + FOOT), and a commercial/industrial structure must include the claimant's own
     *  profession-appropriate vanilla work-site block - reusing vanilla's own mapping, never an
     *  invented one. Verified directly against the planning function, which needs only bootstrapped
     *  block registries (available in this GameTest environment), not a live site. */
    @GameTest(template = "settlement_test")
    public static void residentialIncludesAFunctionalBedAndCommercialIncludesTheClaimantsWorkstation(GameTestHelper helper) {
        List<TownBuildingTemplates.Placement> residential = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.RESIDENTIAL, 0, 0, 0, 0, VillagerProfession.FARMER);
        boolean hasFoot = residential.stream().anyMatch(p -> p.block() == Blocks.RED_BED
                && p.state().getValue(BedBlock.PART) == BedPart.FOOT);
        boolean hasHead = residential.stream().anyMatch(p -> p.block() == Blocks.RED_BED
                && p.state().getValue(BedBlock.PART) == BedPart.HEAD);
        helper.assertTrue(hasFoot && hasHead, "a residential building must include a full (head+foot) bed");

        List<TownBuildingTemplates.Placement> armorerShop = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.COMMERCIAL, 0, 0, 0, 0, VillagerProfession.ARMORER);
        helper.assertTrue(armorerShop.stream().anyMatch(p -> p.block() == Blocks.BLAST_FURNACE),
                "a commercial building for an ARMORER claimant must include a blast furnace work-site");

        List<TownBuildingTemplates.Placement> masonYard = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.INDUSTRIAL, 0, 0, 0, 0, VillagerProfession.MASON);
        helper.assertTrue(masonYard.stream().anyMatch(p -> p.block() == Blocks.STONECUTTER),
                "an industrial building for a MASON claimant must include a stonecutter work-site");

        List<TownBuildingTemplates.Placement> unemployedShop = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.COMMERCIAL, 0, 0, 0, 0, VillagerProfession.NONE);
        helper.assertTrue(unemployedShop.stream().noneMatch(p -> p.block() != Blocks.BRICKS
                        && p.block() != Blocks.OAK_DOOR && p.block() != Blocks.RED_BED),
                "an unclaimed/unemployed profession must never add an invented workstation block");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 4, finding #8: a commercial/industrial building's workstation is
     * the ZONE's own deterministically-assigned FINAL profession's real vanilla work-site block -
     * NEVER another campfire, and NEVER derived from whatever profession the claiming villager
     * currently holds (in production, always the temporary Builder - exactly the case the old,
     * wrong behaviour hard-coded to "place another campfire"). A residential building for the same
     * real claimant must still include a bed. Every generated building must also include an actual
     * door (both halves), not a sealed shell.
     */
    @GameTest(template = "settlement_test")
    public static void commercialAndIndustrialWorkstationsAreTheZonesRealFinalProfessionNeverAnotherCampfire(GameTestHelper helper) {
        List<TownBuildingTemplates.Placement> commercial = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.COMMERCIAL, 0, 0, 0, 0, VillagerProfession.FLETCHER);
        helper.assertTrue(commercial.stream().noneMatch(p -> p.block() == Blocks.CAMPFIRE),
                "a commercial building's workstation must never be another campfire");
        helper.assertTrue(commercial.stream().anyMatch(p -> p.block() == Blocks.FLETCHING_TABLE),
                "a commercial building must include the assigned final profession's real vanilla workstation");
        helper.assertTrue(commercial.stream().anyMatch(p -> p.block() == Blocks.RED_BED),
                "a commercial building's own claimant must not go without a bed just because the workplace is zoned commercial");

        List<TownBuildingTemplates.Placement> industrial = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.INDUSTRIAL, 0, 0, 0, 0, VillagerProfession.MASON);
        helper.assertTrue(industrial.stream().noneMatch(p -> p.block() == Blocks.CAMPFIRE),
                "an industrial building's workstation must never be another campfire");
        helper.assertTrue(industrial.stream().anyMatch(p -> p.block() == Blocks.STONECUTTER),
                "an industrial building must include the assigned final profession's real vanilla workstation");
        helper.assertTrue(industrial.stream().anyMatch(p -> p.block() == Blocks.RED_BED),
                "an industrial building's own claimant must not go without a bed just because the workplace is zoned industrial");

        List<TownBuildingTemplates.Placement> residential = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.RESIDENTIAL, 0, 0, 0, 0, null);
        helper.assertTrue(residential.stream().anyMatch(p -> p.block() == Blocks.RED_BED),
                "a residential building must still include a bed");

        for (TownBuildingTemplates.Kind kind : TownBuildingTemplates.Kind.values()) {
            List<TownBuildingTemplates.Placement> generated = TownBuildingTemplates.generate(kind, 0, 0, 0, 0, VillagerProfession.FARMER);
            long doorHalves = generated.stream().filter(p -> p.block() == Blocks.OAK_DOOR).count();
            helper.assertTrue(doorHalves == 2, kind + " building must have exactly one functional (two-half) door, found " + doorHalves);
        }
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 3, finding #5 / round 4, finding #8 (production-path half):
     * drives {@link TownConstructionService#buildAt} to full completion for a REAL Builder claimant
     * (the only profession production can ever actually hand this method, per
     * {@code TownCampfireClaims}), then checks the "Builder-to-final-site transition": once every
     * planned cell matches (door included), the claimant must never be left a permanently jobless
     * Builder - released to {@code VillagerProfession.NONE} for a residential/storehouse site, or
     * handed off to the zone's own assigned final profession (with its JOB_SITE memory pointed at
     * the real vanilla workstation this same build just placed) for a commercial/industrial site.
     */
    @GameTest(template = "settlement_test")
    public static void realBuilderClaimantCompletesASiteAndIsReleasedFromItsJobAfterward(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);

        // Work out, exactly as production does, which kind/final-profession this site resolves to.
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        java.util.Optional<VillagerProfession> expectedFinalProfession = TownProfessionWorkstations.finalProfessionFor(
                kind, helper.getLevel().getSeed(), absoluteCampfire.getX(), absoluteCampfire.getZ());

        // Bounded but generous: large enough to finish even the biggest (storehouse) template at
        // CONSTRUCTION_BLOCKS_PER_TICK per call, with headroom to spare.
        boolean sawCampfireDisappear = false;
        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
            if (!helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE)) {
                sawCampfireDisappear = true;
            }
        }

        helper.assertTrue(sawCampfireDisappear, "the site never actually finished replacing its own campfire cell");
        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "a claimant whose site is fully built must never be left a permanently jobless Builder");
        if (expectedFinalProfession.isPresent()) {
            helper.assertTrue(claimant.getVillagerData().getProfession() == expectedFinalProfession.get(),
                    "a commercial/industrial claimant must be handed off to the zone's own assigned final profession, expected "
                            + expectedFinalProfession.get() + " found " + claimant.getVillagerData().getProfession());
            BlockPos expectedWorkstation = TownBuildingTemplates.workstationPosition(
                    absoluteCampfire.getX(), TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire), absoluteCampfire.getZ());
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            .map(gp -> gp.pos().equals(expectedWorkstation)).orElse(false),
                    "the handed-off claimant's JOB_SITE must point at the real workstation this build just placed");
        } else {
            helper.assertTrue(claimant.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "a residential/storehouse claimant whose site is fully built must be released back to NONE, found "
                            + claimant.getVillagerData().getProfession());
        }
        helper.assertTrue(store.balance(TownResourceCategory.STONE) < 100000 || store.balance(TownResourceCategory.WOOD) < 100000,
                "a completed site should have actually debited some resource category");
        helper.succeed();
    }

    /** Review round 2, finding #4: a road cell whose headroom (the two cells above the surface) is
     *  blocked by protected content must be skipped entirely - never tunnelled through - and never
     *  charged for. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionSkipsACellWhoseHeadroomIsBlockedWithoutCharging(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        // The single-cell route's width expands along X (ambiguous direction); block headroom
        // above all three width positions so none of them can slip through unblocked.
        helper.setBlock(cell.offset(-1, 1, 0), Blocks.CHEST);
        helper.setBlock(cell.offset(0, 1, 0), Blocks.CHEST);
        helper.setBlock(cell.offset(1, 1, 0), Blocks.CHEST);
        BlockPos absoluteCell = helper.absolutePos(cell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-headroom");
        store.credit(TownResourceCategory.STONE, 1000);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(placed == 0, "every width cell has blocked headroom and must be skipped, found " + placed);
        helper.assertTrue(helper.getBlockState(cell).isAir(), "the surface cell must not be placed when headroom above it is blocked");
        helper.assertTrue(helper.getBlockState(cell.above()).is(Blocks.CHEST), "headroom must never be tunnelled through");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000, "a skipped cell must not be charged");
        helper.succeed();
    }

    /** Review round 2, finding #4: a road cell floating over an open gap must have its support
     *  filled below before the surface itself is placed, rather than floating. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionFillsUnsupportedGroundBeforePlacingTheSurface(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        for (int x = -1; x <= 1; x++) {
            helper.setBlock(cell.offset(x, -1, 0), Blocks.AIR); // dig out support under all 3 width cells
        }
        BlockPos absoluteCell = helper.absolutePos(cell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-support");
        store.credit(TownResourceCategory.STONE, 1000);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(placed == 3, "all three width cells should place once support is filled, found " + placed);
        for (int x = -1; x <= 1; x++) {
            helper.assertTrue(helper.getBlockState(cell.offset(x, -1, 0)).is(Blocks.DIRT),
                    "the gap below the road surface must be filled with support, not left floating");
            helper.assertTrue(helper.getBlockState(cell.offset(x, 0, 0)).is(Blocks.DIRT_PATH),
                    "the road surface itself must still be placed once support is filled");
        }
        helper.succeed();
    }

    /** Review round 2, finding #4: an entity physically standing on a road cell must make that
     *  exact cell skip for this tick, never be paved under it. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionSkipsACellOccupiedByAnEntity(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        BlockPos absoluteCell = helper.absolutePos(cell);
        Villager bystander = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCell, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(bystander);

        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));
        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-entity");
        store.credit(TownResourceCategory.STONE, 1000);

        TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(!helper.getBlockState(cell).is(Blocks.DIRT_PATH),
                "a cell occupied by an entity must never be paved under it");
        helper.succeed();
    }

    /** Review round 2, finding #4: major routes must actually be built at
     *  {@link TownConstants#MAJOR_ROAD_WIDTH}, wider than minor branches at
     *  {@link TownConstants#MINOR_ROAD_WIDTH} - production previously used the minor width for
     *  every route regardless of major/minor. */
    @GameTest(template = "settlement_test")
    public static void majorWidthPlacesMoreCellsThanMinorWidthForTheSameRouteCell(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        List<TownRoadPlanner.RoadCell> singleCell = List.of(
                new TownRoadPlanner.RoadCell(absoluteAnchor.getX(), absoluteAnchor.getZ(), absoluteAnchor.getY()));

        TownStorage minorStorage = new TownStorage();
        TownResourceStore minorStore = minorStorage.storeFor("test:minor-width");
        minorStore.credit(TownResourceCategory.STONE, 1000);
        int minorPlaced = TownRoadConstructionService.buildRoadCells(helper.getLevel(), singleCell, TownConstants.MINOR_ROAD_WIDTH, minorStorage, minorStore);

        for (int x = -3; x <= 3; x++) {
            helper.setBlock(ANCHOR.offset(x, 0, 0), Blocks.AIR); // reset for a clean major-width attempt
        }

        TownStorage majorStorage = new TownStorage();
        TownResourceStore majorStore = majorStorage.storeFor("test:major-width");
        majorStore.credit(TownResourceCategory.STONE, 1000);
        int majorPlaced = TownRoadConstructionService.buildRoadCells(helper.getLevel(), singleCell, TownConstants.MAJOR_ROAD_WIDTH, majorStorage, majorStore);

        helper.assertTrue(minorPlaced == TownConstants.MINOR_ROAD_WIDTH,
                "minor width should place exactly MINOR_ROAD_WIDTH cells for a single route cell, found " + minorPlaced);
        helper.assertTrue(majorPlaced == Math.min(TownConstants.MAJOR_ROAD_WIDTH, TownConstants.ROAD_BLOCKS_PER_TICK),
                "major width should place more (budget-capped) cells than minor, found " + majorPlaced);
        helper.assertTrue(majorPlaced > minorPlaced, "major width must place strictly more cells than minor width");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------
    // Review round 4 regressions
    // ---------------------------------------------------------------------------------------

    /** Finds the kind/final-profession this ANCHOR-based site would resolve to, exactly as
     *  production does - shared by several round 4 regressions below. */
    private static TownBuildingTemplates.Kind kindAt(GameTestHelper helper, BlockPos absoluteCampfire) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        return isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
    }

    /** MINECRAFT-388 review round 4, finding #1: at a non-trivial base height, every wall AND door
     *  coordinate must be offset by {@code baseY} exactly like the floor/roof already are - the old
     *  bug hardcoded walls to absolute Y=1..3 regardless of {@code baseY}. */
    @GameTest(template = "settlement_test")
    public static void wallOffsetAtNonzeroBaseYMatchesEveryWallDoorAndRoofCoordinate(GameTestHelper helper) {
        int baseY = 50;
        int height = TownBuildingTemplates.heightFor(TownBuildingTemplates.Kind.STOREHOUSE, 0);
        List<TownBuildingTemplates.Placement> placements = TownBuildingTemplates.generate(
                TownBuildingTemplates.Kind.STOREHOUSE, 0, baseY, 0, 0, null);

        int radius = (TownConstants.STOREHOUSE_FOOTPRINT_WIDTH - 1) / 2;
        for (TownBuildingTemplates.Placement placement : placements) {
            if (placement.block() == Blocks.OAK_DOOR) {
                helper.assertTrue(placement.y() == baseY + 1 || placement.y() == baseY + 2,
                        "a door half must be offset by baseY, found absolute Y " + placement.y());
                continue;
            }
            boolean isPerimeter = Math.abs(placement.x()) == radius || Math.abs(placement.z()) == radius;
            if (placement.y() == baseY || placement.y() == baseY + height) {
                continue; // floor or roof plane - already known correct
            }
            // The old bug hardcoded walls to absolute Y=1..3 regardless of baseY; at baseY=50 that
            // would show up here as a wall-plane placement far below baseY, which the bound below
            // (y > baseY) already catches directly.
            helper.assertTrue(isPerimeter && placement.y() > baseY && placement.y() < baseY + height,
                    "every non-floor/roof/door placement must be a perimeter wall cell offset by baseY, found (x="
                            + placement.x() + ", y=" + placement.y() + ", z=" + placement.z() + ") for baseY=" + baseY);
        }
        helper.succeed();
    }

    /** Production-path half of the same finding: a real construction site at a non-trivial base
     *  height must actually show walls at the correct offset once built, not near the world
     *  bottom, and must not have corrupted the unrelated low-Y cells the old bug wrote into. */
    @GameTest(template = "settlement_test")
    public static void productionShellAtNonzeroBaseYHasWallsAtTheCorrectOffset(GameTestHelper helper) {
        // Elevated flat ground (real surface at Y=9) instead of the usual Y~2 ground, so a wall-
        // offset bug (walls hardcoded near absolute Y=1..3) is obviously wrong rather than
        // coincidentally right, and so the unrelated cells at absolute Y=1..3 can be checked for
        // corruption afterward.
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 9 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
        BlockPos campfireCell = new BlockPos(5, 9, 5);
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);
        TownBuildingTemplates.Kind kind = kindAt(helper, absoluteCampfire);

        for (int i = 0; i < 500; i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        int radius = kind == TownBuildingTemplates.Kind.STOREHOUSE ? (TownConstants.STOREHOUSE_FOOTPRINT_WIDTH - 1) / 2 : 2;
        BlockPos wallCellAboveFloor = campfireCell.offset(radius, 1, 0); // a perimeter wall, one above the real floor
        helper.assertTrue(helper.getBlockState(wallCellAboveFloor).is(TownBuildingTemplates.wallBlockFor(kind)),
                "expected a wall at (x+radius, baseY+1, z) once built, found " + helper.getBlockState(wallCellAboveFloor));
        BlockPos oldBuggyAbsoluteWallCell = new BlockPos(campfireCell.getX() + radius, 2, campfireCell.getZ());
        helper.assertTrue(!helper.getBlockState(oldBuggyAbsoluteWallCell).is(TownBuildingTemplates.wallBlockFor(kind)),
                "the old bug's absolute Y=1..3 wall plane must never have been written to - that column's real ground must be untouched");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 4, finding #2: a foreign block that merely happens to match the
     * planned material (here, pre-placed at a wall cell before any construction has ever run) must
     * never be silently adopted as "already placed/paid progress" - it must abort the whole site
     * exactly like any other protected/foreign content, never be silently skipped as free progress.
     */
    @GameTest(template = "settlement_test")
    public static void foreignMatchingMaterialAbortsTheWholeSiteRatherThanBeingAdoptedAsFreeProgress(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);
        TownBuildingTemplates.Kind kind = kindAt(helper, absoluteCampfire);

        // The exact material this site's own template would plan here anyway - a player (or
        // another mod) who happened to pre-place the matching block, never Dynamic Towns itself.
        BlockPos wallCell = campfireCell.offset(2, 1, 0);
        helper.setBlock(wallCell, TownBuildingTemplates.wallBlockFor(kind));

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE), "an aborted site must leave the campfire untouched");
        helper.assertTrue(helper.getBlockState(campfireCell.offset(-2, 1, -2)).isAir(),
                "no other cell in the footprint should have been built either - a foreign matching block must abort the WHOLE site");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                "a foreign matching block must never let the site bypass resource cost - no charge at all");
        helper.succeed();
    }

    /** Direct coverage of the new persisted-progress primitives {@link TownConstructionService}
     *  and {@link TownRoadConstructionService} now use instead of live-world diffing. */
    @GameTest(template = "settlement_test")
    public static void townStorageTracksPlacedCellsIndependentlyPerPosition(GameTestHelper helper) {
        TownStorage storage = new TownStorage();
        BlockPos a = helper.absolutePos(ANCHOR);
        BlockPos b = a.offset(1, 0, 0);
        String owner = "site:test-owner";
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), a, owner), "a fresh store must not report any cell as placed");

        storage.markCellPlaced(helper.getLevel().dimension(), a, owner);

        helper.assertTrue(storage.isCellPlaced(helper.getLevel().dimension(), a, owner), "a marked cell must read back as placed");
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), b, owner), "marking one cell must not affect a different position");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 5, finding #2: a cell previously placed-and-paid-for by one plan
     * must never be silently adopted as a DIFFERENT plan's own already-paid progress merely because
     * they share a coordinate and the live block happens to match the second plan's own material
     * too - each plan's progress is scoped to its own owner key.
     */
    @GameTest(template = "settlement_test")
    public static void placedCellOwnershipIsScopedPerPlanNotJustPerPosition(GameTestHelper helper) {
        TownStorage storage = new TownStorage();
        BlockPos pos = helper.absolutePos(ANCHOR);
        String roadOwner = TownStorage.roadOwner(helper.getLevel().dimension(), TownRegion.fromBlockCoords(pos.getX(), pos.getZ()));
        String siteOwner = TownStorage.siteOwner(pos);

        storage.markCellPlaced(helper.getLevel().dimension(), pos, roadOwner);

        helper.assertTrue(storage.isCellPlaced(helper.getLevel().dimension(), pos, roadOwner),
                "the road plan that actually placed this cell must read it back as its own progress");
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), pos, siteOwner),
                "a different plan (a building site) must never read the road's own paid progress at the same coordinate as its own");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 4, finding #3: road construction's region-selection must be driven
     * purely by nearby-player presence in an already-FOUNDED region, entirely independent of
     * whether any campfire is claimed ANYWHERE in the level - exercises
     * {@link TownConstructionService#regionsWithNearbyPlayerActivity} directly, the exact piece
     * factored out of the per-tick driver for this. A real seed-derived road ROUTE is deliberately
     * NOT exercised end-to-end here (the existing {@code roadConstructionPlacesSurfaceBlocksAndDebitsStonePerPlacedCell}
     * test already explains why: {@link TownPlanner}'s real town centre cannot be relied on to land
     * inside this tiny GameTest structure's own loaded footprint) - this proves the SELECTION logic
     * itself never again depends on a claimed campfire, which is exactly what regressed.
     */
    @GameTest(template = "settlement_test")
    public static void roadRegionSelectionIsDrivenByNearbyPlayersInAFoundedRegionNeverByAClaimedCampfire(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);

        TownStorage storage = new TownStorage();
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.teleportTo(absoluteAnchor.getX() + 0.5, absoluteAnchor.getY(), absoluteAnchor.getZ() + 0.5);

        // Not yet founded: a nearby player in an UN-founded region must not count as active.
        helper.assertTrue(!TownConstructionService.regionsWithNearbyPlayerActivity(helper.getLevel(), storage).contains(region),
                "an un-founded region must never count as road-active even with a player nearby");

        storage.markTownFounded(key);

        // No claimed campfire exists anywhere in the level at all - region selection must still
        // include this region purely because it is founded and a player is nearby.
        helper.assertTrue(TownConstructionService.regionsWithNearbyPlayerActivity(helper.getLevel(), storage).contains(region),
                "a founded region with a nearby player must count as road-active, even with NO claimed campfire anywhere in the level");
        helper.succeed();
    }

    /** MINECRAFT-388 review round 4, finding #4: support fill must dig DOWN through a deep gap
     *  (not just one cell) until real solid ground is found, so a route crossing a valley/gap does
     *  not leave the surface floating. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionFillsDeepSupportGapsNotJustOneCell(GameTestHelper helper) {
        prepareFlatGround(helper);
        // At Y=8, the flat-ground helper already leaves 6 cells of open air below (down to the
        // Y<2 dirt floor) - well beyond the single-cell fix round 2 already covered.
        BlockPos cell = new BlockPos(5, 8, 5);
        BlockPos absoluteCell = helper.absolutePos(cell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-deep-support");
        store.credit(TownResourceCategory.STONE, 1000);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(placed == 3, "all three width cells should place once the deep gap is filled, found " + placed);
        for (int x = -1; x <= 1; x++) {
            helper.assertTrue(helper.getBlockState(cell.offset(x, -1, 0)).is(Blocks.DIRT),
                    "the cell immediately below the surface must be filled with support");
            helper.assertTrue(helper.getBlockState(cell.offset(x, -5, 0)).is(Blocks.DIRT),
                    "support fill must reach all the way down through the deep gap, not stop after one cell");
            helper.assertTrue(helper.getBlockState(cell.offset(x, 0, 0)).is(Blocks.DIRT_PATH),
                    "the road surface itself must still be placed once the deep gap is filled");
        }
        helper.succeed();
    }

    /** MINECRAFT-388 review round 4, finding #5: a successfully-cleared headroom block must credit
     *  {@link TownClearingYields} salvage exactly once, the same way building clearing already does
     *  - previously discarded entirely for roads. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionCreditsClearingSalvageForClearedHeadroomExactlyOnce(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        helper.setBlock(cell.above(), Blocks.OAK_LOG);
        BlockPos absoluteCell = helper.absolutePos(cell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-salvage");
        store.credit(TownResourceCategory.STONE, 1000);
        int woodBefore = store.balance(TownResourceCategory.WOOD);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(placed > 0, "the cell should still place despite the log in its headroom (natural terrain, clearable)");
        helper.assertTrue(!helper.getBlockState(cell.above()).is(Blocks.OAK_LOG), "the log must have actually been cleared");
        int expectedWoodCredit = TownClearingYields.creditsFor(TownClearingYields.ClearedMaterial.LOG, 1);
        helper.assertTrue(store.balance(TownResourceCategory.WOOD) == woodBefore + expectedWoodCredit,
                "clearing the log from headroom must credit WOOD salvage exactly once, expected +" + expectedWoodCredit
                        + " found +" + (store.balance(TownResourceCategory.WOOD) - woodBefore));
        helper.succeed();
    }

    /** MINECRAFT-388 review round 5, finding #4: the original surface block a road cell itself
     *  consumes (not just its headroom) must also credit salvage exactly once - previously
     *  discarded entirely. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionCreditsSalvageForTheOriginalSurfaceBlockItself(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        helper.setBlock(cell, Blocks.STONE);
        BlockPos absoluteCell = helper.absolutePos(cell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-surface-salvage");
        store.credit(TownResourceCategory.STONE, 1000);
        int before = store.balance(TownResourceCategory.STONE);

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(placed >= 1, "the targeted stone cell should place");
        helper.assertTrue(helper.getBlockState(cell).is(Blocks.DIRT_PATH), "the road surface must actually be placed");
        // Without surface salvage, net change would be exactly -placed (ROAD_COST_PER_BLOCK=1 per
        // cell, debit only); the stone salvage credit for the one STONE surface block consumed
        // must offset part of that, making the net change strictly greater than -placed.
        int netChange = store.balance(TownResourceCategory.STONE) - before;
        helper.assertTrue(netChange > -placed,
                "the original stone surface block consumed by the road must be credited as salvage, net change was " + netChange);
        helper.succeed();
    }

    /** MINECRAFT-388 review round 5, finding #4: a road cell whose support scan exhausts its whole
     *  bounded depth without ever finding real solid ground (a deep ravine/cave/void) must be
     *  rejected entirely - filling the bounded depth with dirt on top of nothing still leaves the
     *  surface floating over whatever lies beyond that depth. */
    @GameTest(template = "settlement_test")
    public static void roadConstructionRejectsACellWithNoReachableSupportWithinTheBoundedScanDepth(GameTestHelper helper) {
        prepareFlatGround(helper);
        // Override this one column to be air all the way down through where the flat-ground
        // floor would otherwise be, so no solid ground exists within ROAD_SUPPORT_FILL_MAX_DEPTH
        // cells below the road cell - deliberately deeper than roadConstructionFillsDeepSupportGapsNotJustOneCell's
        // still-reachable gap.
        for (int y = 0; y <= 12; y++) {
            helper.setBlock(new BlockPos(5, y, 5), Blocks.AIR);
        }
        BlockPos cell = new BlockPos(5, 10, 5);
        BlockPos absoluteCell = helper.absolutePos(cell);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-impossible-support");
        store.credit(TownResourceCategory.STONE, 1000);

        TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(helper.getBlockState(cell).isAir(),
                "a cell with no reachable support within the bounded scan depth must never be built over, found "
                        + helper.getBlockState(cell));
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000,
                "a rejected impossible-support cell must never be charged");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 4, finding #6: the claimant's exemption from occupancy-blocking
     * must be narrowed to ONLY its reserved campfire cell - standing in a planned WALL cell must
     * still block that cell from being built (never place a wall into the claimant's own body).
     */
    @GameTest(template = "settlement_test")
    public static void constructionAbortsWhenTheClaimantIsStandingInAPlannedWallCellNotTheCampfireCell(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        BlockPos wallCell = campfireCell.offset(2, 1, 0);
        BlockPos absoluteWallCell = helper.absolutePos(wallCell);
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteWallCell, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(VillagerProfession.FARMER));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE), "an aborted site must leave the campfire untouched");
        helper.assertTrue(helper.getBlockState(wallCell).isAir(),
                "the wall cell the claimant itself is standing in must never be built into - the claimant's exemption is only its OWN campfire cell");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                "an aborted site must not charge any resources at all");
        helper.succeed();
    }

    /** MINECRAFT-388 review round 4, finding #6: the whole-site preflight must reject an occupied
     *  footprint even for an entity with no relationship to this claim at all - entity occupancy was
     *  previously never checked by the whole-site preflight (only blocks were). */
    @GameTest(template = "settlement_test")
    public static void constructionAbortsWhenAnUnrelatedEntityOccupiesTheFootprint(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);

        BlockPos bystanderCell = campfireCell.offset(-2, 0, -2);
        BlockPos absoluteBystander = helper.absolutePos(bystanderCell);
        Villager bystander = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteBystander, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(bystander); // unemployed, no relationship to this claim at all

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE), "an aborted site must leave the campfire untouched");
        helper.assertTrue(helper.getBlockState(bystanderCell).isAir(), "the bystander's own cell must never be built over");
        helper.assertTrue(helper.getBlockState(campfireCell.offset(2, 0, 2)).isAir(),
                "no other cell in the footprint should have been built either - an occupied footprint aborts the whole site");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                "an aborted site must not charge any resources at all");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 5, finding #3: a campfire sitting well ABOVE the planned building's
     * own roof (e.g. atop a tall tree) is OUTSIDE the footprint entirely once finished - its real
     * final state is AIR (removed), never an invented floating solid interior wall block. Driven to
     * full completion (not just the floor, which {@code constructionFloorIsTheTreeExcludedGroundNotTheCampfiresOwnElevatedY}
     * already covers) to prove the elevated case actually finishes correctly end to end.
     */
    @GameTest(template = "settlement_test")
    public static void elevatedCampfireAboveThePlannedRoofEndsAsAirNotAFloatingWallBlock(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        // A tall trunk well above any building's planned roof (height 4-7, per heightFor) - the
        // campfire perched on top is unambiguously outside the planned footprint volume entirely.
        for (int y = 0; y < 10; y++) {
            helper.setBlock(campfireCell.above(y), Blocks.OAK_LOG);
        }
        BlockPos elevatedCampfireCell = campfireCell.above(10);
        helper.setBlock(elevatedCampfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(elevatedCampfireCell);
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absoluteCampfire, 100000);

        for (int i = 0; i < 500 && helper.getBlockState(elevatedCampfireCell).is(Blocks.CAMPFIRE); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(helper.getBlockState(elevatedCampfireCell).isAir(),
                "a campfire above the planned roof must end as AIR (removed), found " + helper.getBlockState(elevatedCampfireCell));
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 5, finding #1: the claimant parks AT the campfire while working,
     * which is fine for every other placement, but the FINAL closing placement there can be a solid
     * block landing exactly where its own body still is. It must be relocated to a validated, safe,
     * reachable exit first - never placed through, never damaged - and the site must still actually
     * finish closing rather than stalling forever because its own claimant can never move itself.
     */
    @GameTest(template = "settlement_test")
    public static void claimantIsRelocatedSafelyBeforeTheFinalClosingPlacementAndSurvivesUnharmed(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        float startingHealth = claimant.getHealth();

        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absoluteCampfire, 100000);

        for (int i = 0; i < 500 && helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(!helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE),
                "the site must still actually finish closing over the campfire cell, not stall forever on its own claimant");
        helper.assertTrue(claimant.isAlive(), "the claimant must survive being relocated out of its own closing site");
        helper.assertTrue(claimant.getHealth() >= startingHealth,
                "the claimant must take no damage (e.g. suffocation) from the final closing placement");
        helper.assertTrue(claimant.blockPosition().getX() != campfireCell.getX() || claimant.blockPosition().getZ() != campfireCell.getZ(),
                "the claimant must have been relocated out of the campfire's own column before the final solid block closed over it");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 5, finding #1: a villager whose JOB_SITE memory points at this
     * exact campfire position but in a DIFFERENT dimension is a stale/foreign bystander, never this
     * site's own claimant - it cannot be actively working a job site in a dimension it is not
     * currently in, so the campfire-column exemption must never apply to it.
     */
    @GameTest(template = "settlement_test")
    public static void isEntityBlockingNeverExemptsAJobSiteMemoryFromADifferentDimension(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        Villager bystander = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(bystander);
        bystander.setVillagerData(bystander.getVillagerData().setProfession(VillagerProfession.FARMER));
        ResourceKey<Level> otherDimension = helper.getLevel().dimension().equals(Level.NETHER) ? Level.END : Level.NETHER;
        bystander.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(otherDimension, absoluteCampfire));

        helper.assertTrue(TownSitePreflight.isEntityBlocking(helper.getLevel(), absoluteCampfire, absoluteCampfire),
                "a villager whose JOB_SITE memory points here but in a DIFFERENT dimension must never be exempted as this site's own claimant");
        helper.succeed();
    }
}
