package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-196 AC: a jobless villager next to the new {@link DispatcherBlocks#DISPATCH_BOARD}
 * takes the {@code dispatcher} profession, and right-clicking that villager opens the offers UI.
 *
 * <p>Both tests run against {@code data/dynamicvehicles/structure/dispatch_profession.nbt}, a
 * flat 5x4x5 grass platform. {@code ci.yml}'s "Run GameTests" step runs
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
        helper.setBlock(new BlockPos(2, 1, 2), DispatcherBlocks.DISPATCH_BOARD.get());
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 3));
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NONE));

        helper.succeedWhen(() -> {
            helper.assertTrue(villager.getVillagerData().getProfession() == DispatcherProfession.DISPATCHER.get(),
                    "villager next to the dispatch board never took the dispatcher profession");
        });
    }

    @GameTest(template = "dispatch_profession")
    public static void rightClickingADispatcherOpensTheOffersMenu(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        villager.setVillagerData(villager.getVillagerData().setProfession(DispatcherProfession.DISPATCHER.get()));
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absolutePos(new BlockPos(2, 1, 3)).getCenter());

        var event = new PlayerInteractEvent.EntityInteract(player, InteractionHand.MAIN_HAND, villager);
        DispatcherInteractionHandler.onEntityInteract(event);

        helper.assertTrue(event.isCanceled(), "right-clicking a dispatcher should intercept vanilla's own villager interaction");
        helper.assertTrue(player.containerMenu instanceof DispatcherOfferMenu, "right-clicking a dispatcher should open the offers menu");
        helper.succeed();
    }
}
