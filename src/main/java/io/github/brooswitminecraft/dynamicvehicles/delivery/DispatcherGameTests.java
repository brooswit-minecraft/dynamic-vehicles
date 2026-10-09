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
 * flat 5x4x5 grass platform. <b>Neither test currently runs in this repo's CI</b> &mdash;
 * {@code ci.yml} only invokes {@code ./gradlew build}, which runs the {@code test} task
 * (unit tests) but not the {@code gameTestServer} run NeoForge wires these methods into. Running
 * them requires a human (or a future CI job) to invoke {@code ./gradlew runGameTestServer}
 * explicitly; see the PR description for the in-game verification checklist covering the same
 * behavior by hand in the meantime.
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
