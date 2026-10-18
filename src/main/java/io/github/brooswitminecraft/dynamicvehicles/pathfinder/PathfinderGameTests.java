package io.github.brooswitminecraft.dynamicvehicles.pathfinder;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod;
import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;

/**
 * Integration coverage for the Pathfinder vertical slice (MINECRAFT-379 review follow-up:
 * "Finish ... integration tests"). Reuses {@code data/dynamicvehicles/structure/dispatch_profession.nbt}
 * (a flat 5x5x5 platform), exactly as {@code delivery.DispatcherGameTests} does, since nothing
 * here needs a Pathfinder-specific structure.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class PathfinderGameTests {
    private PathfinderGameTests() {
    }

    /**
     * {@link GameTestMockPlayers#create}'s connection never ran the real client's mod-channel
     * negotiation (see its own javadoc) — declaring "advanced_open_screen" is Dispatcher's own
     * tests' equivalent fix for their menu payload; this is the same fix for {@link
     * PathfinderHudPayload}, needed by any test that triggers a HUD send.
     */
    private static void declarePathfinderHudChannel(ServerPlayer player) {
        ChannelAttributes.getOrCreateAdHocChannels(player.connection.getConnection())
                .add(ResourceLocation.fromNamespaceAndPath(DynamicVehiclesMod.MODID, "pathfinder_hud"));
    }

    /**
     * Seeds {@code villager}'s level-1 route with the placeholder placer, bypassing real terrain
     * placement, for tests exercising attempt lifecycle behavior rather than placement itself.
     * See {@link PathfinderRouteStorage#seedForTest}'s own javadoc for why: a real region-center
     * candidate routinely lands hundreds of blocks from this GameTest's own tiny test structure,
     * even though {@link PathfinderTerrainPlacer} would resolve it fine (MINECRAFT-403: it never
     * fails) - this just keeps the checkpoint conveniently close enough to teleport a test player to.
     */
    private static void seedLevel1RouteForTest(GameTestHelper helper, Villager villager) {
        PathfinderRegion origin = PathfinderAttempts.regionOf(villager);
        String dimensionId = helper.getLevel().dimension().location().toString();
        // DEFAULT_UNPLACED's own placeholder dimension ("unplaced") would fail every proximity
        // check against a real player's real dimension - this is the same placeholder math with
        // the GameTest level's own real dimension id instead, so teleporting a real player to a
        // checkpoint's position actually counts as reaching it.
        PathfinderCheckpointPlacer realDimensionPlaceholder = (region, indexInRegion, countInRegion) -> {
            double centerX = region.x() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
            double centerZ = region.z() * (double) PathfinderRegion.SIZE_BLOCKS + PathfinderRegion.SIZE_BLOCKS / 2.0;
            double x = centerX;
            double z = centerZ;
            if (countInRegion > 1) {
                double angle = 2 * Math.PI * indexInRegion / countInRegion;
                x += PathfinderCheckpointPlacer.DEFAULT_LOOP_RADIUS_BLOCKS * Math.cos(angle);
                z += PathfinderCheckpointPlacer.DEFAULT_LOOP_RADIUS_BLOCKS * Math.sin(angle);
            }
            return java.util.Optional.of(new PathfinderCheckpoint(region, new PathfinderPosition(dimensionId, x, 0, z)));
        };
        PathfinderRoute route = PathfinderRouteGenerator.generate(origin, PathfinderLevel.LEVEL_1, realDimensionPlaceholder).orElseThrow();
        PathfinderRouteStorage.of(helper.getLevel().getServer()).seedForTest(helper.getLevel(), origin, PathfinderLevel.LEVEL_1, route);
    }

    /**
     * Regression for a real bug found while writing this test: the Route Board's PoiType was
     * correctly registered and correctly backed the block, but nothing had added
     * {@code dynamicvehicles:route_board} to the vanilla {@code acquirable_job_site} POI tag
     * ({@code data/minecraft/tags/point_of_interest_type/acquirable_job_site.json}) - the tag
     * {@code net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite} and the
     * jobless-villager job-site scan actually key off. Without it, a jobless villager never even
     * considers the board as a potential job site, no matter how long it waits next to it; the
     * Dispatcher and Builder entries already in that same tag file are why their own equivalent
     * tests pass. This test would have caught it - {@link #joblessVillagerNextToRouteBoardBecomesPathfinder}
     * below is the direct test; this one isolates the POI-registration half.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void routeBoardIsRegisteredAsAPoi(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos, PathfinderBlocks.ROUTE_BOARD.get());
        BlockPos absolutePos = helper.absolutePos(pos);
        var poiType = helper.getLevel().getPoiManager().getType(absolutePos);
        helper.assertTrue(poiType.isPresent(), "no POI at all was registered at the route board's position");
        poiType.ifPresent(holder -> helper.assertTrue(holder.is(PathfinderPoiTypes.ROUTE_BOARD.getKey()),
                "POI at the route board's position is "
                        + net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE.getKey(holder.value())
                        + ", not route_board"));
        helper.succeed();
    }

    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void joblessVillagerNextToRouteBoardBecomesPathfinder(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 2, 2), PathfinderBlocks.ROUTE_BOARD.get());
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        helper.succeedWhen(() -> helper.assertTrue(villager.getVillagerData().getProfession() == PathfinderProfession.PATHFINDER.get(),
                "villager next to the route board never took the pathfinder profession; actual profession: "
                        + net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession())));
    }

    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void rightClickingAPathfinderStartsAnAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        // A fresh villager's own Pathfinder progression (PathfinderVillagerLevel, persistent-data-backed,
        // NOT VillagerData's level) defaults to 1 - see anti-bypass note on PathfinderVillagerProgress.
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        var event = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
        PathfinderInteractionHandler.onEntityInteract(event);

        helper.assertTrue(event.isCanceled(), "right-clicking a pathfinder should intercept vanilla's own villager interaction");
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(helper.getLevel().getServer());
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElse(null);
        helper.assertTrue(record != null, "right-clicking a pathfinder villager should start an attempt");
        helper.assertTrue(record.level() == PathfinderLevel.LEVEL_1, "a level-1 villager should start a level-1 attempt");
        helper.assertTrue(record.reachedCount() == 0, "a freshly started attempt should have no checkpoints reached yet");
        helper.succeed();
    }

    /**
     * MINECRAFT-403 acceptance #2: crossing into the current target chunk credits the checkpoint
     * regardless of player Y, driven through the real server tick evaluation path. Teleports
     * deep underground (far below the checkpoint's own representative Y) but inside its chunk.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void checkpointCreditedUndergroundInTheTargetChunk(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level()).orElseThrow();
        PathfinderCheckpoint first = route.checkpoints().get(0);

        // Same chunk (same X/Z) as the checkpoint, but far underground - below any naturally
        // generated terrain and far from the checkpoint's own representative Y.
        player.teleportTo(first.position().x(), dimension.getMinBuildHeight() + 1, first.position().z());

        helper.runAfterDelay(25, () -> {
            helper.assertTrue(storage.active(player.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1) == 1,
                    "entering the target chunk underground must credit the checkpoint");
            helper.succeed();
        });
    }

    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void aSecondRightClickWhileActiveDoesNotStartASecondAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        // A fresh villager's own Pathfinder progression (PathfinderVillagerLevel, persistent-data-backed,
        // NOT VillagerData's level) defaults to 1 - see anti-bypass note on PathfinderVillagerProgress.
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(helper.getLevel().getServer());
        long firstStartTick = storage.active(player.getUUID()).orElseThrow().startTick();

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        PathfinderAttemptRecord afterSecondClick = storage.active(player.getUUID()).orElseThrow();

        helper.assertTrue(afterSecondClick.startTick() == firstStartTick, "a second right-click while active must not restart or replace the attempt");
        helper.succeed();
    }

    /**
     * Review follow-up: route selection was missing - interaction always auto-started the
     * villager's current maximum unlocked level. {@code tryStart(..., requestedLevel)} must
     * reject a level the villager has not actually unlocked, server-side, without starting an
     * attempt.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void requestingAnUnlockedLevelIsRejected(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        // Fresh villager: Pathfinder level 1 only. Level 2 is still locked.
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderAttempts.tryStart(player, helper.getLevel(), villager, PathfinderLevel.LEVEL_2);

        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(helper.getLevel().getServer());
        helper.assertTrue(storage.active(player.getUUID()).isEmpty(),
                "requesting a level the villager has not unlocked must not start an attempt");
        helper.succeed();
    }

    /**
     * Review follow-up: once a villager has unlocked a higher level, a player must still be able
     * to select and run an earlier, already-unlocked circuit - not just the current maximum.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void requestingAnAlreadyUnlockedLowerLevelSucceeds(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        MinecraftServer server = helper.getLevel().getServer();
        int threshold = PathfinderVillagerProgress.DEFAULT_COMPLETIONS_TO_ADVANCE;
        for (int i = 0; i < threshold; i++) {
            PathfinderVillagerLevel.recordCompletion(server, villager.getUUID(), 1, threshold);
        }
        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, villager.getUUID()) == PathfinderLevel.LEVEL_2,
                "sanity: the villager should now be unlocked through level 2");
        seedLevel1RouteForTest(helper, villager);

        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderAttempts.tryStart(player, helper.getLevel(), villager, PathfinderLevel.LEVEL_1);

        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElse(null);
        helper.assertTrue(record != null, "requesting an already-unlocked lower level must start an attempt");
        helper.assertTrue(record != null && record.level() == PathfinderLevel.LEVEL_1,
                "the attempt must be for the REQUESTED level 1, not the villager's current maximum (2)");
        helper.succeed();
    }

    /**
     * Fast-forwards a real, started attempt to its last checkpoint (rather than re-deriving
     * checkpoint positions by hand), then drives the real {@link PathfinderTickHandler} through
     * the last leg and the closing leg via actual server ticks, confirming the reward is granted
     * and the attempt is cleared exactly once.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void completingTheLastCheckpointAndClosingLegGrantsRewardAndClearsTheAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        // A fresh villager's own Pathfinder progression (PathfinderVillagerLevel, persistent-data-backed,
        // NOT VillagerData's level) defaults to 1 - see anti-bypass note on PathfinderVillagerProgress.
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));

        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level()).orElseThrow();
        int lastIndex = route.checkpoints().size() - 1;

        // Jump straight to "one checkpoint left" via the same storage API a reload would use,
        // instead of teleporting through all 8 - the per-checkpoint logic is already covered by
        // PathfinderProgressTest; this test is about the real tick/reward/clear wiring.
        storage.update(new PathfinderAttemptRecord(record.playerId(), record.villagerId(), record.dimensionId(), record.origin(), record.level(),
                record.startTick(), record.timeLimitTicks(), lastIndex, false, false, false, false));

        PathfinderCheckpoint last = route.checkpoints().get(lastIndex);
        player.teleportTo(last.position().x(), last.position().y(), last.position().z());

        boolean[] movedToClosingLeg = {false};
        helper.succeedWhen(() -> {
            if (!movedToClosingLeg[0]) {
                int reached = storage.active(player.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1);
                helper.assertTrue(reached == route.checkpoints().size(), "expected the last checkpoint to register as reached first");
                PathfinderCheckpoint first = route.checkpoints().get(0);
                player.teleportTo(first.position().x(), first.position().y(), first.position().z());
                movedToClosingLeg[0] = true;
                helper.fail("moved to the closing leg - wait for the next evaluation"); // keep polling
            }
            helper.assertTrue(storage.active(player.getUUID()).isEmpty(), "the attempt should be cleared once the closing leg is reached");
            int emeralds = player.getInventory().countItem(Items.EMERALD);
            int expected = PathfinderConfig.rewardEmeralds(record.level());
            helper.assertTrue(emeralds == expected, "expected " + expected + " emerald(s) rewarded, got " + emeralds);
            PathfinderVillagerProgress.State villagerState = PathfinderVillagerLevel.stateOf(server, villager.getUUID());
            helper.assertTrue(villagerState.completionsAtLevel() == 1,
                    "the villager's own completion count should have advanced once the attempt it started finished");
        });
    }

    /**
     * Review follow-up (re-review): joining NeoForge's IO worker proves a write was ATTEMPTED,
     * not that it SUCCEEDED - {@code SavedData#save(File, ...)} catches and logs any {@code
     * IOException} inside the queued task, never surfacing it to the joining thread. This injects
     * a REAL write failure (making the save directory read-only, an actual OS-level permission
     * denial, not a simulated one) at the moment the completion would otherwise pay out, and
     * confirms: the reward is withheld and the attempt record is retained (not lost) while the
     * write keeps failing; once the directory is made writable again, the next tick's retry
     * confirms the write, pays the reward exactly once, and clears the attempt.
     *
     * <p>Bug found while adding the two sibling fault-injection tests below: all three (and every
     * other GameTest here) default to vanilla's {@code "defaultBatch"}, and a batch's tests all
     * tick CONCURRENTLY against the one shared overworld data directory ({@code
     * GameTestRunner#runBatch} only advances once every test in the batch finishes) - toggling
     * that directory's writable bit here was intermittently starving/flipping writes for
     * unrelated concurrently-running tests (even ones with no fault injection of their own, like
     * {@code completingTheLastCheckpointAndClosingLegGrantsRewardAndClearsTheAttempt}), and the
     * two sibling tests toggling the SAME bit were intermittently racing each other too. Each of
     * the three gets its own dedicated batch so none of them ever ticks concurrently with
     * anything else that might be saving to the same directory at the same moment.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 150, batch = "pathfinder_fault_attempt_write")
    public static void writeFailureWithholdsRewardUntilConfirmedThenRetrySucceeds(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));

        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level()).orElseThrow();
        int lastIndex = route.checkpoints().size() - 1;
        storage.update(new PathfinderAttemptRecord(record.playerId(), record.villagerId(), record.dimensionId(), record.origin(), record.level(),
                record.startTick(), record.timeLimitTicks(), lastIndex, false, false, false, false));

        // Ensure the data file already exists once (a brand-new world has no "data" dir yet) and
        // locate it, exactly as PathfinderTickHandler's own verification does.
        server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        java.io.File dataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile();
        helper.assertTrue(dataDir.isDirectory(), "sanity: the world's data directory must exist by now");

        boolean permissionsBlockWrites = !dataDir.setWritable(false);
        if (permissionsBlockWrites) {
            // This process can override its own permission bits (e.g. running as root) - the
            // fault can't actually be injected this way in that environment. Restore and skip
            // rather than report a false pass/fail for a mechanism this run cannot exercise.
            dataDir.setWritable(true);
            helper.succeed();
            return;
        }

        PathfinderCheckpoint last = route.checkpoints().get(lastIndex);
        PathfinderCheckpoint first = route.checkpoints().get(0);
        player.teleportTo(last.position().x(), last.position().y(), last.position().z());

        // Safety net, independent of the test's own outcome: if anything above goes wrong and the
        // succeedWhen below never restores write access itself, this guarantees the directory is
        // writable again well before this GameTest's own timeout - a shared "Test Level" data
        // directory left read-only would otherwise break every later GameTest in the same run.
        helper.runAfterDelay(120L, () -> dataDir.setWritable(true));

        boolean[] restoredWritable = {false};
        boolean[] movedToClosingLeg = {false};
        helper.succeedWhen(() -> {
            if (!movedToClosingLeg[0]) {
                int reached = storage.active(player.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1);
                helper.assertTrue(reached == route.checkpoints().size(), "expected the last checkpoint to register first");
                player.teleportTo(first.position().x(), first.position().y(), first.position().z());
                movedToClosingLeg[0] = true;
                helper.fail("moved to the closing leg - wait for the next evaluation to hit the injected write failure");
            }
            if (!restoredWritable[0]) {
                // The write should be failing right now: the attempt must be retained
                // (finished=true, still present) and no reward granted yet.
                PathfinderAttemptRecord pending = storage.active(player.getUUID()).orElse(null);
                helper.assertTrue(pending != null && pending.finished(), "a failed write must retain the finished-but-unpaid attempt for retry, not lose it");
                helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0, "reward must be withheld while the write cannot be confirmed durable");
                dataDir.setWritable(true);
                restoredWritable[0] = true;
                helper.fail("restored write access - wait for the retry to confirm and pay out");
            }
            helper.assertTrue(storage.active(player.getUUID()).isEmpty(), "once the write succeeds, the retried payout should clear the attempt");
            int expected = PathfinderConfig.rewardEmeralds(record.level());
            int actual = player.getInventory().countItem(Items.EMERALD);
            helper.assertTrue(actual == expected, "expected exactly " + expected + " emerald(s) after the retry succeeded, got " + actual);
        });
    }

    /**
     * Anti-bypass regression (independent review): {@code VillagerData#setProfession} preserves
     * the existing numeric level across a profession change, so a villager trade-leveled under
     * ANY other profession and then converted to Pathfinder must NOT instantly unlock a higher
     * Pathfinder level - {@link PathfinderAttempts#levelOf} must read {@link
     * PathfinderVillagerLevel}'s own persistent-data-backed progression, not {@code
     * VillagerData#getLevel()}.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void villagerTradeLevelDoesNotUnlockHigherPathfinderLevels(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        // Simulates a villager trade-leveled to 5 under some other profession before ever becoming a Pathfinder.
        villager.setVillagerData(villager.getVillagerData().setLevel(5).setProfession(PathfinderProfession.PATHFINDER.get()));

        helper.assertTrue(villager.getVillagerData().getLevel() == 5, "sanity: VillagerData's own level should still read 5");
        helper.assertTrue(PathfinderAttempts.levelOf(helper.getLevel(), villager) == PathfinderLevel.LEVEL_1,
                "a villager's vanilla trade level must never unlock a Pathfinder level it hasn't earned through actual completions");
        helper.succeed();
    }

    /** {@link PathfinderVillagerProgress}'s threshold, exercised against a real villager's UUID. */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void pathfinderVillagerLevelAdvancesAfterThreeCompletions(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, villager.getUUID()) == PathfinderLevel.LEVEL_1,
                "a fresh villager should start at Pathfinder level 1");

        int threshold = PathfinderVillagerProgress.DEFAULT_COMPLETIONS_TO_ADVANCE;
        PathfinderVillagerLevel.recordCompletion(server, villager.getUUID(), 1, threshold);
        PathfinderVillagerLevel.recordCompletion(server, villager.getUUID(), 1, threshold);
        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, villager.getUUID()) == PathfinderLevel.LEVEL_1,
                "two completions should not yet advance the level");

        PathfinderVillagerLevel.recordCompletion(server, villager.getUUID(), 1, threshold);
        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, villager.getUUID()) == PathfinderLevel.LEVEL_2,
                "the third completion should advance to level 2");
        helper.succeed();
    }

    /**
     * Review follow-up: progression must be credited "independently of loaded entities" - this
     * proves it directly, with no {@link Villager} entity ever spawned for this UUID in this
     * test at all. A real completion still looks up the villager by UUID in a possibly-unloaded
     * dimension ({@code PathfinderAttempts#recordVillagerCompletion}), but the credit itself,
     * exercised here, has no dependency on that lookup succeeding.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void villagerProgressionIsCreditedByUuidWithNoLoadedEntityAtAll(GameTestHelper helper) {
        java.util.UUID neverSpawnedVillagerId = java.util.UUID.randomUUID();
        MinecraftServer server = helper.getLevel().getServer();
        int threshold = PathfinderVillagerProgress.DEFAULT_COMPLETIONS_TO_ADVANCE;

        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, neverSpawnedVillagerId) == PathfinderLevel.LEVEL_1,
                "an unknown villager id should default to level 1, not error");
        for (int i = 0; i < threshold; i++) {
            PathfinderVillagerLevel.recordCompletion(server, neverSpawnedVillagerId, 1, threshold);
        }
        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, neverSpawnedVillagerId) == PathfinderLevel.LEVEL_2,
                "progression must be credited by UUID alone, independent of any loaded entity");
        helper.succeed();
    }

    /**
     * Review follow-up (a real concurrency exploit): many concurrent level-1 attempts finishing
     * in sequence used to each count toward advancing the villager past level 1 even after
     * earlier ones in the same batch had already advanced it - twelve concurrent level-1
     * completions could unlock level 5 at a threshold of 3, without ever running a level 2-5
     * circuit. Twelve completions all reported as "completedLevel = 1" (simulating twelve
     * concurrent level-1 attempts finishing one after another) must advance the villager exactly
     * ONE level (to 2, at which point further level-1 completions are stale - the villager isn't
     * gated at level 1 anymore), never all the way to 5.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void manyConcurrentLowLevelCompletionsCannotUnlockHigherLevels(GameTestHelper helper) {
        java.util.UUID villagerId = java.util.UUID.randomUUID();
        MinecraftServer server = helper.getLevel().getServer();
        int threshold = PathfinderVillagerProgress.DEFAULT_COMPLETIONS_TO_ADVANCE;

        for (int i = 0; i < 12; i++) {
            PathfinderVillagerLevel.recordCompletion(server, villagerId, 1, threshold);
        }
        helper.assertTrue(PathfinderVillagerLevel.levelOf(server, villagerId) == PathfinderLevel.LEVEL_2,
                "twelve level-1 completions (threshold 3) should advance exactly once, to level 2, not all the way to 5");
        helper.succeed();
    }

    /** The selected death policy (independent review): death fails the attempt outright, no partial credit. */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void deathClearsTheActiveAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(helper.getLevel().getServer());
        helper.assertTrue(storage.active(player.getUUID()).isPresent(), "the attempt should be active before the death");

        PathfinderTickHandler.onPlayerRespawn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(player, false));

        helper.assertTrue(storage.active(player.getUUID()).isEmpty(), "death should clear the active attempt, not leave it active");
        helper.succeed();
    }

    /** An end-portal respawn is NOT a death and must not fail an active attempt. */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void endConqueredRespawnDoesNotClearTheActiveAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(helper.getLevel().getServer());

        PathfinderTickHandler.onPlayerRespawn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(player, true));

        helper.assertTrue(storage.active(player.getUUID()).isPresent(), "an end-portal respawn must not fail the active attempt");
        helper.succeed();
    }

    /**
     * Review follow-up: death must fail the attempt AT THE MOMENT OF DEATH, not deferred to
     * respawn - triggered here via a real death ({@code player.kill}, which fires the real
     * {@code LivingDeathEvent} through NeoForge's normal event hooks), not by calling the
     * handler method directly, so this also proves the event is actually wired up in {@code
     * DynamicVehiclesMod}, not just correct in isolation.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void aRealDeathClearsTheActiveAttemptImmediately(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(helper.getLevel().getServer());
        helper.assertTrue(storage.active(player.getUUID()).isPresent(), "the attempt should be active before the death");

        player.kill();

        helper.assertTrue(storage.active(player.getUUID()).isEmpty(),
                "a real death must clear the active attempt immediately, via the actual wired-up LivingDeathEvent listener");
        helper.succeed();
    }

    /**
     * Review follow-up defense-in-depth: {@code onServerTick} must skip (never advance/complete)
     * a dead player's attempt. The primary fix is clearing at death itself ({@link
     * #aRealDeathClearsTheActiveAttemptImmediately}); this test exercises the {@code isAlive()}
     * guard directly by constructing the "attempt still technically active, player not alive"
     * state the guard exists for (bypassing the death-clears-it path, which would otherwise make
     * this state unreachable in a controlled test).
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void aDeadPlayersAttemptIsNotEvaluatedByTheServerTick(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));
        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level()).orElseThrow();

        // Stand the player exactly on the next checkpoint, then make them not-alive without going
        // through the normal death event (so the attempt is deliberately left active, the one
        // state the isAlive() guard protects, which a real death would have already cleared).
        PathfinderCheckpoint next = route.checkpoints().get(0);
        player.teleportTo(next.position().x(), next.position().y(), next.position().z());
        player.setHealth(0.0F);
        helper.assertTrue(!player.isAlive(), "sanity: the player must actually read as not-alive for this guard to matter");

        helper.runAfterDelay(25, () -> {
            helper.assertTrue(storage.active(player.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1) == 0,
                    "a server tick must not advance a dead player's attempt");
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-403 acceptance #1: a checkpoint candidate column that is lava or otherwise
     * "inaccessible" must still be placed and offered AT THAT EXACT COLUMN, not rejected or
     * silently moved to a different one. Review follow-up: an earlier version of this test built
     * the lava fixture at a GameTest-local anchor but then called {@code place()} with region
     * math whose real candidate (512-block region center) lands at a totally different,
     * uncontrolled column - so it never actually exercised the lava column it built, and could
     * not have caught a regression that moved the checkpoint. Uses {@link
     * PathfinderTerrainPlacer}'s package-visible {@code placeAt} to pin the candidate to the
     * exact column the lava is poisoned at, then asserts the returned checkpoint's own X/Z
     * floors to that same column - not just that placement succeeded.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void placementSucceedsOverLavaAtTheExactCandidateColumn(GameTestHelper helper) {
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(anchor.below(), net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
        level.setBlockAndUpdate(anchor, net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());

        PathfinderRegion region = new PathfinderRegion(0, 0);
        PathfinderTerrainPlacer placer = new PathfinderTerrainPlacer(level);
        var result = placer.placeAt(region, anchor.getX() + 0.5, anchor.getZ() + 0.5);

        helper.assertTrue(result.isPresent(), "placement must never fail over terrain accessibility (MINECRAFT-403)");
        PathfinderCheckpoint checkpoint = result.get();
        String dimensionId = level.dimension().location().toString();
        helper.assertTrue(checkpoint.position().dimensionId().equals(dimensionId), "the real dimension must be used, not a placeholder");
        helper.assertTrue((int) Math.floor(checkpoint.position().x()) == anchor.getX()
                        && (int) Math.floor(checkpoint.position().z()) == anchor.getZ(),
                "the lava-poisoned candidate column must be returned unchanged, not moved to a different one");
        helper.succeed();
    }

    /**
     * Same regression coverage as {@link #placementSucceedsOverLavaAtTheExactCandidateColumn},
     * for a solid block occupying the candidate's headroom (the old {@code isSafeColumn}'s other
     * rejection case) and for a water-filled column - both terrain-independent per MINECRAFT-403,
     * covered without ever reading the world (this placer makes no world calls at all).
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void placementSucceedsOverBlockedHeadroomAndWaterAtTheExactCandidateColumn(GameTestHelper helper) {
        BlockPos blockedAnchor = helper.absolutePos(new BlockPos(2, 2, 2));
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(blockedAnchor, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(blockedAnchor.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());

        PathfinderRegion region = new PathfinderRegion(0, 0);
        PathfinderTerrainPlacer placer = new PathfinderTerrainPlacer(level);
        var blockedResult = placer.placeAt(region, blockedAnchor.getX() + 0.5, blockedAnchor.getZ() + 0.5);
        helper.assertTrue(blockedResult.isPresent(), "a solid-headroom column must still be placed, not rejected");
        helper.assertTrue((int) Math.floor(blockedResult.get().position().x()) == blockedAnchor.getX()
                        && (int) Math.floor(blockedResult.get().position().z()) == blockedAnchor.getZ(),
                "the blocked-headroom candidate column must be returned unchanged");

        BlockPos waterAnchor = helper.absolutePos(new BlockPos(3, 2, 2));
        level.setBlockAndUpdate(waterAnchor, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        var waterResult = placer.placeAt(region, waterAnchor.getX() + 0.5, waterAnchor.getZ() + 0.5);
        helper.assertTrue(waterResult.isPresent(), "a water column must still be placed, not rejected");
        helper.assertTrue((int) Math.floor(waterResult.get().position().x()) == waterAnchor.getX()
                        && (int) Math.floor(waterResult.get().position().z()) == waterAnchor.getZ(),
                "the water candidate column must be returned unchanged");
        helper.succeed();
    }

    /**
     * MINECRAFT-403 acceptance #4: an inability to resolve a real terrain height for the
     * representative marker must never affect arrival. The player teleports to the checkpoint's
     * own chunk at a Y far from the placer's own representative ({@code getSeaLevel()}) height -
     * arrival must still credit.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void representativeMarkerHeightDoesNotGateArrival(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PathfinderTerrainPlacer placer = new PathfinderTerrainPlacer(level);
        PathfinderCheckpoint checkpoint = placer.place(new PathfinderRegion(0, 0), 0, 1).orElseThrow();
        String dimensionId = level.dimension().location().toString();
        PathfinderPosition farFromMarkerY = new PathfinderPosition(
                dimensionId, checkpoint.position().x(), checkpoint.position().y() - 200, checkpoint.position().z());

        helper.assertTrue(checkpoint.isReachedBy(farFromMarkerY),
                "arrival must not depend on matching the marker's own representative Y");
        helper.succeed();
    }

    /**
     * Review follow-up: "a save/reload no-duplicate regression". save()/load() round-trip
     * through a plain {@link net.minecraft.nbt.CompoundTag} - the test sourceSet has no path to
     * Minecraft classes (see {@code delivery.DispatcherProfessionTest}'s own javadoc for why),
     * so this lives here instead of a plain unit test, exercising the exact shape {@link
     * PathfinderAttemptStorage#save}/{@link PathfinderAttemptStorage#load} persist, including
     * {@link PathfinderAttemptStorage#clear} actually removing the entry from what gets saved.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void clearedAttemptDoesNotReappearAfterASaveReloadRoundTrip(GameTestHelper helper) {
        var record = new PathfinderAttemptRecord(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "minecraft:overworld",
                new PathfinderRegion(3, -5), PathfinderLevel.LEVEL_2, 100, 1000, 2, false, false, false, false);

        PathfinderAttemptStorage storage = new PathfinderAttemptStorage();
        helper.assertTrue(storage.tryStart(record), "starting a fresh attempt should succeed");

        var savedBeforeClear = storage.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        var reloadedBeforeClear = PathfinderAttemptStorage.load(savedBeforeClear, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedBeforeClear.active(record.playerId()).orElseThrow().equals(record),
                "the started attempt should round-trip through save/load unchanged");

        storage.clear(record.playerId());
        var savedAfterClear = storage.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        var reloadedAfterClear = PathfinderAttemptStorage.load(savedAfterClear, helper.getLevel().registryAccess());
        helper.assertTrue(reloadedAfterClear.active(record.playerId()).isEmpty(),
                "a cleared attempt must not reappear after a save/reload round trip (no duplicate reward on reload)");
        helper.succeed();
    }

    /**
     * Review follow-up: "the gameTime clock also needs the requested disconnect/reload expiry
     * coverage; don't claim wall-clock server-downtime expiry from game ticks." The deadline is,
     * and only ever was, {@code startTick + timeLimitTicks} in {@code MinecraftServer#overworld()
     * .getGameTime()} ticks (confirmed here with a real, persisted, re-loaded record and a real
     * server tick) - never wall-clock time: a server that is actually stopped does not advance
     * game time at all, so downtime during a real restart does not count against a player, which
     * is a different (and already-true) property from "disconnected while the server keeps
     * running does not pause the clock" (the selected death/disconnect policy - no special
     * pause mechanism, by design).
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 100)
    public static void persistedAttemptExpiresByElapsedGameTicksNotWallClock(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = GameTestMockPlayers.create(helper);
        declarePathfinderHudChannel(player);
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderRegion origin = new PathfinderRegion(0, 0);
        String dimensionId = helper.getLevel().dimension().location().toString();
        // Seed the route cache directly (same reasoning as seedLevel1RouteForTest): this test is
        // about expiry timing, not terrain placement, and a real region(0,0) candidate would
        // routinely land in this GameTest world's own void far from any test structure.
        PathfinderRoute route = PathfinderRouteGenerator.generate(origin, PathfinderLevel.LEVEL_2,
                (region, indexInRegion, countInRegion) -> java.util.Optional.of(new PathfinderCheckpoint(region, new PathfinderPosition(dimensionId, 0, 0, 0))))
                .orElseThrow();
        PathfinderRouteStorage.of(server).seedForTest(helper.getLevel(), origin, PathfinderLevel.LEVEL_2, route);

        long startTick = server.overworld().getGameTime();
        var record = new PathfinderAttemptRecord(player.getUUID(), java.util.UUID.randomUUID(),
                dimensionId, origin, PathfinderLevel.LEVEL_2, startTick, PathfinderTickHandler.EVALUATE_INTERVAL_TICKS, 0, false, false, false, false);
        helper.assertTrue(storage.tryStart(record), "starting a fresh attempt should succeed");

        // Simulate a reload: reconstruct storage from a real save/load round trip, exactly as a
        // server restart would, before the next tick evaluates it - the deadline must survive
        // that round trip exactly (same startTick/timeLimitTicks), not be silently reset.
        var saved = storage.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        var reloaded = PathfinderAttemptStorage.load(saved, helper.getLevel().registryAccess());
        storage.update(reloaded.active(record.playerId()).orElseThrow());

        // EVALUATE_INTERVAL_TICKS worth of real SERVER ticks is exactly what the deadline is
        // measured in - the whole point: it is game time, not wall-clock time, and it survived
        // the reload unchanged. Waits two full evaluation cycles' worth: onServerTick only
        // evaluates on ticks aligned to EVALUATE_INTERVAL_TICKS (a GLOBAL counter, not relative
        // to this attempt's own startTick), so depending on this test's phase within that cycle
        // when it starts, the first aligned tick reached might still be short of the deadline by
        // up to nearly one full interval - a second cycle guarantees catching it regardless.
        helper.runAfterDelay(2L * PathfinderTickHandler.EVALUATE_INTERVAL_TICKS + 10L, () -> {
            helper.assertTrue(storage.active(record.playerId()).isEmpty(),
                    "the reloaded attempt must have expired by now, purely from elapsed game ticks");
            helper.succeed();
        });
    }

    /**
     * Review follow-up: the villager-progression write ({@link PathfinderVillagerProgressStorage},
     * a SEPARATE {@code SavedData} file from the attempt's own) can independently fail even when
     * the attempt file's own write/clear succeeds - a durability check that only verified the
     * attempt file would then pay the reward while the earned progression silently never lands.
     * This targets the fault at exactly that one file: pre-creating its on-disk path as a
     * DIRECTORY (not a regular file) makes the atomic-rename step of just that file's write fail
     * with a real {@code IOException} (replacing a directory with a regular file fails), while the
     * attempt file's own write, at a different path in the same data directory, is entirely
     * unaffected.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 150, batch = "pathfinder_fault_progression_write")
    public static void progressionOnlyWriteFailureWithholdsRewardUntilConfirmedThenRetrySucceeds(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));

        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level()).orElseThrow();
        int lastIndex = route.checkpoints().size() - 1;
        storage.update(new PathfinderAttemptRecord(record.playerId(), record.villagerId(), record.dimensionId(), record.origin(), record.level(),
                record.startTick(), record.timeLimitTicks(), lastIndex, false, false, false, false));

        server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        java.io.File dataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile();
        helper.assertTrue(dataDir.isDirectory(), "sanity: the world's data directory must exist by now");
        java.io.File progressFile = new java.io.File(dataDir, PathfinderVillagerProgressStorage.NAME + ".dat");
        // The shared "Test Level" world may already have this file from an earlier test/batch in
        // this same run (every completion anywhere writes it) - remove any pre-existing regular
        // file first; its on-disk bytes are not the source of truth (the in-memory singleton,
        // already populated for the whole server lifetime, is), so deleting it here is harmless.
        if (progressFile.isFile()) {
            helper.assertTrue(progressFile.delete(), "sanity: must be able to remove the pre-existing progression file to inject the fault");
        }
        helper.assertTrue(!progressFile.exists(), "sanity: the progression path must be clear before injecting the fault");
        helper.assertTrue(progressFile.mkdir(), "sanity: must be able to pre-create the progression path as a directory");

        PathfinderCheckpoint last = route.checkpoints().get(lastIndex);
        PathfinderCheckpoint first = route.checkpoints().get(0);
        player.teleportTo(last.position().x(), last.position().y(), last.position().z());

        // Safety net, independent of the test's own outcome - see writeFailureWithholdsRewardUntilConfirmedThenRetrySucceeds.
        helper.runAfterDelay(120L, () -> deleteIfEmptyDirectory(progressFile));

        boolean[] restoredWritable = {false};
        boolean[] movedToClosingLeg = {false};
        helper.succeedWhen(() -> {
            if (!movedToClosingLeg[0]) {
                int reached = storage.active(player.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1);
                helper.assertTrue(reached == route.checkpoints().size(), "expected the last checkpoint to register first");
                player.teleportTo(first.position().x(), first.position().y(), first.position().z());
                movedToClosingLeg[0] = true;
                helper.fail("moved to the closing leg - wait for the next evaluation to hit the injected progression-only write failure");
            }
            if (!restoredWritable[0]) {
                PathfinderAttemptRecord pending = storage.active(player.getUUID()).orElse(null);
                helper.assertTrue(pending != null && pending.finished() && !pending.progressionCredited(),
                        "a failed progression write must retain the finished-but-uncredited attempt for retry, even though the attempt file's own write succeeded");
                helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0,
                        "reward must be withheld while the villager's progression credit cannot be confirmed durable");

                // Review follow-up: prove a REAL restart right now (progression write still
                // failing) would not lose the completed run or falsely settle it as credited -
                // read the attempt file straight back off disk, bypassing the live in-memory
                // singleton entirely, exactly as PathfinderTickHandler's own verification does.
                PathfinderAttemptRecord onDiskRecord;
                try {
                    net.minecraft.nbt.CompoundTag attemptRoot = server.overworld().getDataStorage().readTagFromDisk(
                            PathfinderAttemptStorage.NAME, null, net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
                    PathfinderAttemptStorage onDisk = PathfinderAttemptStorage.load(attemptRoot.getCompound("data"), server.overworld().registryAccess());
                    onDiskRecord = onDisk.active(player.getUUID()).orElse(null);
                } catch (java.io.IOException e) {
                    onDiskRecord = null;
                }
                helper.assertTrue(onDiskRecord != null && onDiskRecord.finished() && !onDiskRecord.progressionCredited(),
                        "a fresh reload from disk (simulating a restart right now, while the progression write is still failing) must still see the "
                                + "completed-but-uncredited attempt - not lose it (the old bug: the attempt was cleared to disk despite the overall "
                                + "verification failing) and not see it falsely marked progressionCredited=true ahead of the progression write actually landing");

                helper.assertTrue(deleteIfEmptyDirectory(progressFile), "must be able to remove the fault directory to let the real file be written");
                restoredWritable[0] = true;
                helper.fail("removed the fault directory - wait for the retry to confirm and pay out");
            }
            helper.assertTrue(storage.active(player.getUUID()).isEmpty(), "once the progression write succeeds, the retried payout should clear the attempt");
            int expected = PathfinderConfig.rewardEmeralds(record.level());
            int actual = player.getInventory().countItem(Items.EMERALD);
            helper.assertTrue(actual == expected, "expected exactly " + expected + " emerald(s) after the retry succeeded, got " + actual);
            PathfinderVillagerProgress.State villagerState = PathfinderVillagerLevel.stateOf(server, villager.getUUID());
            helper.assertTrue(villagerState.completionsAtLevel() == 1,
                    "the villager's own completion count should have advanced exactly once, not lost or double-counted across the retry");
        });
    }

    /**
     * Review follow-up (post-merge CI failure): draining NeoForge's IO worker FIRST, before
     * removing the fault directory, matters here specifically - its own asynchronous temp-file
     * cleanup ({@code IOUtilities#tryCleanupTempFiles}) can otherwise still be scanning this
     * exact directory when this test code deletes it out from under that scan, racing a
     * NoSuchFileException into an unrelated later IO-worker task. {@code
     * PathfinderTickHandler}'s own durability checks are now hardened against that regardless
     * (any such failure there must fail closed, not crash), but draining first avoids
     * manufacturing the race from the test side at all.
     */
    private static boolean deleteIfEmptyDirectory(java.io.File dir) {
        try {
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        } catch (RuntimeException e) {
            // Best-effort drain only - see this method's javadoc. Proceeding either way is fine:
            // worst case the directory removal below still races a cleanup task, which
            // PathfinderTickHandler's own durability checks are hardened to fail closed against.
        }
        return !dir.isDirectory() || dir.delete();
    }

    /**
     * Review follow-up (round 8): tracking only the single most-recently-credited attempt key per
     * villager suppressed a retry for the SAME attempt (A, A) but not an interleaving of two
     * DIFFERENT players' pending completions on the same villager (A, B, A) - crediting B
     * overwrote A's remembered key, so A's own still-legitimately-pending retry (its own
     * verification never confirmed) no longer matched anything and was credited a second time,
     * advancing progression from two real completions to three. Two distinct players both finish
     * a circuit against the SAME villager while the progression write is failing, so both
     * attempts' retries interleave for real through the normal {@code onServerTick} loop (which
     * evaluates every active attempt each cycle) - once writes recover, both players must be
     * rewarded and the villager must show EXACTLY two credited completions, never three, and must
     * not have unlocked level 2 at the default threshold of 3.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 200, batch = "pathfinder_fault_progression_write_two_players")
    public static void twoInterleavedPendingCompletionsOnSameVillagerCreditExactlyOnceEach(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);

        ServerPlayer playerA = GameTestMockPlayers.create(helper);
        playerA.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(playerA);
        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(playerA, InteractionHand.MAIN_HAND, villager));

        ServerPlayer playerB = GameTestMockPlayers.create(helper);
        playerB.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(playerB);
        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(playerB, InteractionHand.MAIN_HAND, villager));

        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord recordA = storage.active(playerA.getUUID()).orElseThrow();
        PathfinderAttemptRecord recordB = storage.active(playerB.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, recordA.origin(), recordA.level()).orElseThrow();
        int lastIndex = route.checkpoints().size() - 1;
        storage.update(new PathfinderAttemptRecord(recordA.playerId(), recordA.villagerId(), recordA.dimensionId(), recordA.origin(), recordA.level(),
                recordA.startTick(), recordA.timeLimitTicks(), lastIndex, false, false, false, false));
        storage.update(new PathfinderAttemptRecord(recordB.playerId(), recordB.villagerId(), recordB.dimensionId(), recordB.origin(), recordB.level(),
                recordB.startTick(), recordB.timeLimitTicks(), lastIndex, false, false, false, false));

        server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        java.io.File dataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile();
        helper.assertTrue(dataDir.isDirectory(), "sanity: the world's data directory must exist by now");
        java.io.File progressFile = new java.io.File(dataDir, PathfinderVillagerProgressStorage.NAME + ".dat");
        if (progressFile.isFile()) {
            helper.assertTrue(progressFile.delete(), "sanity: must be able to remove the pre-existing progression file to inject the fault");
        }
        helper.assertTrue(!progressFile.exists(), "sanity: the progression path must be clear before injecting the fault");
        helper.assertTrue(progressFile.mkdir(), "sanity: must be able to pre-create the progression path as a directory");

        PathfinderCheckpoint last = route.checkpoints().get(lastIndex);
        PathfinderCheckpoint first = route.checkpoints().get(0);
        playerA.teleportTo(last.position().x(), last.position().y(), last.position().z());
        playerB.teleportTo(last.position().x(), last.position().y(), last.position().z());

        helper.runAfterDelay(170L, () -> deleteIfEmptyDirectory(progressFile));

        boolean[] restoredWritable = {false};
        boolean[] movedToClosingLeg = {false};
        helper.succeedWhen(() -> {
            if (!movedToClosingLeg[0]) {
                int reachedA = storage.active(playerA.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1);
                int reachedB = storage.active(playerB.getUUID()).map(PathfinderAttemptRecord::reachedCount).orElse(-1);
                helper.assertTrue(reachedA == route.checkpoints().size() && reachedB == route.checkpoints().size(),
                        "expected both players' last checkpoint to register first");
                playerA.teleportTo(first.position().x(), first.position().y(), first.position().z());
                playerB.teleportTo(first.position().x(), first.position().y(), first.position().z());
                movedToClosingLeg[0] = true;
                helper.fail("moved both players to the closing leg - wait for interleaved evaluation to hit the injected progression-only write failure");
            }
            if (!restoredWritable[0]) {
                PathfinderAttemptRecord pendingA = storage.active(playerA.getUUID()).orElse(null);
                PathfinderAttemptRecord pendingB = storage.active(playerB.getUUID()).orElse(null);
                helper.assertTrue(pendingA != null && pendingA.finished() && !pendingA.progressionCredited(),
                        "player A's completion must still be pending while the progression write fails");
                helper.assertTrue(pendingB != null && pendingB.finished() && !pendingB.progressionCredited(),
                        "player B's completion must still be pending while the progression write fails");
                helper.assertTrue(playerA.getInventory().countItem(Items.EMERALD) == 0 && playerB.getInventory().countItem(Items.EMERALD) == 0,
                        "reward must be withheld for both players while the progression credit cannot be confirmed durable");
                helper.assertTrue(deleteIfEmptyDirectory(progressFile), "must be able to remove the fault directory to let the real file be written");
                restoredWritable[0] = true;
                helper.fail("removed the fault directory - wait for both retries to confirm and pay out");
            }
            helper.assertTrue(storage.active(playerA.getUUID()).isEmpty() && storage.active(playerB.getUUID()).isEmpty(),
                    "once the progression write succeeds, both retried payouts should clear their attempts");
            int expected = PathfinderConfig.rewardEmeralds(recordA.level());
            helper.assertTrue(playerA.getInventory().countItem(Items.EMERALD) == expected,
                    "expected player A to receive exactly " + expected + " emerald(s)");
            helper.assertTrue(playerB.getInventory().countItem(Items.EMERALD) == expected,
                    "expected player B to receive exactly " + expected + " emerald(s)");
            PathfinderVillagerProgress.State villagerState = PathfinderVillagerLevel.stateOf(server, villager.getUUID());
            helper.assertTrue(villagerState.level() == 1 && villagerState.completionsAtLevel() == 2,
                    "exactly two real completions (one each for A and B) must be credited, never three, and the villager must not have unlocked "
                            + "level 2 at the default threshold of 3");
        });
    }

    /**
     * Review follow-up: a failed death-clear previously put the ORIGINAL, still-"live" record
     * back into storage (every terminal flag false) - letting a later tick, by then past respawn,
     * treat it as a normal in-progress attempt and advance/complete it off whatever movement
     * happened to follow. This drives the real sequence with a genuinely injected write failure
     * (read-only save directory, exactly as {@link #writeFailureWithholdsRewardUntilConfirmedThenRetrySucceeds}):
     * die one checkpoint short of completion, respawn, move to the closing checkpoint exactly as a
     * real completion would, and confirm across several real ticks that nothing is ever rewarded
     * or credited while write access stays blocked - only once it is restored does the attempt
     * finally clear (with no reward, since this was a death, not a completion).
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 200, batch = "pathfinder_fault_death_write")
    public static void deathDuringWriteFailureSurvivingRespawnAndClosingCheckpointNeverRewardsThenClears(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()));
        seedLevel1RouteForTest(helper, villager);
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));

        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level()).orElseThrow();
        int lastIndex = route.checkpoints().size() - 1;
        storage.update(new PathfinderAttemptRecord(record.playerId(), record.villagerId(), record.dimensionId(), record.origin(), record.level(),
                record.startTick(), record.timeLimitTicks(), lastIndex, false, false, false, false));

        server.overworld().getDataStorage().save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        java.io.File dataDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data").toFile();
        helper.assertTrue(dataDir.isDirectory(), "sanity: the world's data directory must exist by now");

        boolean permissionsBlockWrites = !dataDir.setWritable(false);
        if (permissionsBlockWrites) {
            dataDir.setWritable(true);
            helper.succeed();
            return;
        }
        helper.runAfterDelay(170L, () -> dataDir.setWritable(true));

        // A real death (fires the actual, wired-up LivingDeathEvent) while writes are failing:
        // onLivingDeath's own durable-clear verification must genuinely fail here, leaving the
        // record marked died=true, still in storage - not discarded, and not left in its
        // original live shape.
        player.kill();
        PathfinderAttemptRecord afterDeath = storage.active(player.getUUID()).orElse(null);
        helper.assertTrue(afterDeath != null && afterDeath.died(),
                "a death-clear that cannot be confirmed durable must leave the record marked died, not discard or revive it");

        // Respawn while writes are still failing - the no-matching-prior-death-event fallback
        // path must be a harmless no-op here (the record is already died=true), not a second
        // chance to revive it.
        PathfinderTickHandler.onPlayerRespawn(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent(player, false));
        helper.assertTrue(storage.active(player.getUUID()).map(PathfinderAttemptRecord::died).orElse(false),
                "respawn's fallback clear attempt must not revive the attempt while writes still fail");

        // Firing the respawn EVENT does not itself revive the entity - a real client respawn
        // would. Restore health now so onServerTick's own isAlive() guard (defense in depth
        // against evaluating a dead player) stops skipping this player once writes recover,
        // exactly as a real post-respawn player would be picked back up by the normal tick loop.
        player.setHealth(player.getMaxHealth());

        PathfinderCheckpoint first = route.checkpoints().get(0);
        player.teleportTo(first.position().x(), first.position().y(), first.position().z());

        boolean[] restoredWritable = {false};
        helper.succeedWhen(() -> {
            // Real server ticks pass here via this polling loop - onServerTick fires for real
            // through the event bus on each one. The player stands right at what would be the
            // closing checkpoint; a bug reviving the record as live would complete or reward it
            // on exactly this movement.
            helper.assertTrue(player.getInventory().countItem(Items.EMERALD) == 0,
                    "a dead attempt must never be rewarded, even while standing at the closing checkpoint");
            PathfinderVillagerProgress.State villagerState = PathfinderVillagerLevel.stateOf(server, villager.getUUID());
            helper.assertTrue(villagerState.completionsAtLevel() == 0,
                    "a dead attempt must never credit villager progression, even while standing at the closing checkpoint");
            if (!restoredWritable[0]) {
                PathfinderAttemptRecord pending = storage.active(player.getUUID()).orElse(null);
                helper.assertTrue(pending != null && pending.died(), "the died attempt must still be retained, pending a successful clear, while writes keep failing");
                dataDir.setWritable(true);
                restoredWritable[0] = true;
                helper.fail("restored write access - wait for the retry to confirm the clear");
            }
            helper.assertTrue(storage.active(player.getUUID()).isEmpty(), "once writes recover, the died attempt should finally clear");
        });
    }
}
