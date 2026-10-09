package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

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
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
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
}
