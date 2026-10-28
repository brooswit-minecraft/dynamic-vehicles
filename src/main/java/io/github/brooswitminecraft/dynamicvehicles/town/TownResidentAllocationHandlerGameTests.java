package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.BuilderProfession;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MINECRAFT-388/455: {@link TownResidentAllocationHandler} must give EVERY villager in a founded
 * town reachable accommodation/work - not only the one claimant a construction site's own handoff
 * (see {@code TownConstructionServiceGameTests}) already covers - and must do so scoped to exactly
 * that resident's own town, transactionally, and with a Builder exemption that recognises claim loss.
 *
 * <p>Every fixture and villager here is placed within THIS test's own GameTest structure, via the
 * same proven-stable {@code helper.absolutePos(anchor(helper))}-based placement every other test in this
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

    /** Keep every fixture offset (-2..2) inside one town and the prepared 10x10 floor.
     * GameTest grid origins vary; a fixed local anchor can straddle a 384-block town edge. */
    private static BlockPos anchor(GameTestHelper helper) {
        int[] candidates = {5, 2, 3, 4, 6, 7};
        for (int x : candidates) {
            for (int z : candidates) {
                BlockPos candidate = new BlockPos(x, 2, z);
                BlockPos min = helper.absolutePos(candidate.offset(-2, 0, -2));
                BlockPos max = helper.absolutePos(candidate.offset(2, 0, 2));
                if (TownRegion.fromBlockCoords(min.getX(), min.getZ())
                        .equals(TownRegion.fromBlockCoords(max.getX(), max.getZ()))) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("No same-town fixture footprint inside the test floor");
    }
    private static final BlockPos FIRST_VILLAGER_OFFSET = new BlockPos(0, 1, 2);
    private static final BlockPos SECOND_VILLAGER_OFFSET = new BlockPos(0, 1, -2);
    private static final BlockPos BED_HEAD_OFFSET = new BlockPos(1, 1, 0);
    private static final BlockPos WORKSTATION_OFFSET = new BlockPos(-1, 1, -1);
    private static final BlockPos OLD_HOME_OFFSET = new BlockPos(2, 1, 2);
    private static final BlockPos OLD_WORKSTATION_OFFSET = new BlockPos(-2, 1, 2);

    /** Read-only timeout context for the target's exact entity-ticking radius. */
    private static String chunkDebugSnapshot(ServerLevel level, ChunkPos center) {
        StringBuilder snapshot = new StringBuilder("[");
        for (int zOffset = -2; zOffset <= 2; zOffset++) {
            for (int xOffset = -2; xOffset <= 2; xOffset++) {
                if (snapshot.length() > 1) {
                    snapshot.append(", ");
                }
                int radius = Math.max(Math.abs(xOffset), Math.abs(zOffset));
                String ring = radius == 0 ? "center" : radius == 1 ? "radius1" : "outerRing";
                ChunkPos chunk = new ChunkPos(center.x + xOffset, center.z + zOffset);
                snapshot.append(ring)
                        .append(":")
                        .append(chunk)
                        .append("=")
                        .append(level.getChunkSource().getChunkDebugData(chunk));
            }
        }
        return snapshot.append("]").toString();
    }

    /**
     * Prepares precisely the target's entity-ticking neighborhood through the public Level API.
     * {@link ServerLevel#getChunk(int, int)} delegates to the required {@code ChunkStatus.FULL}
     * load; normal transient tickets are owned by the engine, not this test.
     */
    private static void prepareEntityTickingNeighborhood(ServerLevel level, ChunkPos center) {
        for (int zOffset = -2; zOffset <= 2; zOffset++) {
            for (int xOffset = -2; xOffset <= 2; xOffset++) {
                level.getChunk(center.x + xOffset, center.z + zOffset);
            }
        }
    }

    /** Keeps the complete distant takeover fixture inside one region without collapsing its lane. */
    private static BlockPos distantFixtureHome(BlockPos absoluteAnchor, int laneX, int laneZ) {
        int x = absoluteAnchor.getX() + laneX;
        int z = absoluteAnchor.getZ() + laneZ;
        int localX = Math.floorMod(x, TownConstants.REGION_SIZE_BLOCKS);
        int localZ = Math.floorMod(z, TownConstants.REGION_SIZE_BLOCKS);
        x += localX < 4 ? 4 - localX : localX > TownConstants.REGION_SIZE_BLOCKS - 9
                ? TownConstants.REGION_SIZE_BLOCKS - 9 - localX : 0;
        z += localZ < 1 ? 1 - localZ : 0;
        return new BlockPos(x, 65, z);
    }

    private static boolean distantFixtureCaseHolds(int desiredX, int desiredZ, int expectedX, int expectedZ) {
        BlockPos home = distantFixtureHome(new BlockPos(desiredX - 60_000, 0, desiredZ + 60_000), 60_000, -60_000);
        RegionCoord region = TownRegion.fromBlockCoords(desiredX, desiredZ);
        return home.equals(new BlockPos(expectedX, 65, expectedZ))
                && TownRegion.fromBlockCoords(home.getX(), home.getZ()).equals(region)
                && TownRegion.fromBlockCoords(home.north().getX(), home.north().getZ()).equals(region)
                && TownRegion.fromBlockCoords(home.offset(4, 0, 0).getX(), home.offset(4, 0, 0).getZ()).equals(region)
                && TownRegion.fromBlockCoords(home.offset(-4, 0, 0).getX(), home.offset(-4, 0, 0).getZ()).equals(region)
                && TownRegion.fromBlockCoords(home.offset(-4, 0, 0).north().getX(), home.offset(-4, 0, 0).north().getZ()).equals(region)
                && TownRegion.fromBlockCoords(home.offset(8, 0, 0).getX(), home.offset(8, 0, 0).getZ()).equals(region);
    }

    private static boolean distantFixtureHomeRegressionHolds() {
        return distantFixtureCaseHolds(0, 100, 4, 100)
                && distantFixtureCaseHolds(378, 100, 375, 100)
                && distantFixtureCaseHolds(-384, 100, -380, 100)
                && distantFixtureCaseHolds(-1, 100, -9, 100)
                && distantFixtureCaseHolds(100, 0, 100, 1)
                && distantFixtureCaseHolds(100, -384, 100, -383)
                && distantFixtureCaseHolds(100, 100, 100, 100);
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
        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));
        return TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
    }

    /** [Review round 19, finding #2] Candidate search is now scoped to fixtures Dynamic Towns itself
     *  actually placed-and-paid-for ({@link TownStorage#isTownBuiltFixture}), not merely same-region
     *  POIs - so every fixture a test wants the allocation logic to actually find must be registered
     *  here too, exactly as {@code TownConstructionService} itself would via {@code markCellPlaced}
     *  when it builds one for real: recording the state it actually placed there (round 25, P2 - the
     *  fixture recorded from it must still match the live one). The owner/signature values are never
     *  inspected by {@code isTownBuiltFixture}, so arbitrary constants are fine for those. */
    private static void markTownOwned(GameTestHelper helper, TownStorage storage, BlockPos absolutePos) {
        storage.markCellPlaced(helper.getLevel().dimension(), absolutePos, "test-owner", "test-signature",
                helper.getLevel().getBlockState(absolutePos));
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        // No HOME memory at all, profession NONE by default - a real villager that simply exists in
        // this founded town, never claimed any campfire.

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos workstationPos = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(workstationPos));

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));

        Villager first = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        Villager second = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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

    /** The consumer asks construction only for the resident still missing capacity after normal
     * fixture allocation.  In particular, it must not pass the two-resident aggregate shortage to
     * both residents and manufacture duplicate admissions across a retry/reload. */
    @GameTest(template = "settlement_test", batch = "residentAllocationConsumerIsolated")
    public static void allocationConsumerAdmitsOnlyTheStillUnhousedResidentAndReusesItAfterReload(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));
        Villager first = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        Villager second = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));

        // EntityType#create + addFreshEntity is synchronous for block access but a freshly spawned
        // GameTest villager does not always appear in the exact production iterable
        // level.getEntities().getAll() until normal ticks have progressed.
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getEntity(first.getUUID()) == first
                                    && helper.getLevel().getEntity(second.getUUID()) == second
                                    && first.isAlive() && second.isAlive(),
                            "test setup: both named residents must be alive and UUID-resolvable in the level before whole-level allocation");
                    helper.assertTrue(TownRegion.fromBlockCoords(first.blockPosition().getX(), first.blockPosition().getZ()).equals(region)
                                    && TownRegion.fromBlockCoords(second.blockPosition().getX(), second.blockPosition().getZ()).equals(region)
                                    && first.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                                    && second.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                                    && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(bedHead)) == 1,
                            "test setup: both named residents must be unhoused in this region and the one town-owned bed must be free");
                    TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);
                    boolean firstHoused = first.getBrain().getMemory(MemoryModuleType.HOME).isPresent();
                    Villager waiting = firstHoused ? second : first;
                    Villager housed = firstHoused ? first : second;
                    Optional<ResidentCapacityAdmission> firstAdmission = storage.capacityAdmissionFor(helper.getLevel().dimension(), region, first.getUUID());
                    Optional<ResidentCapacityAdmission> secondAdmission = storage.capacityAdmissionFor(helper.getLevel().dimension(), region, second.getUUID());
                    Optional<ResidentCapacityAdmission> admission = firstHoused ? secondAdmission : firstAdmission;
                    helper.assertTrue(firstHoused ^ second.getBrain().getMemory(MemoryModuleType.HOME).isPresent(),
                            "exactly one named resident must receive the single town-owned bed");
                    helper.assertTrue(housed.getBrain().getMemory(MemoryModuleType.HOME).isPresent()
                                    && waiting.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                                    && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(bedHead)) == 0,
                            "the single bed's real POI ticket must belong to the named housed resident, leaving the other named resident waiting");
                    helper.assertTrue(firstAdmission.isPresent() ^ secondAdmission.isPresent()
                                    && admission.isPresent()
                                    && admission.get().beneficiaryId().equals(waiting.getUUID())
                                    && admission.get().requestedHomes() == 1
                                    && admission.get().requestedWorkplaces().isEmpty(),
                            "only the named resident still without the one town-owned bed may receive one resident-sized admission");

                    CompoundTag saved = storage.save(new CompoundTag(), helper.getLevel().registryAccess());
                    ListTag savedAdmissions = saved.getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
                    helper.assertTrue(savedAdmissions.size() == 1,
                            "test setup: exactly one resident admission must be serialized before reload");
                    TownStorage reloaded = TownStorage.load(saved, helper.getLevel().registryAccess());
                    // Prove persistence BEFORE the retry can call the admission boundary.  If load
                    // dropped the record, runResidentAllocationTick could otherwise recreate an
                    // indistinguishable admission and make this look like a successful round trip.
                    Optional<ResidentCapacityAdmission> loadedAdmission = reloaded.capacityAdmissionFor(
                            helper.getLevel().dimension(), region, waiting.getUUID());
                    ListTag loadedAdmissions = reloaded.save(new CompoundTag(), helper.getLevel().registryAccess())
                            .getList("ResidentCapacityAdmissions", Tag.TAG_COMPOUND);
                    helper.assertTrue(loadedAdmissions.size() == 1,
                            "reload must retain exactly one admission before any retry can recreate it");
                    helper.assertTrue(loadedAdmission.isPresent(),
                            "reload must retain an admission under the waiting resident's durable identity before retry");
                    helper.assertTrue(loadedAdmission.get().equals(admission.get())
                                    && loadedAdmission.get().beneficiaryId().equals(waiting.getUUID())
                                    && loadedAdmission.get().dimension().equals(helper.getLevel().dimension())
                                    && loadedAdmission.get().region().equals(region)
                                    && loadedAdmission.get().requestedHomes() == 1
                                    && loadedAdmission.get().requestedWorkplaces().isEmpty()
                                    && loadedAdmission.get().state() == ResidentCapacityAdmission.State.AWAITING_CLAIM,
                            "reload must retain the waiting resident's exact one-home admission profile before any retry can recreate it");
                    TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), reloaded);
                    helper.assertTrue(reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, waiting.getUUID())
                                    .filter(loadedAdmission.get()::equals).isPresent()
                                    && reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, housed.getUUID()).isEmpty()
                                    && helper.getLevel().getPoiManager().getFreeTickets(helper.absolutePos(bedHead)) == 0,
                            "a retry after reload must reuse the one waiting resident admission and never add a second bed owner or admission");
                })
                .thenSucceed();
    }

    @GameTest(template = "settlement_test", batch = "residentAdmissionZeroNeedIsolated")
    public static void allocationCancelsOnlyAnAwaitingAdmissionWhenExistingFixturesResolveNeed(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos anchor = anchor(helper);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        Villager resident = spawnVillager(helper, anchor.offset(FIRST_VILLAGER_OFFSET.getX(),
                FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        BlockPos bed = anchor.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        BlockPos workstation = anchor.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    resident.setVillagerData(resident.getVillagerData().setProfession(VillagerProfession.ARMORER));
                    boolean inProductionIterable = false;
                    for (Entity entity : helper.getLevel().getEntities().getAll()) {
                        if (entity == resident) {
                            inProductionIterable = true;
                            break;
                        }
                    }
                    RegionCoord actualRegion = TownRegion.fromBlockCoords(resident.blockPosition().getX(), resident.blockPosition().getZ());
                    boolean hasHome = resident.getBrain().getMemory(MemoryModuleType.HOME).isPresent();
                    boolean hasJobSite = resident.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent();
                    TownResidentDemand aggregateDemand = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
                    helper.assertTrue(helper.getLevel().getEntity(resident.getUUID()) == resident && resident.isAlive()
                                    && inProductionIterable
                                    && resident.getVillagerData().getProfession() == VillagerProfession.ARMORER
                                    && actualRegion.equals(region)
                                    && !hasHome
                                    && !hasJobSite
                                    && aggregateDemand.missingHomes() >= 1
                                    && aggregateDemand.missingWorkplaces().getOrDefault(VillagerProfession.ARMORER, 0) >= 1,
                            "test setup: live beneficiary must be iterable, retained ARMORER, unhoused, unassigned, and in the founded region "
                                    + "with aggregate capacity for its shortage; profession=" + resident.getVillagerData().getProfession()
                                    + ", actualRegion=" + actualRegion + ", expectedRegion=" + region
                                    + ", hasHome=" + hasHome + ", hasJobSite=" + hasJobSite
                                    + ", aggregateDemand=" + aggregateDemand);
                    TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);
                    Optional<ResidentCapacityAdmission> awaiting = storage.capacityAdmissionFor(
                            helper.getLevel().dimension(), region, resident.getUUID());
                    helper.assertTrue(awaiting.filter(value -> value.state() == ResidentCapacityAdmission.State.AWAITING_CLAIM
                                    && value.requestedHomes() == 1
                                    && value.requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))).isPresent(),
                            "a live resident missing both fixtures must first receive one resident-sized unbound admission; profession="
                                    + resident.getVillagerData().getProfession() + ", region=" + actualRegion
                                    + ", aggregateDemand=" + aggregateDemand
                                    + ", returnedAdmission=" + awaiting.map(Object::toString).orElse("absent"));

                    placeValidBed(helper, bed);
                    placeWorkstation(helper, workstation, Blocks.BLAST_FURNACE);
                    BlockPos absoluteBed = helper.absolutePos(bed);
                    BlockPos absoluteWorkstation = helper.absolutePos(workstation);
                    markTownOwned(helper, storage, absoluteBed);
                    markTownOwned(helper, storage, absoluteWorkstation);
                    TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), storage);

                    Optional<ResidentCapacityAdmission> cancelled = storage.capacityAdmissionFor(
                            helper.getLevel().dimension(), region, resident.getUUID());
                    helper.assertTrue(resident.getBrain().getMemory(MemoryModuleType.HOME)
                                    .map(home -> home.pos().equals(absoluteBed)).orElse(false)
                                    && resident.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                                            .map(site -> site.pos().equals(absoluteWorkstation)).orElse(false)
                                    && helper.getLevel().getPoiManager().getFreeTickets(absoluteBed) == 0
                                    && helper.getLevel().getPoiManager().getFreeTickets(absoluteWorkstation) == 0
                                    && TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage)
                                            == TownResidentDemand.NONE
                                    && cancelled.filter(value -> value.state() == ResidentCapacityAdmission.State.CANCELLED
                                            && value.beneficiaryId().equals(resident.getUUID())
                                            && value.requestedHomes() == 1
                                            && value.requestedWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1))).isPresent(),
                            "real town-owned HOME and matching JOB_SITE must resolve need and retire only the stale unbound intent");

                    TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), helper.getLevel().registryAccess()),
                            helper.getLevel().registryAccess());
                    helper.assertTrue(reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, resident.getUUID())
                                    .filter(cancelled.get()::equals).isPresent(),
                            "the terminal disposition of the resolved resident admission must survive reload before any later allocation pass");
                    TownResidentAllocationHandler.runResidentAllocationTick(helper.getLevel(), reloaded);
                    helper.assertTrue(reloaded.capacityAdmissionFor(helper.getLevel().dimension(), region, resident.getUUID())
                                    .filter(cancelled.get()::equals).isPresent(),
                            "the terminal disposition of the resolved resident admission must survive reload and a later allocation pass");
                })
                .thenSucceed();
    }

    /** The pure demand query observes current shortage without granting a ticket or changing any
     * resident state.  A valid held HOME/JOB_SITE removes demand; losing the matching job ticket
     * restores only that retained profession's workplace demand. */
    @GameTest(template = "settlement_test", batch = "residentDemandNetherFixtureIsolated")
    public static void unmetDemandUsesFoundedTownValidatedReservationsAndHasNoSideEffects(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos anchor = anchor(helper);
        BlockPos bed = anchor.offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        BlockPos workstation = anchor.offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeValidBed(helper, bed);
        placeWorkstation(helper, workstation, Blocks.BLAST_FURNACE);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        BlockPos absoluteBed = helper.absolutePos(bed);
        BlockPos absoluteWorkstation = helper.absolutePos(workstation);
        markTownOwned(helper, storage, absoluteBed);
        markTownOwned(helper, storage, absoluteWorkstation);

        Villager armorer = spawnVillager(helper, anchor.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        armorer.setVillagerData(armorer.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(helper.getLevel(), absoluteBed);
        takeTicketDirectly(helper.getLevel(), absoluteWorkstation);
        storage.setReservationOwner(helper.getLevel().dimension(), absoluteBed, armorer.getUUID());
        storage.setReservationOwner(helper.getLevel().dimension(), absoluteWorkstation, armorer.getUUID());
        armorer.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBed));
        armorer.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteWorkstation));

        storage.setDirty(false);
        TownResidentDemand satisfied = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        int baselineMissingHomes = satisfied.missingHomes();
        int baselineArmorerDemand = satisfied.missingWorkplaces().getOrDefault(VillagerProfession.ARMORER, 0);
        helper.assertTrue(baselineArmorerDemand == 0 && !storage.isDirty()
                        && armorer.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(absoluteBed)).orElse(false)
                        && armorer.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(absoluteWorkstation)).orElse(false)
                        && helper.getLevel().getPoiManager().getFreeTickets(absoluteBed) == 0
                        && helper.getLevel().getPoiManager().getFreeTickets(absoluteWorkstation) == 0,
                "a valid existing HOME/JOB_SITE must suppress demand and the pure query must not mutate storage, memories, or POI tickets");

        armorer.getBrain().eraseMemory(MemoryModuleType.HOME);
        TownResidentDemand missingHome = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        helper.assertTrue(missingHome.missingHomes() == baselineMissingHomes + 1 && !storage.isDirty()
                        && armorer.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                        && helper.getLevel().getPoiManager().getFreeTickets(absoluteBed) == 0,
                "a valid held HOME must suppress exactly this resident's housing demand without the query restoring a rejected memory");
        armorer.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBed));
        helper.getLevel().getPoiManager().release(absoluteWorkstation);
        storage.setDirty(false);
        TownResidentDemand missingWork = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        helper.assertTrue(missingWork.missingWorkplaces().getOrDefault(VillagerProfession.ARMORER, 0) == baselineArmorerDemand + 1
                        && !storage.isDirty()
                        && armorer.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(absoluteWorkstation)).orElse(false)
                        && helper.getLevel().getPoiManager().getFreeTickets(absoluteWorkstation) == 1,
                "a released matching ticket must add exactly the retained Armorer's workplace demand without reclaiming it");
        TownStorage foundedOtherRegion = newStorageWithFoundedTown(helper, GUARANTEED_EMPTY_REGION);
        ServerLevel nether = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, new TownStorage()) == TownResidentDemand.NONE,
                "an unfounded Overworld town must report no demand");
        helper.assertTrue(TownResidentAllocationHandler.unmetDemand(helper.getLevel(), GUARANTEED_EMPTY_REGION, foundedOtherRegion) == TownResidentDemand.NONE,
                "a founded other region must not report this region's residents as demand");

        helper.assertTrue(nether != null, "test setup: the Nether must be loaded in this GameTest server");
        BlockPos netherResidentPos = helper.absolutePos(anchor).atY(80);
        RegionCoord netherRegion = TownRegion.fromBlockCoords(netherResidentPos.getX(), netherResidentPos.getZ());
        int netherChunkX = netherResidentPos.getX() >> 4;
        int netherChunkZ = netherResidentPos.getZ() >> 4;
        ChunkPos netherChunk = new ChunkPos(netherResidentPos);
        boolean initiallyForced = nether.getForcedChunks().contains(netherChunk.toLong());
        long netherGameTimeAtSetup = nether.getGameTime();
        Villager[] netherResident = {null};
        boolean[] forcedByThisTest = {false};
        boolean[] ticketOwnedByThisTest = {false};
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) {
                return;
            }
            cleaned[0] = true;
            try {
                if (netherResident[0] != null && !netherResident[0].isRemoved()) {
                    netherResident[0].discard();
                }
            } finally {
                try {
                    if (ticketOwnedByThisTest[0]) {
                        nether.getChunkSource().removeRegionTicket(TicketType.PORTAL, netherChunk, 2, netherResidentPos, true);
                        ticketOwnedByThisTest[0] = false;
                    }
                } finally {
                    if (forcedByThisTest[0]) {
                        nether.setChunkForced(netherChunkX, netherChunkZ, false);
                        forcedByThisTest[0] = false;
                    }
                }
            }
        };
        // Install terminal cleanup before any ticket, forced chunk, or sequence state is acquired.
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) {}
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) { cleanup.run(); }
        });
        forcedByThisTest[0] = nether.setChunkForced(netherChunkX, netherChunkZ, true);
        // setChunkForced loads blocks but does not make an entity enter the ServerLevel's ticking
        // entity manager.  A distance-two full-chunk ticket is ENTITY_TICKING (ChunkLevel level
        // 31); forceTicks=true also records it in the ticking-ticket tracker.  This is deliberately
        // test-owned and terminal-listener-released, unlike the founded-town state being observed.
        nether.getChunkSource().addRegionTicket(TicketType.PORTAL, netherChunk, 2, netherResidentPos, true);
        ticketOwnedByThisTest[0] = true;
        // Public FULL preparation establishes the exact 5x5 prerequisite for the subsequent
        // readiness gate; it neither substitutes for nor changes those real readiness probes.
        prepareEntityTickingNeighborhood(nether, netherChunk);
        storage.markTownFounded(TownKeys.keyFor(nether.dimension(), netherRegion));
        helper.startSequence()
                // A ticket request is not readiness.  Wait for the same target chunk to report
                // ticking completion, entity-manager visibility, and completed entity storage load
                // before creating the exact resident that must be visible to production iteration.
                .thenWaitUntil(() -> {
                    long netherChunkLong = netherChunk.toLong();
                    boolean fullChunkPresent = nether.getChunkSource().getChunkNow(netherChunk.x, netherChunk.z) != null;
                    boolean blockTickEligible = nether.shouldTickBlocksAt(netherChunkLong);
                    long netherGameTimeNow = nether.getGameTime();
                    boolean chunkTicking = nether.getChunkSource().isPositionTicking(netherChunkLong);
                    boolean entityManagerTicking = nether.isNaturalSpawningAllowed(netherChunk);
                    boolean entitiesLoaded = nether.areEntitiesLoaded(netherChunkLong);
                    String radius2DebugSnapshot = chunkTicking && entityManagerTicking && entitiesLoaded
                            ? ""
                            : chunkDebugSnapshot(nether, netherChunk);
                    helper.assertTrue(chunkTicking && entityManagerTicking && entitiesLoaded,
                            "test setup: Nether resident chunk readiness timed out; chunk=" + netherChunk
                                    + ", initialForced=" + initiallyForced
                                    + ", forceAdded=" + forcedByThisTest[0]
                                    + ", currentlyForced=" + nether.getForcedChunks().contains(netherChunkLong)
                                    + ", fullChunkPresent=" + fullChunkPresent
                                    + ", blockTickEligible=" + blockTickEligible
                                    + ", netherGameTimeAtSetup=" + netherGameTimeAtSetup
                                    + ", netherGameTimeNow=" + netherGameTimeNow
                                    + ", netherGameTimeDelta=" + (netherGameTimeNow - netherGameTimeAtSetup)
                                    + ", chunkTicking=" + chunkTicking
                                    + ", entityManagerTicking=" + entityManagerTicking
                                    + ", entitiesLoaded=" + entitiesLoaded
                                    + ", radius2ChunkDebug=" + radius2DebugSnapshot);
                })
                .thenExecute(() -> {
                    netherResident[0] = EntityType.VILLAGER.create(nether, null, netherResidentPos, MobSpawnType.EVENT, false, false);
                    helper.assertTrue(netherResident[0] != null, "test setup: Nether villager must be constructible");
                    boolean added = nether.addFreshEntity(netherResident[0]);
                    boolean chunkLoaded = nether.hasChunkAt(netherResidentPos);
                    boolean founded = storage.hasFoundedTown(TownKeys.keyFor(nether.dimension(), netherRegion));
                    Entity uuidLookup = nether.getEntity(netherResident[0].getUUID());
                    boolean uuidLookupMatches = uuidLookup == netherResident[0];
                    boolean alive = netherResident[0].isAlive();
                    boolean removed = netherResident[0].isRemoved();
                    boolean unhoused = netherResident[0].getBrain().getMemory(MemoryModuleType.HOME).isEmpty();
                    boolean correctRegion = TownRegion.fromBlockCoords(netherResident[0].blockPosition().getX(), netherResident[0].blockPosition().getZ())
                                .equals(netherRegion);
                    boolean inProductionIterable = false;
                    int sameRegionVillagers = 0;
                    int sameUuidEntities = 0;
                    for (Entity entity : nether.getEntities().getAll()) {
                        if (entity == netherResident[0]) {
                            inProductionIterable = true;
                        }
                        if (entity.getUUID().equals(netherResident[0].getUUID())) {
                            sameUuidEntities++;
                        }
                        if (entity instanceof Villager villager
                                && TownRegion.fromBlockCoords(villager.blockPosition().getX(), villager.blockPosition().getZ())
                                .equals(netherRegion)) {
                            sameRegionVillagers++;
                        }
                    }
                    String trackingState = "createdUuid=" + netherResident[0].getUUID()
                                + ", added=" + added
                                + ", lookup=" + uuidLookup
                                + ", lookupUuid=" + (uuidLookup == null ? "none" : uuidLookup.getUUID())
                                + ", sameInstance=" + uuidLookupMatches
                                + ", alive=" + alive
                                + ", removed=" + removed
                                + ", iterable=" + inProductionIterable
                                + ", sameUuidEntities=" + sameUuidEntities
                                + ", sameRegionVillagers=" + sameRegionVillagers
                                + ", requestedDimension=" + nether.dimension()
                                + ", actualDimension=" + netherResident[0].level().dimension()
                                + ", requestedRegion=" + netherRegion
                                + ", actualRegion=" + TownRegion.fromBlockCoords(netherResident[0].blockPosition().getX(), netherResident[0].blockPosition().getZ())
                                + ", position=" + netherResident[0].blockPosition();
                    helper.assertTrue(chunkLoaded, "test setup: forced Nether resident chunk must be loaded at " + netherResidentPos);
                    helper.assertTrue(founded, "test setup: Nether resident region must be founded: " + netherRegion);
                    helper.assertTrue(added && uuidLookupMatches && alive && !removed && unhoused && correctRegion && inProductionIterable
                                    && sameUuidEntities == 1,
                            "test setup: the exact created Nether resident must remain uniquely UUID-resolvable, live, unremoved, unhoused, "
                                    + "in the queried dimension/region, and visible to the production iterable; " + trackingState);
                    TownResidentDemand netherDemand = TownResidentAllocationHandler.unmetDemand(nether, netherRegion, storage);
                    helper.assertTrue(netherDemand == TownResidentDemand.NONE,
                            "the non-Overworld guard must suppress demand despite a visible founded Nether resident; demand=" + netherDemand);
                })
                .thenSucceed();
    }

    /** Actively claimed temporary Builders belong to the existing construction lifecycle and have no
     * retained final profession for the pure resident-demand value to request. */
    @GameTest(template = "settlement_test")
    public static void unmetDemandExcludesActiveTemporaryBuildersAndDiscardedResidents(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos anchor = anchor(helper);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        TownResidentDemand beforeBuilder = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        Villager builder = spawnVillager(helper, anchor.offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        builder.setVillagerData(builder.getVillagerData().setProfession(BuilderProfession.BUILDER.get()));
        BlockPos campfire = helper.absolutePos(anchor.offset(0, 1, 0));
        helper.getLevel().setBlock(campfire, Blocks.CAMPFIRE.defaultBlockState(), Block.UPDATE_ALL);
        builder.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), campfire));
        TownResidentDemand withBuilder = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        helper.assertTrue(withBuilder.equals(beforeBuilder),
                "an active temporary Builder must not change preexisting shared-region demand; before="
                        + beforeBuilder + ", withBuilder=" + withBuilder);

        Villager nitwit = spawnVillager(helper, anchor.offset(2, 1, 0));
        nitwit.setVillagerData(nitwit.getVillagerData().setProfession(VillagerProfession.NITWIT));
        TownResidentDemand withNitwit = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        helper.assertTrue(withNitwit.missingHomes() == withBuilder.missingHomes() + 1
                        && withNitwit.missingWorkplaces().equals(withBuilder.missingWorkplaces())
                        && !withNitwit.missingWorkplaces().containsKey(VillagerProfession.NITWIT),
                "a live NITWIT must add housing only, preserving preexisting workplace demand; before="
                        + withBuilder + ", withNitwit=" + withNitwit);
        nitwit.discard();
        Villager discarded = spawnVillager(helper, anchor.offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        discarded.discard();

        Villager waitingResident = spawnVillager(helper, anchor.offset(2, 1, 0));
        TownResidentDemand withResident = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        waitingResident.discard();
        TownResidentDemand afterDiscard = TownResidentAllocationHandler.unmetDemand(helper.getLevel(), region, storage);
        helper.assertTrue(withResident.missingHomes() == withBuilder.missingHomes() + 1
                        && afterDiscard.equals(withBuilder),
                "a live ordinary resident must count once, while a discarded resident must be filtered back out");
        helper.succeed();
    }

    /** The record makes a defensive immutable copy so callers cannot mutate a demand observation
     * after it has been returned by the resident/planning lane. */
    @GameTest(template = "settlement_test")
    public static void townResidentDemandIsImmutable(GameTestHelper helper) {
        Map<VillagerProfession, Integer> mutable = new HashMap<>();
        mutable.put(VillagerProfession.ARMORER, 1);
        TownResidentDemand demand = new TownResidentDemand(2, mutable);
        mutable.clear();
        boolean immutable = false;
        try {
            demand.missingWorkplaces().put(VillagerProfession.FARMER, 1);
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        helper.assertTrue(immutable && demand.missingHomes() == 2
                        && demand.missingWorkplaces().equals(Map.of(VillagerProfession.ARMORER, 1)),
                "TownResidentDemand must retain an immutable defensive copy of workplace counts");
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        TownStorage storage = newStorageWithFoundedTown(helper, regionOf(helper));
        markTownOwned(helper, storage, absoluteBedHead);

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos newBedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(newBedHead));

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));

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

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos wrongWorkstationPos = anchor(helper).offset(OLD_WORKSTATION_OFFSET.getX(), OLD_WORKSTATION_OFFSET.getY(), OLD_WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, wrongWorkstationPos, Blocks.FLETCHING_TABLE);
        BlockPos absoluteWrongWorkstationPos = helper.absolutePos(wrongWorkstationPos);
        // This villager's OWN real profession's matching workstation - a real armorer's blast
        // furnace.
        BlockPos matchingWorkstationPos = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, matchingWorkstationPos, Blocks.BLAST_FURNACE);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteWrongWorkstationPos);
        markTownOwned(helper, storage, helper.absolutePos(matchingWorkstationPos));

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.ARMORER));
        // No JOB_SITE memory at all - simulating "already Armorer, but its own workstation was lost".

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos campfireCell = anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), 0, FIRST_VILLAGER_OFFSET.getZ());
        helper.setBlock(campfireCell, Blocks.CAMPFIRE);
        BlockPos absoluteCampfire = helper.absolutePos(campfireCell);
        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));

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
        BlockPos staleCampfireCell = anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), 0, FIRST_VILLAGER_OFFSET.getZ());
        BlockPos absoluteStaleCampfire = helper.absolutePos(staleCampfireCell);
        // The remembered campfire position no longer holds a campfire at all (destroyed) - the
        // claim has genuinely lapsed, even though the profession field still says Builder.
        helper.setBlock(staleCampfireCell, Blocks.AIR);
        BlockPos workstationPos = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));

        TownStorage storage = new TownStorage();
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        storage.markTownFounded(TownKeys.keyFor(helper.getLevel().dimension(), region));
        markTownOwned(helper, storage, helper.absolutePos(workstationPos));

        Villager orphan = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
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
        BlockPos newBedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(newBedHead));

        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));
        BlockPos oldHomeInDifferentRegion = absoluteAnchor.offset(50_000, 65 - absoluteAnchor.getY(), 50_000);
        helper.assertTrue(!TownRegion.fromBlockCoords(oldHomeInDifferentRegion.getX(), oldHomeInDifferentRegion.getZ()).equals(region),
                "test setup: the old home's own offset must actually land in a different region");
        placeValidBedAt(helper.getLevel(), oldHomeInDifferentRegion);
        takeTicketDirectly(helper.getLevel(), oldHomeInDifferentRegion);
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(oldHomeInDifferentRegion) == 0,
                "test setup: the old (different-region) home's ticket must actually be held before reconciliation runs");

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), oldHomeInDifferentRegion));
        // [Review round 24] Release now requires POSITIVE proof of self-ownership - genuinely record
        // this old home as the villager's own (as production code would have on the original grant).
        storage.setReservationOwner(helper.getLevel().dimension(), oldHomeInDifferentRegion, villager.getUUID());

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos staleBedHead = anchor(helper).offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, staleBedHead); // registered, but its ticket is never taken - fully free
        BlockPos newBedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(newBedHead));

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        BlockPos absoluteStaleBedHead = helper.absolutePos(staleBedHead);
        villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteStaleBedHead));
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteStaleBedHead) > 0,
                "test setup: the stale home's own ticket must NOT actually be held (fully free) before reconciliation runs");

        BlockPos townCenter = helper.absolutePos(anchor(helper));
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), villager, townCenter, region, storage);

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> !gp.pos().equals(absoluteStaleBedHead)).orElse(false),
                "a HOME memory whose own ticket is not actually held must never be treated as already housed");
        helper.succeed();
    }

    /** [Review round 19, finding #2] A real, vacant, correctly-typed, SAME-REGION workstation that
     *  Dynamic Towns itself never built (a same-region vanilla- or player-placed fixture) must never
     *  be matched, even though region filtering alone would otherwise accept it - proving the
     *  town-ownership filter ({@link TownStorage#isTownBuiltFixture}) is actually what excludes it, not
     *  merely the region check. */
    @GameTest(template = "settlement_test")
    public static void aSameRegionNonTownOwnedWorkstationIsNeverMatched(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos workstationPos = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        // Deliberately NOT registered via storage.markCellPlaced - standing in for a real, vacant,
        // same-region workstation Dynamic Towns itself never built.
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        BlockPos townCenter = helper.absolutePos(anchor(helper));
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
        BlockPos staleCampfireCell = anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), 0, FIRST_VILLAGER_OFFSET.getZ());
        helper.setBlock(staleCampfireCell, Blocks.AIR); // claim already lapsed - no real campfire
        BlockPos absoluteStaleCampfire = helper.absolutePos(staleCampfireCell);

        // Deliberately an EMPTY TownStorage - no town founded anywhere, including this villager's own
        // current region - standing in for a Builder whose region Dynamic Towns has never founded.
        TownStorage storage = new TownStorage();

        Villager builder = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
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
        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));
        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        TownStorage storage = newStorageWithFoundedTown(helper, region);

        ServerLevel otherDimensionLevel = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(otherDimensionLevel != null, "test setup: the nether dimension must be loaded in this GameTest server");
        BlockPos oldClaimInOtherDimension = new BlockPos(1_000, 65, 1_000);
        placeValidBedAt(otherDimensionLevel, oldClaimInOtherDimension); // a real, reservable stand-in fixture; this orphan path never requires it to be a campfire specifically
        takeTicketDirectly(otherDimensionLevel, oldClaimInOtherDimension);
        helper.assertTrue(otherDimensionLevel.getPoiManager().getFreeTickets(oldClaimInOtherDimension) == 0,
                "test setup: the cross-dimension old claim's ticket must actually be held before reconciliation runs");

        Villager orphan = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        // A SECOND, genuinely different vacant bed - without this, an impostor correctly rejected
        // from the stolen bed would simply have no alternative at all, leaving its stale memory
        // unchanged (nothing to overwrite it with) and making the rejection itself unobservable.
        // With a real alternative present, actually ending up reassigned HERE is the positive proof
        // the stale memory was genuinely treated as invalid, not merely left alone by coincidence.
        BlockPos secondBedHead = anchor(helper).offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, secondBedHead);
        BlockPos absoluteSecondBedHead = helper.absolutePos(secondBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        markTownOwned(helper, storage, absoluteSecondBedHead);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        Villager rightfulOwner = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
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
        Villager impostor = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead); // registered, but its ticket is never taken - fully free
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        Villager first = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        // A stale memory pointing at the SOLE eligible bed, whose ticket is NOT currently held -
        // exactly the sequence that previously caused a self-inflicted immediate release.
        first.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));
        // A second real resident, also unhoused, also searching the same space - proving the fix
        // holds with more than one villager present, not merely in isolation.
        Villager second = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));

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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        BlockPos secondBedHead = anchor(helper).offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, secondBedHead);
        BlockPos absoluteSecondBedHead = helper.absolutePos(secondBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        markTownOwned(helper, storage, absoluteSecondBedHead);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        // An UNTRACKED resident: its real reservation at the first bed was never granted through
        // this mod's own ownership-recording code paths (standing in for vanilla's own natural AI,
        // or one that predates this tracking) - TownStorage has no owner recorded for it at all.
        Villager untrackedResident = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(helper.getLevel(), absoluteBedHead);
        untrackedResident.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));

        // A second, unrelated villager whose own memory also (incorrectly/stalely) points at that
        // exact same, genuinely-occupied bed.
        Villager impostor = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, helper.absolutePos(bedHead));
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        Villager owner = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
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

        Villager impostor = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
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
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteBedHead);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        // B's real, genuinely-held reservation - gone (unloaded/despawned) before this reconciliation
        // has EVER once looked at it. No registry entry, no live villager left to confirm it either.
        Villager neverValidatedOwner = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(helper.getLevel(), absoluteBedHead);
        neverValidatedOwner.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteBedHead));
        neverValidatedOwner.discard();

        // A's own stale memory also (incorrectly) points at that same, genuinely-occupied bed.
        Villager stale = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
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
        BlockPos workstationPos = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, workstationPos, Blocks.BLAST_FURNACE);
        BlockPos absoluteWorkstationPos = helper.absolutePos(workstationPos);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteWorkstationPos);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        // B's real, genuinely-held workstation - gone before this reconciliation ever once validated it.
        Villager neverValidatedOwner = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        neverValidatedOwner.setVillagerData(neverValidatedOwner.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(helper.getLevel(), absoluteWorkstationPos);
        neverValidatedOwner.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteWorkstationPos));
        neverValidatedOwner.discard();

        // A's own stale memory and matching profession also (incorrectly) point at that same workstation.
        Villager stale = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        stale.setVillagerData(stale.getVillagerData().setProfession(VillagerProfession.ARMORER));
        stale.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absoluteWorkstationPos));

        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(helper.getLevel(), stale, townCenter, region, storage);

        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteWorkstationPos) == 0,
                "an unresolved workstation reservation must never be released just because a stale memory also pointed at it");
        helper.assertTrue(!storage.isReservedBySelf(helper.getLevel().dimension(), absoluteWorkstationPos, stale.getUUID()),
                "no ownership record must be fabricated for anyone just because this reservation could not be resolved");
        helper.succeed();
    }

    /** [Review round 25] The registry's "self" record is a HISTORICAL fact, not a live one - it can
     *  go stale: if the physical ticket is later released and re-acquired by a real, different
     *  villager entirely outside this mod's own bookkeeping (standing in for vanilla's own natural
     *  AI naturally taking over an abandoned fixture), the registry still says the ORIGINAL owner,
     *  even though someone else now genuinely, currently holds it. Positive registry proof alone is
     *  therefore not sufficient for release - actually exercises the replacement-release path (the
     *  round-24 tests never did: their "old" reservation had no genuine new, live occupant to
     *  protect), proving the live-scan veto restored this round actually fires. */
    @GameTest(template = "settlement_test")
    public static void aStaleRegistryRecordNeverReleasesARealVillagerThatGenuinelyTookOverTheOldPosition(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos oldBedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, oldBedHead);
        BlockPos absoluteOldBedHead = helper.absolutePos(oldBedHead);
        BlockPos newBedHead = anchor(helper).offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        BlockPos absoluteNewBedHead = helper.absolutePos(newBedHead);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteOldBedHead);
        markTownOwned(helper, storage, absoluteNewBedHead);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        Villager a = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        // A's registry record at the old bed is genuinely established, exactly as a real prior grant
        // would have.
        storage.setReservationOwner(helper.getLevel().dimension(), absoluteOldBedHead, a.getUUID());
        a.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteOldBedHead));

        // The ticket is released OUTSIDE this mod's own tracked API (standing in for some real-world
        // event this mod never observes), and a REAL, different, alive villager B genuinely,
        // naturally takes it over - while the registry's own "A" record is never told, and so goes
        // stale.
        helper.getLevel().getPoiManager().release(absoluteOldBedHead);
        Villager b = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(helper.getLevel(), absoluteOldBedHead);
        b.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(helper.getLevel().dimension(), absoluteOldBedHead));

        // A must now be reassigned (its own stale memory fails validation: the live scan already
        // shows B's own, different, current claim on that exact position) - triggering an attempted
        // release of A's OLD, now stale, registry-recorded home.
        TownResidentAllocationHandler.allocateHomeIfNeeded(helper.getLevel(), a, townCenter, region, storage);

        helper.assertTrue(a.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(absoluteNewBedHead)).orElse(false),
                "A must be reassigned the real, genuinely vacant alternative instead of keeping its stale memory");
        helper.assertTrue(helper.getLevel().getPoiManager().getFreeTickets(absoluteOldBedHead) == 0,
                "B's real, current ticket must never be released just because the registry's own stale history still says A owns it");
        helper.assertTrue(b.getBrain().getMemory(MemoryModuleType.HOME)
                        .map(gp -> gp.pos().equals(absoluteOldBedHead)).orElse(false),
                "B's own HOME memory must be completely unaffected by A's reconciliation");
        // [Review round 25] The vetoed record is reconciled, not kept: left in place it would
        // authorize exactly this release the moment B unloads and the live scan can no longer see it.
        helper.assertTrue(!storage.isReservedBySelf(helper.getLevel().dimension(), absoluteOldBedHead, a.getUUID()),
                "the stale record the live veto contradicted must be dropped, not left to authorize a release once B unloads");
        helper.assertTrue(!storage.isReservedBySelf(helper.getLevel().dimension(), absoluteOldBedHead, b.getUUID()),
                "B must not be recorded as owner either - an observed memory is not proof of ownership");
        helper.succeed();
    }

    private static void placeWorkstationAt(ServerLevel level, BlockPos absolutePos, Block block) {
        level.setBlock(absolutePos, block.defaultBlockState(), Block.UPDATE_ALL);
        registerPoiImmediately(level, absolutePos);
    }

    /** [Review round 25] The JOB_SITE counterpart of
     *  {@link #aStaleRegistryRecordNeverReleasesARealVillagerThatGenuinelyTookOverTheOldPosition}:
     *  A's recorded workstation is re-granted to B outside this mod's bookkeeping, and A's genuine
     *  replacement (a second vacant workstation of its own profession) must never release B's
     *  ticket - and must drop the contradicted record. */
    @GameTest(template = "settlement_test")
    public static void aStaleRegistryRecordNeverReleasesAWorkstationAnotherVillagerGenuinelyTookOver(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        BlockPos oldWorkstation = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, oldWorkstation, Blocks.BLAST_FURNACE);
        BlockPos absoluteOldWorkstation = helper.absolutePos(oldWorkstation);
        BlockPos newWorkstation = anchor(helper).offset(OLD_WORKSTATION_OFFSET.getX(), OLD_WORKSTATION_OFFSET.getY(), OLD_WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, newWorkstation, Blocks.BLAST_FURNACE);
        BlockPos absoluteNewWorkstation = helper.absolutePos(newWorkstation);
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        markTownOwned(helper, storage, absoluteOldWorkstation);
        markTownOwned(helper, storage, absoluteNewWorkstation);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        // This mod originally granted the workstation to A: ticket taken, owner recorded.
        Villager a = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        a.setVillagerData(a.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(level, absoluteOldWorkstation);
        storage.setReservationOwner(level.dimension(), absoluteOldWorkstation, a.getUUID());
        a.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), absoluteOldWorkstation));

        // Vanilla releases it and re-grants it to B; the registry is never told.
        level.getPoiManager().release(absoluteOldWorkstation);
        Villager b = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        b.setVillagerData(b.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(level, absoluteOldWorkstation);
        b.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), absoluteOldWorkstation));

        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, a, townCenter, region, storage);

        helper.assertTrue(a.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(absoluteNewWorkstation)).orElse(false),
                "A must be moved to the genuinely vacant replacement workstation");
        helper.assertTrue(level.getPoiManager().getFreeTickets(absoluteOldWorkstation) == 0,
                "B's real, current workstation ticket must never be released on the strength of A's stale record");
        helper.assertTrue(b.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                        .map(gp -> gp.pos().equals(absoluteOldWorkstation)).orElse(false),
                "B's own JOB_SITE memory must be completely unaffected by A's reconciliation");
        helper.assertTrue(!storage.isReservedBySelf(level.dimension(), absoluteOldWorkstation, a.getUUID())
                        && !storage.isReservedBySelf(level.dimension(), absoluteOldWorkstation, b.getUUID()),
                "the contradicted record must be dropped, and B must not be recorded in its place");
        helper.succeed();
    }

    /**
     * [Review round 25, retained P1] The live-scan veto cannot protect a takeover owner that is no
     * longer loaded. Tracked A was granted home P and workstation W (in A's OLD town); vanilla
     * re-granted both tickets to B outside this mod's bookkeeping; B's chunk then unloads BEFORE any
     * reconciliation pass has ever seen B. A, now living in a different town, genuinely needs
     * replacements there - which runs the old-reservation release path for P and W.
     *
     * <p>Lifecycle exercised, in order: B unloads through the real removal path (tracking end, the
     * same callback a chunk unload runs, firing the registered {@code EntityLeaveLevelEvent}
     * listener against the real server-wide {@link TownStorage}); that storage is saved and loaded
     * back, standing in for a restart; A reconciles against the reloaded storage; B's saved form is
     * then loaded back into the level, as its chunk reloading would. Both tickets must survive every
     * step, A's stale records must not, nothing may be recorded for B, and the reloaded B must still
     * be validly housed and employed - a vacant town-built bed and workstation next to its own stay
     * untouched, which they would not if B's reservations were judged invalid.
     */
    @GameTest(template = "settlement_test")
    public static void aTakeoverOwnerThatUnloadsBeforeReconciliationKeepsBothTicketsAcrossSaveAndReload(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        RegionCoord region = regionOf(helper);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        // A's replacement destinations in its CURRENT town (this test's own region).
        BlockPos newBedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        BlockPos newWorkstation = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, newWorkstation, Blocks.BLAST_FURNACE);

        // A's OLD home and workstation, in a genuinely different region, plus a vacant bed and
        // workstation there for B to be (wrongly) moved to. A distinct offset from
        // aResidentWhoseHomeIsOutsideItsCurrentRegionIsReassignedAndTheOldTicketReleased's own, so the
        // two tests' far fixtures can never land on each other.
        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));
        BlockPos oldHome = distantFixtureHome(absoluteAnchor, 60_000, -60_000);
        BlockPos oldWorkstation = oldHome.offset(4, 0, 0);
        BlockPos spareBed = oldHome.offset(-4, 0, 0);
        BlockPos spareWorkstation = oldHome.offset(8, 0, 0);
        RegionCoord oldRegion = TownRegion.fromBlockCoords(oldHome.getX(), oldHome.getZ());
        BlockPos spareBedFoot = spareBed.north();
        helper.assertTrue(!oldRegion.equals(region)
                        && TownRegion.fromBlockCoords(oldWorkstation.getX(), oldWorkstation.getZ()).equals(oldRegion)
                        && TownRegion.fromBlockCoords(spareBed.getX(), spareBed.getZ()).equals(oldRegion)
                        && TownRegion.fromBlockCoords(spareBedFoot.getX(), spareBedFoot.getZ()).equals(oldRegion)
                        && TownRegion.fromBlockCoords(spareWorkstation.getX(), spareWorkstation.getZ()).equals(oldRegion)
                        && distantFixtureHomeRegressionHolds(),
                "test setup: every old fixture position and bed foot must share one different region and deterministic lane-boundary cases");
        placeValidBedAt(level, oldHome);
        placeWorkstationAt(level, oldWorkstation, Blocks.BLAST_FURNACE);
        placeValidBedAt(level, spareBed);
        placeWorkstationAt(level, spareWorkstation, Blocks.BLAST_FURNACE);

        // This mod originally granted both to A: tickets taken, owners recorded in the REAL
        // server-wide storage - the one the registered unload listener reconciles.
        Villager a = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        a.setVillagerData(a.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(level, oldHome);
        takeTicketDirectly(level, oldWorkstation);
        TownStorage real = TownStorage.of(level.getServer());
        real.setReservationOwner(level.dimension(), oldHome, a.getUUID());
        real.setReservationOwner(level.dimension(), oldWorkstation, a.getUUID());
        a.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), oldHome));
        a.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), oldWorkstation));

        // Vanilla releases both and re-grants them to B; the registry is never told.
        level.getPoiManager().release(oldHome);
        level.getPoiManager().release(oldWorkstation);
        Villager b = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        b.setVillagerData(b.getVillagerData().setProfession(VillagerProfession.ARMORER));
        takeTicketDirectly(level, oldHome);
        takeTicketDirectly(level, oldWorkstation);
        b.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), oldHome));
        b.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), oldWorkstation));
        helper.assertTrue(level.getPoiManager().getFreeTickets(oldHome) == 0 && level.getPoiManager().getFreeTickets(oldWorkstation) == 0,
                "test setup: B must actually hold both tickets");

        // B unloads before any reconciliation pass has seen it.
        CompoundTag savedB = new CompoundTag();
        helper.assertTrue(b.save(savedB), "test setup: B must serialize like a chunk save would");
        boolean wasDirty = real.isDirty();
        real.setDirty(false);
        b.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        boolean dirtiedByUnload = real.isDirty();
        real.setDirty(wasDirty || dirtiedByUnload);
        helper.assertTrue(level.getEntity(b.getUUID()) == null, "test setup: an unloaded B must be invisible to the live scan");
        helper.assertTrue(!real.isReservedBySelf(level.dimension(), oldHome, a.getUUID())
                        && !real.isReservedBySelf(level.dimension(), oldWorkstation, a.getUUID()),
                "B unloading with memories that contradict A's records must drop those records");
        helper.assertTrue(!real.isReservedBySelf(level.dimension(), oldHome, b.getUUID())
                        && !real.isReservedBySelf(level.dimension(), oldWorkstation, b.getUUID()),
                "B must not be recorded as owner either - an observed memory is not proof of ownership");
        helper.assertTrue(dirtiedByUnload, "dropping the records must mark the storage dirty, or a real save could skip it");

        // Restart: only the persisted form survives.
        TownStorage reloaded = TownStorage.load(real.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        helper.assertTrue(!reloaded.isReservedBySelf(level.dimension(), oldHome, a.getUUID())
                        && !reloaded.isReservedBySelf(level.dimension(), oldWorkstation, a.getUUID()),
                "the dropped records must stay dropped across a save/load round trip");
        markTownOwned(helper, reloaded, helper.absolutePos(newBedHead));
        markTownOwned(helper, reloaded, helper.absolutePos(newWorkstation));
        markTownOwned(helper, reloaded, spareBed);
        markTownOwned(helper, reloaded, spareWorkstation);

        // A reconciles in its current town: both old reservations are out of region, so A really
        // does take replacements, and the old-reservation release path runs for P and W.
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, a, townCenter, region, reloaded);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, a, townCenter, region, reloaded);
        helper.assertTrue(a.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> !gp.pos().equals(oldHome)).orElse(false)
                        && a.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> !gp.pos().equals(oldWorkstation)).orElse(false),
                "test setup: A must actually have been moved to replacements, or the release path never ran");
        helper.assertTrue(level.getPoiManager().getFreeTickets(oldHome) == 0,
                "B's home ticket must be preserved while B is unloaded - nothing proves A still owns it");
        helper.assertTrue(level.getPoiManager().getFreeTickets(oldWorkstation) == 0,
                "B's workstation ticket must be preserved while B is unloaded - nothing proves A still owns it");

        // B's chunk loads again.
        Villager reloadedB = (Villager) EntityType.create(savedB, level).orElseThrow();
        helper.assertTrue(level.addFreshEntity(reloadedB), "test setup: B must load back into the level");
        helper.assertTrue(reloadedB.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(oldHome)).orElse(false)
                        && reloadedB.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(oldWorkstation)).orElse(false),
                "test setup: the reloaded B must come back with its own memories");
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, reloadedB, oldHome, oldRegion, reloaded);
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, reloadedB, oldHome, oldRegion, reloaded);
        helper.assertTrue(reloadedB.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(oldHome)).orElse(false)
                        && reloadedB.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(oldWorkstation)).orElse(false)
                        && reloadedB.getVillagerData().getProfession() == VillagerProfession.ARMORER,
                "the reloaded B must still be validly housed and employed at its own reservations");
        helper.assertTrue(level.getPoiManager().getFreeTickets(spareBed) > 0 && level.getPoiManager().getFreeTickets(spareWorkstation) > 0,
                "the reloaded B must not have been moved: its reservations were never vacated or reassigned");
        helper.assertTrue(level.getPoiManager().getFreeTickets(oldHome) == 0 && level.getPoiManager().getFreeTickets(oldWorkstation) == 0,
                "both tickets must still be held after B reloads");
        helper.succeed();
    }

    /**
     * [Review round 25, retained P1] The restart variant: a server stop does not unload spawn or
     * force-loaded chunks, so no unload event fires for a takeover owner standing in one. The
     * periodic reconciliation pass is what drops the contradicted record there - it runs while
     * both A and B are loaded, before A has anywhere new to go. After a save/load in which B never
     * comes back, A's eventual relocation must still preserve B's ticket.
     */
    @GameTest(template = "settlement_test")
    public static void aTakeoverSeenByTheReconciliationPassIsPreservedAcrossARestartWithoutItsOwner(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        BlockPos absoluteAnchor = helper.absolutePos(anchor(helper));
        BlockPos oldHome = absoluteAnchor.offset(-70_000, 65 - absoluteAnchor.getY(), 70_000);
        helper.assertTrue(!TownRegion.fromBlockCoords(oldHome.getX(), oldHome.getZ()).equals(region),
                "test setup: the old home must be in a different region from A's current one");
        placeValidBedAt(level, oldHome);

        Villager a = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(level, oldHome);
        storage.setReservationOwner(level.dimension(), oldHome, a.getUUID());
        a.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), oldHome));

        level.getPoiManager().release(oldHome);
        Villager b = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        takeTicketDirectly(level, oldHome);
        b.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), oldHome));

        // The production reconciliation pass, with no vacant town-built bed anywhere yet - so A
        // cannot move this pass, and only the reconciliation itself can touch the record.
        TownResidentAllocationHandler.runResidentAllocationTick(level, storage);
        helper.assertTrue(!storage.isReservedBySelf(level.dimension(), oldHome, a.getUUID())
                        && !storage.isReservedBySelf(level.dimension(), oldHome, b.getUUID()),
                "the reconciliation pass must drop the record B's live memory contradicts, recording nobody in its place");
        helper.assertTrue(level.getPoiManager().getFreeTickets(oldHome) == 0, "nothing may be released by that pass");

        // Restart in which B's chunk never loads again.
        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        b.discard();
        BlockPos newBedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, newBedHead);
        BlockPos absoluteNewBedHead = helper.absolutePos(newBedHead);
        markTownOwned(helper, reloaded, absoluteNewBedHead);

        TownResidentAllocationHandler.allocateHomeIfNeeded(level, a, townCenter, region, reloaded);

        helper.assertTrue(a.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(absoluteNewBedHead)).orElse(false),
                "test setup: A must actually move to the new bed, or the release path never ran");
        helper.assertTrue(level.getPoiManager().getFreeTickets(oldHome) == 0,
                "B's ticket must survive a restart in which B never reloaded");
        helper.succeed();
    }

    /** A cell recorded exactly as {@code TownConstructionService} records an ordinary paid wall or
     *  floor block - paid-for progress, but never a fixture. */
    private static void markPaidNonFixtureCell(GameTestHelper helper, TownStorage storage, BlockPos absolutePos) {
        storage.markCellPlaced(helper.getLevel().dimension(), absolutePos, "site:test-owner",
                "RESIDENTIAL:0:none:minecraft:oak_planks:WOOD", Blocks.OAK_PLANKS.defaultBlockState());
    }

    /** [Review round 25, P2] A coordinate the town once paid for is not a town-built fixture: a
     *  player who replaces a paid wall cell with their own bed must never get that bed treated as
     *  town housing, while a town bed that is still exactly what the town placed stays eligible.
     *  Both records go through a save/load round trip first, as they would across a restart. */
    @GameTest(template = "settlement_test")
    public static void aPlayerBedOnAHistoricallyPaidCellIsNeverReservableWhileAnUnchangedTownBedStillIs(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        BlockPos paidWallCell = anchor(helper).offset(OLD_HOME_OFFSET.getX(), OLD_HOME_OFFSET.getY(), OLD_HOME_OFFSET.getZ());
        BlockPos absolutePaidWallCell = helper.absolutePos(paidWallCell);
        markPaidNonFixtureCell(helper, storage, absolutePaidWallCell);
        placeValidBed(helper, paidWallCell); // the player's bed, its head on the paid wall cell

        BlockPos townBed = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, townBed);
        BlockPos absoluteTownBed = helper.absolutePos(townBed);
        markTownOwned(helper, storage, absoluteTownBed);

        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());

        Villager first = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, first, townCenter, region, reloaded);
        helper.assertTrue(first.getBrain().getMemory(MemoryModuleType.HOME).map(gp -> gp.pos().equals(absoluteTownBed)).orElse(false),
                "an unchanged town-built bed must remain eligible");

        Villager second = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, second, townCenter, region, reloaded);
        helper.assertTrue(second.getBrain().getMemory(MemoryModuleType.HOME).isEmpty(),
                "a player's bed on a historically paid wall cell must never be reserved as town housing");
        helper.assertTrue(level.getPoiManager().getFreeTickets(absolutePaidWallCell) > 0,
                "the player's bed must be left unreserved");
        helper.succeed();
    }

    /** [Review round 25, P2] The workstation counterpart, through both allocation paths (an
     *  unemployed villager, and one that already holds the matching profession): neither a player's
     *  blast furnace on a paid floor cell nor one swapped in for the smoker the town actually placed
     *  may be reserved, while the town's own unchanged blast furnace stays eligible. */
    @GameTest(template = "settlement_test")
    public static void aPlayerWorkstationOnAHistoricallyPaidCellIsNeverReservableWhileAnUnchangedTownOneStillIs(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        BlockPos townCenter = helper.absolutePos(anchor(helper));

        BlockPos paidFloorCell = anchor(helper).offset(OLD_WORKSTATION_OFFSET.getX(), OLD_WORKSTATION_OFFSET.getY(), OLD_WORKSTATION_OFFSET.getZ());
        BlockPos absolutePaidFloorCell = helper.absolutePos(paidFloorCell);
        markPaidNonFixtureCell(helper, storage, absolutePaidFloorCell);
        placeWorkstation(helper, paidFloorCell, Blocks.BLAST_FURNACE);

        BlockPos swappedFixtureCell = anchor(helper).offset(2, 1, -2);
        BlockPos absoluteSwappedFixtureCell = helper.absolutePos(swappedFixtureCell);
        storage.markCellPlaced(level.dimension(), absoluteSwappedFixtureCell, "site:test-owner",
                "COMMERCIAL:0:minecraft:butcher:minecraft:smoker:STONE", Blocks.SMOKER.defaultBlockState());
        placeWorkstation(helper, swappedFixtureCell, Blocks.BLAST_FURNACE);

        BlockPos townWorkstation = anchor(helper).offset(WORKSTATION_OFFSET.getX(), WORKSTATION_OFFSET.getY(), WORKSTATION_OFFSET.getZ());
        placeWorkstation(helper, townWorkstation, Blocks.BLAST_FURNACE);
        BlockPos absoluteTownWorkstation = helper.absolutePos(townWorkstation);
        markTownOwned(helper, storage, absoluteTownWorkstation);

        TownStorage reloaded = TownStorage.load(storage.save(new CompoundTag(), level.registryAccess()), level.registryAccess());

        Villager unemployed = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        unemployed.setVillagerData(unemployed.getVillagerData().setProfession(VillagerProfession.NONE));
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, unemployed, townCenter, region, reloaded);
        helper.assertTrue(unemployed.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(gp -> gp.pos().equals(absoluteTownWorkstation)).orElse(false)
                        && unemployed.getVillagerData().getProfession() == VillagerProfession.ARMORER,
                "an unchanged town-built workstation must remain eligible");

        Villager armorer = spawnVillager(helper, anchor(helper).offset(SECOND_VILLAGER_OFFSET.getX(), SECOND_VILLAGER_OFFSET.getY(), SECOND_VILLAGER_OFFSET.getZ()));
        armorer.setVillagerData(armorer.getVillagerData().setProfession(VillagerProfession.ARMORER));
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, armorer, townCenter, region, reloaded);
        helper.assertTrue(armorer.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty(),
                "neither a player workstation on a paid floor cell nor one swapped in for a different town fixture may be reserved");
        Villager secondUnemployed = spawnVillager(helper, anchor(helper).offset(0, 1, 0));
        secondUnemployed.setVillagerData(secondUnemployed.getVillagerData().setProfession(VillagerProfession.NONE));
        TownResidentAllocationHandler.allocateWorkplaceIfNeeded(level, secondUnemployed, townCenter, region, reloaded);
        helper.assertTrue(secondUnemployed.getVillagerData().getProfession() == VillagerProfession.NONE
                        && secondUnemployed.getBrain().getMemory(MemoryModuleType.JOB_SITE).isEmpty(),
                "the unemployed path must not reserve either player workstation either");
        helper.assertTrue(level.getPoiManager().getFreeTickets(absolutePaidFloorCell) > 0
                        && level.getPoiManager().getFreeTickets(absoluteSwappedFixtureCell) > 0,
                "both player workstations must be left unreserved");
        helper.succeed();
    }

    /** [Review round 25, P2] Migration: a placed-cell record saved before fixture identity was
     *  recorded carries no proof of WHICH fixture (if any) the town placed there, so it is not
     *  reservable by allocation - the fail-safe default. Residents already housed or employed at
     *  such a fixture keep it (validation of an existing memory never consults provenance). */
    @GameTest(template = "settlement_test")
    public static void aPlacedCellSavedBeforeFixtureIdentityExistedIsNotReservable(GameTestHelper helper) {
        prepareFlatGround(helper);
        ServerLevel level = helper.getLevel();
        RegionCoord region = regionOf(helper);
        TownStorage storage = newStorageWithFoundedTown(helper, region);
        BlockPos bedHead = anchor(helper).offset(BED_HEAD_OFFSET.getX(), BED_HEAD_OFFSET.getY(), BED_HEAD_OFFSET.getZ());
        placeValidBed(helper, bedHead);
        BlockPos absoluteBedHead = helper.absolutePos(bedHead);
        markTownOwned(helper, storage, absoluteBedHead);

        CompoundTag saved = storage.save(new CompoundTag(), level.registryAccess());
        net.minecraft.nbt.ListTag placedCells = saved.getList("PlacedCells", net.minecraft.nbt.Tag.TAG_COMPOUND);
        helper.assertTrue(!placedCells.isEmpty(), "test setup: the bed's record must have been saved");
        for (int i = 0; i < placedCells.size(); i++) {
            placedCells.getCompound(i).remove("Fixture"); // the pre-change save format
        }
        TownStorage legacy = TownStorage.load(saved, level.registryAccess());

        Villager villager = spawnVillager(helper, anchor(helper).offset(FIRST_VILLAGER_OFFSET.getX(), FIRST_VILLAGER_OFFSET.getY(), FIRST_VILLAGER_OFFSET.getZ()));
        TownResidentAllocationHandler.allocateHomeIfNeeded(level, villager, helper.absolutePos(anchor(helper)), region, legacy);

        helper.assertTrue(villager.getBrain().getMemory(MemoryModuleType.HOME).isEmpty()
                        && level.getPoiManager().getFreeTickets(absoluteBedHead) > 0,
                "a legacy record without fixture identity must not make its cell reservable");
        helper.succeed();
    }
}
