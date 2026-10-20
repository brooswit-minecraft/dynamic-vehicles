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

    /** [Review round 19, finding #2] Candidate search is now scoped to fixtures Dynamic Towns itself
     *  actually placed-and-paid-for ({@link TownStorage#isAnyCellPlaced}), not merely same-region
     *  POIs - so every fixture a test wants the allocation logic to actually find must be registered
     *  here too, exactly as {@code TownConstructionService} itself would via {@code markCellPlaced}
     *  when it builds one for real. The owner/signature values themselves are never inspected by
     *  {@code isAnyCellPlaced} - only whether SOME record exists at all - so arbitrary constants are
     *  fine here. */
    private static void markTownOwned(GameTestHelper helper, TownStorage storage, BlockPos absolutePos) {
        storage.markCellPlaced(helper.getLevel().dimension(), absolutePos, "test-owner", "test-signature");
    }

    private static TownStorage newStorageWithFoundedTown(GameTestHelper helper, RegionCoord region) {
        TownStorage storage = new TownStorage();
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        return storage;
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
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        // No HOME memory at all, profession NONE by default - a real villager that simply exists in
        // this founded town, never claimed any campfire.

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

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
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(workstationPos));

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

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
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));

        Villager first = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        Villager second = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), first, townCenter, region, storage);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), second, townCenter, region, storage);

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

    /** A region so far from the shared GameTest world's own cluster of ~126 structures that no test
     *  anywhere in the suite could ever have placed real content in it - unlike an adjacent
     *  ({@code TownRegion.neighbor(...)}) region, which proved NOT reliably empty in CI: this
     *  suite's own sibling structures sit closer together than one {@code REGION_SIZE_BLOCKS}, so a
     *  geometrically-adjacent "wrong" region can genuinely contain another test's own real,
     *  currently-vacant bed, which the allocation logic then (correctly, per its own contract) finds
     *  - an entirely different failure from the one this test means to catch. The region-membership
     *  filter under test ({@code TownResidentAllocationHandler}'s {@code inRegion}) only ever checks
     *  equality, never adjacency, so an arbitrarily distant region exercises the exact same check
     *  while actually guaranteeing isolation. */
    private static final RegionCoord GUARANTEED_EMPTY_REGION = new RegionCoord(1_000_000, 1_000_000);

    /** [Review round 18, finding #1] A real, vacant bed must never be matched when the
     *  {@code RegionCoord} passed to the allocation call does not actually contain it - proving the
     *  exact region-membership filter that is the only thing excluding a neighbouring region's
     *  content (the raw search radius alone reaches well past a single region's own edge; see
     *  {@link TownConstants#RESIDENT_ALLOCATION_SEARCH_RADIUS}'s own doc). {@link #GUARANTEED_EMPTY_REGION}
     *  is used as the wrong region rather than a geometric neighbor - see its own doc for why that
     *  was NOT reliably isolated in CI's shared world. */
    @GameTest(template = "settlement_test")
    public static void aBedOutsideTheGivenRegionIsNeverMatched(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        TownStorage storage = newStorageWithFoundedTown(helper, regionOf(helper));
        markTownOwned(helper, storage, absoluteBedHead);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, GUARANTEED_EMPTY_REGION, storage);

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
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(newBedHead));

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
        // [Review round 24] Release now requires POSITIVE proof of self-ownership, not merely the
        // absence of counter-evidence - this old home must be genuinely recorded as THIS villager's
        // own (exactly as production code would have, had it originally granted it) for the release
        // below to be expected at all.
        storage.setReservationOwner(net.minecraft.world.level.Level.NETHER, oldHomeInOtherDimension, villager.getUUID());

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

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
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteWrongWorkstationPos);
        markTownOwned(helper, storage, helper.absolutePos(matchingWorkstationPos));

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.ARMORER));
        // No JOB_SITE memory at all - simulating "already Armorer, but its own workstation was lost".

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

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
        markTownOwned(helper, storage, helper.absolutePos(workstationPos));

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

    /** [Review round 19, finding #1] A resident whose remembered HOME is a real, intact, still-held
     *  bed, but in a DIFFERENT region from the one it currently stands in (it has moved to - or this
     *  reconciliation is now running against - a different founded town), must be reassigned a new,
     *  in-region bed, and the stale out-of-region reservation must actually be released rather than
     *  kept forever just because the fixture itself is still perfectly intact. The old home is placed
     *  far enough away (a real offset, not merely a different {@code RegionCoord} value) to land in a
     *  genuinely different region while staying within the world border and far from every other
     *  GameTest structure. */
    @GameTest(template = "settlement_test")
    public static void aResidentWhoseHomeIsOutsideItsCurrentRegionIsReassignedAndTheOldTicketReleased(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos newBedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(newBedHead));

        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        BlockPos oldHomeInDifferentRegion = absoluteAnchor.offset(50_000, 65 - absoluteAnchor.getY(), 50_000);
        helper.assertTrue(!TownRegion.fromBlockCoords(oldHomeInDifferentRegion.getX(), oldHomeInDifferentRegion.getZ()).equals(region),
                "test setup: the old home's own offset must actually land in a different region");
        placeValidBedAt(helper.getLevel(), oldHomeInDifferentRegion);
        takeTicketDirectly(helper.getLevel(), oldHomeInDifferentRegion);
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(oldHomeInDifferentRegion) == 0,
                "test setup: the old (different-region) home's ticket must actually be held before reconciliation runs");

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), oldHomeInDifferentRegion));
        // [Review round 24] Release now requires POSITIVE proof of self-ownership - genuinely record
        // this old home as the villager's own (as production code would have on the original grant).
        storage.setReservationOwner(helper.getLevel().dimension(), oldHomeInDifferentRegion, villager.getUUID());

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> !gp.pos().equals(oldHomeInDifferentRegion)).orElse(false),
                "a resident whose remembered HOME is outside its current region must be assigned a new, in-region bed instead of keeping the stale one");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(oldHomeInDifferentRegion) > 0,
                "the old, now-out-of-region home's ticket must actually be released, not permanently leaked just because it was in a different region");
        helper.succeed();
    }

    /** [Review round 19, finding #1] A HOME memory that resolves to a real, correctly-typed,
     *  in-region bed is still not "housed" if that bed's own ticket is not actually held (fully
     *  free) - some OTHER mechanism released it without this villager's own memory being updated.
     *  Existence alone must never be read as occupancy. */
    @GameTest(template = "settlement_test")
    public static void anUnticketedHomeMemoryIsNotTreatedAsHoused(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos staleBedHead = ANCHOR.offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, staleBedHead); // registered, but its ticket is never taken - fully free
        BlockPos newBedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(newBedHead));

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        BlockPos absoluteStaleBedHead = helper.absolutePos(staleBedHead);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteStaleBedHead));
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteStaleBedHead) > 0,
                "test setup: the stale home's own ticket must NOT actually be held (fully free) before reconciliation runs");

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> !gp.pos().equals(absoluteStaleBedHead)).orElse(false),
                "a HOME memory whose own ticket is not actually held must never be treated as already housed");
        helper.succeed();
    }

    /** [Review round 19, finding #2] A real, vacant, correctly-typed, SAME-REGION workstation that
     *  Dynamic Towns itself never built (a same-region vanilla- or player-placed fixture) must never
     *  be matched, even though region filtering alone would otherwise accept it - proving the
     *  town-ownership filter ({@link TownStorage#isAnyCellPlaced}) is actually what excludes it, not
     *  merely the region check. */
    @GameTest(template = "settlement_test")
    public static void aSameRegionNonTownOwnedWorkstationIsNeverMatched(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos workstationPos = ANCHOR.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        // Deliberately NOT registered via storage.markCellPlaced - standing in for a real, vacant,
        // same-region workstation Dynamic Towns itself never built.
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);

        Villager villager = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        BlockPos townCenter = helper.absolutePos(ANCHOR);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE,
                "a same-region workstation Dynamic Towns itself never built must never be matched, even though it is otherwise real and vacant");
        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty(),
                "no JOB_SITE must be assigned when the only candidate in range is not a town-owned fixture");
        helper.succeed();
    }

    /** [Review round 19, finding #3] A Builder standing in a region with NO founded Dynamic Towns
     *  town at all - not this mod's concern regardless of its own claim's liveness - must never be
     *  mutated by this reconciliation pass. The founded-town check must run BEFORE any Builder
     *  mutation, not after, so a non-town Builder anywhere in the loaded Overworld is never touched. */
    @GameTest(template = "settlement_test")
    public static void aNonTownBuilderIsNeverTouchedByResidentAllocation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos staleCampfireCell = ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), 0, FIRST_VILLAGER_OFFSET.getZ());
        helper.setBlock(staleCampfireCell, Blocks.AIR); // claim already lapsed - no real campfire
        BlockPos absoluteStaleCampfire = helper.absolutePos(staleCampfireCell);

        // Deliberately an EMPTY TownStorage - no town founded anywhere, including this villager's own
        // current region - standing in for a Builder whose region Dynamic Towns has never founded.
        TownStorage storage = new TownStorage();

        Villager builder = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        builder.setVillagerData(builder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        builder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteStaleCampfire));

        TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);

        helper.assertTrue(builder.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(),
                "a Builder standing in a region with no founded Dynamic Towns town at all must never be touched by this reconciliation, even with a lapsed claim");
        helper.assertTrue(builder.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(absoluteStaleCampfire)).orElse(false),
                "its stale JOB_SITE memory must be left exactly as-is");
        helper.succeed();
    }

    /** [Review round 19, finding #3] An orphaned Builder's stale claim can itself be a real,
     *  cross-dimension reservation (its remembered JOB_SITE is in a dimension other than the one it
     *  is reconciled in) - releasing to NONE must still release that reservation from ITS OWN actual
     *  level, never merely erase the memory and leave the ticket permanently held. */
    @GameTest(template = "settlement_test")
    public static void anOrphanedBuilderWithACrossDimensionClaimReleasesItsOldReservation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        TownStorage storage = newStorageWithFoundedTown(helper, region);

        ServerLevel otherDimensionLevel = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(otherDimensionLevel != null, "test setup: the nether dimension must be loaded in this GameTest server");
        BlockPos oldClaimInOtherDimension = new BlockPos(1_000, 65, 1_000);
        placeValidBedAt(otherDimensionLevel, oldClaimInOtherDimension); // a real, reservable stand-in fixture; this orphan path never requires it to be a campfire specifically
        takeTicketDirectly(otherDimensionLevel, oldClaimInOtherDimension);
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldClaimInOtherDimension) == 0,
                "test setup: the cross-dimension old claim's ticket must actually be held before reconciliation runs");

        Villager orphan = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        orphan.setVillagerData(orphan.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        orphan.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(net.minecraft.world.level.Level.NETHER, oldClaimInOtherDimension));
        // [Review round 24] Release now requires POSITIVE proof of self-ownership - genuinely record
        // this old claim as the orphan's own (as production code would have on the original grant).
        storage.setReservationOwner(net.minecraft.world.level.Level.NETHER, oldClaimInOtherDimension, orphan.getUUID());

        TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);

        helper.assertTrue(orphan.getVillagerData().getProfession() != BuilderProfession.BUILDER.get(),
                "a Builder whose remembered claim is in a DIFFERENT dimension (never physically here) must be treated as orphaned, not actively claimed");
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldClaimInOtherDimension) > 0,
                "the orphaned Builder's old cross-dimension claim ticket must actually be released, not permanently leaked");
        helper.succeed();
    }

    /** [Review round 20, finding #1] {@code PoiManager} tracks only a free-ticket COUNT per position,
     *  never which entity holds it - so a stale HOME memory that happens to point at a bed NOW
     *  genuinely held by a DIFFERENT villager must never be read as still this villager's own, and
     *  reassigning this villager elsewhere must never release the OTHER villager's real reservation.
     *  Uses {@link TownResidentAllocationHandler#allocateHomeIfNeeded} (production ownership
     *  recording) for the real resident, then manually seeds a SECOND villager's stale memory
     *  pointing at that exact same, now-rightfully-occupied bed - the data-corruption path the
     *  previous unticketed-memory test (which only covers the fully-free case) cannot catch. */
    @GameTest(template = "settlement_test")
    public static void aStaleHomeMemoryNowOwnedByAnotherVillagerIsNeverStolenOrTreatedAsValid(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        // A SECOND, genuinely different vacant bed - without this, an impostor correctly rejected
        // from the stolen bed would simply have no alternative at all, leaving its stale memory
        // unchanged (nothing to overwrite it with) and making the rejection itself unobservable.
        // With a real alternative present, actually ending up reassigned HERE is the positive proof
        // the stale memory was genuinely treated as invalid, not merely left alone by coincidence.
        BlockPos secondBedHead = ANCHOR.offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, secondBedHead);
        BlockPos absoluteSecondBedHead = helper.absolutePos(secondBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        markTownOwned(helper, storage, absoluteSecondBedHead);
        BlockPos townCenter = helper.absolutePos(ANCHOR);

        Villager rightfulOwner = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), rightfulOwner, townCenter, region, storage);
        helper.assertTrue(rightfulOwner.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "test setup: the rightful owner must actually be assigned one of the two beds by production code first");
        // Whichever of the two beds production code actually picked (search order between two
        // equally-eligible candidates is not this test's concern) - the impostor's stale memory
        // targets THIS exact one, and the other is the real, genuinely vacant alternative it must
        // end up reassigned to instead.
        BlockPos rightfulOwnerHome = rightfulOwner.getBrain().getMemory(MemoryModuleType.HOME).get().pos();
        BlockPos theOtherVacantBed = rightfulOwnerHome.equals(absoluteBedHead) ? absoluteSecondBedHead : absoluteBedHead;

        // A THIRD, unrelated villager whose own memory also (incorrectly/stalely) points at the
        // rightful owner's exact bed - standing in for some earlier, now-outdated state that was
        // never cleared.
        Villager impostor = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        impostor.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), rightfulOwnerHome));

        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), impostor, townCenter, region, storage);

        helper.assertTrue(impostor.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(theOtherVacantBed)).orElse(false),
                "a villager must never be treated as housed at a bed this mod's own records show belongs to someone else, and must be reassigned the real, genuinely vacant alternative instead");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(rightfulOwnerHome) == 0,
                "the rightful owner's ticket must never be stolen/released just because a different villager's stale memory also pointed at it");
        helper.assertTrue(rightfulOwner.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(rightfulOwnerHome)).orElse(false),
                "the rightful owner's own HOME memory must be completely unaffected by the impostor's reconciliation");
        helper.succeed();
    }

    /** [Review round 20, finding #2] When the SOLE eligible owned fixture in range is the exact same
     *  one a villager's own stale (since-freed) memory already points to, reacquiring it must not
     *  immediately release the ticket this same call just took - and this must hold with a SECOND
     *  resident also present in the search space (not only in a single-villager world), proving the
     *  second resident is correctly left unhoused (nothing is left to give it) rather than the first
     *  resident's own ticket being wrongly freed out from under it. */
    @GameTest(template = "settlement_test")
    public static void reacquiringTheSoleEligibleFixtureAlreadyPointedToDoesNotFreeItsOwnNewTicket(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead); // registered, but its ticket is never taken - fully free
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        BlockPos townCenter = helper.absolutePos(ANCHOR);

        Villager first = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        // A stale memory pointing at the SOLE eligible bed, whose ticket is NOT currently held -
        // exactly the sequence that previously caused a self-inflicted immediate release.
        first.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));
        // A second real resident, also unhoused, also searching the same space - proving the fix
        // holds with more than one villager present, not merely in isolation.
        Villager second = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));

        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), first, townCenter, region, storage);
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteBedHead) == 0,
                "reacquiring the sole eligible fixture it already pointed to must leave its own ticket actually held, not immediately freed again");
        helper.assertTrue(first.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(absoluteBedHead)).orElse(false),
                "the first resident must end up genuinely assigned the one bed that exists");

        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), second, townCenter, region, storage);
        helper.assertTrue(second.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "the second resident must correctly end up unhoused - the sole bed is genuinely, validly taken, not freed up for it by the first resident's own self-release bug");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteBedHead) == 0,
                "the sole bed's ticket must still be held after reconciling the second resident too");
        helper.succeed();
    }

    /** [Review round 20, follow-up finding #1] {@link TownStorage}'s own ownership record only knows
     *  about reservations THIS MOD itself granted - a resident that holds a real bed via vanilla's
     *  own natural AI (or one granted before this tracking existed) has no record at all, yet its
     *  reservation is just as real. A live scan of every OTHER currently alive villager's own actual
     *  memory (see {@code TownResidentAllocationHandler#isCurrentlyClaimedByAnotherVillager}) is what
     *  actually protects this case, since the registry alone cannot. */
    @GameTest(template = "settlement_test")
    public static void anUntrackedResidentsRealReservationIsNeverStolenByAnotherVillagersStaleMemory(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        BlockPos secondBedHead = ANCHOR.offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, secondBedHead);
        BlockPos absoluteSecondBedHead = helper.absolutePos(secondBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        markTownOwned(helper, storage, absoluteSecondBedHead);
        BlockPos townCenter = helper.absolutePos(ANCHOR);

        // An UNTRACKED resident: its real reservation at the first bed was never granted through
        // this mod's own ownership-recording code paths (standing in for vanilla's own natural AI,
        // or one that predates this tracking) - TownStorage has no owner recorded for it at all.
        Villager untrackedResident = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(helper.getLevel(), absoluteBedHead);
        untrackedResident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));

        // A second, unrelated villager whose own memory also (incorrectly/stalely) points at that
        // exact same, genuinely-occupied bed.
        Villager impostor = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        impostor.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));

        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), impostor, townCenter, region, storage);

        helper.assertTrue(impostor.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(absoluteSecondBedHead)).orElse(false),
                "a villager must never be treated as housed at a bed some OTHER real villager's own current memory still references, even with no tracked owner recorded for it, and must be reassigned the real, genuinely vacant alternative instead");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteBedHead) == 0,
                "an untracked resident's real ticket must never be stolen/released just because a different villager's stale memory also pointed at it, even with no ownership record to protect it");
        helper.assertTrue(untrackedResident.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(absoluteBedHead)).orElse(false),
                "the untracked resident's own HOME memory must be completely unaffected by the impostor's reconciliation");
        helper.succeed();
    }

    /** [Review round 20, follow-up finding #1] The reservation-ownership record itself must actually
     *  survive a real save/load round trip (not merely a same-session in-memory check) - the same
     *  durable-persistence proof this suite already requires of {@link TownStorage}'s other records
     *  (see its own class doc on {@code blockedCells}). */
    @GameTest(template = "settlement_test")
    public static void reservationOwnershipMarksTheStorageDirtyAndSurvivesASaveLoadRoundTrip(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));
        BlockPos townCenter = helper.absolutePos(ANCHOR);

        Villager owner = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), owner, townCenter, region, storage);
        helper.assertTrue(owner.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                "test setup: the owner must actually be assigned the bed first");
        BlockPos ownedPos = owner.getBrain().getMemory(MemoryModuleType.HOME).get().pos();

        // [Review follow-up] A direct save()/load() round trip alone does not prove a REAL server
        // save would ever have happened: the normal save scheduler only writes data whose own
        // isDirty() is true. setReservationOwner must actually mark this storage dirty, not merely
        // mutate its in-memory map, or a real save could silently skip persisting it.
        helper.assertTrue(storage.isDirty(),
                "setReservationOwner must mark the storage dirty, or a real server save could silently skip persisting it");

        net.minecraft.nbt.CompoundTag saved = storage.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());

        Villager impostor = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        helper.assertTrue(reloaded.isReservedByADifferentVillager(helper.getLevel().dimension(), ownedPos, impostor.getUUID()),
                "the reservation-ownership record must survive a save/load round trip, not silently reset on reload");

        reloaded.setDirty(false);
        reloaded.clearReservationOwner(helper.getLevel().dimension(), ownedPos);
        helper.assertTrue(reloaded.isDirty(),
                "clearReservationOwner must also mark the storage dirty");
        helper.succeed();
    }

    /** [Review round 24: replaces round 22's since-reverted backfill test] An untracked reservation
     *  whose real owner disappears (unloaded/despawned) BEFORE this reconciliation ever once
     *  validates it is the hardest case: there is no registry entry (no backfill happens on
     *  validation any more - see {@code hasValidHome}'s own doc on why that was unsound) AND no
     *  live villager left to find either. The only safe behaviour is to PRESERVE this unresolved
     *  reservation - never release it on a stale villager's memory, and never fabricate an ownership
     *  record for anyone either. A real save/load round trip must not manufacture one later. */
    @GameTest(template = "settlement_test")
    public static void anUnresolvedHomeIsPreservedNotBackfilledWhenItsRealOwnerDisappearsBeforeFirstValidation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = ANCHOR.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        BlockPos townCenter = helper.absolutePos(ANCHOR);

        // B's real, genuinely-held reservation - gone (unloaded/despawned) before this reconciliation
        // has EVER once looked at it. No registry entry, no live villager left to confirm it either.
        Villager neverValidatedOwner = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(helper.getLevel(), absoluteBedHead);
        neverValidatedOwner.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));
        neverValidatedOwner.discard();

        // A's own stale memory also (incorrectly) points at that same, genuinely-occupied bed.
        Villager stale = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        stale.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));

        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), stale, townCenter, region, storage);

        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteBedHead) == 0,
                "an unresolved reservation (no tracked owner, no live claimant left to confirm it) must never be released just because a stale memory also pointed at it");
        helper.assertTrue(!storage.isReservedByADifferentVillager(helper.getLevel().dimension(), absoluteBedHead, stale.getUUID())
                        && !storage.isReservedBySelf(helper.getLevel().dimension(), absoluteBedHead, stale.getUUID()),
                "no ownership record must be fabricated for anyone just because this reservation could not be resolved");

        net.minecraft.nbt.CompoundTag saved = storage.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
        helper.assertTrue(!reloaded.isReservedBySelf(helper.getLevel().dimension(), absoluteBedHead, stale.getUUID()),
                "a real save/load round trip must not manufacture an ownership record either");
        helper.succeed();
    }

    /** [Review round 24] The same preserve-not-backfill guarantee for {@code JOB_SITE}/workplaces,
     *  explicitly required alongside the {@code HOME} case above. */
    @GameTest(template = "settlement_test")
    public static void anUnresolvedWorkplaceIsPreservedNotBackfilledWhenItsRealOwnerDisappearsBeforeFirstValidation(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos workstationPos = ANCHOR.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        BlockPos absoluteWorkstationPos = helper.absolutePos(workstationPos);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteWorkstationPos);
        BlockPos townCenter = helper.absolutePos(ANCHOR);

        // B's real, genuinely-held workstation - gone before this reconciliation ever once validated it.
        Villager neverValidatedOwner = spawnVillager(helper, ANCHOR.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        neverValidatedOwner.setVillagerData(neverValidatedOwner.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(helper.getLevel(), absoluteWorkstationPos);
        neverValidatedOwner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteWorkstationPos));
        neverValidatedOwner.discard();

        // A's own stale memory and matching profession also (incorrectly) point at that same workstation.
        Villager stale = spawnVillager(helper, ANCHOR.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        stale.setVillagerData(stale.getVillagerData().setProfession(VillagerProfession.ARMORER));
        stale.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteWorkstationPos));

        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), stale, townCenter, region, storage);

        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteWorkstationPos) == 0,
                "an unresolved workstation reservation must never be released just because a stale memory also pointed at it");
        helper.assertTrue(!storage.isReservedBySelf(helper.getLevel().dimension(), absoluteWorkstationPos, stale.getUUID()),
                "no ownership record must be fabricated for anyone just because this reservation could not be resolved");
        helper.succeed();
    }
}
