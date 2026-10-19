package io.github.brooswitminecraft.dynamicvehicles.town;

import io.github.brooswitminecraft.dynamicvehicles.delivery.GameTestMockPlayers;
import io.github.brooswitminecraft.dynamicvehicles.delivery.RegionCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-388 review finding #5: credit must scale with the quantity actually consumed by the
 * REGISTERED trade event, not a flat per-trade amount - exercising {@link TownDonationHandler#onTrade}
 * against a real {@link TradeWithVillagerEvent}, not just {@code TownDonationConversion#creditFor}
 * in isolation (which the review explicitly called out as insufficient coverage).
 */
@GameTestHolder(io.github.brooswitminecraft.dynamicvehicles.DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class TownDonationHandlerGameTests {
    private TownDonationHandlerGameTests() {}

    private static final BlockPos ANCHOR = new BlockPos(5, 2, 5);

    private static void prepareFlatGround(GameTestHelper helper) {
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 0; y <= 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
                }
            }
        }
    }

    /** [Review round 4, finding #7] {@link TownDonationHandler} now credits the villager's own
     *  CLAIMED job site (its JOB_SITE memory's position + dimension), never its current physical
     *  position - so a villager used by these tests must actually carry that memory, matching the
     *  real precondition any genuinely employed vanilla villager has. */
    private static Villager employedVillagerAt(GameTestHelper helper, BlockPos absolutePos) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absolutePos, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), absolutePos));
        return villager;
    }

    /**
     * Mirrors {@code BuilderTradeHandler}'s own registered 16-cobblestone offer: the documented
     * rate (2 stone per single item) must be multiplied by the 16 actually consumed, crediting 32,
     * never a flat 2.
     */
    @GameTest(template = "settlement_test")
    public static void creditScalesWithTheActualQuantityConsumedByTheTrade(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = employedVillagerAt(helper, absoluteAnchor);
        ServerPlayer player = GameTestMockPlayers.create(helper);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        int before = storage.storeFor(key).balance(TownResourceCategory.STONE);

        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.COBBLESTONE, 16), new ItemStack(Items.STICK), 6, 5, 0.05F);
        TradeWithVillagerEvent event = new TradeWithVillagerEvent(player, offer, villager);

        TownDonationHandler.onTrade(event);

        int after = storage.storeFor(key).balance(TownResourceCategory.STONE);
        int expectedCredit = TownConstants.DONATION_COBBLESTONE_STONE_CREDIT * 16;
        helper.assertTrue(after - before == expectedCredit,
                "expected a credit of " + expectedCredit + " (rate-per-item x quantity consumed), but balance changed by "
                        + (after - before));
        helper.succeed();
    }

    /** A different consumed quantity for the SAME item must scale the credit proportionally -
     *  proves this is genuinely reading the consumed stack size, not a coincidental match to one
     *  hard-coded trade's own cost count. */
    @GameTest(template = "settlement_test")
    public static void creditScalesDifferentlyForADifferentConsumedQuantity(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = employedVillagerAt(helper, absoluteAnchor);
        ServerPlayer player = GameTestMockPlayers.create(helper);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        String key = TownKeys.keyFor(helper.getLevel().dimension(), region);
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        int before = storage.storeFor(key).balance(TownResourceCategory.WOOD);

        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.OAK_LOG, 8), new ItemStack(Items.STICK), 8, 1, 0.05F);
        TradeWithVillagerEvent event = new TradeWithVillagerEvent(player, offer, villager);

        TownDonationHandler.onTrade(event);

        int after = storage.storeFor(key).balance(TownResourceCategory.WOOD);
        int expectedCredit = TownConstants.DONATION_OAK_LOG_WOOD_CREDIT * 8;
        helper.assertTrue(after - before == expectedCredit,
                "expected a credit of " + expectedCredit + " for 8 consumed oak logs, but balance changed by " + (after - before));
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 4, finding #7: a donation must credit the town the villager is
     * actually CLAIMED/working for (its JOB_SITE memory's position), never whichever region its
     * current physical position happens to fall in - here the villager's JOB_SITE is far across a
     * region boundary from where it is standing (and trading) right now.
     */
    @GameTest(template = "settlement_test")
    public static void donationCreditsTheClaimedJobSiteRegionNotTheVillagersCurrentPosition(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteAnchor, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        // A claimed job site far across a 384-block region boundary from the villager's CURRENT
        // position - the exact "wandered since taking its job" scenario the review describes.
        BlockPos claimedJobSite = absoluteAnchor.offset(TownConstants.REGION_SIZE_BLOCKS * 2, 0, 0);
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(helper.getLevel().dimension(), claimedJobSite));
        ServerPlayer player = GameTestMockPlayers.create(helper);

        RegionCoord currentRegion = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        RegionCoord claimedRegion = TownRegion.fromBlockCoords(claimedJobSite.getX(), claimedJobSite.getZ());
        helper.assertTrue(!currentRegion.equals(claimedRegion), "test setup must actually place the claim in a DIFFERENT region");

        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        String currentKey = TownKeys.keyFor(helper.getLevel().dimension(), currentRegion);
        String claimedKey = TownKeys.keyFor(helper.getLevel().dimension(), claimedRegion);
        int currentBefore = storage.storeFor(currentKey).balance(TownResourceCategory.STONE);
        int claimedBefore = storage.storeFor(claimedKey).balance(TownResourceCategory.STONE);

        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.COBBLESTONE, 16), new ItemStack(Items.STICK), 6, 5, 0.05F);
        TownDonationHandler.onTrade(new TradeWithVillagerEvent(player, offer, villager));

        int currentAfter = storage.storeFor(currentKey).balance(TownResourceCategory.STONE);
        int claimedAfter = storage.storeFor(claimedKey).balance(TownResourceCategory.STONE);
        helper.assertTrue(currentAfter == currentBefore,
                "the region the villager is merely STANDING in must not be credited");
        helper.assertTrue(claimedAfter > claimedBefore,
                "the region the villager is actually CLAIMED/working for (its JOB_SITE) must be credited");
        helper.succeed();
    }

    /**
     * MINECRAFT-388 review round 5, finding #6: a JOB_SITE memory in a DIFFERENT dimension from
     * the one this trade is actually happening in is stale/foreign - a villager cannot be actively
     * working a job site in a dimension it is not currently in. Neither this trade's own dimension
     * (the claim does not point here) nor the claim's stale dimension (the villager is not actually
     * there right now to be credited for working it) may be credited; the trade must be a no-op.
     */
    @GameTest(template = "settlement_test")
    public static void donationWithAStaleCrossDimensionJobSiteCreditsNoTownAtAll(GameTestHelper helper) {
        prepareFlatGround(helper);
        BlockPos absoluteAnchor = helper.absolutePos(ANCHOR);
        Villager villager = EntityType.VILLAGER.create(helper.getLevel(), null, absoluteAnchor, MobSpawnType.EVENT, false, false);
        helper.getLevel().addFreshEntity(villager);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER));
        ResourceKey<Level> otherDimension = helper.getLevel().dimension().equals(Level.NETHER) ? Level.END : Level.NETHER;
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(otherDimension, absoluteAnchor));
        ServerPlayer player = GameTestMockPlayers.create(helper);

        RegionCoord region = TownRegion.fromBlockCoords(absoluteAnchor.getX(), absoluteAnchor.getZ());
        TownStorage storage = TownStorage.of(helper.getLevel().getServer());
        String thisDimensionKey = TownKeys.keyFor(helper.getLevel().dimension(), region);
        String otherDimensionKey = TownKeys.keyFor(otherDimension, region);
        int thisBefore = storage.storeFor(thisDimensionKey).balance(TownResourceCategory.STONE);
        int otherBefore = storage.storeFor(otherDimensionKey).balance(TownResourceCategory.STONE);

        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.COBBLESTONE, 16), new ItemStack(Items.STICK), 6, 5, 0.05F);
        TownDonationHandler.onTrade(new TradeWithVillagerEvent(player, offer, villager));

        helper.assertTrue(storage.storeFor(thisDimensionKey).balance(TownResourceCategory.STONE) == thisBefore,
                "this trade's own dimension must not be credited when the claim points at a different one");
        helper.assertTrue(storage.storeFor(otherDimensionKey).balance(TownResourceCategory.STONE) == otherBefore,
                "a stale claim in a dimension the villager is not currently in must never be credited either");
        helper.succeed();
    }
}
