package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * MINECRAFT-388 review round 4, finding #7: {@link TownPassiveProductionHandler} must validate a
 * JOB_SITE memory's own dimension against the ticking level's dimension before trusting its
 * position - a stale/cross-dimension memory must never credit the wrong dimension's town just
 * because the raw (x, z) coordinates happen to overlap.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownPassiveProductionHandlerGameTests {
    private TownPassiveProductionHandlerGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);
    private static final int ISOLATED_REGION_OFFSET = 40_000;

    /** Cleanup is registered before an isolated fixture mutates remote world state.  It runs on
     * success, assertion failure, and timeout; each owned restoration is attempted independently.
     * Cleanup never replaces a test's primary failure. */
    static final class OwnedFixtureCleanup implements GameTestListener {
        private final List<Runnable> restorations = new ArrayList<>();
        private boolean cleaned;

        void add(Runnable restoration) { restorations.add(restoration); }

        void cleanup() {
            if (cleaned) return;
            cleaned = true;
            RuntimeException cleanupFailure = null;
            for (int i = restorations.size() - 1; i >= 0; i--) {
                try {
                    restorations.get(i).run();
                } catch (RuntimeException failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) throw cleanupFailure;
        }

        void cleanupPreserving(Throwable primary) {
            try {
                cleanup();
            } catch (RuntimeException cleanupFailure) {
                primary.addSuppressed(cleanupFailure);
            }
        }

        @Override public void testStructureLoaded(GameTestInfo test) {}
        @Override public void testPassed(GameTestInfo test, GameTestRunner runner) { cleanup(); }
        @Override public void testFailed(GameTestInfo test, GameTestRunner runner) { cleanupPreserving(test.getError()); }
        @Override public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {}
    }

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    /** Establishes the same compatible, occupied Farmer workplace that production attribution
     * requires. GameTest's normal POI update is deferred, so register and occupy it explicitly. */
    private static void placeAndClaimFarmerWorkstation(GameTestHelper helper, BlockPos relativePos) {
        helper.setBlock(relativePos, Blocks.COMPOSTER);
        BlockPos absolutePos = helper.absolutePos(relativePos);
        PoiTypes.forState(helper.getLevel().getBlockState(absolutePos)).ifPresent(type -> {
            helper.getLevel().getPoiManager().add(absolutePos, type);
            helper.getLevel().getPoiManager().take(t -> true, (t, pos) -> pos.equals(absolutePos), absolutePos, 1);
        });
    }

    private static boolean appearsInProductionIterable(ServerLevel level, Villager villager) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity.getUUID().equals(villager.getUUID())) return true;
        }
        return false;
    }

    private static int liveWorkersInRegion(ServerLevel level, TownStorage storage, RegionCoord region) {
        int workers = 0;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof Villager villager
                    && TownRegion.liveClaimedRegion(level, villager, storage).filter(region::equals).isPresent()) {
                workers++;
            }
        }
        return workers;
    }

    private static String workerDiagnostics(ServerLevel level, Villager villager, TownStorage storage, RegionCoord region, BlockPos jobSite, boolean ownedTicket) {
        return "uuid=" + villager.getUUID() + " present=" + appearsInProductionIterable(level, villager)
                + " alive=" + villager.isAlive() + " removed=" + villager.isRemoved()
                + " chunkLoaded=" + level.hasChunkAt(villager.blockPosition()) + " chunkTicking=" + level.isPositionEntityTicking(villager.blockPosition())
                + " profession=" + villager.getVillagerData().getProfession() + " jobSite=" + villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null)
                + " poi=" + PoiTypes.forState(level.getBlockState(jobSite)).map(Object::toString).orElse(null)
                + " ownedTicket=" + ownedTicket + " liveClaim=" + TownRegion.liveClaimedRegion(level, villager, storage).orElse(null)
                + " founded=" + storage.hasFoundedTown(TownKeys.keyFor(level.dimension(), region)) + " liveWorkers=" + liveWorkersInRegion(level, storage, region);
    }

    private static String unloadedWorkerDiagnostics(ServerLevel level, Villager villager, TownStorage storage, BlockPos unavailableSite, TownResourceStore store) {
        BlockPos workerPos = villager.blockPosition();
        return "uuid=" + villager.getUUID() + " pos=" + workerPos
                + " alive=" + villager.isAlive() + " removed=" + villager.isRemoved()
                + " iterable=" + appearsInProductionIterable(level, villager)
                + " workerChunkLoaded=" + level.hasChunkAt(workerPos) + " workerChunkTicking=" + level.isPositionEntityTicking(workerPos)
                + " profession=" + villager.getVillagerData().getProfession()
                + " jobSite=" + villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null)
                + " unavailableChunkLoaded=" + level.hasChunkAt(unavailableSite)
                + " ownedReservation=" + storage.isReservedBySelf(level.dimension(), unavailableSite, villager.getUUID())
                + " balance=" + store.balance(TownResourceCategory.FOOD);
    }

    /** A JOB_SITE memory pointing at the right position but the WRONG dimension must never credit
     *  this level's own town, even though {@link TownPassiveProductionHandler} is ticking THIS level.
     *
     * <p>Asserts {@link TownRegion#liveClaimedRegion} - the per-villager decision the tick credits
     * from - directly, for the same reason (and in the same way) as
     * {@link #aDestroyedJobSiteWithNoRemainingBlockIsNeverCredited}: a before/after balance on the
     * real, server-wide storage also counts every OTHER live worker that happens to share this
     * region in the dense GameTest world, and failed intermittently that way (the PR's own known
     * "region-collision" flake). The position holds a real job-site block and the region is
     * founded, so the dimension is the only thing that can reject it - confirmed by the same memory
     * resolving normally once it names this level's own dimension. */
    @GameTest(template = "settlement_test")
    public static void aCrossDimensionJobSiteMemoryIsNeverCreditedToThisLevelsOwnTown(GameTestHelper helper) {
        prepareFlatGround(helper);
        placeAndClaimFarmerWorkstation(helper, ANCHOR);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteAnchor, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));

        ResourceKey<Level> otherDimension = helper.getLevel().dimension().equals(Level.NETHER) ? Level.END : Level.NETHER;
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(otherDimension, absoluteAnchor));
        helper.assertTrue(TownRegion.liveClaimedRegion(helper.getLevel(), villager, storage).isEmpty(),
                "a JOB_SITE memory from a DIFFERENT dimension must never resolve to (and so never credit) this level's own town");

        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteAnchor));
        helper.assertTrue(TownRegion.liveClaimedRegion(helper.getLevel(), villager, storage).map(region::equals).orElse(false),
                "test setup: the same memory in this level's own dimension must resolve to the founded region");
        helper.succeed();
    }

    /** The same villager, with a JOB_SITE memory in THIS level's own dimension, must still be
     *  credited normally - proves the dimension check is not simply rejecting everything. */
    @GameTest(template = "settlement_test")
    public static void aSameDimensionJobSiteMemoryIsStillCreditedNormally(GameTestHelper helper) {
        prepareFlatGround(helper);
        placeAndClaimFarmerWorkstation(helper, ANCHOR);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteAnchor, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteAnchor));

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        storage.markTownFounded(key);
        TownResourceStore store = storage.storeFor(key);
        int before = store.balance(TownResourceCategory.FOOD);

        TownPassiveProductionHandler.runPassiveProductionTick(helper.getLevel());

        helper.assertTrue(store.balance(TownResourceCategory.FOOD) > before,
                "a JOB_SITE memory in THIS level's own dimension, in an actually-founded region, must still be credited normally");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 6, finding #5: a same-dimension, otherwise-valid JOB_SITE memory
     * in a region that was never actually founded must not be credited - a memory predating (or
     * surviving past) this town's own existence is not live activity.
     */
    @GameTest(template = "settlement_test")
    public static void aJobSiteInAnUnfoundedRegionIsNeverCredited(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteAnchor, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        // A deliberately far-flung, test-unique job site (well outside any chunk this or any other
        // concurrently-running GameTest ever touches) so its region can never have been marked
        // founded by an unrelated test sharing TownStorage.of's real, server-wide storage.
        BlockPos farJobSite = absoluteAnchor.offset(987_654_321, 0, 0);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), farJobSite));

        RegionCoord region = TownRegion.fromBlockCoords(farJobSite.getX(), farJobSite.getZ());
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        TownResourceStore store = storage.storeFor(key);
        int before = store.balance(TownResourceCategory.FOOD);

        TownPassiveProductionHandler.runPassiveProductionTick(helper.getLevel());

        helper.assertTrue(store.balance(TownResourceCategory.FOOD) == before,
                "a JOB_SITE memory in a region that was never founded must never be credited");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 6, finding #5: a JOB_SITE memory whose remembered position no
     * longer holds any physical block at all (destroyed since the memory was recorded) must not be
     * credited, even in a founded region with a matching dimension.
     *
     * <p>[Round 11 hardening] Asserts {@link TownRegion#liveClaimedRegion} directly - the exact
     * unit of behaviour this finding is about - rather than observing it indirectly through a full
     * {@link TownPassiveProductionHandler#runPassiveProductionTick} call. That tick scans EVERY
     * villager in the entire shared GameTest level (not just this structure's own), and the real
     * production tick-event hook keeps running automatically for the whole GameTestServer session
     * regardless of any single test's own code; a before/after balance comparison on the real,
     * server-wide {@link TownStorage#of} was observed to fail intermittently (including on CI, not
     * just locally) from unrelated activity landing in the same region, even though
     * {@code liveClaimedRegion} itself was independently confirmed (via temporary diagnostics) to
     * reject this exact scenario correctly every single time. Testing the unit directly removes
     * that entire shared-world surface from the equation.
     */
    @GameTest(template = "settlement_test")
    public static void aDestroyedJobSiteWithNoRemainingBlockIsNeverCredited(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteAnchor, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        // The job site must stay WITHIN this test's own loaded structure (hasChunkAt must be true)
        // so the "destroyed block" check can actually confirm air - a position far enough away to
        // dodge cross-test region collisions would also be far enough that hasChunkAt reads false,
        // which skips the air check entirely (treated as "can't tell, don't reject") and defeats
        // the very thing this test exists to prove. The flat-ground helper leaves ANCHOR as AIR
        // already - the memory points at a position that was never (or no longer) a real job site.
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteAnchor));

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        TownStorage storage = new TownStorage();
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        storage.markTownFounded(key);

        helper.assertTrue(TownRegion.liveClaimedRegion(helper.getLevel(), villager, storage).isEmpty(),
                "a JOB_SITE memory whose remembered position no longer holds any physical block must never resolve to a live claimed region");
        helper.succeed();
    }

    /** A remembered Farmer job site replaced with a solid non-POI block is no more live than AIR:
     * the actual passive handler must make no new credit from that stale claim. */
    @GameTest(templateNamespace = "dynamicvehicles_m454_passive_stone", template = "settlement_test", batch = "incomeAuthorityPassiveIsolated")
    public static void aSolidReplacementForAFarmerJobSiteReceivesNoPassiveCredit(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord templateRegion = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        RegionCoord region = new RegionCoord(templateRegion.x() + ISOLATED_REGION_OFFSET, templateRegion.z() - ISOLATED_REGION_OFFSET);
        BlockPos jobSite = new BlockPos(TownRegion.minBlockX(region) + 8, absoluteAnchor.getY(), TownRegion.minBlockZ(region) + 8);
        int chunkX = jobSite.getX() >> 4;
        int chunkZ = jobSite.getZ() >> 4;
        helper.assertTrue(!level.getPoiManager().exists(jobSite, type -> true), "isolated job-site fixture must not take or release another fixture's POI");
        var originalState = level.getBlockState(jobSite);
        final boolean[] forcedByThisTest = {false};
        final boolean[] blockWritten = {false};
        final boolean[] poiInserted = {false};
        final boolean[] ownedTicket = {false};
        var expectedFixturePoi = PoiTypes.forState(Blocks.COMPOSTER.defaultBlockState()).orElseThrow();
        OwnedFixtureCleanup cleanup = new OwnedFixtureCleanup();
        helper.testInfo.addListener(cleanup);
        cleanup.add(() -> { if (forcedByThisTest[0]) level.setChunkForced(chunkX, chunkZ, false); });
        cleanup.add(() -> { if (blockWritten[0] && (level.getBlockState(jobSite).equals(Blocks.COMPOSTER.defaultBlockState()) || level.getBlockState(jobSite).equals(Blocks.STONE.defaultBlockState()))) level.setBlock(jobSite, originalState, Block.UPDATE_ALL); });
        cleanup.add(() -> { if (poiInserted[0] && (level.getBlockState(jobSite).equals(Blocks.COMPOSTER.defaultBlockState()) || level.getBlockState(jobSite).equals(Blocks.STONE.defaultBlockState()))) level.getPoiManager().remove(jobSite); });
        cleanup.add(() -> {
            boolean fixturePoiStillPresent = level.getBlockState(jobSite).equals(Blocks.COMPOSTER.defaultBlockState())
                    && level.getPoiManager().exists(jobSite, type -> type.equals(expectedFixturePoi));
            boolean ticketStillOccupied = level.getPoiManager().getCountInRange(type -> type.equals(expectedFixturePoi), jobSite, 0, PoiManager.Occupancy.IS_OCCUPIED) == 1;
            if (ownedTicket[0] && fixturePoiStillPresent && ticketStillOccupied) level.getPoiManager().release(jobSite);
        });
        forcedByThisTest[0] = level.setChunkForced(chunkX, chunkZ, true);
        level.setBlock(jobSite, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
        blockWritten[0] = true;
        PoiTypes.forState(level.getBlockState(jobSite)).ifPresent(type -> {
            level.getPoiManager().add(jobSite, type);
            poiInserted[0] = true;
            ownedTicket[0] = level.getPoiManager().take(t -> true, (t, pos) -> pos.equals(jobSite), jobSite, 1).isPresent();
        });
        helper.assertTrue(ownedTicket[0], "test setup: isolated Farmer must acquire its own POI ticket");
        Villager villager = EntityType.VILLAGER.create(level, null, jobSite, MobSpawnType.EVENT, false, false);
        helper.assertTrue(villager != null, "test setup: isolated Farmer must be constructible");
        cleanup.add(villager::discard);
        helper.assertTrue(level.addFreshEntity(villager), "test setup: isolated Farmer must be accepted by ServerLevel");
        TownStorage storage = TownStorage.of(level.getServer());
        String key = TownKeys.keyFor(level.dimension(), region);
        storage.markTownFounded(key);
        TownResourceStore store = storage.storeFor(key);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(appearsInProductionIterable(level, villager),
                "the controlled Farmer UUID must become visible to the production iterable: " + workerDiagnostics(level, villager, storage, region, jobSite, ownedTicket[0])))
        .thenExecute(() -> {
            try {
                villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
                villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), jobSite));
                helper.assertTrue(liveWorkersInRegion(level, storage, region) == 1,
                        "the controlled Farmer must be this town's only live worker (expected=1): " + workerDiagnostics(level, villager, storage, region, jobSite, ownedTicket[0]));
                int before = store.balance(TownResourceCategory.FOOD);
                TownPassiveProductionHandler.runPassiveProductionTick(level);
                int afterValidCredit = store.balance(TownResourceCategory.FOOD);
                helper.assertTrue(afterValidCredit == before + TownConstants.PASSIVE_PRODUCTION_PER_WORKER,
                        "one visible compatible Farmer must produce exactly one passive credit before replacement");
                level.setBlock(jobSite, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                helper.assertTrue(TownRegion.liveClaimedRegion(level, villager, storage).isEmpty(),
                        "a solid but profession-incompatible replacement must invalidate the remembered JOB_SITE");
                TownPassiveProductionHandler.runPassiveProductionTick(level);
                helper.assertTrue(store.balance(TownResourceCategory.FOOD) == afterValidCredit,
                        "the actual passive handler must add no credit after this visible Farmer's job site becomes STONE");
            } catch (RuntimeException primary) { cleanup.cleanupPreserving(primary); throw primary; }
              finally { cleanup.cleanup(); }
        }).thenSucceed();
    }

    /** An unavailable job site defers passive credit without loading its chunk or mutating the
     * unresolved memory/reservation; a later normal load may validate it. */
    @GameTest(templateNamespace = "dynamicvehicles_m454_passive_unloaded", template = "settlement_test", batch = "incomeAuthorityPassiveIsolated")
    public static void anUnloadedJobSiteDefersPassiveCreditWithoutReleasingTheClaim(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord templateRegion = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        RegionCoord workerRegion = new RegionCoord(templateRegion.x() + ISOLATED_REGION_OFFSET + 1, templateRegion.z() - ISOLATED_REGION_OFFSET);
        BlockPos workerPos = new BlockPos(TownRegion.minBlockX(workerRegion) + 8, absoluteAnchor.getY(), TownRegion.minBlockZ(workerRegion) + 8);
        int workerChunkX = workerPos.getX() >> 4;
        int workerChunkZ = workerPos.getZ() >> 4;
        OwnedFixtureCleanup cleanup = new OwnedFixtureCleanup();
        helper.testInfo.addListener(cleanup);
        boolean forcedByThisTest = level.setChunkForced(workerChunkX, workerChunkZ, true);
        cleanup.add(() -> { if (forcedByThisTest) level.setChunkForced(workerChunkX, workerChunkZ, false); });
        BlockPos unavailableSite = workerPos.offset(987_654_321, 0, 0);
        GlobalPos rememberedSite = GlobalPos.of(level.dimension(), unavailableSite);
        Villager villager = EntityType.VILLAGER.create(level, null, workerPos, MobSpawnType.EVENT, false, false);
        helper.assertTrue(villager != null, "test setup: unavailable-site Farmer must be constructible");
        cleanup.add(villager::discard);
        helper.assertTrue(level.addFreshEntity(villager),
                "test setup: unavailable-site worker insertion must succeed uuid=" + villager.getUUID() + " pos=" + workerPos);

        RegionCoord region = TownRegion.fromBlockCoords(unavailableSite.getX(), unavailableSite.getZ());
        TownStorage storage = TownStorage.of(level.getServer());
        String key = TownKeys.keyFor(level.dimension(), region);
        storage.markTownFounded(key);
        TownResourceStore store = storage.storeFor(key);
        final int[] balanceBefore = new int[1];
        helper.assertTrue(!level.hasChunkAt(unavailableSite), "test setup: the remembered site must be unavailable");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(appearsInProductionIterable(level, villager),
                "readiness: unavailable-site worker UUID must become visible before profession/claim setup: "
                        + unloadedWorkerDiagnostics(level, villager, storage, unavailableSite, store)))
        .thenExecute(() -> {
            villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
            villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, rememberedSite);
            storage.setReservationOwner(level.dimension(), unavailableSite, villager.getUUID());
            balanceBefore[0] = store.balance(TownResourceCategory.FOOD);
        }).thenIdle(20).thenExecute(() -> {
            try {
                helper.assertTrue(appearsInProductionIterable(level, villager),
                        "final: unavailable-site Farmer UUID must remain visible after the full lifecycle interval: "
                                + unloadedWorkerDiagnostics(level, villager, storage, unavailableSite, store));
                TownPassiveProductionHandler.runPassiveProductionTick(level);
                helper.assertTrue(!level.hasChunkAt(unavailableSite)
                                && villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(rememberedSite::equals).orElse(false)
                                && storage.isReservedBySelf(level.dimension(), unavailableSite, villager.getUUID())
                                && store.balance(TownResourceCategory.FOOD) == balanceBefore[0],
                        "final: unavailable JOB_SITE must remain unloaded and claimed while a visible worker produces no credit: "
                                + unloadedWorkerDiagnostics(level, villager, storage, unavailableSite, store));
            } catch (RuntimeException primary) { cleanup.cleanupPreserving(primary); throw primary; }
              finally { cleanup.cleanup(); }
        }).thenSucceed();
    }
}
