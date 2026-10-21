package io.github.brooswitminecraft.dynamicvehicles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Room to drive in, for the driving GameTests whose cars really travel (PR #180 review): a floor laid at a
 * fixed site near the world origin, lanes along it, and a per-tick check that a car stays on its own lane.
 *
 * <p>Why these fixtures need it. GameTest places every test's structure at a random position up to
 * &plusmn;15,000,000 blocks out ({@code GameTestServer#startTests}), and how far a car travels depends on
 * where that lands: far out, Sable's single-precision positions round the car back in place every step while
 * its velocity integrates, so it drives on the spot; nearer the origin it really travels. Several fixtures
 * assumed the first case. They parked their cars nose to tail in one lane of the 24 x 24 {@code
 * settlement_test} template, so in any run placed with fine precision the cars drove into each other and
 * into the template's barrier wall, and the speed and load assertions then read a crashed car.
 *
 * <p>Lanes give every car its own room, {@link #LANE_SPACING} blocks from the next and as long as the longest
 * run these fixtures drive (an elevated-drag coast-down, about 100 m). The floor is laid at a fixed site rather
 * than in the test's own structure because a car driving on the spot also wears the spot: every tick of each
 * wheel's tyre wear (see {@link Wear}) lands on the one block under it. With these fixtures' lanes in the
 * randomly placed structure, the per-tick wear chances measured over whole runs put the odds that some checked
 * lane block is worn through two damage stages -- grass past dirt, stone past cobblestone -- at 25-37% for a
 * run placed far out, against 1.6-2.4% where the cars really drove, and three of the four far-placed full runs
 * made that way failed a lane check on gravel. At a fixed site every run is the one where the cars drive,
 * wherever GameTest put the structure; the repo builds its other driving rigs at fixed coordinates for the
 * same reason ({@link CarDrivetrainGameTests#carRecoversCleanlyAfterDrivingOffALedgeAndLanding}, the motorboat
 * rigs in {@link NewVehicleGameTests}).
 *
 * <p>Relative coordinates are measured from the fixture's {@link Site} origin, the way a structure's are
 * from its origin. The floor is {@code settlement_test}'s own -- two layers of grass, open above -- stretched
 * to {@link #WIDTH} x {@link #LENGTH}: lane material goes in at {@link #FLOOR_Y}, flush with the grass, and
 * cars spawn resting on it at {@link #SPAWN_Y}, on whole-block coordinates centred on their lane, facing +z
 * along it. Everything a rig does -- the chunks it forces, the blocks it changes, the cars it spawns -- is
 * recorded as it happens and undone when the test ends, however it ends.
 */
final class VehicleLanes {
    /** The floor: three lanes across, and long enough for the longest run these fixtures drive. */
    static final int WIDTH = 24;
    static final int LENGTH = 128;
    /** The layer lane material replaces: the floor's top grass layer, so a lane is flush with the floor. */
    static final int FLOOR_Y = 2;
    /** A car spawned here rests on the lane, not inside it. */
    static final int SPAWN_Y = FLOOR_Y + 1;
    /** Air kept clear above the floor, from {@link #SPAWN_Y} up: well over a car's height. */
    private static final int CLEARANCE = 4;
    /** Lane centre lines, in x. A car centred on one has its wheel tracks at x &plusmn; 0.8. */
    static final int FIRST_LANE_X = 4;
    static final int SECOND_LANE_X = 12;
    static final int THIRD_LANE_X = 20;
    static final int LANE_SPACING = SECOND_LANE_X - FIRST_LANE_X;
    /** A lane covers x in [centre - 2, centre + 2): 1.2 m of its own material outside each wheel track. */
    static final int LANE_HALF_WIDTH = 2;
    /** Where cars spawn along their lane, unless a fixture needs room behind the car as well. */
    static final int SPAWN_Z = 6;
    /** Extra {@code timeoutTicks} a fixture allows for its site's chunks to start ticking entities, which
     * {@link #build} waits for before running it -- a tick or two, since {@link #forceChunks} loads them. */
    static final int CHUNK_LOAD_ALLOWANCE = 40;

    /** Sites lie along z from here, {@link #SITE_SPACING} blocks apart: near enough the origin for
     * position precision of about 2 mm, and clear of the other fixed-coordinate rigs (the motorboat rigs
     * around z = 2,000 and the ledge rig around z = 20,000). Chunk-aligned. */
    private static final int FIRST_SITE_Z = 30_000;
    private static final int SITE_SPACING = 256;
    private static final int SITE_Y = 100;

    /** Each fixture's own site, so fixtures running at the same time never share ground. One constant per
     * fixture, so a new fixture cannot quietly reuse another's. */
    enum Site {
        STONE_VS_SAND,
        MIXED_MATERIALS,
        SLAB_OVER_SAND,
        AIRBORNE_OVER_PIT,
        FRONT_ONLY_WING,
        WING_AT_TWO_SPEEDS,
        ELEVATED_DRAG,
        APPLIED_WING,
        WING_VS_CONTROL,
        NON_SHIFTER,
        OPPOSING_THROTTLE,
        SUB_STEP_SPEED,
        GEAR_RATIO,
        LIMITER_WALL,
        INTEGRATION_CONTACT,
        EXHAUST_CADENCE,
        EXHAUST_DISABLED,
        WHEEL_DUST,
        CRASH_ACCEPTANCE,
        DUST_REVERSE,
        DUST_BURNOUT;

        BlockPos origin() {
            return new BlockPos(0, SITE_Y, FIRST_SITE_Z + ordinal() * SITE_SPACING);
        }
    }

    /**
     * How much of its own wear a lane may show under a checked wheel and still count as that lane. The wear is
     * Dynamic Terrain's: each tick a wheel slips (and a little more under throttle, {@code CarEffectsMath#wearSlip})
     * the block under it may move one stage along Dynamic Terrain's {@code damage} table -- grass to dirt to
     * gravel to air, stone and smooth stone to cobblestone to gravel to air; sand and slabs have no stage. The
     * chance per tick is small, but a full-throttle launch puts many ticks on the few blocks it starts on: the
     * chances measured on these fixtures put the odds of some checked lane block taking two stages at one run
     * in 40-60.
     */
    enum Wear {
        /** The lane's own material, or a stage that is still the same surface to the tyre model, which
         * Dynamic Terrain infers from a block's sound type: stone wears to cobblestone, still stone. For
         * fixtures whose subject is the surface itself. Their gentle throttle makes a stone lane worn on to
         * gravel under a checked car about a one-in-10,000 event per run. */
        SAME_SURFACE,
        /** The lane's own material or anything its damage stages wear it to short of air, for fixtures about
         * something other than the surface (aero, drivetrain) that launch at full throttle. A lane worn
         * through still fails the check: the wheel then hits the layer under the lane. */
        ANY_STAGE
    }

    private final GameTestHelper helper;
    private final BlockPos origin;
    private final Map<BlockPos, BlockState> priorStates = new LinkedHashMap<>();
    private final List<ChunkPos> forcedChunks = new ArrayList<>();
    private final List<Entity> spawned = new ArrayList<>();

    private VehicleLanes(GameTestHelper helper, BlockPos origin) {
        this.helper = helper;
        this.origin = origin;
    }

    /**
     * Lays {@code site}'s floor, waits until every chunk under it ticks entities -- the readiness GameTest
     * itself waits for before it starts a test in its own structure -- and then runs {@code fixture} on it.
     * The fixture's own timeline starts there; allow {@link #CHUNK_LOAD_ALLOWANCE} for the wait.
     */
    static void build(GameTestHelper helper, Site site, Consumer<VehicleLanes> fixture) {
        VehicleLanes lanes = new VehicleLanes(helper, site.origin());
        // Registered first, so everything below is undone on every outcome.
        helper.testInfo.addListener(lanes.cleanupOnEveryOutcome());
        lanes.forceChunks();
        lanes.layFloor();
        // A sequence rather than runAfterDelay: what the fixture schedules from here is then never added to
        // GameTestInfo's runAtTickTime map while that map is being iterated (see CarDrivetrainGameTests'
        // ledge test, round 14).
        helper.startSequence()
                .thenWaitUntil(lanes::assertChunksTickEntities)
                .thenExecute(() -> lanes.run(fixture));
    }

    private void run(Consumer<VehicleLanes> fixture) {
        try {
            fixture.accept(this);
        } catch (GameTestAssertException e) {
            throw e;
        } catch (RuntimeException e) {
            // Anything else thrown from a sequence step would escape GameTest's own handling and stop the
            // server, taking every other test with it.
            helper.testInfo.fail(e);
        }
    }

    /**
     * Forces every chunk under the floor, then loads every chunk within two of them, here and now. A chunk
     * only ticks entities once its neighbours that far out are loaded too, and left to world generation in
     * the background that takes however long it takes -- while GameTest, waiting, runs ticks back to back:
     * one run with the machine busy spent over 160 ticks there and timed its fixture out. Loaded here, the
     * site ticks entities a tick or two later. The forced chunks' own tickets keep that ring loaded.
     */
    private void forceChunks() {
        ServerLevel level = helper.getLevel();
        int minChunkX = origin.getX() >> 4;
        int maxChunkX = (origin.getX() + WIDTH - 1) >> 4;
        int minChunkZ = origin.getZ() >> 4;
        int maxChunkZ = (origin.getZ() + LENGTH - 1) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // true only if this call forced it; a chunk something else already forced stays forced.
                if (level.setChunkForced(chunkX, chunkZ, true)) {
                    forcedChunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }
        for (int chunkX = minChunkX - 2; chunkX <= maxChunkX + 2; chunkX++) {
            for (int chunkZ = minChunkZ - 2; chunkZ <= maxChunkZ + 2; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private void assertChunksTickEntities() {
        for (int chunkX = origin.getX() >> 4; chunkX <= (origin.getX() + WIDTH - 1) >> 4; chunkX++) {
            for (int chunkZ = origin.getZ() >> 4; chunkZ <= (origin.getZ() + LENGTH - 1) >> 4; chunkZ++) {
                helper.assertTrue(helper.getLevel().isPositionEntityTicking(new ChunkPos(chunkX, chunkZ).getWorldPosition()),
                        "lane chunk " + chunkX + ", " + chunkZ + " is not ticking entities yet");
            }
        }
    }

    private void layFloor() {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < LENGTH; z++) {
                setBlock(new BlockPos(x, FLOOR_Y - 1, z), grass);
                setBlock(new BlockPos(x, FLOOR_Y, z), grass);
                for (int y = SPAWN_Y; y < SPAWN_Y + CLEARANCE; y++) {
                    setBlock(new BlockPos(x, y, z), air);
                }
            }
        }
    }

    /** Sets the block at {@code relative}, remembering what was there first so it can be put back. */
    void setBlock(BlockPos relative, BlockState state) {
        BlockPos pos = origin.offset(relative);
        priorStates.computeIfAbsent(pos, helper.getLevel()::getBlockState);
        helper.getLevel().setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** A lane of {@code material} along the floor's full length, centred on {@code centreX}. */
    void layLane(int centreX, BlockState material) {
        layLane(centreX, FLOOR_Y, material, material);
    }

    /** A lane split down its centre line: {@code lowSide} for x below {@code centreX}, {@code highSide} from it
     * upward. A car centred on the lane then has its x = -0.8 wheels ({@code CarGeometry.MOUNTS} 0 and 2) on
     * {@code lowSide} and its x = +0.8 wheels (1 and 3) on {@code highSide}, each 0.8 m from the seam. */
    void layLane(int centreX, BlockState lowSide, BlockState highSide) {
        layLane(centreX, FLOOR_Y, lowSide, highSide);
    }

    /** One layer of a lane at height {@code y}, for fixtures that build a lane more than one block deep. */
    void layLane(int centreX, int y, BlockState lowSide, BlockState highSide) {
        for (int x = centreX - LANE_HALF_WIDTH; x < centreX + LANE_HALF_WIDTH; x++) {
            BlockState material = x < centreX ? lowSide : highSide;
            for (int z = 0; z < LENGTH; z++) {
                setBlock(new BlockPos(x, y, z), material);
            }
        }
    }

    /** Where a car on the lane centred at {@code centreX} spawns, {@code z} blocks along it. */
    static Vec3 spawnPoint(int centreX, int z) {
        return new Vec3(centreX, SPAWN_Y, z);
    }

    static Vec3 spawnPoint(int centreX) {
        return spawnPoint(centreX, SPAWN_Z);
    }

    /** A car of {@code type} at {@code relative}, facing +z, removed again when the test ends. */
    <E extends Entity> E spawn(EntityType<E> type, Vec3 relative) {
        E car = type.create(helper.getLevel());
        helper.assertTrue(car != null, "failed to construct a " + type.getDescriptionId());
        car.moveTo(origin.getX() + relative.x, origin.getY() + relative.y, origin.getZ() + relative.z, 0.0f, 0.0f);
        spawned.add(car);
        helper.getLevel().addFreshEntity(car);
        return car;
    }

    /** Every wheel on the same material, in {@code CarGeometry.MOUNTS} order. */
    static Block[] allWheels(Block material) {
        return new Block[] {material, material, material, material};
    }

    /** The next stage of Dynamic Terrain's {@code damage} table (its {@code
     * data/dynamicterrain/dynamicterrain/transitions/damage.json}) for the blocks these lanes are made of;
     * {@code null} for gravel (worn to air) and for blocks with no stage. */
    private static Block nextWearStage(Block block) {
        if (block == Blocks.GRASS_BLOCK) {
            return Blocks.DIRT;
        }
        if (block == Blocks.STONE || block == Blocks.SMOOTH_STONE) {
            return Blocks.COBBLESTONE;
        }
        if (block == Blocks.DIRT || block == Blocks.COBBLESTONE) {
            return Blocks.GRAVEL;
        }
        return null;
    }

    private static boolean counts(Block intended, Wear wear, BlockState hit) {
        if (hit.is(intended)) {
            return true;
        }
        for (Block stage = nextWearStage(intended); stage != null; stage = nextWearStage(stage)) {
            if (wear == Wear.SAME_SURFACE
                    && stage.defaultBlockState().getSoundType() != intended.defaultBlockState().getSoundType()) {
                return false;
            }
            if (hit.is(stage)) {
                return true;
            }
        }
        return false;
    }

    private static String accepted(Block intended, Wear wear) {
        StringBuilder names = new StringBuilder(intended.toString());
        for (Block stage = nextWearStage(intended); stage != null; stage = nextWearStage(stage)) {
            if (wear == Wear.SAME_SURFACE
                    && stage.defaultBlockState().getSoundType() != intended.defaultBlockState().getSoundType()) {
                break;
            }
            names.append(" or ").append(stage);
        }
        return names.toString();
    }

    /**
     * Asserts on every tick from {@code fromTick} to {@code toTick} (counted from now) that {@code car} is still
     * on its own lane, the one centred on {@code laneCentreX}: it is touching the ground; every wheel's own
     * raycast hit something (the contact {@code SableCarBody#tick} itself resolves); what each wheel hit lies
     * inside that lane, on the lane layer; and it is that wheel's intended material, {@code
     * wheelMaterials[wheel]} in {@code CarGeometry.MOUNTS} order, or what {@code wear} allows it to have worn
     * to. A speed or load reading taken inside that window is then a reading of the car on the surface the
     * fixture means it to be on -- not of a car that drifted off its lane, ran onto another one, or left the
     * ground.
     *
     * <p>{@code toTick} must be earlier than the tick the fixture's own final assertion runs on: a check
     * scheduled on that same tick can run after the test has finished and its cars are gone.
     */
    void assertStaysOnLane(CarEntity car, int laneCentreX, Block[] wheelMaterials, Wear wear,
            long fromTick, long toTick, String label) {
        for (long tick = fromTick; tick <= toTick; tick++) {
            long at = tick;
            helper.runAfterDelay(tick, () -> {
                helper.assertTrue(car.sableBody() != null, label + ": car has no Sable body at tick " + at);
                helper.assertTrue(car.isTouchingGroundForTest(),
                        label + ": car lost the ground at tick " + at + " (" + describe(car) + ")");
                BlockPos[] contacts = SableCompat.wheelContactBlocks(car.sableBody());
                for (int wheel = 0; wheel < contacts.length; wheel++) {
                    helper.assertTrue(contacts[wheel] != null, label + ": wheel " + wheel
                            + "'s raycast found no ground at tick " + at + " (" + describe(car) + ")");
                    BlockPos relative = contacts[wheel].subtract(origin);
                    helper.assertTrue(relative.getY() == FLOOR_Y && relative.getX() >= laneCentreX - LANE_HALF_WIDTH
                                    && relative.getX() < laneCentreX + LANE_HALF_WIDTH,
                            label + ": wheel " + wheel + " is not on its own lane at tick " + at + ": it hit the "
                                    + "block at relative " + relative.toShortString() + " (" + describe(car) + ")");
                    BlockState hit = helper.getLevel().getBlockState(contacts[wheel]);
                    helper.assertTrue(counts(wheelMaterials[wheel], wear, hit), label + ": wheel " + wheel + " is on "
                            + hit.getBlock() + " at tick " + at + ", not " + accepted(wheelMaterials[wheel], wear)
                            + " (" + describe(car) + ")");
                }
            });
        }
    }

    /** {@link #driveAlongLane}'s steering per metre off the lane's centre line, and per m/s of drift. */
    private static final double LANE_KEEP_OFFSET_GAIN = 0.3;
    private static final double LANE_KEEP_DRIFT_GAIN = 0.6;

    /**
     * Drives {@code car} at {@code throttle} for {@code ticks} ticks while holding it on the centre line of the
     * lane centred on {@code laneCentreX}, the way a driver would: each tick it steers back toward the line in
     * proportion to how far off it is and how fast it is drifting. For a car whose wheels pull evenly this
     * asks for essentially no steering. It is what a car with different materials under its left and right
     * wheels needs: the higher-drag side yaws it toward that side, and an unsteered car on such a lane drifted
     * 2.1 m sideways within 100 ticks, taking its wheels across the seam.
     *
     * <p>Positive steer turns the front wheels toward +x ({@code SableCarBody} rotates each front wheel's
     * forward axis by +steer angle about up), so the correction is the negative of the offset.
     */
    void driveAlongLane(CarEntity car, int laneCentreX, double throttle, int ticks) {
        car.forceDrive(throttle, 0.0, ticks);
        for (int tick = 1; tick < ticks; tick++) {
            int remaining = ticks - tick;
            helper.runAfterDelay(tick, () -> {
                double offset = car.getX() - origin.getX() - laneCentreX;
                double drift = car.getDeltaMovement().x * 20.0;
                double steer = -(LANE_KEEP_OFFSET_GAIN * offset + LANE_KEEP_DRIFT_GAIN * drift);
                car.forceDrive(throttle, Math.max(-1.0, Math.min(1.0, steer)), remaining);
            });
        }
    }

    /** The car's site-relative position and speed, for assertion messages. */
    String describe(CarEntity car) {
        Vec3 v = car.getDeltaMovement();
        return String.format("relative x=%.2f z=%.2f, %.2f m/s", car.getX() - origin.getX(), car.getZ() - origin.getZ(),
                Math.hypot(v.x, v.z) * 20.0);
    }

    /** {@code car}'s site-relative z, for fixtures that act on where a car has really got to. */
    double relativeZ(CarEntity car) {
        return car.getZ() - origin.getZ();
    }

    private GameTestListener cleanupOnEveryOutcome() {
        return new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanup();
            }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanup();
            }

            @Override
            public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) {
                cleanup();
            }
        };
    }

    /** Capture before changing the global setting. Scheduled assertions and timeouts do not unwind
     * the fixture's setup stack, so restoration belongs to the test lifecycle. */
    void restoreSubStepsOnEveryOutcome() {
        restoreSubStepsOnEveryOutcome(helper);
    }

    static void restoreSubStepsOnEveryOutcome(GameTestHelper helper) {
        int original = CarConfig.WHEEL_SUB_STEPS.get();
        helper.testInfo.addListener(new GameTestListener() {
            private boolean restored;

            private void restore() {
                if (!restored) {
                    CarConfig.WHEEL_SUB_STEPS.set(original);
                    restored = true;
                }
            }

            @Override
            public void testStructureLoaded(GameTestInfo info) {}

            @Override
            public void testPassed(GameTestInfo info, GameTestRunner runner) { restore(); }

            @Override
            public void testFailed(GameTestInfo info, GameTestRunner runner) { restore(); }

            @Override
            public void testAddedForRerun(GameTestInfo info, GameTestInfo retry, GameTestRunner runner) { restore(); }
        });
    }

    /** Undoes everything recorded so far; running it again does nothing. */
    private void cleanup() {
        for (Entity entity : spawned) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }
        spawned.clear();
        ServerLevel level = helper.getLevel();
        priorStates.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
        priorStates.clear();
        for (ChunkPos chunk : forcedChunks) {
            level.setChunkForced(chunk.x, chunk.z, false);
        }
        forcedChunks.clear();
    }
}
