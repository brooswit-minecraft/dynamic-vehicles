package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
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
        helper.setBlock(ANCHOR, Blocks.SMOKER);
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
        helper.setBlock(ANCHOR, Blocks.SMOKER); // a real, live job site - not a bare coordinate in open air
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
}
