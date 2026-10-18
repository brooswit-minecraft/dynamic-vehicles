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
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()).setLevel(1));
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

    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void aSecondRightClickWhileActiveDoesNotStartASecondAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()).setLevel(1));
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
     * Fast-forwards a real, started attempt to its last checkpoint (rather than re-deriving
     * checkpoint positions by hand), then drives the real {@link PathfinderTickHandler} through
     * the last leg and the closing leg via actual server ticks, confirming the reward is granted
     * and the attempt is cleared exactly once.
     */
    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void completingTheLastCheckpointAndClosingLegGrantsRewardAndClearsTheAttempt(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(PathfinderProfession.PATHFINDER.get()).setLevel(1));
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());
        declarePathfinderHudChannel(player);

        PathfinderInteractionHandler.onEntityInteract(new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager));

        MinecraftServer server = helper.getLevel().getServer();
        PathfinderAttemptStorage storage = PathfinderAttemptStorage.of(server);
        PathfinderAttemptRecord record = storage.active(player.getUUID()).orElseThrow();
        ServerLevel dimension = helper.getLevel();
        PathfinderRoute route = PathfinderRouteStorage.of(server).routeFor(dimension, record.origin(), record.level());
        int lastIndex = route.checkpoints().size() - 1;

        // Jump straight to "one checkpoint left" via the same storage API a reload would use,
        // instead of teleporting through all 8 - the per-checkpoint logic is already covered by
        // PathfinderProgressTest; this test is about the real tick/reward/clear wiring.
        storage.update(new PathfinderAttemptRecord(record.playerId(), record.dimensionId(), record.origin(), record.level(),
                record.startTick(), record.timeLimitTicks(), lastIndex, false, false));

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
        });
    }
}
