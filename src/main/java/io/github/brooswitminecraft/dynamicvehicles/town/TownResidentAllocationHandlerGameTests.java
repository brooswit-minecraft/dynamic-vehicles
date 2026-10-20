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
 * (see {@code TownConstructionServiceGameTests}) already covers - and must do so scoped to exactly
 * that resident's own town, transactionally, and with a Builder exemption that recognises claim loss.
 *
 * <p>Every fixture and villager here is placed within THIS test's own GameTest structure, via the
 * same proven-stable {@code helper.absolutePos(ANCHOR)}-based placement every other test in this
 * codebase already relies on. A fixture (or villager) placed at an arbitrary, far-away, self-chosen
 * world position was tried extensively here and confirmed GENUINELY unreliable in this specific
 * GameTest harness - not specific to entities, nor to one block type over another (both beds and
 * workstations intermittently failed to still exist moments later within the SAME synchronous test
 * call, despite explicit chunk forcing) - in ways this suite could not fully root-cause despite
 * exhausting several independent mitigations (settle-tick delays, staged spawn-after-settle,
 * UUID-based re-resolution). Staying within already-established structure bounds is what actually
 * proved reliable.
 *
 * <p>That stability trades off exact isolation: this file's own test methods are confirmed to land
 * in GameTest grid slots closer together than one {@link TownConstants#REGION_SIZE_BLOCKS}-wide
 * region, so two sibling tests here (or this file and some unrelated, already-built real site
 * elsewhere in this 100+-test world) CAN share a region. Tests that need true region-boundary
 * behaviour therefore pass a DELIBERATELY WRONG {@code RegionCoord} to the allocation call under
 * test rather than attempting real geographic separation - proving the exact same exclusion
 * mechanism without needing any fixture to actually be far away. Tests whose own outcome could
 * otherwise be influenced by sibling interference assert internal consistency (whichever real
 * vacant fixture is found, the profession/position/ticket it ends up with are each other's correct
 * counterpart) rather than exact identity with a specific test-placed fixture.
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
    private static final BlockPos OLD_HOME_OFFSET = new BlockPos(2, 1, 2);
    private static final BlockPos OLD_WORKSTATION_OFFSET = new BlockPos(-2, 1, 2);

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

    /** Takes a ticket at {@code absolutePos} directly, standing in for "some other, unrelated
     *  villager already holds this" - used to seed a STALE existing reservation this test's own
     *  villager starts out pointed at. */
    private static void takeTicketDirectly(ServerLevel level, BlockPos absolutePos) {
        net.minecraft.world.entity.ai.village.poi.PoiTypes.forState(level.getBlockState(absolutePos))
                .ifPresent(type -> {
                    level.getPoiManager().add(absolutePos, type);
                    level.getPoiManager().take(t -> true, (t, p) -> p.equals(absolutePos), absolutePos, 1);
                });
    }

    private static Villager spawnVillager(GameTestHelper helper, BlockPos relativePos) {
        BlockPos absolutePos = helper.absolutePos(relativePos);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absolutePos, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        return villager;
    }

    private static RegionCoord regionOf(GameTestHelper helper) {
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        return TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
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
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, regionOf(helper));

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "an unhoused non-Builder villager within range of a real vacant bed must be assigned a HOME");
        BlockPos assignedHome = villager.getBrain().getMemory(MemoryModuleType.HOME).get().pos();
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(assignedHome) == 0,
                "the assigned HOME's own POI ticket must actually be held, not merely referenced by memory");
        helper.succeed();
    }

    /** An unemployed ({@code NONE}) villager must pick up a real, vacant, already-built workplace
     *  and be assigned the EXACT profession that SAME workstation belongs to - never an invented or
     *  mismatched one. Asserted on internal consistency, not exact fixture identity. */
    @GameTest(template = "settlement_test")
    public static void anUnemployedVillagerIsAssignedAVacantWorkstationAndItsMatchingProfession(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos workstationPos = ANCHOR.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), villager, townCenter, regionOf(helper));

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
        RegionCoord region = regionOf(helper);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), first, townCenter, region);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), second, townCenter, region);

        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        boolean firstHoused = first.getBrain().getMemory(MemoryModuleType.HOME)
                .map(gp -> gp.pos().equals(absoluteBedHead)).orElse(false);
        boolean secondHoused = second.getBrain().getMemory(MemoryModuleType.HOME)
                .map(gp -> gp.pos().equals(absoluteBedHead)).orElse(false);
        helper.assertTrue(firstHoused ^ secondHoused,
                "exactly ONE of the two competing villagers must end up assigned the single vacant bed, never both, never neither");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteBedHead) == 0,
                "the single bed's ticket must be held exactly once, by whichever villager actually won it");
        helper.succeed();
    }

    /** [Review round 18, finding #1] A real, vacant bed must never be matched when the
     *  {@code RegionCoord} passed to the allocation call does not actually contain it - proving the
     *  exact region-membership filter that is the only thing excluding a neighbouring region's
     *  content (the raw search radius alone reaches well past a single region's own edge; see
     *  {@link TownConstants#RESIDENT_ALLOCATION_SEARCH_RADIUS}'s own doc). A deliberately wrong
     *  region is passed here rather than attempting real geographic separation between two actual
     *  regions, which this suite's own fixture placement cannot reliably achieve (see the class doc). */
    @GameTest(template = "settlement_test")
    public static void aBedOutsideTheGivenRegionIsNeverMatched(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        RegionCoord trueRegion = regionOf(helper);
        RegionCoord wrongRegion = TownRegion.neighbor(trueRegion, TownEdge.NORTH);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, wrongRegion);

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "a real, vacant bed must never be matched when the given region does not actually contain it");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteBedHead) > 0,
                "the bed must remain untouched when the wrong region is given");
        helper.succeed();
    }

    /** [Review round 18, finding #2] Replacing an existing HOME in a DIFFERENT dimension - a real,
     *  intact, still-held reservation there, simply superseded by a new same-dimension one - must
     *  actually RELEASE that old ticket from its OWN actual level once the new one is secured,
     *  transactionally - never silently overwritten, permanently leaking it. (A destroyed-block
     *  staleness scenario cannot test this: destroying the block already removes its own POI record
     *  via vanilla's normal hook, leaving nothing left to explicitly release - see
     *  {@code TownConstructionService}'s own identical round-14 precedent for this exact pattern.) */
    @GameTest(template = "settlement_test")
    public static void replacingACrossDimensionHomeReleasesTheOldTicketTransactionally(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos newBedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));

        // A real, pre-existing home in a DIFFERENT dimension - still intact, still genuinely held.
        ServerLevel otherDimensionLevel = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(otherDimensionLevel != null, "test setup: the nether dimension must be loaded in this GameTest server");
        BlockPos oldHomeInOtherDimension = new BlockPos(0, 70, 0);
        placeValidBedAt(otherDimensionLevel, oldHomeInOtherDimension);
        takeTicketDirectly(otherDimensionLevel, oldHomeInOtherDimension);
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldHomeInOtherDimension) == 0,
                "test setup: the cross-dimension old home's ticket must actually be held before reconciliation runs");
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(net.minecraft.world.level.Level.NETHER, oldHomeInOtherDimension));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, regionOf(helper));

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.dimension().equals(helper.getLevel().dimension())).orElse(false),
                "the villager's HOME must be replaced with a new, same-dimension bed");
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldHomeInOtherDimension) > 0,
                "the cross-dimension old home's ticket must actually be released, not permanently leaked just because it was in a different dimension");
        helper.succeed();
    }

    private static void placeValidBedAt(ServerLevel level, BlockPos absoluteHeadPos) {
        BlockState head = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD);
        BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT);
        level.setBlock(absoluteHeadPos, head, Block.UPDATE_ALL);
        level.setBlock(absoluteHeadPos.north(), foot, Block.UPDATE_ALL);
        registerPoiImmediately(level, absoluteHeadPos);
    }

    /** [Review round 18, finding #2] A villager that already holds a REAL profession but lost its
     *  specific workstation (destroyed) must have a NEW workstation matching ITS OWN SAME profession
     *  searched for first - never silently reassigned to a different profession's own vacancy. */
    @GameTest(template = "settlement_test")
    public static void aVillagerWithARealProfessionKeepsItByFindingAMatchingWorkstation(GameTestHelper helper) {
        prepareFlatGround(helper);
        // A DIFFERENT profession's own vacant workstation (so an unconstrained "first vacancy wins"
        // search would wrongly prefer it) - a real fletcher's fletching table.
        BlockPos wrongWorkstationPos = ANCHOR.offset(OLD_WORKSTATION_OFFSET.getX(), OLD_WORKSTATION_OFFSET.getY(), OLD_WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, wrongWorkstationPos, Blocks.FLETCHING_TABLE);
        BlockPos absoluteWrongWorkstationPos = helper.absolutePos(wrongWorkstationPos);
        // This villager's OWN real profession's matching workstation - a real armorer's blast
        // furnace.
        BlockPos matchingWorkstationPos = ANCHOR.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, matchingWorkstationPos, Blocks.BLAST_FURNACE);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.ARMORER));
        // No JOB_SITE memory at all - simulating "already Armorer, but its own workstation was lost".

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), villager, townCenter, regionOf(helper));

        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.ARMORER,
                "a villager that already held a real profession must keep it, found "
                        + villager.getVillagerData().getProfession());
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent(),
                "the retained profession must come with a JOB_SITE pointed at a real matching workstation");
        BlockPos assignedJobSite = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).get().pos();
        // Self-consistency, not exact fixture identity (this test's own region may coincide with a
        // sibling's - see the class doc): the assigned JOB_SITE's own real block must be the correct
        // ARMORER workstation specifically, which also proves it is NOT the fletching table.
        helper.assertTrue(helper.getLevel().getBlockState(assignedJobSite).is(Blocks.BLAST_FURNACE),
                "the villager must be matched to its OWN profession's real workstation type, never a different profession's vacancy, found block "
                        + helper.getLevel().getBlockState(assignedJobSite));
        helper.assertTrue(!assignedJobSite.equals(absoluteWrongWorkstationPos),
                "the villager must never be matched to the unrelated (wrong-profession) workstation specifically");
        helper.succeed();
    }

    /** [Review round 18, finding #3] A villager actively working as this mod's own temporary Builder
     *  profession, with a LIVE campfire claim, must never be reassigned a HOME/profession by this
     *  separate reconciliation pass - {@code TownConstructionService} owns its entire lifecycle
     *  exclusively while that claim is active. Drives the FULL per-tick scan, so (unlike the direct-
     *  call tests above) its assertions are scoped to exactly this one villager's own data. */
    @GameTest(template = "settlement_test")
    public static void anActivelyClaimedBuilderIsNeverTouchedByResidentAllocation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos campfireCell = ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), 0, FIRST_VILLAGER_OFFSET.getZ());
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);

        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));

        Villager builder = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteCampfire, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(builder);
        builder.setVillagerData(builder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        builder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteCampfire));

        TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);

        helper.assertTrue(builder.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "an actively-claimed Builder must never be assigned a HOME by this reconciliation pass");
        helper.assertTrue(builder.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "an actively-claimed Builder's profession must never be reassigned by this reconciliation pass");
        helper.succeed();
    }

    /** [Review round 18, finding #3] A villager still LABELLED Builder, but whose claim has
     *  genuinely lapsed (its remembered campfire no longer physically exists), must never be
     *  permanently excluded from reconciliation just because its profession field still says
     *  Builder - it must be released to NONE and then reconciled like any other real resident. */
    @GameTest(template = "settlement_test")
    public static void anOrphanedBuilderWithALapsedClaimIsReconciledLikeAnyOtherVillager(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos staleCampfireCell = ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), 0, FIRST_VILLAGER_OFFSET.getZ());
        BlockPos absoluteStaleCampfire = helper.absolutePos(staleCampfireCell);
        // The remembered campfire position no longer holds a campfire at all (destroyed) - the
        // claim has genuinely lapsed, even though the profession field still says Builder.
        helper.setBlock(staleCampfireCell, Blocks.AIR);
        BlockPos workstationPos = ANCHOR.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);

        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));

        Villager orphan = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        orphan.setVillagerData(orphan.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        orphan.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteStaleCampfire));

        TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);

        VillagerProfession assignedProfession = orphan.getVillagerData().getProfession();
        helper.assertTrue(assignedProfession != BuilderProfession.BUILDER.get(),
                "an orphaned Builder (lapsed claim) must never remain permanently labelled Builder");
        if (assignedProfession != VillagerProfession.NONE) {
            helper.assertTrue(orphan.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent(),
                    "a real newly-assigned profession must come with a JOB_SITE pointed at its matching workstation");
            BlockPos assignedJobSite = orphan.getBrain().getMemory(MemoryModuleType.JOB_SITE).get().pos();
            Block assignedBlock = helper.getLevel().getBlockState(assignedJobSite).getBlock();
            helper.assertTrue(TownProfessionWorkstations.blockFor(assignedProfession).map(b -> b == assignedBlock).orElse(false),
                    "the orphaned Builder's new profession and its JOB_SITE's own real block must be each other's correct vanilla counterpart");
        }
        helper.succeed();
    }
}
