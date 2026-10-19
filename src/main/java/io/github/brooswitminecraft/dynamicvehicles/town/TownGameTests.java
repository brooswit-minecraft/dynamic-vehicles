package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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

    // ---------------------------------------------------------------------------------------
    // Review round 2 regressions
    // ---------------------------------------------------------------------------------------

    /** TEMP DIAGNOSTIC - remove before merge. */
    @GameTest(template = "settlement_test")
    public static void diagnosticFloorYVsCampfireY(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        int floorY = TownTerrain.estimateGroundHeight(helper.getLevel(), absoluteCampfire.getX(), absoluteCampfire.getZ());
        int rawTop = TownTerrain.rawCanopyTop(helper.getLevel(), absoluteCampfire.getX(), absoluteCampfire.getZ());
        helper.fail("DIAGNOSTIC campfireY=" + absoluteCampfire.getY() + " floorY=" + floorY + " rawCanopyTop=" + rawTop
                + " minBuild=" + helper.getLevel().getMinBuildHeight() + " maxBuild=" + helper.getLevel().getMaxBuildHeight());
    }

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
        helper.assertTrue(unemployedShop.stream().noneMatch(p -> p.block() != Blocks.BRICKS),
                "an unclaimed/unemployed profession must never add an invented workstation block");
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

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, store);

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

        int placed = TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, store);

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

        TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, store);

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
        int minorPlaced = TownRoadConstructionService.buildRoadCells(helper.getLevel(), singleCell, TownConstants.MINOR_ROAD_WIDTH, minorStore);

        for (int x = -3; x <= 3; x++) {
            helper.setBlock(ANCHOR.offset(x, 0, 0), Blocks.AIR); // reset for a clean major-width attempt
        }

        TownStorage majorStorage = new TownStorage();
        TownResourceStore majorStore = majorStorage.storeFor("test:major-width");
        majorStore.credit(TownResourceCategory.STONE, 1000);
        int majorPlaced = TownRoadConstructionService.buildRoadCells(helper.getLevel(), singleCell, TownConstants.MAJOR_ROAD_WIDTH, majorStore);

        helper.assertTrue(minorPlaced == TownConstants.MINOR_ROAD_WIDTH,
                "minor width should place exactly MINOR_ROAD_WIDTH cells for a single route cell, found " + minorPlaced);
        helper.assertTrue(majorPlaced == Math.min(TownConstants.MAJOR_ROAD_WIDTH, TownConstants.ROAD_BLOCKS_PER_TICK),
                "major width should place more (budget-capped) cells than minor, found " + majorPlaced);
        helper.assertTrue(majorPlaced > minorPlaced, "major width must place strictly more cells than minor width");
        helper.succeed();
    }
}
