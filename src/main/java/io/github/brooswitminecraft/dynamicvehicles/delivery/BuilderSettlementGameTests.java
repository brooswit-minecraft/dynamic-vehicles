package io.github.brooswitminecraft.dynamicvehicles.delivery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Integration tests use real blocks, plain saved-offer equivalents, and the registered completed-trade event. */
@GameTestHolder("dynamicvehicles")
@PrefixGameTestTemplate(false)
public final class BuilderSettlementGameTests {
    private static final BlockPos ANCHOR = new BlockPos(10, 2, 10);

    private static void prepareFixture(GameTestHelper helper) {
        // Establish an explicit surface and clear the GameTest barrier roof before heightmap checks.
        // Bounded to the template's own 24x24 footprint (settlement_test.nbt): the house centres on the
        // campfire's own absolute chunk, so its template-relative position shifts with wherever the test
        // happens to land in the world, and the template was grown to 24x24 so that every centre the
        // chunk-center arithmetic can produce (relative 3..18) still leaves room for the anchor at
        // centre-3 and the footprint/entrance at centre+3 without depending on GameTest's inter-structure
        // gap to absorb an out-of-bounds write.
        for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++)
            for (int y = 0; y <= 12; y++) helper.setBlock(new BlockPos(x, y, z), y < 2 ? Blocks.DIRT : Blocks.AIR);
    }

    /**
     * The house now centres on the campfire's own chunk (an absolute-coordinate computation), not a
     * fixed offset from ANCHOR, so where it lands relative to this template depends on where the test
     * happens to be placed in the world. Translate the absolute chunk centre back into template-relative
     * space so assertions below can keep using helper's relative-position methods.
     */
    private static BlockPos expectedHouseCenter(GameTestHelper helper) {
        BlockPos absAnchor = helper.absolutePos(ANCHOR);
        int absCenterX = BuilderSettlementPlan.chunkCenterCoordinate(absAnchor.getX());
        int absCenterZ = BuilderSettlementPlan.chunkCenterCoordinate(absAnchor.getZ());
        return ANCHOR.offset(absCenterX - absAnchor.getX(), 0, absCenterZ - absAnchor.getZ());
    }

    /** Spawns a Builder with a recorded campfire anchor at an arbitrary (already-prepared) position. */
    private static Villager builderAt(GameTestHelper helper, BlockPos anchor) {
        helper.setBlock(anchor, Blocks.CAMPFIRE);
        Villager villager = helper.spawn(EntityType.VILLAGER, anchor.offset(0, 0, 1));
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(BuilderProfession.BUILDER.get()).setLevel(5));
        villager.setVillagerXp(250);
        villager.getPersistentData().putLong(BuilderSettlementService.CAMPFIRE_POS_TAG, helper.absolutePos(anchor).asLong());
        return villager;
    }

    @GameTest(template = "settlement_test")
    public static void completedDonationBuildsEnclosedHouseAndRetiresTradedBuilder(GameTestHelper helper) {
        prepareFixture(helper);
        // Derive the anchor from the centre (as every other test here does) rather than placing the
        // campfire at a fixed ANCHOR first: ANCHOR's chunk-relative centre moves with wherever the test
        // happens to land in the world, and a fixed campfire position can land inside the resulting
        // footprint for roughly 1 in 10 placements, tripping the "fixture blocks house" check below on
        // the fixture itself rather than on real behaviour.
        BlockPos center = expectedHouseCenter(helper);
        BlockPos campfireAnchor = center.offset(-3, 0, -3);
        Villager villager = builderAt(helper, campfireAnchor);
        var player = GameTestMockPlayers.create(helper);
        villager.setTradingPlayer(player);
        // A plain offer has no custom take() subclass, just like one restored from a saved world.
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.QUARTZ_BLOCK, 28), new ItemStack(Items.DIRT), 2, 30, 0.05F);
        villager.notifyTrade(offer);
        helper.assertTrue(villager.getPersistentData().getIntArray("dynamicvehicles.pendingBuilderDonations").length == 2,
                "completed-trade listener did not queue the donation");
        villager.setVillagerXp(villager.getVillagerXp() + offer.getXp());
        for (int x=-2; x<=2; x++) for (int z=-2; z<=2; z++) for(int y=0;y<4;y++)
            helper.assertTrue(helper.getBlockState(center.offset(x,y,z)).isAir(), "fixture blocks house: " + center.offset(x,y,z));
        BuilderSettlementService.processPendingDonations(villager);
        helper.assertTrue(!helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE), "guaranteed donation did not settle: anchor=" + helper.absolutePos(campfireAnchor) + " center=" + center + " data=" + villager.getPersistentData());
        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE, "Builder did not retire");
        helper.assertTrue(villager.getVillagerData().getLevel() == 1 && villager.getVillagerXp() == 0, "traded Builder kept progression");
        helper.assertTrue(villager.getOffers().isEmpty(), "retired Builder kept donation offers");
        helper.assertTrue(helper.getBlockState(center.above()).getBlock() instanceof BedBlock, "house has no bed head");
        Direction facing = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (helper.getBlockState(center.relative(direction.getOpposite()).above()).getBlock() instanceof BedBlock) {
                facing = direction;
            }
        }
        helper.assertTrue(facing != null, "house has no bed foot to determine facing");
        int doors = 0;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            helper.assertTrue(!helper.getBlockState(center.offset(x, 0, z)).isAir(), "missing floor");
            helper.assertTrue(!helper.getBlockState(center.offset(x, 3, z)).isAir(), "missing roof");
            if (Math.abs(x) == 2 || Math.abs(z) == 2) for (int y = 1; y <= 2; y++) {
                var state = helper.getBlockState(center.offset(x, y, z));
                helper.assertTrue(!state.isAir(), "wall gap");
                if (state.getBlock() instanceof DoorBlock) doors++;
            }
        }
        helper.assertTrue(doors == 2, "door needs both halves");
        helper.assertTrue(helper.getBlockState(center.relative(facing.getOpposite()).above()).getBlock() instanceof BedBlock, "bed foot missing");
        helper.assertTrue(helper.getBlockState(center.relative(facing.getClockWise()).above()).is(Blocks.BARREL), "barrel missing");
        BuilderSettlementService.attemptDonationSettlement(villager, 5);
        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE, "duplicate trigger changed retirement");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollStillPreservesBlockedSite(GameTestHelper helper) {
        prepareFixture(helper);
        // Derive the anchor from the centre (see completedDonationBuildsEnclosedHouseAndRetiresTradedBuilder)
        // instead of a fixed ANCHOR, which can otherwise land inside the centred footprint and have the
        // CHEST below overwrite the campfire itself before settlement even runs.
        BlockPos center = expectedHouseCenter(helper);
        BlockPos campfireAnchor = center.offset(-3, 0, -3);
        Villager villager = builderAt(helper, campfireAnchor);
        // The centred house footprint no longer depends on rotation, so blocking the single centre
        // point blocks every rotation attempt.
        helper.setBlock(center, Blocks.CHEST);
        BuilderSettlementService.attemptDonationSettlement(villager, 5);
        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE), "failed placement consumed anchor");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(), "failed placement retired Builder");
        helper.assertTrue(helper.getBlockState(center).is(Blocks.CHEST), "overwrote existing build");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollSettlesWhenCampfireItselfSitsInTheCentredFootprint(GameTestHelper helper) {
        // Put the campfire exactly where the centred house will land, so the only "obstacle" in the
        // footprint and the only entity nearby is the campfire and its own Builder.
        prepareFixture(helper);
        BlockPos center = expectedHouseCenter(helper);
        Villager villager = builderAt(helper, center);
        BuilderSettlementService.attemptDonationSettlement(villager, 5);
        helper.assertTrue(!helper.getBlockState(center).is(Blocks.CAMPFIRE),
                "centred campfire blocked its own replacement");
        helper.assertTrue(helper.getBlockState(center.above()).getBlock() instanceof BedBlock,
                "house missing after centred settlement");
        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE, "Builder did not retire");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollRelocatesBuilderClearOfACornerPostItWasStandingIn(GameTestHelper helper) {
        // The campfire lands inside the footprint near a corner, and the Builder itself stands exactly
        // where that corner's post gets written -- the collision found on review, now fixed by moving
        // the villager to the already-validated clear entrance space once settlement is confirmed safe.
        prepareFixture(helper);
        BlockPos center = expectedHouseCenter(helper);
        BlockPos campfireAnchor = center.offset(2, 0, 1);
        Villager villager = builderAt(helper, campfireAnchor);
        Vec3 cornerAbs = helper.absoluteVec(new Vec3(center.getX() + 2.5D, center.getY(), center.getZ() + 2.5D));
        villager.moveTo(cornerAbs.x, cornerAbs.y, cornerAbs.z, 0.0F, 0.0F);
        var pig = helper.spawn(EntityType.PIG, center.offset(-3, 0, -3));
        pig.setNoAi(true);
        // Snapshot empirically rather than recomputing expected coordinates: round-tripping an entity's
        // position through the GameTest relative/absolute transform is lossy for fractional (spawn-style)
        // positions under some test rotations, which isn't what this assertion means to exercise.
        Vec3 pigPosBefore = pig.position();

        BuilderSettlementService.attemptDonationSettlement(villager, 5);

        helper.assertTrue(!helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE),
                "overlapping campfire blocked its own replacement");
        helper.assertTrue(helper.getBlockState(center.above()).getBlock() instanceof BedBlock,
                "house missing after overlapping settlement");
        // The corner post the Builder was standing in must actually be solid: proving the build wasn't
        // silently skipped there just to dodge the Builder's old position.
        helper.assertTrue(!helper.getBlockState(center.offset(2, 1, 2)).isAir(), "corner post missing at former Builder position");
        // The floor must be intact exactly where the campfire used to be, not left as a hole because
        // placement special-cased that one tile to avoid the campfire.
        helper.assertTrue(!helper.getBlockState(campfireAnchor).isAir(), "floor hole left at former campfire position");
        helper.assertTrue(villager.getVillagerData().getProfession() == VillagerProfession.NONE, "Builder did not retire");
        helper.assertTrue(villager.isAlive(), "Builder died from being entombed in the new house");
        BlockPos villagerPos = helper.relativePos(villager.blockPosition());
        helper.assertTrue(!BuilderSettlementPlan.isWithinHouseFootprint(center.getX(), center.getZ(), villagerPos.getX(), villagerPos.getZ()),
                "Builder was left standing inside its own new house's footprint: " + villagerPos);
        helper.assertTrue(helper.getBlockState(villagerPos).isAir() && helper.getBlockState(villagerPos.above()).isAir(),
                "Builder was relocated into a solid block: " + villagerPos);
        // An unrelated entity well outside the footprint is untouched by the relocation/placement.
        helper.assertTrue(pig.isAlive(), "unrelated entity died during settlement");
        helper.assertTrue(pig.position().equals(pigPosBefore),
                "unrelated entity moved during settlement: was " + pigPosBefore + ", now " + pig.position());
        // A synchronous check proves the Builder wasn't placed inside a solid block; it can't prove no
        // damage-over-time hazard (fire, drowning) started ticking against it. Let a few ticks actually
        // pass and confirm it is still alive and undamaged before declaring it safe.
        float healthAfterSettlement = villager.getHealth();
        helper.runAfterDelay(5L, () -> {
            helper.assertTrue(villager.isAlive(), "Builder died in the few ticks after relocation");
            helper.assertTrue(villager.getHealth() >= healthAfterSettlement, "Builder took damage after relocation");
            helper.succeed();
        });
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollPreservesCampfireWhenEveryExitIsHazardous(GameTestHelper helper) {
        // Every one of the four possible exits sits on a lit campfire -- a sturdy but dangerous block the
        // old sturdy-only check would have accepted. No safe landing exists anywhere, so settlement must
        // leave the originating campfire and the Builder untouched rather than commit the house anyway.
        prepareFixture(helper);
        BlockPos center = expectedHouseCenter(helper);
        BlockPos campfireAnchor = center.offset(-3, 0, -3);
        Villager villager = builderAt(helper, campfireAnchor);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.setBlock(center.relative(direction, BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH / 2 + 1), Blocks.CAMPFIRE);
        }

        BuilderSettlementService.attemptDonationSettlement(villager, 5);

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE), "unsafe exits still consumed the originating campfire");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(), "unsafe exits still retired the Builder");
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.assertTrue(helper.getBlockState(center.relative(direction, BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH / 2 + 1)).is(Blocks.CAMPFIRE),
                    "an unrelated hazard campfire was disturbed");
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollPreservesCampfireWhenEveryExitHasHazardousLandingSupport(GameTestHelper helper) {
        // Every exit tile itself stays clear, but the block the Builder would actually land on one tick
        // after relocation (entrance.below()) is magma at all four exits -- sturdy, so the old sturdy-only
        // landing check would have accepted it, but still a hazard to stand on. No safe landing exists
        // anywhere, so settlement must leave the originating campfire and the Builder untouched.
        prepareFixture(helper);
        BlockPos center = expectedHouseCenter(helper);
        BlockPos campfireAnchor = center.offset(-3, 0, -3);
        Villager villager = builderAt(helper, campfireAnchor);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.setBlock(center.relative(direction, BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH / 2 + 1).below(), Blocks.MAGMA_BLOCK);
        }
        float healthBeforeSettlement = villager.getHealth();
        // Snapshot empirically rather than recomputing an expected coordinate: round-tripping an entity's
        // position through the GameTest relative/absolute transform is lossy for fractional (spawn-style)
        // positions under some test rotations (see the pig snapshot above), which isn't what this
        // assertion means to exercise.
        Vec3 villagerPosBefore = villager.position();

        BuilderSettlementService.attemptDonationSettlement(villager, 5);

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE), "unsafe exits still consumed the originating campfire");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(), "unsafe exits still retired the Builder");
        helper.assertTrue(villager.getHealth() == healthBeforeSettlement, "Builder was harmed by a landing hazard it was never placed on");
        helper.assertTrue(villager.position().equals(villagerPosBefore),
                "Builder was relocated despite settlement failing: was " + villagerPosBefore + ", now " + villager.position());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.assertTrue(helper.getBlockState(center.relative(direction, BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH / 2 + 1).below()).is(Blocks.MAGMA_BLOCK),
                    "an unrelated hazard landing support was disturbed");
        }
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void guaranteedRollPreservesCampfireWhenEveryExitIsEntityBlocked(GameTestHelper helper) {
        // A genuinely unrelated entity (not the settling Builder) standing in every possible exit must
        // still block settlement there, the same way an unrelated block obstacle does.
        prepareFixture(helper);
        BlockPos center = expectedHouseCenter(helper);
        BlockPos campfireAnchor = center.offset(-3, 0, -3);
        Villager villager = builderAt(helper, campfireAnchor);
        java.util.List<net.minecraft.world.entity.animal.Pig> blockers = new java.util.ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            var blocker = helper.spawn(EntityType.PIG, center.relative(direction, BuilderSettlementPlan.HOUSE_FOOTPRINT_WIDTH / 2 + 1).above());
            blocker.setNoAi(true);
            blockers.add(blocker);
        }

        BuilderSettlementService.attemptDonationSettlement(villager, 5);

        helper.assertTrue(helper.getBlockState(campfireAnchor).is(Blocks.CAMPFIRE), "entity-blocked exits still consumed the originating campfire");
        helper.assertTrue(villager.getVillagerData().getProfession() == BuilderProfession.BUILDER.get(), "entity-blocked exits still retired the Builder");
        for (var blocker : blockers) helper.assertTrue(blocker.isAlive(), "a blocking entity was harmed");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void terrainPreparationLevelsNaturalGroundAndPreservesOutsideFootprint(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos center = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR.offset(-2, 0, -2), Blocks.DIRT);
        helper.setBlock(ANCHOR.offset(-2, 1, -2), Blocks.SHORT_GRASS);
        helper.setBlock(ANCHOR.offset(2, -1, 2), Blocks.AIR);
        helper.setBlock(ANCHOR.offset(4, 0, 0), Blocks.CHEST);
        SettlementFounder.placeTradingPost(helper.getLevel(), center);
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(2, -1, 2)).is(Blocks.DIRT), "depression was not filled: " + helper.getBlockState(ANCHOR.offset(2,-1,2)));
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(-2, 1, -2)).isAir(), "grass was not cleared");
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(4, 0, 0)).is(Blocks.CHEST), "changed outside footprint");
        helper.succeed();
    }

    @GameTest(template = "settlement_test")
    public static void tradingPostForcesItsWayOverBuildsAndOpenAir(GameTestHelper helper) {
        prepareFixture(helper);
        BlockPos center = helper.absolutePos(ANCHOR);
        helper.setBlock(ANCHOR.offset(2, 0, 2), Blocks.CHEST);
        helper.setBlock(ANCHOR.offset(-2, -1, -2), Blocks.AIR);
        helper.setBlock(ANCHOR.offset(-2, -2, -2), Blocks.AIR);
        SettlementFounder.placeTradingPost(helper.getLevel(), center);
        helper.assertTrue(helper.getBlockState(ANCHOR.offset(2, 0, 2)).is(Blocks.SPRUCE_PLANKS), "build was not replaced");
        helper.assertTrue(!helper.getBlockState(ANCHOR.offset(-2, -1, -2)).isAir(), "floor left unsupported");
        helper.succeed();
    }
}
