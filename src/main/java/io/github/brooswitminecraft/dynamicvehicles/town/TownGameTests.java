package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    // Keep the standalone old-home fixture inside this test's own 24x8x24 structure.  The former
    // ANCHOR.offset(30, 50, 30) escaped into later GameTest grid slots (and its bed foot could
    // become foreign canopy content for a neighboring construction preflight).
    private static final BlockPos OWNED_OLD_HOME_HEAD = new BlockPos(15, 3, 15);

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    /** Prepares exactly the selected construction footprint, rather than assuming every site is
     *  the ordinary five-wide template. */
    private static void prepareFlatGround(GameTestHelper helper, BlockPos center, int radius) {
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    private static TownBuildingTemplates.Placement placementAt(List<TownBuildingTemplates.Placement> template,
            ConstructionSitePlan.PlannedBlock planned) {
        return template.stream().filter(placement -> placement.x() == planned.x()
                        && placement.y() == planned.y() && placement.z() == planned.z())
                .findFirst().orElseThrow(() -> new IllegalStateException("production plan lost placement " + planned));
    }

    /** Road progress belongs to the region containing each individual surface cell, not to the
     * campfire that happened to found the nearby town.  Keep this in the test rather than
     * duplicating a production constant/owner string at every assertion. */
    private static String roadOwnerAt(ServerLevel level, BlockPos pos) {
        return TownStorage.roadOwner(level.dimension(), TownRegion.fromBlockCoords(pos.getX(), pos.getZ()));
    }

    private static String roadActivityRoster(ServerLevel level, ServerPlayer first, ServerPlayer second) {
        StringBuilder roster = new StringBuilder();
        for (ServerPlayer player : level.players()) {
            if (!roster.isEmpty()) roster.append("; ");
            boolean owned = player.getUUID().equals(first.getUUID()) || player.getUUID().equals(second.getUUID());
            BlockPos pos = player.blockPosition();
            roster.append(player.getUUID()).append(" owned=").append(owned).append(" pos=").append(pos)
                    .append(" region=").append(TownRegion.fromBlockCoords(pos.getX(), pos.getZ()));
        }
        return roster.toString();
    }

    /** Remove only fixture-owned mock players, but never skip the second cleanup after a failure. */
    private static void removeOwnedMockPlayers(ServerLevel level, ServerPlayer first, ServerPlayer second) {
        Throwable failure = null;
        if (first != null) {
            try {
                level.getServer().getPlayerList().remove(first);
            } catch (Throwable exception) {
                failure = exception;
            }
        }
        if (second != null) {
            try {
                level.getServer().getPlayerList().remove(second);
            } catch (Throwable exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure != null) throw (RuntimeException) failure;
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
     * MINECRAFT-388 review round 6, finding #8: a PENDING retry's campfire is already there from a
     * prior attempt - re-placing the identical block via {@code Level#setBlock} can legitimately
     * return {@code false} ("nothing changed"), which must never be read as a placement FAILURE and
     * abort the retry before it ever attempts the villager spawn again.
     */
    @GameTest(template = "settlement_test")
    public static void pendingOrphanCampfireRetryStillSpawnsAVillagerDespiteTheNoOpSetBlockReturn(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord region = new RegionCoord(0, 0);
        TownStorage storage = new TownStorage();
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);

        // Simulate the exact state a prior failed attempt persists: the campfire is already
        // standing there, but its companion villager (or the orphan's own cleanup) never confirmed.
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        storage.setPendingCampfire(key, absoluteAnchor);

        TownFounder.tryFoundAt(helper.getLevel(), region, absoluteAnchor, storage);

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        List<Villager> villagers = helper.getLevel().getEntitiesOfClass(Villager.class, footprint);
        helper.assertTrue(villagers.size() == 1,
                "a pending retry whose campfire already exists must still proceed to spawn its companion villager, found " + villagers.size());
        helper.assertTrue(storage.hasFoundedTown(key), "a successful pending retry must mark the region founded");
        helper.assertTrue(storage.pendingCampfireFor(key).isEmpty(), "the pending marker must be cleared once founding succeeds");
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

    /** A valid foreign bed is protected content too: preflight must refuse it before any debit. */
    @GameTest(template = "settlement_test")
    public static void constructionAbortsWhenAForeignBedOccupiesTheSelectedFootprint(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        spawnClaimedVillager(helper, absoluteCampfire);
        OwnedStandaloneBedFixture foreignBed = placeOwnedStandaloneBedFixture(helper, campfireCell.north());
        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);
        Throwable failure = null;
        try {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
            helper.assertTrue(helper.getLevel().getBlockState(foreignBed.head()).equals(foreignBed.expectedHead())
                            && helper.getLevel().getBlockState(foreignBed.foot()).equals(foreignBed.expectedFoot()),
                    "a foreign valid bed in the selected footprint must remain untouched");
            helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                    "a foreign bed must reject the whole site before any resource debit");
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            finishOwnedBedFixture(foreignBed, failure);
        }
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

    /**
     * MINECRAFT-453: paid road progress is not a blanket permission to preserve whatever happens
     * to be live at that coordinate.  An owned, matching dirt path is a no-op; a foreign dirt
     * path and protected content are skipped; but an owned record whose live natural surface has
     * changed is safely rebuilt and charged again.  This production-path fixture also proves that
     * the exact debit/retry decision survives a {@link TownStorage} save/load round trip.
     */
    @GameTest(template = "settlement_test")
    public static void widthOneRoadRebuildsChangedPaidNaturalSurfaceAfterReloadWithoutAdoptingForeignPaths(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        BlockPos matching = helper.absolutePos(ANCHOR.offset(-2, 0, 0));
        BlockPos foreign = helper.absolutePos(ANCHOR.offset(-1, 0, 0));
        BlockPos protectedCell = helper.absolutePos(ANCHOR);
        BlockPos changedPaidSurface = helper.absolutePos(ANCHOR.offset(1, 0, 0));
        BlockPos unpaidNatural = helper.absolutePos(ANCHOR.offset(2, 0, 0));
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();

        TownStorage storage = new TownStorage();
        String matchingOwner = roadOwnerAt(level, matching);
        String changedOwner = roadOwnerAt(level, changedPaidSurface);
        storage.markCellPlaced(level.dimension(), matching, matchingOwner, signature, Blocks.DIRT_PATH.defaultBlockState());
        storage.markCellPlaced(level.dimension(), changedPaidSurface, changedOwner, signature, Blocks.DIRT_PATH.defaultBlockState());
        level.setBlock(matching, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(foreign, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(protectedCell, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(changedPaidSurface, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(unpaidNatural, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        // changedPaidSurface deliberately remains natural DIRT: its persisted road record alone
        // must not turn this changed live surface into a free no-op.

        List<TownRoadPlanner.RoadCell> route = List.of(
                new TownRoadPlanner.RoadCell(matching.getX(), matching.getZ(), matching.getY()),
                new TownRoadPlanner.RoadCell(foreign.getX(), foreign.getZ(), foreign.getY()),
                new TownRoadPlanner.RoadCell(protectedCell.getX(), protectedCell.getZ(), protectedCell.getY()),
                new TownRoadPlanner.RoadCell(changedPaidSurface.getX(), changedPaidSurface.getZ(), changedPaidSurface.getY()),
                new TownRoadPlanner.RoadCell(unpaidNatural.getX(), unpaidNatural.getZ(), unpaidNatural.getY()));
        TownResourceStore store = storage.storeFor("test:road-changed-paid-surface");
        store.credit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK);

        int firstPlaced = TownRoadConstructionService.buildRoadCells(level, route, 1, storage, store);
        helper.assertTrue(firstPlaced == 1 && level.getBlockState(changedPaidSurface).is(Blocks.DIRT_PATH),
                "a changed owned natural surface must be rebuilt exactly once before funds run out");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 0 && level.getBlockState(unpaidNatural).is(Blocks.DIRT),
                "the next natural cell must pause without funds, never mutate or charge");
        helper.assertTrue(level.getBlockState(matching).is(Blocks.DIRT_PATH)
                        && storage.isCellPlaced(level.dimension(), matching, matchingOwner, signature),
                "a matching owned paid path must remain a no-op");
        helper.assertTrue(level.getBlockState(foreign).is(Blocks.DIRT_PATH)
                        && !storage.isCellPlaced(level.dimension(), foreign, roadOwnerAt(level, foreign), signature),
                "a foreign dirt path must be preserved rather than adopted as paid progress");
        helper.assertTrue(level.getBlockState(protectedCell).is(Blocks.CHEST)
                        && !storage.isCellPlaced(level.dimension(), protectedCell, roadOwnerAt(level, protectedCell), signature),
                "protected content must be preserved without a debit or ownership record");

        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor("test:road-changed-paid-surface");
        helper.assertTrue(reloaded.isCellPlaced(level.dimension(), matching, matchingOwner, signature)
                        && reloaded.isCellPlaced(level.dimension(), changedPaidSurface, changedOwner, signature),
                "save/load must retain the exact owner/signature records for both previously paid road cells");
        helper.assertTrue(!reloaded.isCellPlaced(level.dimension(), foreign, roadOwnerAt(level, foreign), signature)
                        && !reloaded.isCellPlaced(level.dimension(), protectedCell, roadOwnerAt(level, protectedCell), signature)
                        && !reloaded.isCellPlaced(level.dimension(), unpaidNatural, roadOwnerAt(level, unpaidNatural), signature),
                "save/load must not fabricate paid road records for foreign, protected, or still-unpaid cells");
        reloadedStore.credit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK);
        int retryPlaced = TownRoadConstructionService.buildRoadCells(level, route, 1, reloaded, reloadedStore);
        helper.assertTrue(retryPlaced == 1 && level.getBlockState(unpaidNatural).is(Blocks.DIRT_PATH)
                        && reloadedStore.balance(TownResourceCategory.STONE) == 0
                        && reloaded.isCellPlaced(level.dimension(), unpaidNatural, roadOwnerAt(level, unpaidNatural), signature)
                        && !reloaded.isCellPlaced(level.dimension(), foreign, roadOwnerAt(level, foreign), signature)
                        && !reloaded.isCellPlaced(level.dimension(), protectedCell, roadOwnerAt(level, protectedCell), signature),
                "after reload, retrying with exactly one stone must build and debit only the paused natural cell");
        reloadedStore.credit(TownResourceCategory.STONE, TownConstants.ROAD_COST_PER_BLOCK);
        int completedBalance = reloadedStore.balance(TownResourceCategory.STONE);
        helper.assertTrue(TownRoadConstructionService.buildRoadCells(level, route, 1, reloaded, reloadedStore) == 0
                        && reloadedStore.balance(TownResourceCategory.STONE) == completedBalance
                        && level.getBlockState(matching).is(Blocks.DIRT_PATH)
                        && level.getBlockState(changedPaidSurface).is(Blocks.DIRT_PATH)
                        && level.getBlockState(unpaidNatural).is(Blocks.DIRT_PATH)
                        && level.getBlockState(foreign).is(Blocks.DIRT_PATH)
                        && level.getBlockState(protectedCell).is(Blocks.CHEST)
                        && reloaded.isCellPlaced(level.dimension(), matching, matchingOwner, signature)
                        && reloaded.isCellPlaced(level.dimension(), changedPaidSurface, changedOwner, signature)
                        && reloaded.isCellPlaced(level.dimension(), unpaidNatural, roadOwnerAt(level, unpaidNatural), signature)
                        && !reloaded.isCellPlaced(level.dimension(), foreign, roadOwnerAt(level, foreign), signature)
                        && !reloaded.isCellPlaced(level.dimension(), protectedCell, roadOwnerAt(level, protectedCell), signature),
                "a positively funded completed retry must preserve exact paid records, live states, balance, and foreign ownership boundaries");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------
    // Review round 2 regressions
    // ---------------------------------------------------------------------------------------

    /** Review round 2, finding #1: the claim must be the villager's real JOB_SITE memory, NEVER
     *  spatial proximity.  The campfire is far from the claimant but centered so even a selected
     *  seven-wide STOREHOUSE fits in the fixture; construction progress is checked against the
     *  exact plan/order rather than two cells that only observe a five-wide site's first tick. */
    @GameTest(template = "settlement_test")
    public static void claimSurvivesTheClaimingVillagerWanderingFarFromTheCampfire(GameTestHelper helper) {
        BlockPos campfireCell = new BlockPos(6, 2, 6);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        TownPlan plan = TownPlanner.generate(TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ()),
                helper.getLevel().getSeed());
        TownBuildingTemplates.Kind kind = siteKind(helper, absoluteCampfire);
        int radius = (kind == TownBuildingTemplates.Kind.STOREHOUSE ? TownConstants.STOREHOUSE_FOOTPRINT_WIDTH : 5) / 2;
        prepareFlatGround(helper, campfireCell, radius);
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos farCorner = helper.absolutePos(new BlockPos(0, 2, 0));
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, farCorner, MobSpawnType.EVENT, false, false);
        helper.assertTrue(helper.getLevel().addFreshEntity(claimant),
                "test setup: distant claimant insertion was rejected");
        claimant.setVillagerData(claimant.getVillagerData().setProfession(VillagerProfession.FARMER));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        double claimantDistanceSquared = farCorner.distSqr(absoluteCampfire);
        helper.assertTrue(claimantDistanceSquared > 4.0,
                "test setup: claimant must be more than two blocks from its campfire, distanceSquared=" + claimantDistanceSquared);

        helper.assertTrue(TownCampfireClaims.isClaimed(helper.getLevel(), absoluteCampfire),
                "a villager more than two blocks from its own claimed campfire must still read as claimed - this is never spatial proximity"
                        + "; distanceSquared=" + claimantDistanceSquared);

        TownStorage storage = new TownStorage();
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 1000);
        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        java.util.Optional<VillagerProfession> finalProfession = TownProfessionWorkstations.finalProfessionFor(
                kind, helper.getLevel().getSeed(), absoluteCampfire.getX(), absoluteCampfire.getZ());
        List<TownBuildingTemplates.Placement> template = TownBuildingTemplates.generate(kind, absoluteCampfire.getX(), floorY,
                absoluteCampfire.getZ(), plan.storehouseVariant(), finalProfession.orElse(null));
        List<ConstructionSitePlan.PlannedBlock> ordered = ConstructionSitePlan.bottomUpOrder(
                template.stream().map(TownBuildingTemplates.Placement::toPlan).toList(),
                absoluteCampfire.getX(), absoluteCampfire.getY(), absoluteCampfire.getZ());
        String planDiagnostic = "kind=" + kind + ", radius=" + radius + ", floorY=" + floorY
                + ", center=" + plan.townCenterX() + "," + plan.townCenterZ()
                + ", variant=" + plan.storehouseVariant();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos footprint = absoluteCampfire.offset(x, 0, z);
                helper.assertTrue(helper.getLevel().hasChunkAt(footprint),
                        "the selected full footprint must be loaded before testing construction progress: " + planDiagnostic
                                + ", missing=" + footprint);
            }
        }
        java.util.Optional<TownSitePreflight.Rejection> preflightRejection = TownSitePreflight.firstRejection(
                helper.getLevel(), absoluteCampfire, radius, floorY,
                floorY + TownBuildingTemplates.heightFor(kind, plan.storehouseVariant()), pos -> false,
                claimant.getUUID(), pos -> storage.isCellBlocked(helper.getLevel().dimension(), pos));
        helper.assertTrue(preflightRejection.isEmpty(),
                "the prepared selected footprint must pass production preflight before testing construction progress: "
                        + planDiagnostic + ", seed=" + helper.getLevel().getSeed()
                        + ", firstRejection=" + preflightRejection.orElse(null));

        TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);

        for (int i = 0; i < TownConstants.CONSTRUCTION_BLOCKS_PER_TICK; i++) {
            TownBuildingTemplates.Placement placement = placementAt(template, ordered.get(i));
            BlockPos placed = new BlockPos(placement.x(), placement.y(), placement.z());
            helper.assertTrue(helper.getLevel().getBlockState(placed).equals(placement.state()),
                    "construction must place the selected plan's ordered cell " + i + " for a distant claimant: "
                            + planDiagnostic + ", expected=" + placement.state() + ", found=" + helper.getLevel().getBlockState(placed));
        }
        TownResourceCategory cost = TownBuildingTemplates.costFor(kind);
        int expectedStone = 1000 - (cost == TownResourceCategory.STONE ? TownConstants.CONSTRUCTION_BLOCKS_PER_TICK : 0);
        int expectedWood = 1000 - (cost == TownResourceCategory.WOOD ? TownConstants.CONSTRUCTION_BLOCKS_PER_TICK : 0);
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == expectedStone
                        && store.balance(TownResourceCategory.WOOD) == expectedWood,
                "construction must debit exactly its selected category for the four attributable placements: " + planDiagnostic
                        + ", stone=" + store.balance(TownResourceCategory.STONE) + ", wood=" + store.balance(TownResourceCategory.WOOD));
        DynamicVehiclesMod.LOGGER.info(
                "M454 wandering-claim success seed={} origin={} campfire={} kind={} radius={} firstPlacements={} stone={} wood={}",
                helper.getLevel().getSeed(), helper.absolutePos(BlockPos.ZERO), absoluteCampfire, kind, radius,
                ordered.subList(0, TownConstants.CONSTRUCTION_BLOCKS_PER_TICK),
                store.balance(TownResourceCategory.STONE), store.balance(TownResourceCategory.WOOD));
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
        // [Review round 12, finding #1] Seed real Builder trade state (offers/XP/level) so the
        // handoff's reset can actually be proven, rather than trivially "still empty".
        MerchantOffer staleBuilderOffer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD), 1, 1, 0.05f);
        claimant.getOffers().add(staleBuilderOffer);
        claimant.setVillagerXp(50);
        claimant.setVillagerData(claimant.getVillagerData().setLevel(3));

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
        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        if (expectedFinalProfession.isPresent()) {
            helper.assertTrue(claimant.getVillagerData().getProfession() == expectedFinalProfession.get(),
                    "a commercial/industrial claimant must be handed off to the zone's own assigned final profession, expected "
                            + expectedFinalProfession.get() + " found " + claimant.getVillagerData().getProfession());
            BlockPos expectedWorkstation = TownBuildingTemplates.workstationPosition(
                    absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            .map(gp -> gp.pos().equals(expectedWorkstation)).orElse(false),
                    "the handed-off claimant's JOB_SITE must point at the real workstation this build just placed");
            // [Review round 12, finding #1] The new workstation POI must be actually RESERVED
            // (ticket taken), not just referenced by a raw memory write - otherwise another
            // villager could still freely acquire the same workstation.
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(expectedWorkstation) == 0,
                    "the new workstation's POI ticket must already be held by the handed-off claimant, not still free");
        } else {
            helper.assertTrue(claimant.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "a residential/storehouse claimant whose site is fully built must be released back to NONE, found "
                            + claimant.getVillagerData().getProfession());
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty(),
                    "a claimant released to NONE must never keep a stale JOB_SITE memory pointed at the old campfire");
        }
        // [Review round 13] Stale Builder trade state must be reset on EVERY path the claimant
        // leaves Builder through, not only a real-job handoff. A real new profession legitimately
        // regenerates its OWN fresh trades (vanilla nulls cached offers on any profession change,
        // see Villager#setVillagerData) - the thing that must actually be gone is the STALE BUILDER
        // offer specifically, not every possible offer.
        helper.assertTrue(!claimant.getOffers().contains(staleBuilderOffer),
                "the released claimant's stale Builder offer must be gone, not carried into its new life");
        helper.assertTrue(claimant.getVillagerXp() == 0, "the released claimant's stale Builder XP must be reset");
        helper.assertTrue(claimant.getVillagerData().getLevel() == 1, "the released claimant's stale Builder level must be reset");
        if (kind != TownBuildingTemplates.Kind.STOREHOUSE) {
            BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME)
                            .map(gp -> gp.pos().equals(expectedBed)).orElse(false),
                    "the claimant must be assigned a HOME at the bed this build just placed");
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(expectedBed) == 0,
                    "the new bed's POI ticket must already be held by the claimant, not still free");
        }
        helper.assertTrue(store.balance(TownResourceCategory.STONE) < 100000 || store.balance(TownResourceCategory.WOOD) < 100000,
                "a completed site should have actually debited some resource category");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 13, finding #2: a claimant that already had a real HOME before this
     * construction (e.g. it lived somewhere else first) must never have that memory blindly erased.
     * A kind with no bed of its own (storehouse) must leave it completely untouched - ticket and
     * all; a kind that DOES get a new bed must only release the old ticket once the new one is
     * actually secured, never erase-then-leave-unreplaced.
     */
    @GameTest(template = "settlement_test")
    public static void anExistingHomeIsNeverLeakedOrErasedWithoutAnActualReplacement(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        OwnedStandaloneBedFixture oldHomeFixture = placeOwnedStandaloneBedFixture(helper);
        BlockPos absoluteOldHome = oldHomeFixture.head();
        claimant.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteOldHome));

        TownStorage storage = new TownStorage();
        Throwable failure = null;
        try {
        // [Review round 24] Releasing an old home now requires POSITIVE proof of self-ownership -
        // genuinely record it as this claimant's own, as production code would have on the
        // original grant.
        storage.setReservationOwner(helper.getLevel().dimension(), absoluteOldHome, claimant.getUUID());
        oldHomeFixture.trackReservationOwner(storage, helper.getLevel().dimension(), claimant.getUUID());
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));

        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "test setup: the site never actually finished (kind=" + kind + ")");
        if (kind == TownBuildingTemplates.Kind.STOREHOUSE) {
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME)
                            .map(gp -> gp.pos().equals(absoluteOldHome)).orElse(false),
                    "a kind with no bed of its own must never touch an already-existing HOME");
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteOldHome) == 0,
                    "an untouched existing home's ticket must never be released out from under its own resident");
        } else {
            int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
            BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME)
                            .map(gp -> gp.pos().equals(expectedBed)).orElse(false),
                    "a kind with its own bed must replace the claimant's HOME with the new bed, not keep the stale one");
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteOldHome) > 0,
                    "the old home's ticket must be released once a real replacement is actually secured");
        }
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            finishOwnedBedFixture(oldHomeFixture, failure);
        }
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 14, finding #3: a claimant's existing HOME in a DIFFERENT
     * dimension must be released from ITS OWN actual level's {@code PoiManager}, never silently
     * skipped (which would permanently leak that reservation) just because it does not match the
     * level this handoff happens to be running on.
     */
    @GameTest(template = "settlement_test")
    public static void aCrossDimensionHomeIsReleasedFromItsOwnActualLevelNotSilentlyLeaked(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        // A real, pre-existing home in a DIFFERENT dimension from the one this handoff runs on.
        net.minecraft.server.level.ServerLevel otherDimensionLevel = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(otherDimensionLevel != null, "test setup: the nether dimension must be loaded in this GameTest server");
        BlockPos oldHomeInOtherDimension = new BlockPos(0, 70, 0);
        net.minecraft.world.level.block.state.BlockState head = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD);
        net.minecraft.world.level.block.state.BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT);
        otherDimensionLevel.setBlock(oldHomeInOtherDimension, head, net.minecraft.world.level.block.Block.UPDATE_ALL);
        otherDimensionLevel.setBlock(oldHomeInOtherDimension.north(), foot, net.minecraft.world.level.block.Block.UPDATE_ALL);
        net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(otherDimensionLevel.getBlockState(oldHomeInOtherDimension))
                .ifPresent(type -> {
                    otherDimensionLevel.getPoiManager().add(oldHomeInOtherDimension, type);
                    otherDimensionLevel.getPoiManager().take(t -> true, (t, p) -> p.equals(oldHomeInOtherDimension), oldHomeInOtherDimension, 1);
                });
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldHomeInOtherDimension) == 0,
                "test setup: the cross-dimension old home's ticket must actually be held before construction starts");
        claimant.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(Level.NETHER, oldHomeInOtherDimension));

        TownStorage storage = new TownStorage();
        // [Review round 24] Releasing an old home now requires POSITIVE proof of self-ownership -
        // genuinely record it as this claimant's own, as production code would have on the
        // original grant.
        storage.setReservationOwner(Level.NETHER, oldHomeInOtherDimension, claimant.getUUID());
        TownResourceStore store = fundStore(helper, storage, absoluteCampfire, 100000);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        if (kind == TownBuildingTemplates.Kind.STOREHOUSE) {
            // No bed of its own to ever replace the cross-dimension home with this run - the other
            // kinds/slots already cover the replacement path.
            helper.succeed();
            return;
        }

        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "test setup: the site never actually finished (kind=" + kind + ")");
        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
        helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(expectedBed) && gp.dimension().equals(helper.getLevel().dimension())).orElse(false),
                "the claimant's HOME must be replaced with the new, same-dimension bed");
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldHomeInOtherDimension) > 0,
                "the cross-dimension old home's ticket must actually be released, not permanently leaked just because it was in a different dimension");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 14, finding #1: a workstation or bed already reserved by some
     * other (unrelated) villager must block the WHOLE handoff, never commit a half-handed-off
     * terminal state. The claimant must come away EXACTLY as it went in - still Builder, still
     * claiming this exact campfire, any uncontested fixture rolled back too - so a LATER tick, once
     * the contested fixture actually frees up, can retry the whole handoff and reach a complete,
     * internally consistent assignment.
     */
    @GameTest(template = "settlement_test")
    public static void aContestedFixtureBlocksTheWholeHandoffUntilItFreesUpForARetry(GameTestHelper helper) {
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

        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        java.util.Optional<VillagerProfession> expectedFinalProfession = TownProfessionWorkstations.finalProfessionFor(
                kind, helper.getLevel().getSeed(), absoluteCampfire.getX(), absoluteCampfire.getZ());
        boolean bedApplies = kind != TownBuildingTemplates.Kind.STOREHOUSE;
        if (!bedApplies && expectedFinalProfession.isEmpty()) {
            // This grid slot's storehouse has no bed AND no final profession to ever contest -
            // nothing this test can exercise this run; the other kinds/slots already cover it.
            helper.succeed();
            return;
        }

        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        BlockPos workstationPos = expectedFinalProfession.isPresent()
                ? TownBuildingTemplates.workstationPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ()) : null;
        BlockPos bedPos = bedApplies
                ? TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ()) : null;
        // Contest whichever ONE fixture this site actually has, preferring the bed - proving that
        // contesting even ONE required fixture blocks the whole handoff, including any OTHER
        // (uncontested) fixture the claimant also needed.
        BlockPos contested = bedApplies ? bedPos : workstationPos;
        net.minecraft.world.level.block.Block contestedBlock = bedApplies ? Blocks.RED_BED
                : TownProfessionWorkstations.blockFor(expectedFinalProfession.get()).orElseThrow();

        // A pre-existing fixture in the footprint (even one matching what this site will build)
        // reads as foreign/protected content and aborts the WHOLE site outright (same as a chest
        // would, per TownSitePreflight) - so contention has to be staged AFTER this SAME build
        // actually places the fixture itself (construction is bounded to
        // TownConstants.CONSTRUCTION_BLOCKS_PER_TICK blocks per call, so a real multi-cell site
        // takes many calls) - steal the ticket the moment it first appears, standing in for some
        // OTHER, unrelated villager that got there first, well before this same build's own later
        // completion tick would otherwise reserve it for this claimant.
        boolean[] stolen = {false};
        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
            if (!stolen[0] && helper.getLevel().getBlockState(contested).is(contestedBlock)) {
                stealExistingFixtureTicket(helper, contested);
                stolen[0] = true;
            }
        }

        helper.assertTrue(stolen[0], "test setup: the contested fixture was never actually placed in time to steal it");
        helper.assertTrue(claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "a contested fixture must leave the claimant retryably as Builder, never a half-handed-off profession");
        helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(absoluteCampfire)).orElse(false),
                "a contested fixture must leave JOB_SITE still pointed at this exact campfire, never erased with nothing left to retry from");
        // [Review round 14, finding #1] The claim surviving in memory is not enough on its own:
        // findClaimedCampfireSites (the SOLE way production ever discovers a site to call buildAt
        // on at all) requires the position to be a REAL, physically-present CAMPFIRE block. A
        // contested fixture must never let the campfire's own closing placement land anyway - that
        // would make this exact scenario permanently undiscoverable in real play, no matter what
        // the claimant's own memory still said.
        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE),
                "a contested fixture must leave the campfire itself physically intact - production's only way to ever rediscover this site again");
        if (bedApplies && workstationPos != null) {
            // The bed was contested but the workstation was not - it must still be ROLLED BACK,
            // never left reserved for a handoff that never actually committed.
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(workstationPos) > 0,
                    "an uncontested workstation must be rolled back when the fixture it's paired with is contested");
        }

        // The other villager moves on: the contested fixture frees up, so the next retry should
        // reach a complete, internally consistent assignment - never stuck contested forever.
        helper.getLevel().getPoiManager().release(contested);
        for (int i = 0; i < 10 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "once the contested fixture frees up, the retried handoff must actually complete");
        if (expectedFinalProfession.isPresent()) {
            helper.assertTrue(claimant.getVillagerData().getProfession() == expectedFinalProfession.get(),
                    "once retried successfully, the claimant must be handed off to its real final profession");
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                            .map(gp -> gp.pos().equals(workstationPos)).orElse(false),
                    "once retried successfully, JOB_SITE must point at the real workstation");
        } else {
            helper.assertTrue(claimant.getVillagerData().getProfession() == VillagerProfession.NONE,
                    "once retried successfully, a claimant with no final profession must be released to NONE");
        }
        if (bedApplies) {
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME)
                            .map(gp -> gp.pos().equals(bedPos)).orElse(false),
                    "once retried successfully, the claimant must end up with the real bed as its HOME");
        }
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 16, finding #1: reserving a handoff destination and only THEN
     * discovering the campfire's own closing cell is unaffordable must never leak that reservation -
     * funded to cover every planned cell EXCEPT the final one, the site must pause indefinitely with
     * NO provisional ticket held, then actually complete once resources (and a reload in between)
     * are back.
     */
    @GameTest(template = "settlement_test")
    public static void anInsufficientFundsPauseNeverLeaksAProvisionalHandoffReservation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        TownBuildingTemplates.Kind kind = isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
        java.util.Optional<VillagerProfession> expectedFinalProfession = TownProfessionWorkstations.finalProfessionFor(
                kind, helper.getLevel().getSeed(), absoluteCampfire.getX(), absoluteCampfire.getZ());
        boolean bedApplies = kind != TownBuildingTemplates.Kind.STOREHOUSE;

        int floorY = TownTerrain.estimateGroundHeightBelow(helper.getLevel(), absoluteCampfire);
        List<TownBuildingTemplates.Placement> template = TownBuildingTemplates.generate(
                kind, absoluteCampfire.getX(), floorY, absoluteCampfire.getZ(), plan.storehouseVariant(),
                expectedFinalProfession.orElse(null));
        TownResourceCategory costCategory = TownBuildingTemplates.costFor(kind);
        TownResourceStore store = storage.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));
        // Fund EXACTLY enough for every planned cell except the final (campfire-closing) one -
        // every cell in a single kind's template costs the SAME category at CONSTRUCTION_COST_PER_BLOCK.
        store.credit(costCategory, (template.size() - 1) * TownConstants.CONSTRUCTION_COST_PER_BLOCK);

        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "test setup: the site must be paused on the final cell for insufficient funds, not already complete");
        helper.assertTrue(helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE),
                "the campfire must still be physically present while paused for insufficient funds");
        BlockPos workstationPos = expectedFinalProfession.isPresent()
                ? TownBuildingTemplates.workstationPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ()) : null;
        BlockPos bedPos = bedApplies
                ? TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ()) : null;
        if (workstationPos != null) {
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(workstationPos) > 0,
                    "a paused-for-funds site must never leak a provisional workstation reservation");
        }
        if (bedPos != null) {
            helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(bedPos) > 0,
                    "a paused-for-funds site must never leak a provisional bed reservation");
        }

        // A reload while paused must never disturb the retry - the persisted floor/cell-progress
        // record is the only state this mod itself keeps about this in-progress site.
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(TownKeys.keyFor(helper.getLevel().dimension(), region));

        // Replenish and confirm the site actually completes through normal ticks.
        reloadedStore.credit(costCategory, 1000);
        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, reloaded);
        }
        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "once replenished (and reloaded), the paused site must actually complete");
        helper.succeed();
    }

    /**
     * Creates the old-home fixture in this structure's own unused cells.  It deliberately refuses
     * to overwrite a pre-existing block: a foreign fixture must remain foreign rather than being
     * silently adopted by cleanup.
     */
    private static OwnedStandaloneBedFixture placeOwnedStandaloneBedFixture(GameTestHelper helper) {
        return placeOwnedStandaloneBedFixture(helper, OWNED_OLD_HOME_HEAD);
    }

    private static OwnedStandaloneBedFixture placeOwnedStandaloneBedFixture(GameTestHelper helper, BlockPos localHeadPos) {
        return placeOwnedStandaloneBedFixture(helper, localHeadPos, false);
    }

    /** Test-only setup failpoint: verifies the helper rolls back its first successful write. */
    private static OwnedStandaloneBedFixture placeOwnedStandaloneBedFixture(GameTestHelper helper, BlockPos localHeadPos,
            boolean failAfterHeadWrite) {
        ServerLevel level = helper.getLevel();
        BlockPos headPos = helper.absolutePos(localHeadPos);
        BlockPos footPos = headPos.north();
        helper.assertTrue(level.getBlockState(headPos).isAir() && level.getBlockState(footPos).isAir(),
                "old-home fixture cells must start empty and owned by this test: head=" + level.getBlockState(headPos)
                        + ", foot=" + level.getBlockState(footPos));
        BlockState head = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD);
        BlockState foot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT);
        OwnedStandaloneBedFixture fixture = new OwnedStandaloneBedFixture(level, headPos, footPos, head, foot);
        try {
            fixture.placeAndTakeTicket(failAfterHeadWrite);
            return fixture;
        } catch (RuntimeException | Error exception) {
            // This helper owns partial setup until it returns the fixture to a caller.  Roll back
            // only writes whose successful completion it recorded, while retaining this setup
            // failure as the primary error.
            finishOwnedBedFixture(fixture, exception);
            throw exception;
        }
    }

    /** Runs every independent cleanup action while retaining the original assertion as primary. */
    private static void finishOwnedBedFixture(OwnedStandaloneBedFixture fixture, Throwable primary) {
        Throwable cleanupFailure = fixture.restoreOwnedState();
        if (cleanupFailure == null) return;
        if (primary != null) {
            primary.addSuppressed(cleanupFailure);
            return;
        }
        if (cleanupFailure instanceof Error error) throw error;
        if (cleanupFailure instanceof RuntimeException exception) throw exception;
        throw new IllegalStateException("old-home fixture cleanup failed", cleanupFailure);
    }

    /** Owns exactly the writes and ticket it successfully created, never foreign state. */
    private static final class OwnedStandaloneBedFixture {
        private final ServerLevel level;
        private final BlockPos head;
        private final BlockPos foot;
        private final BlockState expectedHead;
        private final BlockState expectedFoot;
        private final net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType> expectedPoiType;
        private boolean headWritten;
        private boolean footWritten;
        private boolean poiAdded;
        private boolean ticketTaken;
        private TownStorage reservationStorage;
        private ResourceKey<Level> reservationDimension;
        private java.util.UUID reservationOwner;
        private java.util.UUID ticketOwner;
        private final java.util.Set<java.util.UUID> trackedTestOwners = new java.util.HashSet<>();

        private OwnedStandaloneBedFixture(ServerLevel level, BlockPos head, BlockPos foot,
                BlockState expectedHead, BlockState expectedFoot) {
            this.level = level;
            this.head = head;
            this.foot = foot;
            this.expectedHead = expectedHead;
            this.expectedFoot = expectedFoot;
            this.expectedPoiType = net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(expectedHead)
                    .orElseThrow(() -> new IllegalStateException("old-home fixture bed has no POI type"));
        }

        private BlockPos head() { return head; }
        private BlockPos foot() { return foot; }
        private BlockState expectedHead() { return expectedHead; }
        private BlockState expectedFoot() { return expectedFoot; }

        private void placeAndTakeTicket(boolean failAfterHeadWrite) {
            if (level.getPoiManager().exists(head, type -> true)) {
                throw new IllegalStateException("old-home fixture found an unexpected existing POI at " + head);
            }
            if (!level.setBlock(head, expectedHead, Block.UPDATE_ALL)) {
                throw new IllegalStateException("old-home fixture could not write its head at " + head);
            }
            headWritten = true;
            if (failAfterHeadWrite) {
                throw new IllegalStateException("test-only old-home fixture setup failure after head write");
            }
            if (!level.setBlock(foot, expectedFoot, Block.UPDATE_ALL)) {
                throw new IllegalStateException("old-home fixture could not write its foot at " + foot);
            }
            footWritten = true;
            level.getPoiManager().add(head, expectedPoiType);
            poiAdded = true;
            if (level.getPoiManager().take(type -> type.equals(expectedPoiType), (type, pos) -> pos.equals(head), head, 1)
                    .filter(head::equals).isEmpty()) {
                throw new IllegalStateException("old-home fixture could not take its own POI ticket at " + head);
            }
            ticketTaken = true;
        }

        private void trackReservationOwner(TownStorage storage, ResourceKey<Level> dimension, java.util.UUID owner) {
            reservationStorage = storage;
            reservationDimension = dimension;
            reservationOwner = owner;
            trackedTestOwners.add(owner);
        }

        private void releaseFixtureTicketForRegrant() {
            if (!ticketTaken || !poiMatchesExpected()) {
                throw new IllegalStateException("old-home fixture cannot release an unowned or changed POI at " + head);
            }
            if (!level.getPoiManager().release(head)) {
                throw new IllegalStateException("old-home fixture ticket was not held at " + head);
            }
            ticketTaken = false;
            ticketOwner = null;
        }

        private void takeTicketForTestOwner(java.util.UUID owner) {
            if (!poiMatchesExpected()) {
                throw new IllegalStateException("old-home fixture cannot regrant a changed POI at " + head);
            }
            if (level.getPoiManager().take(type -> type.equals(expectedPoiType), (type, pos) -> pos.equals(head), head, 1)
                    .filter(head::equals).isEmpty()) {
                throw new IllegalStateException("old-home fixture could not regrant its ticket at " + head);
            }
            ticketTaken = true;
            ticketOwner = owner;
            trackedTestOwners.add(owner);
        }

        private boolean poiMatchesExpected() {
            return level.getPoiManager().exists(head, type -> type.equals(expectedPoiType));
        }

        private Throwable restoreOwnedState() {
            Throwable failure = ownershipFailure();
            if (failure != null) return failure;
            java.util.UUID trackedReservationOwner = trackedReservationOwner();
            if (trackedReservationOwner != null) {
                try {
                    reservationStorage.clearReservationOwner(reservationDimension, head);
                } catch (Throwable exception) {
                    failure = appendCleanupFailure(failure, exception);
                }
            }
            if (ticketTaken && level.getPoiManager().getFreeTickets(head) == 0) {
                try {
                    if (!level.getPoiManager().release(head)) {
                        failure = appendCleanupFailure(failure, new IllegalStateException(
                                "old-home fixture expected its tracked ticket owner " + ticketOwner + " at " + head));
                    }
                } catch (Throwable exception) {
                    failure = appendCleanupFailure(failure, exception);
                }
            }
            if (poiAdded) {
                try {
                    level.getPoiManager().remove(head);
                } catch (Throwable exception) {
                    failure = appendCleanupFailure(failure, exception);
                }
            }
            if (footWritten) {
                try {
                    level.setBlock(foot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                } catch (Throwable exception) {
                    failure = appendCleanupFailure(failure, exception);
                }
            }
            if (headWritten) {
                try {
                    level.setBlock(head, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                } catch (Throwable exception) {
                    failure = appendCleanupFailure(failure, exception);
                }
            }
            if ((headWritten && !level.getBlockState(head).isAir()) || (footWritten && !level.getBlockState(foot).isAir())) {
                failure = appendCleanupFailure(failure, new IllegalStateException(
                        "old-home fixture cleanup did not restore its owned cells at head=" + level.getBlockState(head)
                                + ", foot=" + level.getBlockState(foot)));
            }
            return failure;
        }

        private Throwable ownershipFailure() {
            if (hasForeignReservationOwner()) {
                return new IllegalStateException("old-home fixture reservation was taken over by another owner; refusing all cleanup at "
                        + head + " (tracked=" + reservationOwner + ", ticketOwner=" + ticketOwner + ")");
            }
            BlockState actualHead = level.getBlockState(head);
            BlockState actualFoot = level.getBlockState(foot);
            if ((headWritten && !actualHead.equals(expectedHead)) || (!headWritten && !actualHead.isAir())
                    || (footWritten && !actualFoot.equals(expectedFoot)) || (!footWritten && !actualFoot.isAir())) {
                return new IllegalStateException("old-home fixture changed by a foreign writer; refusing cleanup head="
                        + actualHead + ", foot=" + actualFoot);
            }
            if (poiAdded && !poiMatchesExpected()) {
                return new IllegalStateException("old-home fixture POI changed or missing at " + head + "; refusing cleanup");
            }
            return null;
        }

        private boolean hasForeignReservationOwner() {
            if (reservationStorage == null || trackedTestOwners.isEmpty()) return false;
            for (java.util.UUID testOwner : trackedTestOwners) {
                if (reservationStorage.isReservedBySelf(reservationDimension, head, testOwner)
                        || !reservationStorage.isReservedByADifferentVillager(reservationDimension, head, testOwner)) {
                    return false;
                }
            }
            return true;
        }

        private java.util.UUID trackedReservationOwner() {
            if (reservationStorage == null) return null;
            for (java.util.UUID testOwner : trackedTestOwners) {
                if (reservationStorage.isReservedBySelf(reservationDimension, head, testOwner)) return testOwner;
            }
            return null;
        }
    }

    private static Throwable appendCleanupFailure(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    /** A foreign overwrite must be preserved while its cleanup failure stays suppressed on the primary error. */
    @GameTest(template = "settlement_test")
    public static void ownedOldHomeFixtureFailureCleanupPreservesPrimaryError(GameTestHelper helper) {
        prepareFlatGround(helper);
        OwnedStandaloneBedFixture fixture = placeOwnedStandaloneBedFixture(helper);
        TownStorage storage = new TownStorage();
        java.util.UUID fixtureOwner = java.util.UUID.randomUUID();
        java.util.UUID deliberateForeignOwner = java.util.UUID.randomUUID();
        storage.setReservationOwner(helper.getLevel().dimension(), fixture.head(), fixtureOwner);
        fixture.trackReservationOwner(storage, helper.getLevel().dimension(), fixtureOwner);
        storage.setReservationOwner(helper.getLevel().dimension(), fixture.head(), deliberateForeignOwner);
        boolean deliberateReplacementInstalled = false;
        Throwable failure = null;
        try {
            BlockPos local = fixture.head().subtract(helper.absolutePos(BlockPos.ZERO));
            helper.assertTrue(local.equals(OWNED_OLD_HOME_HEAD)
                            && local.getX() >= 0 && local.getX() < 24
                            && local.getY() >= 0 && local.getY() < 8
                            && local.getZ() >= 0 && local.getZ() < 24,
                    "old-home fixture must stay inside its own settlement_test bounds: local=" + local);
            helper.assertTrue(helper.getLevel().getBlockState(fixture.head()).equals(fixture.expectedHead())
                            && helper.getLevel().getBlockState(fixture.foot()).equals(fixture.expectedFoot()),
                    "old-home fixture must own both complete bed halves before cleanup");
            // Do not notify neighbours here: the test needs a deliberately foreign foot state
            // without causing vanilla to dismantle the fixture's own head/POI first.
            helper.getLevel().setBlock(fixture.foot(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            deliberateReplacementInstalled = true;
            IllegalStateException primary = new IllegalStateException("primary fixture assertion");
            finishOwnedBedFixture(fixture, primary);
            helper.assertTrue(primary.getSuppressed().length == 1,
                    "a foreign-state cleanup failure must stay suppressed on the primary assertion");
            helper.assertTrue(helper.getLevel().getBlockState(fixture.foot()).is(Blocks.CHEST),
                    "cleanup must not clear a foreign replacement after ownership validation fails");
            helper.assertTrue(storage.isReservedBySelf(helper.getLevel().dimension(), fixture.head(), deliberateForeignOwner)
                            && helper.getLevel().getPoiManager().getFreeTickets(fixture.head()) == 0,
                    "a foreign reservation takeover must preserve the bed, POI ticket, and foreign owner before all cleanup");

            BlockPos partialHead = OWNED_OLD_HOME_HEAD.east(4);
            try {
                placeOwnedStandaloneBedFixture(helper, partialHead, true);
                helper.assertTrue(false, "test-only setup failpoint must abort after the first owned write");
            } catch (IllegalStateException expected) {
                helper.assertTrue(expected.getMessage().contains("after head write"),
                        "partial setup must retain its original first-write failure");
            }
            BlockPos absolutePartialHead = helper.absolutePos(partialHead);
            helper.assertTrue(helper.getLevel().getBlockState(absolutePartialHead).isAir()
                            && helper.getLevel().getBlockState(absolutePartialHead.north()).isAir()
                            && !helper.getLevel().getPoiManager().exists(absolutePartialHead, type -> true),
                    "a partial setup failure must roll back only its successful head write and no POI");
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            Throwable restorationFailure = null;
            try {
                // This regression alone installed the chest and foreign reservation.  Restore
                // neither if another writer has changed either exact state in the meantime.
                if (deliberateReplacementInstalled && helper.getLevel().getBlockState(fixture.foot()).is(Blocks.CHEST)) {
                    helper.getLevel().setBlock(fixture.foot(), fixture.expectedFoot(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
                if (storage.isReservedBySelf(helper.getLevel().dimension(), fixture.head(), deliberateForeignOwner)) {
                    storage.clearReservationOwner(helper.getLevel().dimension(), fixture.head());
                }
            } catch (Throwable exception) {
                restorationFailure = exception;
            }
            if (restorationFailure != null) {
                if (failure != null) failure.addSuppressed(restorationFailure);
                else if (restorationFailure instanceof Error error) throw error;
                else if (restorationFailure instanceof RuntimeException exception) throw exception;
                else throw new IllegalStateException("old-home fixture regression restoration failed", restorationFailure);
            }
            finishOwnedBedFixture(fixture, failure);
        }
        helper.assertTrue(helper.getLevel().getBlockState(fixture.head()).isAir()
                        && helper.getLevel().getBlockState(fixture.foot()).isAir(),
                "after restoring only its own deliberate state, normal fixture cleanup must restore both cells to air");
        helper.succeed();
    }

    /** Steals the ticket for a fixture {@code TownConstructionService} has ALREADY correctly placed
     *  at {@code absolutePos}, standing in for some OTHER, unrelated villager that got there first -
     *  WITHOUT touching the live block at all (re-writing it, even to the exact same block, risks
     *  retriggering vanilla's own neighbour-update/validity side effects - e.g. a bed - for no
     *  reason; the real block is already exactly right, this only needs the ABSTRACT POI ticket
     *  layer to reflect contention). */
    private static void stealExistingFixtureTicket(GameTestHelper helper, BlockPos absolutePos) {
        registerAndTakeTicket(helper, absolutePos);
    }

    private static void registerAndTakeTicket(GameTestHelper helper, BlockPos absolutePos) {
        net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(helper.getLevel().getBlockState(absolutePos))
                .ifPresent(type -> {
                    helper.getLevel().getPoiManager().add(absolutePos, type);
                    helper.getLevel().getPoiManager().take(t -> true, (t, p) -> p.equals(absolutePos), absolutePos, 1);
                });
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
        String signature = "test-plan:minecraft:stone:STONE";
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), a, owner, signature),
                "a fresh store must not report any cell as placed");

        storage.markCellPlaced(helper.getLevel().dimension(), a, owner, signature, Blocks.STONE.defaultBlockState());

        helper.assertTrue(storage.isCellPlaced(helper.getLevel().dimension(), a, owner, signature),
                "a marked cell must read back as placed");
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), b, owner, signature),
                "marking one cell must not affect a different position");
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
        String signature = "test-plan:minecraft:dirt_path:STONE";

        storage.markCellPlaced(helper.getLevel().dimension(), pos, roadOwner, signature, Blocks.DIRT_PATH.defaultBlockState());

        helper.assertTrue(storage.isCellPlaced(helper.getLevel().dimension(), pos, roadOwner, signature),
                "the road plan that actually placed this cell must read it back as its own progress");
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), pos, siteOwner, signature),
                "a different plan (a building site) must never read the road's own paid progress at the same coordinate as its own");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 7, finding #1: the SAME owner (a site's campfire anchor never
     * moves) can still see a genuinely different plan over its lifetime - a cell paid for under the
     * OLD plan's signature must never be silently honored as already-paid progress for a NEW,
     * different plan (a different expected block/cost here) at the exact same owner+position.
     */
    @GameTest(template = "settlement_test")
    public static void placedCellProgressIsInvalidatedWhenTheSameOwnersPlanChanges(GameTestHelper helper) {
        TownStorage storage = new TownStorage();
        BlockPos pos = helper.absolutePos(ANCHOR);
        String owner = TownStorage.siteOwner(pos);
        String oldPlanSignature = "RESIDENTIAL:0:none:minecraft:oak_planks:WOOD";
        String newPlanSignature = "COMMERCIAL:0:FARMER:minecraft:stone_bricks:STONE";

        storage.markCellPlaced(helper.getLevel().dimension(), pos, owner, oldPlanSignature, Blocks.OAK_PLANKS.defaultBlockState());
        helper.assertTrue(storage.isCellPlaced(helper.getLevel().dimension(), pos, owner, oldPlanSignature),
                "the original plan must read its own progress back correctly");

        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), pos, owner, newPlanSignature),
                "the SAME owner's progress under a now-superseded plan must never count as prepaid for a genuinely different current plan");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 10, finding #1: a cell left in a genuinely unknown,
     * partially-mutated state (a mutation AND its own rollback both failed) is permanently,
     * DURABLY excluded from further construction - direct coverage of the persisted primitive
     * {@link TownRoadConstructionService} now uses instead of a local variable that would forget
     * the failure the instant the tick's own call returns.
     */
    @GameTest(template = "settlement_test")
    public static void blockedCellsAreDurablyExcludedFromFurtherConstruction(GameTestHelper helper) {
        TownStorage storage = new TownStorage();
        BlockPos pos = helper.absolutePos(ANCHOR);
        helper.assertTrue(!storage.isCellBlocked(helper.getLevel().dimension(), pos),
                "a fresh store must not report any cell as blocked");

        storage.markCellBlocked(helper.getLevel().dimension(), pos);

        helper.assertTrue(storage.isCellBlocked(helper.getLevel().dimension(), pos),
                "a marked cell must read back as blocked");
        helper.assertTrue(!storage.isCellBlocked(helper.getLevel().dimension(), pos.offset(1, 0, 0)),
                "marking one cell must not affect a different position");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 10, finding #1: road construction must never touch a cell already
     * durably blocked (a prior mutation + rollback failure this mod can no longer trust) - no
     * mutation, no charge, exactly like any other permanently-foreign content.
     */
    @GameTest(template = "settlement_test")
    public static void roadConstructionNeverTouchesAPermanentlyBlockedCell(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        BlockPos absoluteCell = helper.absolutePos(cell);
        TownStorage storage = new TownStorage();
        storage.markCellBlocked(helper.getLevel().dimension(), absoluteCell);
        TownResourceStore store = storage.storeFor("test:road-blocked-cell");
        store.credit(TownResourceCategory.STONE, 1000);
        // A single-cell route (width defaults to MINOR_ROAD_WIDTH=3) still places its OTHER two
        // width cells normally - this test isolates the ONE blocked cell specifically, not the
        // whole route, so it does not assert on the aggregate balance.
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));

        TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, storage, store);

        helper.assertTrue(!helper.getBlockState(cell).is(Blocks.DIRT_PATH),
                "a permanently blocked cell must never be built over, found " + helper.getBlockState(cell));
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 11, finding #2: an actual forward-write-plus-rollback failure
     * (engineered via {@link TownRoadConstructionService}'s test-only fault-injection hooks, since
     * the real Minecraft API gives no deterministic way to force a mutation to fail) must durably
     * block EVERY cell whose own restoration actually failed (not merely the road surface cell),
     * survive an immediate retry with no further mutation or double charge/credit, survive a real
     * save/load reload, AND be respected by a claimed BUILDING's own preflight/terrain-prep/
     * placement paths if its footprint happens to overlap the blocked cell - proving the hold is a
     * genuinely shared, cross-system guarantee, not merely a road-local bookkeeping detail.
     */
    @GameTest(template = "settlement_test")
    public static void injectedRollbackFailureDurablyBlocksAffectedCellsAcrossRetryReloadAndBuildingOverlap(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos cell = ANCHOR;
        BlockPos above1 = cell.above();
        helper.setBlock(above1, Blocks.OAK_LOG); // real headroom to clear, so rollback has something to restore
        BlockPos absoluteCell = helper.absolutePos(cell);
        BlockPos absoluteAbove1 = helper.absolutePos(above1);

        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-rollback-failure");
        store.credit(TownResourceCategory.STONE, 1000);
        store.credit(TownResourceCategory.WOOD, 1000);

        // Engineer the scenario the real API cannot otherwise produce: the surface placement is
        // forced to fail AFTER headroom clearing already happened (so rollback has real work to
        // do), and that rollback's own restoration of above1 is ALSO forced to fail.
        TownRoadConstructionService.testForceSurfaceFailureAt = absoluteCell;
        TownRoadConstructionService.testForceRollbackFailureAt = absoluteAbove1;
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteCell.getX(), absoluteCell.getZ(), absoluteCell.getY()));
        TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, 1, storage, store);

        helper.assertTrue(storage.isCellBlocked(helper.getLevel().dimension(), absoluteCell),
                "the road surface cell itself must be durably blocked after the failed rollback");
        helper.assertTrue(storage.isCellBlocked(helper.getLevel().dimension(), absoluteAbove1),
                "the specific headroom cell whose OWN restoration failed must be durably blocked too, not just the surface cell");
        helper.assertTrue(!helper.getBlockState(cell).is(Blocks.DIRT_PATH), "the surface must never have been placed");
        helper.assertTrue(helper.getBlockState(above1).isAir(),
                "the headroom cell is left exactly as the forced failure made it (air, not restored) - this mod no longer touches it at all");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                "a failed transaction must never charge or credit anything, even partially");

        // Retry: no force flags set this time - a genuinely clean attempt must still be refused,
        // with no further mutation and no double charge/credit, purely because both cells are now
        // durably blocked.
        TownRoadConstructionService.buildRoadCells(helper.getLevel(), cells, 1, storage, store);
        helper.assertTrue(!helper.getBlockState(cell).is(Blocks.DIRT_PATH), "a retry must still never place the surface");
        helper.assertTrue(helper.getBlockState(above1).isAir(), "a retry must never touch the blocked headroom cell either");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000 && store.balance(TownResourceCategory.WOOD) == 1000,
                "a retry of a blocked cell must never charge or credit anything");

        // Reload: the block must survive a genuine save/load round-trip, not merely this session's
        // in-memory state.
        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.isCellBlocked(helper.getLevel().dimension(), absoluteCell),
                "the road surface cell's block must survive a reload");
        helper.assertTrue(reloaded.isCellBlocked(helper.getLevel().dimension(), absoluteAbove1),
                "the headroom cell's block must survive a reload too");

        // Overlap: a claimed BUILDING whose own campfire sits EXACTLY on this same blocked cell
        // must also refuse to adopt it as ordinary clearable terrain - the whole site aborts,
        // exactly like any other protected/foreign content, never silently building elsewhere in
        // the footprint around it either.
        helper.setBlock(cell, Blocks.CAMPFIRE);
        spawnClaimedVillager(helper, absoluteCell);
        TownConstructionService.buildAt(helper.getLevel(), absoluteCell, reloaded);
        helper.assertTrue(helper.getBlockState(cell).is(Blocks.CAMPFIRE), "an aborted site must leave the campfire untouched");
        helper.assertTrue(helper.getBlockState(cell.offset(2, 0, 2)).isAir(),
                "a building site whose own campfire cell is durably blocked must abort the WHOLE site, never build elsewhere in the footprint either");
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

    /** MINECRAFT-453: activity is selected per founded region, not once per nearby player. */
    @GameTest(template = "settlement_test")
    public static void multipleNearbyPlayersDispatchOnePaidRoadBudgetAndResumeAfterInactiveReload(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(ANCHOR);
        RegionCoord fixtureGridRegion = TownRegion.fromBlockCoords(anchor.getX(), anchor.getZ());
        // GameTest batches place many unrelated mock players around their own structures.  Select
        // a remote founded region for these two owned actors, while keeping the bounded callback's
        // deliberately local corridor inside this fixture's loaded template.
        RegionCoord region = new RegionCoord(fixtureGridRegion.x() + 100, fixtureGridRegion.z() + 100);
        String townKey = TownKeys.keyFor(level.dimension(), region);
        TownStorage storage = new TownStorage();
        storage.markTownFounded(townKey);
        TownResourceStore store = storage.storeFor(townKey);
        store.credit(TownResourceCategory.STONE, 1000);
        BlockPos start = helper.absolutePos(new BlockPos(1, 8, 1));
        BlockPos foreign = start.offset(1, 0, 0);
        level.setBlock(foreign, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        List<TownRoadPlanner.RoadCell> route = List.of(
                new TownRoadPlanner.RoadCell(start.getX(), start.getZ(), start.getY()),
                new TownRoadPlanner.RoadCell(foreign.getX(), foreign.getZ(), foreign.getY()),
                new TownRoadPlanner.RoadCell(start.getX() + 2, start.getZ(), start.getY()),
                new TownRoadPlanner.RoadCell(start.getX() + 3, start.getZ(), start.getY()));
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        int[] dispatches = {0};
        TownConstructionService.RoadWork work = (workLevel, selected, selectedStorage, selectedStore) -> {
            helper.assertTrue(selected.equals(region), "two nearby players must dispatch the founded region exactly once");
            dispatches[0]++;
            return TownRoadConstructionService.buildRoadCells(workLevel, route, selectedStorage, selectedStore) > 0;
        };
        List<ServerPlayer> unrelatedBefore = List.copyOf(level.players());
        ServerPlayer first = null;
        ServerPlayer second = null;
        java.util.UUID[] ownedUuids = new java.util.UUID[2];
        try {
        first = GameTestMockPlayers.create(helper);
        ownedUuids[0] = first.getUUID();
        second = GameTestMockPlayers.create(helper);
        ownedUuids[1] = second.getUUID();
        int activeX = TownRegion.minBlockX(region) + 5;
        int activeZ = TownRegion.minBlockZ(region) + 5;
        first.teleportTo(activeX + .5, anchor.getY(), activeZ + .5);
        second.teleportTo(activeX + 2.5, anchor.getY(), activeZ + 2.5);

        TownConstructionService.runConstructionAndRoadsTick(level, storage, work);
        helper.assertTrue(dispatches[0] == 1, "two registered nearby players must consume one region dispatch, not two");
        int afterTwoPlayers = store.balance(TownResourceCategory.STONE);
        helper.assertTrue(1000 - afterTwoPlayers == TownConstants.ROAD_BLOCKS_PER_TICK,
                "one regional dispatch must consume exactly one road budget");
        second.teleportTo(activeX + 2000.5, anchor.getY(), activeZ + 2000.5);
        TownConstructionService.runConstructionAndRoadsTick(level, storage, work);
        helper.assertTrue(dispatches[0] == 2, "one remaining nearby player must keep one regional dispatch active");
        helper.assertTrue(1000 - store.balance(TownResourceCategory.STONE) == 8,
                "exactly two active regional ticks must debit eight paid road cells before inactivity");

        first.teleportTo(activeX - 2000.5, anchor.getY(), activeZ - 2000.5);
        helper.assertTrue(TownConstructionService.regionsWithNearbyPlayerActivity(level, storage).isEmpty(),
                "test setup requires both registered players to be outside the only founded region before inactive assertions: "
                        + "first=" + first.getGameProfile().getName() + "@" + first.blockPosition()
                        + " region=" + TownRegion.fromBlockCoords(first.blockPosition().getX(), first.blockPosition().getZ())
                        + ", second=" + second.getGameProfile().getName() + "@" + second.blockPosition()
                        + " region=" + TownRegion.fromBlockCoords(second.blockPosition().getX(), second.blockPosition().getZ())
                        + ", target=" + region + ", active=" + TownConstructionService.regionsWithNearbyPlayerActivity(level, storage)
                        + ", levelRoster=" + roadActivityRoster(level, first, second));
        int inactiveBalance = store.balance(TownResourceCategory.STONE);
        TownConstructionService.runConstructionAndRoadsTick(level, storage, work);
        helper.assertTrue(dispatches[0] == 2 && store.balance(TownResourceCategory.STONE) == inactiveBalance,
                "with both players outside the founded region there must be no dispatch or debit");
        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(townKey);
        TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
        helper.assertTrue(dispatches[0] == 2 && reloadedStore.balance(TownResourceCategory.STONE) == inactiveBalance,
                "inactive save/load must not manufacture a dispatch or debit");

        first.teleportTo(activeX + .5, anchor.getY(), activeZ + .5);
        for (int tick = 0; tick < 8; tick++) {
            int before = reloadedStore.balance(TownResourceCategory.STONE);
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            if (before == reloadedStore.balance(TownResourceCategory.STONE)) break;
        }
        helper.assertTrue(level.getBlockState(foreign).is(Blocks.DIRT_PATH)
                        && !reloaded.isCellPlaced(level.dimension(), foreign, roadOwnerAt(level, foreign), signature),
                "foreign dirt-path work must remain unadopted after inactive reload and reactivation");
        for (int x = start.getX(); x <= start.getX() + 3; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
            BlockPos cell = new BlockPos(x, start.getY(), z);
            if (!cell.equals(foreign)) helper.assertTrue(reloaded.isCellPlaced(level.dimension(), cell, roadOwnerAt(level, cell), signature),
                    "reactivated road must complete every paid cell with its exact owner/signature: " + cell);
        }
        int finalBalance = reloadedStore.balance(TownResourceCategory.STONE);
        helper.assertTrue(inactiveBalance - finalBalance == 3,
                "reactivation must debit exactly three paid road cells after inactive reload");
        helper.assertTrue(1000 - finalBalance == 11,
                "reactivation must debit exactly three additional cells, for eleven paid cells total");
        TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == finalBalance,
                "completed reactivated road must not debit again on a no-op retry");
        } finally {
            // These are the only actors this fixture created.  Use PlayerList's normal lifecycle
            // removal rather than mutating a shared test roster or waiting for disconnect polling.
            removeOwnedMockPlayers(level, first, second);
        }
        helper.assertTrue(level.players().stream().noneMatch(player -> player.getUUID().equals(ownedUuids[0])
                        || player.getUUID().equals(ownedUuids[1])),
                "fixture cleanup must remove both UUID-owned players from the level roster");
        helper.assertTrue(level.getServer().getPlayerList().getPlayers().stream().noneMatch(player -> player.getUUID().equals(ownedUuids[0])
                        || player.getUUID().equals(ownedUuids[1])),
                "fixture cleanup must remove both UUID-owned players from the server player list");
        helper.assertTrue(unrelatedBefore.stream().allMatch(player -> level.players().stream()
                        .anyMatch(current -> current.getUUID().equals(player.getUUID()))),
                "fixture cleanup must preserve every pre-existing unrelated level player");
        helper.succeed();
    }

    /**
     * MINECRAFT-453: the activity-driven writer must leave a never-loaded remote road corridor
     * completely untouched, including across a storage round trip, then resume its normal paid
     * progress only once this fixture deliberately loads and prepares that exact corridor.
     *
     * <p>This is deliberately a never-loaded-to-loaded acceptance fixture, not a simulation of
     * actual chunk eviction, the full regional planner, or multi-player/network contention.
     */
    @GameTest(template = "settlement_test")
    public static void unloadedRoadCorridorDoesNotLoadOrChargeUntilExplicitlyLoaded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(ANCHOR);
        RegionCoord fixtureGridRegion = TownRegion.fromBlockCoords(anchor.getX(), anchor.getZ());
        // Keep both the player activity region and the two-cell corridor far outside this template
        // and the chunks used by neighbouring GameTest fixtures.
        RegionCoord region = new RegionCoord(fixtureGridRegion.x() + 200, fixtureGridRegion.z() + 200);
        String townKey = TownKeys.keyFor(level.dimension(), region);
        BlockPos start = new BlockPos(TownRegion.minBlockX(region) + 5, anchor.getY(), TownRegion.minBlockZ(region) + 5);
        List<TownRoadPlanner.RoadCell> route = List.of(
                new TownRoadPlanner.RoadCell(start.getX(), start.getZ(), start.getY()),
                new TownRoadPlanner.RoadCell(start.getX() + 1, start.getZ(), start.getY()));
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        TownStorage storage = new TownStorage();
        storage.markTownFounded(townKey);
        TownResourceStore store = storage.storeFor(townKey);
        store.credit(TownResourceCategory.STONE, 1000);
        int[] dispatches = {0};
        TownConstructionService.RoadWork work = (workLevel, selected, selectedStorage, selectedStore) -> {
            helper.assertTrue(selected.equals(region), "only the fixture's founded remote region may dispatch its road work");
            dispatches[0]++;
            return TownRoadConstructionService.buildRoadCells(workLevel, route, selectedStorage, selectedStore) > 0;
        };
        List<ServerPlayer> unrelatedBefore = List.copyOf(level.players());
        ServerPlayer player = null;
        java.util.UUID[] ownedUuids = new java.util.UUID[1];
        try {
            player = GameTestMockPlayers.create(helper);
            ownedUuids[0] = player.getUUID();
            player.teleportTo(TownRegion.minBlockX(region) + 20.5, anchor.getY(), TownRegion.minBlockZ(region) + 20.5);

            // Do not read a block, heightmap, or chunk before this guard: the test's first two
            // road ticks must prove the production guard itself declines this absent corridor.
            helper.assertTrue(!level.hasChunkAt(start), "remote corridor must start absent before the road writer touches it");
            TownConstructionService.runConstructionAndRoadsTick(level, storage, work);
            helper.assertTrue(dispatches[0] == 1 && !level.hasChunkAt(start),
                    "an absent corridor may be dispatched but must neither load nor place road work");
            helper.assertTrue(store.balance(TownResourceCategory.STONE) == 1000,
                    "an absent corridor must not debit stone");
            for (int x = start.getX(); x <= start.getX() + 1; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, start.getY(), z);
                helper.assertTrue(!storage.isCellPlaced(level.dimension(), cell, roadOwnerAt(level, cell), signature),
                        "an absent corridor must not create owned road progress: " + cell);
            }

            TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            TownResourceStore reloadedStore = reloaded.storeFor(townKey);
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            helper.assertTrue(dispatches[0] == 2 && !level.hasChunkAt(start)
                            && reloadedStore.balance(TownResourceCategory.STONE) == 1000,
                    "an absent corridor retry after save/load must neither load nor debit");
            for (int x = start.getX(); x <= start.getX() + 1; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, start.getY(), z);
                helper.assertTrue(!reloaded.isCellPlaced(level.dimension(), cell, roadOwnerAt(level, cell), signature),
                        "an absent corridor retry after save/load must not create owned road progress: " + cell);
            }

            // This is the fixture's only deliberate load of its remote corridor.  Now that it is
            // present, prepare the supported natural terrain the real writer requires.
            level.getChunkAt(start);
            for (int x = start.getX(); x <= start.getX() + 1; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, start.getY(), z);
                level.setBlock(cell.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(cell, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            helper.assertTrue(1000 - reloadedStore.balance(TownResourceCategory.STONE) == TownConstants.ROAD_BLOCKS_PER_TICK,
                    "the first loaded tick must debit exactly its four-cell road budget");
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            helper.assertTrue(1000 - reloadedStore.balance(TownResourceCategory.STONE) == 6,
                    "the second loaded tick must debit exactly the remaining two corridor cells");
            for (int x = start.getX(); x <= start.getX() + 1; x++) for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, start.getY(), z);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.DIRT_PATH)
                                && reloaded.isCellPlaced(level.dimension(), cell, roadOwnerAt(level, cell), signature),
                        "explicitly loaded corridor must finish each paid owned road cell: " + cell);
            }
            int completedBalance = reloadedStore.balance(TownResourceCategory.STONE);
            TownConstructionService.runConstructionAndRoadsTick(level, reloaded, work);
            helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == completedBalance,
                    "a completed loaded corridor retry must not debit again");
        } finally {
            // This fixture owns only this one actor; leave all shared GameTest actors untouched.
            removeOwnedMockPlayers(level, player, null);
        }
        helper.assertTrue(level.players().stream().noneMatch(current -> current.getUUID().equals(ownedUuids[0])),
                "fixture cleanup must remove its UUID-owned player from the level roster");
        helper.assertTrue(level.getServer().getPlayerList().getPlayers().stream()
                        .noneMatch(current -> current.getUUID().equals(ownedUuids[0])),
                "fixture cleanup must remove its UUID-owned player from the server player list");
        helper.assertTrue(unrelatedBefore.stream().allMatch(previous -> level.players().stream()
                        .anyMatch(current -> current.getUUID().equals(previous.getUUID()))),
                "fixture cleanup must preserve every pre-existing unrelated level player");
        helper.succeed();
    }

    /**
     * The lifecycle fixture can land at any GameTest-world coordinate.  Drive the real road writer
     * across a deterministic negative boundary: {@code -385} is in region {@code -2}, whereas
     * {@code -384} is in region {@code -1}.  The writer's minor-width corridor spans both, then
     * resumes from saved progress, so a single region owner cannot satisfy these assertions.
     */
    @GameTest(template = "settlement_test")
    public static void paidRoadProgressUsesEachCellsOwnRegionAcrossANegativeBoundary(GameTestHelper helper) {
        TownStorage storage = new TownStorage();
        ServerLevel level = helper.getLevel();
        int roadY = 2;
        BlockPos westStart = new BlockPos(-385, roadY, -385);
        BlockPos eastEnd = new BlockPos(-384, roadY, -385);
        for (int x = westStart.getX(); x <= eastEnd.getX(); x++) {
            for (int z = westStart.getZ() - 1; z <= westStart.getZ() + 1; z++) {
                BlockPos surface = new BlockPos(x, roadY, z);
                // These are outside the randomly placed tiny GameTest structure.  Loading the
                // exact two chunks first keeps the production writer's hasChunkAt guard active.
                level.getChunkAt(surface);
                level.setBlock(surface.below(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(surface, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(surface.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(surface.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        String signature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        String westOwner = roadOwnerAt(level, westStart);
        String eastOwner = roadOwnerAt(level, eastEnd);
        String storeKey = TownKeys.keyFor(level.dimension(), TownRegion.fromBlockCoords(westStart.getX(), westStart.getZ()));
        TownResourceStore store = storage.storeFor(storeKey);
        store.credit(TownResourceCategory.STONE, 1000);

        helper.assertTrue(TownRegion.fromBlockCoords(westStart.getX(), westStart.getZ())
                        .equals(new RegionCoord(-2, -2)),
                "negative coordinates below a 384-block boundary must floor into region -2");
        helper.assertTrue(TownRegion.fromBlockCoords(eastEnd.getX(), eastEnd.getZ() + 1)
                        .equals(new RegionCoord(-1, -1)),
                "the eastern/northern corridor cell must cross both negative region boundaries");
        helper.assertTrue(!westOwner.equals(eastOwner),
                "adjacent paid road cells across a negative boundary must have different region owners");

        List<TownRoadPlanner.RoadCell> route = List.of(
                new TownRoadPlanner.RoadCell(westStart.getX(), westStart.getZ(), roadY),
                new TownRoadPlanner.RoadCell(eastEnd.getX(), eastEnd.getZ(), roadY));
        int firstPlaced = TownRoadConstructionService.buildRoadCells(level, route, storage, store);
        helper.assertTrue(firstPlaced == TownConstants.ROAD_BLOCKS_PER_TICK,
                "the real writer must spend the first bounded tick on exactly its road budget");
        int balanceBeforeReload = store.balance(TownResourceCategory.STONE);

        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(storeKey);
        int resumedPlaced = TownRoadConstructionService.buildRoadCells(level, route, reloaded, reloadedStore);
        helper.assertTrue(resumedPlaced == 2,
                "save/load retry must finish exactly the two remaining cross-boundary road cells");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE)
                        == balanceBeforeReload - resumedPlaced * TownConstants.ROAD_COST_PER_BLOCK,
                "only the two newly written post-reload cells may be debited");
        for (int x = westStart.getX(); x <= eastEnd.getX(); x++) {
            for (int z = westStart.getZ() - 1; z <= westStart.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, roadY, z);
                String expectedOwner = roadOwnerAt(level, cell);
                helper.assertTrue(reloaded.isCellPlaced(level.dimension(), cell, expectedOwner, signature),
                        "the real writer must persist this cross-boundary cell under its own region owner: cell="
                                + cell + ", expectedOwner=" + expectedOwner);
            }
        }
        int finalBalance = reloadedStore.balance(TownResourceCategory.STONE);
        helper.assertTrue(TownRoadConstructionService.buildRoadCells(level, route, reloaded, reloadedStore) == 0,
                "an already completed cross-boundary route must be a no-op retry");
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == finalBalance,
                "a no-op cross-boundary retry must not charge persisted cells twice");
        helper.succeed();
    }

    /**
     * MINECRAFT-453: the road's lifetime is independent from the single building that originally
     * founded the town.  This deliberately uses a short, controlled route because a real
     * seed-derived region route can lie outside the small disposable GameTest template; the work
     * it drives is nevertheless {@link TownRoadConstructionService}'s real paid-progress writer.
     *
     * <p>The sequence is the production-relevant failure boundary: a real Builder completes and
     * releases its campfire, roads pause with no stone, a foreign dirt path is refused rather than
     * adopted, then paid progress is saved/loaded and subsequent road ticks finish the remaining
     * route without recharging the persisted cells.
     */
    @GameTest(template = "settlement_test")
    public static void roadsResumeAfterTheOnlyBuilderSiteFinishesAcrossFundsAndStorageReload(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absoluteCampfire, 100000);
        buildUntilHandedOff(helper, absoluteCampfire, claimant, storage);
        helper.assertTrue(!helper.getBlockState(campfireCell).is(Blocks.CAMPFIRE),
                "test setup requires the sole site's campfire to be replaced before roads run");
        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "test setup requires the sole Builder to be released before roads run");

        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        String townKey = TownKeys.keyFor(helper.getLevel().dimension(), region);
        TownResourceStore store = storage.storeFor(townKey);
        int stone = store.balance(TownResourceCategory.STONE);
        helper.assertTrue(storage.tryDebitStore(townKey, TownResourceCategory.STONE, stone),
                "test setup must drain the site's remaining stone so the road genuinely pauses");

        BlockPos routeStart = helper.absolutePos(new BlockPos(1, 8, 1));
        BlockPos foreignPath = routeStart.offset(1, 0, 0);
        helper.getLevel().setBlock(foreignPath, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        List<TownRoadPlanner.RoadCell> route = List.of(
                new TownRoadPlanner.RoadCell(routeStart.getX(), routeStart.getZ(), routeStart.getY()),
                new TownRoadPlanner.RoadCell(foreignPath.getX(), foreignPath.getZ(), foreignPath.getY()),
                new TownRoadPlanner.RoadCell(routeStart.getX() + 2, routeStart.getZ(), routeStart.getY()),
                new TownRoadPlanner.RoadCell(routeStart.getX() + 3, routeStart.getZ(), routeStart.getY()));
        String roadSignature = "road:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(Blocks.DIRT_PATH)
                + ":" + TownResourceCategory.STONE.name();
        int[] driverCalls = {0};
        TownConstructionService.RoadWork boundedRoadWork = (level, selectedRegion, selectedStorage, selectedStore) -> {
            driverCalls[0]++;
            return TownRoadConstructionService.buildRoadCells(level, route, selectedStorage, selectedStore) > 0;
        };
        storage.markTownFounded(townKey);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.teleportTo(absoluteCampfire.getX() + 0.5, absoluteCampfire.getY(), absoluteCampfire.getZ() + 0.5);

        TownConstructionService.runConstructionAndRoadsTick(helper.getLevel(), storage, boundedRoadWork);
        helper.assertTrue(driverCalls[0] == 1,
                "the real tick must activate roads for the nearby player in the founded region even after the sole campfire is gone");
        helper.assertTrue(store.balance(TownResourceCategory.STONE) == 0,
                "roads must pause without stone after the sole Builder/campfire is gone");
        helper.assertTrue(helper.getLevel().getBlockState(foreignPath).is(Blocks.DIRT_PATH),
                "a foreign dirt path must not be adopted or changed while the road is paused");

        storage.creditStore(townKey, TownResourceCategory.STONE, 1000);
        TownConstructionService.runConstructionAndRoadsTick(helper.getLevel(), storage, boundedRoadWork);
        int firstTick = 1000 - store.balance(TownResourceCategory.STONE);
        helper.assertTrue(firstTick == TownConstants.ROAD_BLOCKS_PER_TICK,
                "funded road work must make bounded progress after the Builder/campfire lifecycle, found " + firstTick);
        helper.assertTrue(helper.getLevel().getBlockState(foreignPath).is(Blocks.DIRT_PATH),
                "a foreign dirt path must remain protected rather than become free road progress");
        helper.assertTrue(!storage.isCellPlaced(helper.getLevel().dimension(), foreignPath,
                        roadOwnerAt(helper.getLevel(), foreignPath), roadSignature),
                "a foreign dirt path must have no paid ownership record for its own region before reload");
        int balanceBeforeReload = store.balance(TownResourceCategory.STONE);

        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
        TownResourceStore reloadedStore = reloaded.storeFor(townKey);
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == balanceBeforeReload,
                "paid road progress and its balance must survive TownStorage save/load");
        helper.assertTrue(!reloaded.isCellPlaced(helper.getLevel().dimension(), foreignPath,
                        roadOwnerAt(helper.getLevel(), foreignPath), roadSignature),
                "save/load must not invent a paid ownership record for the foreign dirt path's own region");

        int resumed = 0;
        for (int tick = 0; tick < 8; tick++) {
            int before = reloadedStore.balance(TownResourceCategory.STONE);
            TownConstructionService.runConstructionAndRoadsTick(helper.getLevel(), reloaded, boundedRoadWork);
            int placed = before - reloadedStore.balance(TownResourceCategory.STONE);
            resumed += placed;
            if (placed == 0) {
                break;
            }
        }
        helper.assertTrue(resumed == 7,
                "later road-builder ticks must resume and finish the seven remaining buildable cells after reload, found " + resumed);
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == balanceBeforeReload - resumed * TownConstants.ROAD_COST_PER_BLOCK,
                "only newly placed post-reload cells may be charged; persisted paid progress must not be charged again");
        helper.assertTrue(helper.getLevel().getBlockState(foreignPath).is(Blocks.DIRT_PATH),
                "reload/retry must never adopt the foreign dirt path as this road's paid progress");
        helper.assertTrue(!reloaded.isCellPlaced(helper.getLevel().dimension(), foreignPath,
                        roadOwnerAt(helper.getLevel(), foreignPath), roadSignature),
                "the completed route must still not claim the foreign dirt path as paid work in its own region");
        for (int x = routeStart.getX(); x <= routeStart.getX() + 3; x++) {
            for (int z = routeStart.getZ() - 1; z <= routeStart.getZ() + 1; z++) {
                BlockPos cell = new BlockPos(x, routeStart.getY(), z);
                if (cell.equals(foreignPath)) {
                    continue;
                }
                String expectedOwner = roadOwnerAt(helper.getLevel(), cell);
                helper.assertTrue(reloaded.isCellPlaced(helper.getLevel().dimension(), cell, expectedOwner, roadSignature),
                        "every buildable road cell must finish with its own region's exact paid owner/signature: cell="
                                + cell + ", region=" + TownRegion.fromBlockCoords(cell.getX(), cell.getZ())
                                + ", expectedOwner=" + expectedOwner);
            }
        }
        int finalBalance = reloadedStore.balance(TownResourceCategory.STONE);
        TownConstructionService.runConstructionAndRoadsTick(helper.getLevel(), reloaded, boundedRoadWork);
        helper.assertTrue(reloadedStore.balance(TownResourceCategory.STONE) == finalBalance,
                "a no-op retry must not debit the already completed paid route");
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
     * MINECRAFT-388 review round 6, finding #2: a campfire strictly BETWEEN the planned floor and
     * roof at the template's own CENTER column (never a wall - walls are perimeter-only) is a
     * genuinely unplanned interior cell. Its real final state is AIR, the same as the above-roof
     * case {@code elevatedCampfireAboveThePlannedRoofEndsAsAirNotAFloatingWallBlock} already covers
     * - this proves the WITHIN-volume case is not mistakenly given wall material instead.
     */
    @GameTest(template = "settlement_test")
    public static void interiorCentreCampfireBetweenFloorAndRoofEndsAsAirNotAWallBlock(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        // A short 2-tall trunk: the campfire sits two cells above the real (tree-excluded) floor,
        // comfortably below any kind's planned roof (height 4-7, per heightFor) - interior, not
        // above the roof.
        helper.setBlock(campfireCell, Blocks.OAK_LOG);
        helper.setBlock(campfireCell.above(), Blocks.OAK_LOG);
        BlockPos interiorCampfireCell = campfireCell.above(2);
        helper.setBlock(interiorCampfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(interiorCampfireCell);
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absoluteCampfire, 100000);

        for (int i = 0; i < 500 && helper.getBlockState(interiorCampfireCell).is(Blocks.CAMPFIRE); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }

        helper.assertTrue(helper.getBlockState(interiorCampfireCell).isAir(),
                "an interior centre-column campfire between the floor and roof must end as AIR, found "
                        + helper.getBlockState(interiorCampfireCell));
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
     * MINECRAFT-388 review round 6, finding #1: the campfire-column exemption is identity-specific
     * - matched by the EXACT claimant UUID {@link TownConstructionService} selected, never merely
     * "any villager whose JOB_SITE memory happens to match". A second (duplicate/stale-memory)
     * villager also standing in the column, even with an identical JOB_SITE memory, is NOT the
     * selected claimant and must still read as blocking.
     */
    @GameTest(template = "settlement_test")
    public static void isEntityBlockingOnlyExemptsTheExactClaimantByIdentityNotJustByJobSiteMatch(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR;
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);

        Villager realClaimant = spawnClaimedVillager(helper, absoluteCampfire);
        helper.assertTrue(
                !TownSitePreflight.isEntityBlocking(helper.getLevel(), absoluteCampfire, absoluteCampfire, realClaimant.getUUID()),
                "the exact selected claimant must be exempt when it is the only entity present");

        // A second villager with the SAME (erroneous/duplicate) JOB_SITE memory, standing in the
        // same column - not the entity TownConstructionService actually selected as the claimant.
        Villager duplicate = spawnClaimedVillager(helper, absoluteCampfire);

        helper.assertTrue(
                TownSitePreflight.isEntityBlocking(helper.getLevel(), absoluteCampfire, absoluteCampfire, realClaimant.getUUID()),
                "a second villager with a matching JOB_SITE memory, but a DIFFERENT identity than the exact selected claimant, must still block");
        helper.assertTrue(duplicate.isAlive(), "sanity: the duplicate villager is actually present");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 5, finding #1 / round 6, finding #1: a villager whose JOB_SITE
     * memory points at this exact campfire position but in a DIFFERENT dimension is a stale/foreign
     * bystander, never this site's own claimant - exercised here against a real claimant's own
     * identity, so the fix is proven to be the identity check, not an incidental side effect of a
     * now-removed dimension check.
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
        Villager realClaimant = spawnClaimedVillager(helper, absoluteCampfire);

        helper.assertTrue(TownSitePreflight.isEntityBlocking(helper.getLevel(), absoluteCampfire, absoluteCampfire, realClaimant.getUUID()),
                "a villager whose JOB_SITE memory points here but in a DIFFERENT dimension (and whose identity is not the real claimant) must block");
        helper.succeed();
    }

    /** [Review round 21 follow-up] {@code TownStorage#creditStore}/{@code tryDebitStore} are the
     *  SOLE mutation path for resource balances precisely because {@link TownResourceStore} itself
     *  has no reference back to its owning {@code TownStorage} and so cannot mark it dirty on its
     *  own - donation, passive-production, and construction credits/debits that went straight
     *  through the raw store (the pre-round-21-follow-up pattern) could silently never persist.
     *  Proves both halves of that contract: the dirty flag actually flips on a real mutation (and
     *  stays clear on a declined one), and the balance itself genuinely survives a real save/load
     *  round trip, not merely an in-memory check. */
    @GameTest(template = "settlement_test")
    public static void creditAndDebitStoreMarkTheStorageDirtyAndSurviveASaveLoadRoundTrip(GameTestHelper helper) {
        TownStorage storage = new TownStorage();
        String townKey = "test-town-key";
        helper.assertTrue(!storage.isDirty(), "test setup: a fresh TownStorage must not start out dirty");

        storage.creditStore(townKey, TownResourceCategory.STONE, 10);
        helper.assertTrue(storage.isDirty(), "creditStore must mark the storage dirty");
        helper.assertTrue(storage.storeFor(townKey).balance(TownResourceCategory.STONE) == 10,
                "creditStore must actually credit the balance");

        storage.setDirty(false);
        boolean debited = storage.tryDebitStore(townKey, TownResourceCategory.STONE, 4);
        helper.assertTrue(debited, "test setup: the debit must actually succeed");
        helper.assertTrue(storage.isDirty(), "a successful tryDebitStore must mark the storage dirty");

        storage.setDirty(false);
        boolean declinedDebit = storage.tryDebitStore(townKey, TownResourceCategory.STONE, 1000);
        helper.assertTrue(!declinedDebit, "test setup: this debit must actually be declined (insufficient funds)");
        helper.assertTrue(!storage.isDirty(), "a DECLINED tryDebitStore must never mark the storage dirty - nothing actually changed");

        CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.storeFor(townKey).balance(TownResourceCategory.STONE) == 6,
                "the credited-then-debited balance (10 - 4 = 6) must survive a real save/load round trip");
        helper.succeed();
    }

    // ---------------------------------------------------------------------------------------
    // Review round 25 regressions (retained fixture-provenance and takeover-lifecycle items)
    // ---------------------------------------------------------------------------------------

    private static Villager spawnBuilderClaimant(GameTestHelper helper, BlockPos absoluteCampfire) {
        Villager claimant = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(claimant);
        claimant.setVillagerData(claimant.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        claimant.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));
        return claimant;
    }

    /** A villager standing in the prepared area's corner - well outside every footprint, so it never
     *  blocks construction. */
    private static Villager spawnBystander(GameTestHelper helper) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, helper.absolutePos(new BlockPos(0, 2, 0)),
                MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        return villager;
    }

    /** The kind a site at {@code absoluteCampfire} resolves to, decided exactly as
     *  {@link TownConstructionService#buildAt} decides it. */
    private static TownBuildingTemplates.Kind siteKind(GameTestHelper helper, BlockPos absoluteCampfire) {
        RegionCoord region = TownRegion.fromBlockCoords(absoluteCampfire.getX(), absoluteCampfire.getZ());
        TownPlan plan = TownPlanner.generate(region, helper.getLevel().getSeed());
        boolean isStorehouseSite = Math.abs(absoluteCampfire.getX() - plan.townCenterX()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH
                && Math.abs(absoluteCampfire.getZ() - plan.townCenterZ()) <= TownConstants.STOREHOUSE_FOOTPRINT_WIDTH;
        return isStorehouseSite ? TownBuildingTemplates.Kind.STOREHOUSE
                : TownBuildingTemplates.kindFor(plan.zoneAt(absoluteCampfire.getX(), absoluteCampfire.getZ()));
    }

    private static void buildUntilHandedOff(GameTestHelper helper, BlockPos absoluteCampfire, Villager claimant, TownStorage storage) {
        for (int i = 0; i < 500 && claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(); i++) {
            TownConstructionService.buildAt(helper.getLevel(), absoluteCampfire, storage);
        }
    }

    /** Puts a player's own fixture at {@code pos} - never through a Dynamic Towns plan - and brings
     *  its POI record in step at once (see {@link #registerAndTakeTicket} for why the placement hook
     *  cannot be relied on within the same test call). {@code UPDATE_KNOWN_SHAPE} keeps a half-swapped
     *  bed from breaking its other half while the two halves are replaced one at a time. */
    private static void placePlayerFixture(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        net.minecraft.world.entity.ai.village.poi.PoiManager poiManager = level.getPoiManager();
        java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> type =
                net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(state);
        java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> registered = poiManager.getType(pos);
        if (registered.isPresent() && !registered.equals(type)) {
            poiManager.remove(pos);
        }
        type.ifPresent(t -> {
            if (poiManager.getType(pos).isEmpty()) {
                poiManager.add(pos, t);
            }
        });
    }

    private static void placePlayerBed(ServerLevel level, BlockPos foot, Direction facing, Block bed) {
        BlockState footState = bed.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT);
        BlockState headState = bed.defaultBlockState().setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.HEAD);
        placePlayerFixture(level, foot, footState);
        placePlayerFixture(level, foot.relative(facing), headState);
    }

    /**
     * [Review round 25, P2] Provenance from a REAL completed building's own production records: a
     * player's bed laid across two of the building's paid north-wall cells, and a player's blast
     * furnace on one of its paid floor cells, must never be reserved by resident allocation - even
     * after those records go through a save/load round trip - while the building's own bed and
     * workstation, once vacated, stay eligible from the very same records.
     */
    @GameTest(template = "settlement_test")
    public static void aPlayerFixtureOnAPaidWallOrFloorCellOfARealBuildingIsNeverReservable(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        Villager claimant = spawnBuilderClaimant(helper, absoluteCampfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absoluteCampfire, 100000);
        TownBuildingTemplates.Kind kind = siteKind(helper, absoluteCampfire);
        buildUntilHandedOff(helper, absoluteCampfire, claimant, storage);
        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "test setup: the site never finished (kind=" + kind + ")");

        int cx = absoluteCampfire.getX();
        int cz = absoluteCampfire.getZ();
        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        int radius = ((kind == TownBuildingTemplates.Kind.STOREHOUSE ? TownConstants.STOREHOUSE_FOOTPRINT_WIDTH : 5) - 1) / 2;
        Block wall = TownBuildingTemplates.wallBlockFor(kind);
        BlockPos bedFoot = new BlockPos(cx, floorY + 1, cz - radius);
        BlockPos bedHead = bedFoot.east();
        BlockPos floorCell = new BlockPos(cx + 1, floorY, cz - 1);
        helper.assertTrue(level.getBlockState(bedFoot).is(wall) && level.getBlockState(bedHead).is(wall)
                        && level.getBlockState(floorCell).is(wall),
                "test setup: the cells the player replaces must be the building's own paid wall/floor cells");
        placePlayerBed(level, bedFoot, Direction.EAST, Blocks.RED_BED);
        placePlayerFixture(level, floorCell, Blocks.BLAST_FURNACE.defaultBlockState());

        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        RegionCoord region = TownRegion.fromBlockCoords(cx, cz);
        Villager resident = spawnBystander(helper);
        resident.setVillagerData(resident.getVillagerData().setProfession(VillagerProfession.NONE));

        TownResidentAllocationHandler.allocateHomeIfNeeded(level, resident, absoluteCampfire, region, reloaded);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, resident, absoluteCampfire, region, reloaded);
        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "a player's bed on the building's paid wall cells must never be reserved as town housing");
        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty()
                        && resident.getVillagerData().getProfession() == VillagerProfession.NONE,
                "a player's workstation on the building's paid floor cell must never be reserved as town employment");
        helper.assertTrue(level.getPoiManager().getFreeTickets(bedHead) > 0 && level.getPoiManager().getFreeTickets(floorCell) > 0,
                "both player fixtures must be left unreserved");

        if (kind == TownBuildingTemplates.Kind.STOREHOUSE) {
            helper.succeed(); // no bed or workstation of its own to vacate this run; other slots cover it
            return;
        }
        // The building's own fixtures, once vacated, stay eligible from the same production records.
        BlockPos townBed = TownBuildingTemplates.bedPosition(cx, floorY, cz);
        claimant.getBrain().eraseMemory(MemoryModuleType.HOME);
        level.getPoiManager().release(townBed);
        java.util.Optional<VillagerProfession> finalProfession = TownProfessionWorkstations.finalProfessionFor(kind, level.getSeed(), cx, cz);
        BlockPos townWorkstation = TownBuildingTemplates.workstationPosition(cx, floorY, cz);
        if (finalProfession.isPresent()) {
            claimant.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            level.getPoiManager().release(townWorkstation);
        }
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, resident, absoluteCampfire, region, reloaded);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, resident, absoluteCampfire, region, reloaded);
        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(townBed)).orElse(false),
                "the building's own unchanged bed must remain eligible");
        if (finalProfession.isPresent()) {
            helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(townWorkstation)).orElse(false)
                            && resident.getVillagerData().getProfession() == finalProfession.get(),
                    "the building's own unchanged workstation must remain eligible");
        }
        helper.succeed();
    }

    /** [Review round 25, P2] The road service's own production records: a player's bed laid across
     *  two paved, paid road cells must never be reserved as town housing. */
    @GameTest(template = "settlement_test")
    public static void aPlayerBedOnPaidRoadCellsIsNeverReservable(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        List<TownRoadPlanner.RoadCell> cells = List.of(
                new TownRoadPlanner.RoadCell(absoluteAnchor.getX(), absoluteAnchor.getZ(), absoluteAnchor.getY()),
                new TownRoadPlanner.RoadCell(absoluteAnchor.getX() + 1, absoluteAnchor.getZ(), absoluteAnchor.getY()));
        TownStorage storage = new TownStorage();
        TownResourceStore store = storage.storeFor("test:road-provenance");
        store.credit(TownResourceCategory.STONE, 1000);
        for (int i = 0; i < 4; i++) {
            TownRoadConstructionService.buildRoadCells(level, cells, storage, store); // bounded per call
        }
        helper.assertTrue(helper.getBlockState(ANCHOR).is(Blocks.DIRT_PATH) && helper.getBlockState(ANCHOR.east()).is(Blocks.DIRT_PATH),
                "test setup: both route cells must have been paved and paid for by the road service");

        placePlayerBed(level, absoluteAnchor, Direction.EAST, Blocks.RED_BED);
        BlockPos bedHead = absoluteAnchor.east();
        Villager resident = spawnBystander(helper);
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, resident, absoluteAnchor,
                TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ()), storage);

        helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                        && level.getPoiManager().getFreeTickets(bedHead) > 0,
                "a player's bed on paid road cells must never be reserved as town housing");
        helper.succeed();
    }

    /**
     * [Review round 25, P2] The claimant handoff never reserves a player's fixture either. The
     * moment the build places its planned bed (and, for a workplace, its planned workstation), a
     * player swaps in a different fixture of their own at the same cell. The build refuses to
     * overwrite that foreign content and the handoff only ever reserves the plan's own fixture, so
     * the site never closes onto it: the claimant stays a Builder on its intact campfire, and the
     * swapped-in fixtures stay unreserved.
     */
    @GameTest(template = "settlement_test")
    public static void aHandoffNeverReservesAPlayerFixtureSwappedIntoAPlannedFixtureCell(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        Villager claimant = spawnBuilderClaimant(helper, absoluteCampfire);
        TownStorage storage = new TownStorage();
        fundStore(helper, storage, absoluteCampfire, 100000);
        TownBuildingTemplates.Kind kind = siteKind(helper, absoluteCampfire);
        if (kind == TownBuildingTemplates.Kind.STOREHOUSE) {
            helper.succeed(); // no planned fixture to swap this run; other slots cover it
            return;
        }
        int cx = absoluteCampfire.getX();
        int cz = absoluteCampfire.getZ();
        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        BlockPos bedPos = TownBuildingTemplates.bedPosition(cx, floorY, cz);
        java.util.Optional<VillagerProfession> finalProfession = TownProfessionWorkstations.finalProfessionFor(kind, level.getSeed(), cx, cz);
        BlockPos workstationPos = finalProfession.isPresent() ? TownBuildingTemplates.workstationPosition(cx, floorY, cz) : null;
        Block plannedWorkstation = finalProfession.flatMap(TownProfessionWorkstations::blockFor).orElse(null);
        Block swappedWorkstation = plannedWorkstation == Blocks.COMPOSTER ? Blocks.BARREL : Blocks.COMPOSTER;

        // Both fixtures are swapped together, the moment the bed (planned after the workstation, in
        // the same layer) appears - a swapped fixture stalls the build at its own cell, so swapping
        // the workstation first would stop the bed from ever being placed.
        boolean swapped = false;
        for (int i = 0; i < 300; i++) {
            TownConstructionService.buildAt(level, absoluteCampfire, storage);
            if (!swapped && level.getBlockState(bedPos).is(Blocks.RED_BED)
                    && (workstationPos == null || level.getBlockState(workstationPos).is(plannedWorkstation))) {
                placePlayerBed(level, bedPos.north(), Direction.SOUTH, Blocks.BLUE_BED);
                if (workstationPos != null) {
                    placePlayerFixture(level, workstationPos, swappedWorkstation.defaultBlockState());
                }
                swapped = true;
            }
        }

        helper.assertTrue(swapped, "test setup: the planned fixtures were never placed in time to swap them");
        helper.assertTrue(claimant.getVillagerData().getProfession() == BuilderProfession.BUILDER.get()
                        && claimant.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(absoluteCampfire)).orElse(false)
                        && helper.getBlockState(ANCHOR).is(Blocks.CAMPFIRE),
                "the site must never close (and hand off) onto a swapped-in player fixture");
        helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> !gp.pos().equals(bedPos)).orElse(true)
                        && level.getPoiManager().getFreeTickets(bedPos) > 0,
                "the player's bed must never be reserved for the claimant");
        if (workstationPos != null) {
            helper.assertTrue(level.getPoiManager().getFreeTickets(workstationPos) > 0,
                    "the player's workstation must never be reserved for the claimant");
        }
        claimant.discard(); // leave no live claimed campfire behind for the real periodic construction tick
        helper.succeed();
    }

    /**
     * [Review round 25] Handoff counterpart of the resident-allocation takeover regressions: the
     * claimant's old home P is still recorded as the claimant's, but vanilla has since re-granted
     * P's ticket to a different, still-loaded villager B. When the finished site hands the claimant
     * its new bed, B's live claim must veto the old-home release, the contradicted record must be
     * dropped (recording nobody in its place), and B's ticket and memory must be left intact.
     */
    @GameTest(template = "settlement_test")
    public static void aHandoffNeverReleasesAnOldHomeAnotherVillagerGenuinelyTookOver(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        TownBuildingTemplates.Kind kind = siteKind(helper, absoluteCampfire);
        if (kind == TownBuildingTemplates.Kind.STOREHOUSE) {
            helper.succeed(); // no bed of its own, so HOME is never touched (anExistingHomeIsNeverLeakedOrErasedWithoutAnActualReplacement)
            return;
        }
        Villager claimant = spawnBuilderClaimant(helper, absoluteCampfire);
        OwnedStandaloneBedFixture oldHomeFixture = placeOwnedStandaloneBedFixture(helper);
        BlockPos absoluteOldHome = oldHomeFixture.head();
        claimant.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), absoluteOldHome));
        TownStorage storage = new TownStorage();
        Throwable failure = null;
        try {
        storage.setReservationOwner(level.dimension(), absoluteOldHome, claimant.getUUID());
        oldHomeFixture.trackReservationOwner(storage, level.dimension(), claimant.getUUID());

        // Vanilla releases it and re-grants it to B, who stays loaded.
        oldHomeFixture.releaseFixtureTicketForRegrant();
        Villager b = spawnBystander(helper);
        oldHomeFixture.takeTicketForTestOwner(b.getUUID());
        b.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), absoluteOldHome));

        fundStore(helper, storage, absoluteCampfire, 100000);
        buildUntilHandedOff(helper, absoluteCampfire, claimant, storage);
        helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "test setup: the site never finished (kind=" + kind + ")");

        int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
        BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
        helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(expectedBed)).orElse(false),
                "the claimant must be handed its new bed");
        helper.assertTrue(level.getPoiManager().getFreeTickets(absoluteOldHome) == 0,
                "B's real, current ticket must never be released on the strength of the claimant's stale record");
        helper.assertTrue(b.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(absoluteOldHome)).orElse(false),
                "B's own HOME memory must be unaffected by the handoff");
        helper.assertTrue(!storage.isReservedBySelf(level.dimension(), absoluteOldHome, claimant.getUUID())
                        && !storage.isReservedBySelf(level.dimension(), absoluteOldHome, b.getUUID()),
                "the contradicted record must be dropped, and B must not be recorded in its place");
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            finishOwnedBedFixture(oldHomeFixture, failure);
        }
        helper.succeed();
    }

    /**
     * [Review round 25, retained P1] The handoff with the takeover owner UNLOADED before the site
     * completes - the case a live veto cannot see. B (holding the claimant's old home P after a
     * vanilla re-grant) unloads through the real removal path, firing the registered
     * {@code EntityLeaveLevelEvent} listener against the real server-wide {@link TownStorage}; that
     * storage is saved and loaded back (a restart) and the site completes against the reloaded copy;
     * B is then loaded back from its saved form. P's ticket must survive every step, and the reloaded
     * B must still be validly housed there - a vacant town-built bed beside P stays untouched when B
     * reconciles, which it would not if B's reservation were judged invalid.
     */
    @GameTest(template = "settlement_test")
    public static void aHandoffPreservesAnOldHomeWhoseTakeoverOwnerUnloadedAcrossSaveAndReload(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        helper.setBlock(ANCHOR, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(ANCHOR);
        Villager claimant = spawnBuilderClaimant(helper, absoluteCampfire);
        OwnedStandaloneBedFixture oldHomeFixture = placeOwnedStandaloneBedFixture(helper);
        BlockPos absoluteOldHome = oldHomeFixture.head();
        claimant.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), absoluteOldHome));
        TownStorage real = TownStorage.of(level.getServer());
        Throwable failure = null;
        try {
        real.setReservationOwner(level.dimension(), absoluteOldHome, claimant.getUUID());
        oldHomeFixture.trackReservationOwner(real, level.dimension(), claimant.getUUID());

        oldHomeFixture.releaseFixtureTicketForRegrant();
        Villager b = spawnBystander(helper);
        oldHomeFixture.takeTicketForTestOwner(b.getUUID());
        b.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), absoluteOldHome));

        // B unloads before anything has reconciled it.
        CompoundTag savedB = new CompoundTag();
        helper.assertTrue(b.save(savedB), "test setup: B must serialize like a chunk save would");
        boolean wasDirty = real.isDirty();
        real.setDirty(false);
        b.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        boolean dirtiedByUnload = real.isDirty();
        real.setDirty(wasDirty || dirtiedByUnload);
        helper.assertTrue(level.getEntity(b.getUUID()) == null, "test setup: an unloaded B must be invisible to the live scan");
        helper.assertTrue(!real.isReservedBySelf(level.dimension(), absoluteOldHome, claimant.getUUID())
                        && !real.isReservedBySelf(level.dimension(), absoluteOldHome, b.getUUID()),
                "B unloading must drop the record its memory contradicts, recording nobody in its place");
        helper.assertTrue(dirtiedByUnload, "dropping the record must mark the storage dirty, or a real save could skip it");

        TownStorage reloaded = TownStorage.load(real.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        helper.assertTrue(!reloaded.isReservedBySelf(level.dimension(), absoluteOldHome, claimant.getUUID()),
                "the dropped record must stay dropped across a save/load round trip");
        TownBuildingTemplates.Kind kind = siteKind(helper, absoluteCampfire);
        if (kind == TownBuildingTemplates.Kind.STOREHOUSE) {
            claimant.discard();
        } else {
            fundStore(helper, reloaded, absoluteCampfire, 100000);
            buildUntilHandedOff(helper, absoluteCampfire, claimant, reloaded);
            helper.assertTrue(claimant.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                    "test setup: the site never finished (kind=" + kind + ")");
            int floorY = TownTerrain.estimateGroundHeightBelow(level, absoluteCampfire);
            BlockPos expectedBed = TownBuildingTemplates.bedPosition(absoluteCampfire.getX(), floorY, absoluteCampfire.getZ());
            helper.assertTrue(claimant.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(expectedBed)).orElse(false),
                    "the claimant must be handed its new bed");
            helper.assertTrue(level.getPoiManager().getFreeTickets(absoluteOldHome) == 0,
                    "B's ticket must be preserved while B is unloaded - nothing proves the claimant still owns it");

            // B's chunk loads again.
            Villager reloadedB = (Villager) EntityType.create(savedB, level).orElseThrow();
            helper.assertTrue(level.addFreshEntity(reloadedB), "test setup: B must load back into the level");
            BlockPos spareBed = absoluteOldHome.offset(3, 0, 0);
            placePlayerBed(level, spareBed.north(), Direction.SOUTH, Blocks.RED_BED);
            reloaded.markCellPlaced(level.dimension(), spareBed, "site:test-owner", "test-signature", level.getBlockState(spareBed));
            TownResidentAllocationHandler.allocateHomeIfNeeded(level, reloadedB, absoluteOldHome,
                    TownRegion.fromBlockCoords(absoluteOldHome.getX(), absoluteOldHome.getZ()), reloaded);
            helper.assertTrue(reloadedB.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(absoluteOldHome)).orElse(false)
                            && level.getPoiManager().getFreeTickets(spareBed) > 0,
                    "the reloaded B must still be validly housed at its own home, never moved off it");
            helper.assertTrue(level.getPoiManager().getFreeTickets(absoluteOldHome) == 0,
                    "B's ticket must still be held after B reloads");
        }
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            finishOwnedBedFixture(oldHomeFixture, failure);
        }
        helper.succeed();
    }
}
