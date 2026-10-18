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
}
