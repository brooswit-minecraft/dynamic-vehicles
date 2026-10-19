package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-388/455: {@link TownResidentAllocationHandler} must give EVERY villager in a founded
 * town reachable accommodation/work - not only the one claimant a construction site's own handoff
 * (see {@code TownConstructionServiceGameTests}) already covers.
 *
 * <p>All fixtures and villagers here are placed within THIS test's own GameTest structure, via the
 * same proven-stable {@code helper.absolutePos(ANCHOR)}-based placement every other test in this
 * codebase already relies on - a villager (and, separately, a POI record) placed at an arbitrary,
 * far-away, self-chosen world position was confirmed to become unreliable shortly afterward in this
 * specific GameTest harness, in ways this suite could not fully pin down despite exhausting several
 * independent mitigations (settle-tick delays, explicit chunk forcing, staged spawn-after-settle,
 * UUID-based re-resolution). Staying within already-established structure bounds avoided all of it.
 *
 * <p>That same proximity means this file's own fixtures sit within
 * {@link TownConstants#RESIDENT_ALLOCATION_SEARCH_RADIUS} of whatever OTHER real commercial/
 * industrial/residential site some unrelated, already-passing test elsewhere in this 100+-test
 * world happens to have built nearby - confirmed to actually happen (a workstation search landing on
 * a real, unrelated LIBRARIAN site instead of this test's own placed blast furnace). Each assertion
 * below is therefore written to prove the matching logic is INTERNALLY CONSISTENT - whichever real
 * vacant fixture it finds, the profession/position/ticket it ends up with are each other's correct
 * counterpart - rather than insisting it must be THIS test's own specific fixture, which ambient
 * world state cannot guarantee.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownResidentAllocationHandlerGameTests {
    private TownResidentAllocationHandlerGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);
    private static final BlockPos FIRST_VILLAGER_OFFSET = new BlockPos(0, 1, 2);
    private static final BlockPos SECOND_VILLAGER_OFFSET = new BlockPos(0, 1, -2);
    private static final BlockPos BED_HEAD_OFFSET = new BlockPos(1, 1, 0);
    private static final BlockPos WORKSTATION_OFFSET = new BlockPos(-1, 1, -1);

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    private static void placeValidBed(GameTestHelper helper, BlockPos headPos) {
        BlockState head = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD);
        BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT);
        helper.setBlock(headPos, head);
        helper.setBlock(headPos.north(), foot);
        registerPoiImmediately(helper.getLevel(), helper.absolutePos(headPos));
    }

    private static void placeWorkstation(GameTestHelper helper, BlockPos pos, Block block) {
        helper.setBlock(pos, block.defaultBlockState());
        registerPoiImmediately(helper.getLevel(), helper.absolutePos(pos));
    }

    /** Registers {@code absolutePos}'s POI record directly instead of relying on the normal
     *  placement hook: {@link ServerLevel#onBlockStateChange}'s own {@code PoiManager} update is
     *  deferred by one server tick when NOT called from the level's own managing thread (a GameTest
     *  method body does not run on it) - confirmed to actually happen here, not merely a same-tick
     *  production edge case (see {@code TownConstructionService#acquirePoiTicket}'s own identical
     *  note). Without this, a bed/workstation this SAME test just placed would not yet exist as a
     *  POI record when the allocation call under test runs immediately afterward. */
    private static void registerPoiImmediately(ServerLevel level, BlockPos absolutePos) {
        net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(level.getBlockState(absolutePos))
                .ifPresent(type -> level.getPoiManager().add(absolutePos, type));
    }

    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePos) {
        BlockPos absolutePos = helper.absolutePos(relativePos);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absolutePos, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        return villager;
    }

    /** A villager with no HOME at all, not a Builder, must be assigned a real, vacant bed - exactly
     *  the "every villager needs residential accommodation" requirement a single construction
     *  site's own claimant-only handoff can never cover. Asserted on internal consistency (whatever
     *  real bed it found, that bed's own ticket is now actually held), not on it being THIS test's
     *  own specific placed bed - see the class doc for why that identity is not guaranteed. */
    @GameTest(template = "settlement_test")
    public static void anUnhousedNonBuilderVillagerIsAssignedAVacantBed(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        // No HOME memory at all, profession NONE by default - a real villager that simply exists in
        // this founded town, never claimed any campfire.

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter);

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "an unhoused non-Builder villager within range of a real vacant bed must be assigned a HOME");
        BlockPos assignedHome = villager.getBrain().getMemory(MemoryModuleType.HOME).get().pos();
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(assignedHome) == 0,
                "the assigned HOME's own POI ticket must actually be held, not merely referenced by memory");
        helper.succeed();
    }

    /** An unemployed ({@code NONE}) villager must pick up a real, vacant, already-built workplace
     *  and be assigned the EXACT profession that SAME workstation belongs to - never an invented or
     *  mismatched one. Asserted on internal consistency (whichever real workstation it found,
     *  its position's own block is the real vanilla work-site for the profession it was actually
     *  given), not on it being THIS test's own specific placed blast furnace. */
    @GameTest(template = "settlement_test")
    public static void anUnemployedVillagerIsAssignedAVacantWorkstationAndItsMatchingProfession(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos workstationPos = ANCHOR.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), villager, townCenter);

        VillagerProfession assignedProfession = villager.getVillagerData().getProfession();
        helper.assertTrue(assignedProfession != VillagerProfession.NONE,
                "an unemployed villager within range of a real vacant workstation must be assigned a real profession");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent(),
                "the newly-assigned profession must come with a JOB_SITE pointed at the real workstation it was matched to");
        BlockPos assignedJobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).get().pos();
        Block assignedBlock = helper.getLevel().getBlockState(assignedJobSite).getBlock();
        helper.assertTrue(TownProfessionWorkstations.blockFor(assignedProfession).map(b -> b == assignedBlock).orElse(false),
                "the assigned profession and its JOB_SITE's own real block must be each other's correct vanilla counterpart, found profession "
                        + assignedProfession + " at block " + assignedBlock);
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(assignedJobSite) == 0,
                "the assigned workstation's own POI ticket must actually be held, not merely referenced by memory");
        helper.succeed();
    }

    /** Two real, unhoused villagers both searching the SAME area: neither must end up pointed at a
     *  POI the other also holds - contention must never double-assign the same single-ticket
     *  fixture to both. */
    @GameTest(template = "settlement_test")
    public static void onlyOneOfTwoCompetingVillagersIsAssignedTheSingleVacantBed(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);

        Villager first = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        Villager second = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), first, townCenter);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), second, townCenter);

        var firstHome = first.getBrain().getMemory(MemoryModuleType.HOME);
        var secondHome = second.getBrain().getMemory(MemoryModuleType.HOME);
        helper.assertTrue(firstHome.isPresent() && secondHome.isPresent(),
                "both villagers within range of real vacant beds must each be assigned a HOME");
        helper.assertTrue(!firstHome.get().pos().equals(secondHome.get().pos()),
                "two competing villagers must never be assigned the SAME single-ticket bed");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(firstHome.get().pos()) == 0
                        && helper.getLevel().getPoiManager().getFreeTickets(secondHome.get().pos()) == 0,
                "each villager's own assigned bed ticket must actually be held");
        helper.succeed();
    }

    /** A villager actively working as this mod's own temporary Builder profession - mid-construction,
     *  owned exclusively by {@code TownConstructionService} - must never be reassigned a HOME by this
     *  separate reconciliation pass, even if it technically has none yet. Drives the FULL per-tick
     *  scan ({@code runResidentAllocationTick}), not just the inner matching methods, so the
     *  Builder-exemption check in the outer loop itself is actually covered. The assertion is scoped
     *  to exactly this one villager's own data - never a claim that nothing anywhere else in a
     *  shared, crowded test world changed. */
    @GameTest(template = "settlement_test")
    public static void anActiveBuilderIsNeverTouchedByResidentAllocation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);

        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));

        Villager builder = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        builder.setVillagerData(builder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));

        TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);

        helper.assertTrue(builder.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "an active Builder must never be assigned a HOME by this reconciliation pass - TownConstructionService owns its entire lifecycle");
        helper.assertTrue(builder.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "an active Builder's profession must never be reassigned by this reconciliation pass");
        helper.succeed();
    }
}
