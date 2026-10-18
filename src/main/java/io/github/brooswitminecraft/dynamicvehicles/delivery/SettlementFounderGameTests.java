package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * MINECRAFT-393 AC: a successful delivery's destination gets exactly one Dispatch Board and one
 * (Dispatcher) villager placed on the surface, with no shack, platform, or extra terrain
 * mutation - the minimal placement {@link SettlementFounder#onArrival} replaced the Trading Post
 * pavilion with.
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class SettlementFounderGameTests {
    private SettlementFounderGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    /** Clears well above the template's own bounds: a GameTest area sits inside real overworld
     *  terrain, which can rise higher than the template itself and otherwise dominate the heightmap. */
    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    /**
     * A fresh, practically-never-repeated destination region, so DeliveryContractStorage's
     * founded-settlement persistence (world/data/dynamicvehicles_contracts.dat, which outlives a
     * single GameTest server run) can never make a once-successful test see ALREADY_FOUNDED on a
     * later rerun against a reused world and silently skip its own placement.
     */
    private static int[] freshDestinationRegion(GameTestHelper helper) {
        return new int[] {helper.getLevel().random.nextInt(), helper.getLevel().random.nextInt()};
    }

    @GameTest(template = "settlement_test")
    public static void arrivalPlacesExactlyOneBoardAndOneDispatcherOnTheSurface(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);

        ServerPlayer player = GameTestMockPlayers.create(helper);
        DeliveryContractStorage storage = DeliveryContractStorage.of(helper.getLevel().getServer());
        int[] region = freshDestinationRegion(helper);
        DeliveryContract contract = new DeliveryContract(
                player.getUUID(), UUID.randomUUID(), "minecraft:overworld", region[0], region[1],
                absoluteAnchor.getX(), absoluteAnchor.getY(), absoluteAnchor.getZ(),
                0.0D, 0.0D, 0L, 1L);

        boolean completed = SettlementFounder.onArrival(player, contract, storage);

        helper.assertTrue(completed, "arrival over a prepared flat surface should complete immediately");
        helper.assertTrue(helper.getBlockState(ANCHOR).is(DispatcherBlocks.DISPATCH_BOARD.get()),
                "expected a Dispatch Board placed directly on the surface");

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        List<Villager> villagers = helper.getLevel().getEntitiesOfClass(Villager.class, footprint);
        helper.assertTrue(villagers.size() == 1, "expected exactly one villager, found " + villagers.size());
        helper.assertTrue(villagers.get(0).getVillagerData().getProfession() == DispatcherProfession.DISPATCHER.get(),
                "the single placed villager should already be the Dispatcher");

        helper.assertTrue(helper.getBlockState(ANCHOR.offset(1, 0, 0)).isAir(),
                "no shack, platform, or decoration should be generated next to the board");
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(0, 2, 0)).isAir(),
                "no roof or ceiling should be generated above the destination");
        helper.succeed();
    }

    /** AC: the top of water is an acceptable surface, with the water itself left untouched. */
    @GameTest(template = "settlement_test")
    public static void arrivalPlacesTheBoardOnTopOfWaterWithoutDisturbingIt(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    helper.setBlock(pos, y < 2 ? Blocks.DIRT : y <= 5 ? Blocks.WATER : Blocks.AIR);
                }
            }
        }
        BlockPos boardCell = new BlockPos(5, 6, 5);
        BlockPos absoluteAnchor = helper.absolutePos(boardCell);

        ServerPlayer player = GameTestMockPlayers.create(helper);
        DeliveryContractStorage storage = DeliveryContractStorage.of(helper.getLevel().getServer());
        int[] region = freshDestinationRegion(helper);
        DeliveryContract contract = new DeliveryContract(
                player.getUUID(), UUID.randomUUID(), "minecraft:overworld", region[0], region[1],
                absoluteAnchor.getX(), absoluteAnchor.getY(), absoluteAnchor.getZ(),
                0.0D, 0.0D, 0L, 1L);

        boolean completed = SettlementFounder.onArrival(player, contract, storage);

        helper.assertTrue(completed, "arrival over open water should complete immediately");
        helper.assertTrue(helper.getBlockState(boardCell).is(DispatcherBlocks.DISPATCH_BOARD.get()),
                "expected the Dispatch Board placed right at the water's surface");
        helper.assertTrue(helper.getBlockState(boardCell.below()).is(Blocks.WATER),
                "the water the board rests on should be left untouched");
        helper.succeed();
    }

    /**
     * AC: a column whose candidate surface is blocked - here, a single leaf block the
     * {@code MOTION_BLOCKING_NO_LEAVES} heightmap ignores, exactly the low-canopy case the board
     * cell check guards against - defers without mutating anything.
     */
    @GameTest(template = "settlement_test")
    public static void arrivalUnderALowCanopyDefersWithoutMutatingAnything(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
        BlockPos canopyCell = ANCHOR;
        helper.setBlock(canopyCell, Blocks.OAK_LEAVES);
        BlockPos absoluteAnchor = helper.absolutePos(canopyCell);

        ServerPlayer player = GameTestMockPlayers.create(helper);
        DeliveryContractStorage storage = DeliveryContractStorage.of(helper.getLevel().getServer());
        int[] region = freshDestinationRegion(helper);
        DeliveryContract contract = new DeliveryContract(
                player.getUUID(), UUID.randomUUID(), "minecraft:overworld", region[0], region[1],
                absoluteAnchor.getX(), absoluteAnchor.getY(), absoluteAnchor.getZ(),
                0.0D, 0.0D, 0L, 1L);

        boolean completed = SettlementFounder.onArrival(player, contract, storage);

        helper.assertTrue(!completed, "a blocked candidate surface should defer, not complete");
        helper.assertTrue(helper.getBlockState(canopyCell).is(Blocks.OAK_LEAVES),
                "the leaf block occupying the candidate board cell must survive untouched");
        helper.assertTrue(!storage.hasFoundedSettlement(SettlementFounder.settlementKey(contract)),
                "a deferred arrival must not claim the settlement");

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(Villager.class, footprint).isEmpty(),
                "a deferred arrival must not spawn a villager");
        helper.succeed();
    }

    /**
     * The one-way settlement claim (ALREADY_FOUNDED) must survive a second arrival at the same
     * destination within a single run: repeated arrival, reconnect, and restart paths must not
     * place a second board or a second villager.
     */
    @GameTest(template = "settlement_test")
    public static void repeatedArrivalAtTheSameDestinationDoesNotDuplicatePlacement(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);

        ServerPlayer player = GameTestMockPlayers.create(helper);
        DeliveryContractStorage storage = DeliveryContractStorage.of(helper.getLevel().getServer());
        int[] region = freshDestinationRegion(helper);
        DeliveryContract contract = new DeliveryContract(
                player.getUUID(), UUID.randomUUID(), "minecraft:overworld", region[0], region[1],
                absoluteAnchor.getX(), absoluteAnchor.getY(), absoluteAnchor.getZ(),
                0.0D, 0.0D, 0L, 1L);

        boolean firstArrival = SettlementFounder.onArrival(player, contract, storage);
        boolean secondArrival = SettlementFounder.onArrival(player, contract, storage);

        helper.assertTrue(firstArrival, "the first arrival should complete and found the settlement");
        helper.assertTrue(secondArrival, "a repeated arrival at an already-founded destination should still complete");

        AABB footprint = AABB.encapsulatingFullBlocks(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(9, 12, 9)));
        List<Villager> villagers = helper.getLevel().getEntitiesOfClass(Villager.class, footprint);
        helper.assertTrue(villagers.size() == 1, "a repeated arrival must not spawn a second villager, found " + villagers.size());
        helper.succeed();
    }
}
