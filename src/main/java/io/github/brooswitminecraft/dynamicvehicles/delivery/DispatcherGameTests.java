package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.util.UUID;

/**
 * MINECRAFT-196 AC: a jobless villager next to the new {@link DispatcherBlocks#DISPATCH_BOARD}
 * takes the {@code dispatcher} profession, and right-clicking that villager opens the offers UI.
 *
 * <p>Both tests run against {@code data/dynamicvehicles/structure/dispatch_profession.nbt}, a
 * flat 5x5x5 platform with a 2-block-thick grass floor (y=0..1) and air above (y=2..4) &mdash;
 * the thick floor is slack for GameTest's own "northwest corner" vs. structure-block-position
 * bookkeeping (empirically, a single-layer floor left entities standing one layer too low and
 * suffocating; see this PR's history). Everything here is placed/spawned at local y=2, i.e. the
 * first air layer above the floor. {@code ci.yml}'s "Run GameTests" step runs
 * {@code ./gradlew runGameTestServer}, which registers this class via the
 * {@code @GameTestHolder} annotation below (NeoForge's ASM mod-file scan finds it; there is no
 * separate manual {@code RegisterGameTestsEvent} listener in {@code DynamicVehiclesMod}, which
 * would only add the same methods a second time) and exits non-zero on any required test
 * failure (see {@code GameTestServer#onServerExit}), so a regression here fails the build.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class DispatcherGameTests {
    private DispatcherGameTests() {}

    @GameTest(template = "dispatch_profession", timeoutTicks = 600)
    public static void joblessVillagerNextToDispatchBoardBecomesDispatcher(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 2, 2), DispatcherBlocks.DISPATCH_BOARD.get());
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 3));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        helper.succeedWhen(() -> {
            helper.assertTrue(villager.getVillagerData().getProfession() == DispatcherProfession.DISPATCHER.get(),
                    "villager next to the dispatch board never took the dispatcher profession");
        });
    }

    @GameTest(template = "dispatch_profession")
    public static void rightClickingADispatcherOpensTheOffersMenu(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 2, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(DispatcherProfession.DISPATCHER.get()));
        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());

        // makeMockServerPlayerInLevel()'s connection never ran the real client's mod-channel
        // negotiation handshake, so NeoForge's NetworkRegistry#checkPacket would otherwise
        // refuse to send the "advanced_open_screen" payload player.openMenu(...) needs for a
        // menu with extra client data (see createMenu's extraDataWriter) — it only lets through
        // vanilla payloads and payloads the connection has declared support for. Declaring this
        // one as an ad-hoc channel (the same fallback NetworkRegistry itself documents for
        // "additional channels through c:register") is the test-only equivalent of that
        // negotiation actually having happened.
        ChannelAttributes.getOrCreateAdHocChannels(player.connection.getConnection())
                .add(ResourceLocation.fromNamespaceAndPath("neoforge", "advanced_open_screen"));

        var event = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
        DispatcherInteractionHandler.onEntityInteract(event);

        helper.assertTrue(event.isCanceled(), "right-clicking a dispatcher should intercept vanilla's own villager interaction");
        helper.assertTrue(player.containerMenu instanceof DispatcherOfferMenu, "right-clicking a dispatcher should open the offers menu");
        helper.succeed();
    }

    /** MINECRAFT-323 AC1/AC2: right-clicking the board itself opens the same offers menu, as a fixed level-one dispatch source. */
    @GameTest(template = "dispatch_profession")
    public static void rightClickingTheDispatchBoardOpensTheOffersMenu(GameTestHelper helper) {
        BlockPos boardPos = new BlockPos(2, 2, 2);
        helper.setBlock(boardPos, DispatcherBlocks.DISPATCH_BOARD.get());

        ServerPlayer player = GameTestMockPlayers.create(helper);
        player.moveTo(helper.absolutePos(new BlockPos(2, 2, 3)).getCenter());

        // Same ad-hoc channel declaration rightClickingADispatcherOpensTheOffersMenu needs above -
        // player.openMenu(...) sends "advanced_open_screen" for a menu with extra client data.
        ChannelAttributes.getOrCreateAdHocChannels(player.connection.getConnection())
                .add(ResourceLocation.fromNamespaceAndPath("neoforge", "advanced_open_screen"));

        BlockPos absoluteBoardPos = helper.absolutePos(boardPos);
        BlockState boardState = helper.getBlockState(boardPos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absoluteBoardPos), Direction.UP, absoluteBoardPos, false);

        InteractionResult result = DispatcherBlocks.DISPATCH_BOARD.get()
                .useWithoutItem(boardState, helper.getLevel(), absoluteBoardPos, player, hit);

        helper.assertTrue(result == InteractionResult.CONSUME, "right-clicking the board should consume the interaction");
        helper.assertTrue(player.containerMenu instanceof DispatcherOfferMenu, "right-clicking the board should open the offers menu");
        helper.succeed();
    }

    /** MINECRAFT-323 AC4: a placed board's UUID survives its block entity being reloaded from NBT. */
    @GameTest(template = "dispatch_profession")
    public static void dispatchBoardPersistsItsUuidAcrossReload(GameTestHelper helper) {
        BlockPos boardPos = new BlockPos(2, 2, 2);
        helper.setBlock(boardPos, DispatcherBlocks.DISPATCH_BOARD.get());
        BlockPos absoluteBoardPos = helper.absolutePos(boardPos);

        if (!(helper.getLevel().getBlockEntity(absoluteBoardPos) instanceof DispatchBoardBlockEntity boardEntity)) {
            helper.fail("expected a DispatchBoardBlockEntity at the placed board");
            return;
        }
        UUID firstId = boardEntity.getOrCreateBoardId();

        var registries = helper.getLevel().registryAccess();
        var saved = boardEntity.saveWithFullMetadata(registries);
        DispatchBoardBlockEntity reloaded = new DispatchBoardBlockEntity(absoluteBoardPos, boardEntity.getBlockState());
        reloaded.loadWithComponents(saved, registries);

        helper.assertTrue(reloaded.getOrCreateBoardId().equals(firstId),
                "the board's UUID must survive a save/reload instead of being regenerated");
        helper.succeed();
    }
}
