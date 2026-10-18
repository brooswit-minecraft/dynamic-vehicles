package io.github.brooswitminecraft.dynamicvehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-397 review fix: production-adapter regressions for {@link FlatGarageCommand#findSafeLanding}
 * against real blocks. {@link SafeLandingSearchTest}'s pure tests feed the search algorithm already-abstract
 * obstruction/hazard booleans, which cannot catch a bug in how the real adapter turns actual block shapes
 * into those booleans - specifically, the reviewed bug: a fence or wall's real collision shape reaches up
 * to 1.5 blocks tall, taller than its own one-block grid cell, and the deprecated {@code
 * BlockState#blocksMotion()} check that used to back this adapter only ever answered for a block's own
 * nominal cell, so it missed that spill-over into the cell above and would have accepted a landing
 * embedded in it. These run against a real, loaded {@link ServerLevel} instead.
 */
@GameTestHolder(DynamicVehiclesMod.MODID)
@PrefixGameTestTemplate(false)
public final class FlatGarageCommandGameTests {
    private FlatGarageCommandGameTests() {
    }

    @GameTest(template = "settlement_test")
    public static void fenceFloorDoesNotEmbedTheLandingInItsTallerCollisionShape(GameTestHelper helper) {
        // A fence's actual collision shape reaches ~1.5 blocks above its own grid cell (5,2,5), so the
        // cell directly above it (5,3,5) is only half-clear - a naive "is the block at (5,3,5) solid?"
        // check would say no and accept it anyway.
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.OAK_FENCE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 5, 5), Blocks.AIR);
        ServerLevel level = helper.getLevel();

        BlockPos target = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos landing = FlatGarageCommand.findSafeLanding(level, target);

        helper.assertTrue(landing != null, "no safe landing was found at all");
        helper.assertTrue(landing.getY() == target.getY() + 1,
                "landing was not relocated one cell above the fence's spilled-over collision shape: " + landing);
        helper.succeed();
    }

    /** The ordinary case (a real full block as the floor) must still land exactly on top of it, unmoved. */
    @GameTest(template = "settlement_test")
    public static void solidFullBlockFloorLandsDirectlyOnTopUnmoved(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.AIR);
        ServerLevel level = helper.getLevel();

        BlockPos target = helper.absolutePos(new BlockPos(5, 3, 5));
        BlockPos landing = FlatGarageCommand.findSafeLanding(level, target);

        helper.assertTrue(landing != null, "no safe landing was found at all");
        helper.assertTrue(landing.equals(target), "an already-safe landing on a real full block was moved: " + landing);
        helper.succeed();
    }

    /**
     * MINECRAFT-406 regression: a column whose chunk has never been loaded or generated must
     * still find the real floor there, not read as "unobstructed" everywhere. Before the fix,
     * {@code ServerLevel#noCollision} resolved to {@code Level#getChunkForCollisions}, which
     * fetches the chunk with {@code load=false}; for a column nobody has ever visited that
     * returns {@code null}, and {@code BlockCollisions} silently skips a {@code null} chunk
     * instead of treating it as solid - so every Y, including the floor, read as clear and no
     * landing could ever be found.
     *
     * <p>This test's target is thousands of blocks from the {@code settlement_test} structure,
     * a column this GameTest server's own world (a superflat preset - see
     * {@code GameTestServer}'s {@code WorldPresets.FLAT}) has never touched, reproducing that
     * exact "ungenerated destination chunk" condition without touching any live player's world.
     */
    @GameTest(template = "settlement_test")
    public static void firstVisitToAnUngeneratedChunkStillFindsTheRealFloorBelowIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(100_000, level.getMinBuildHeight() + 4, 100_000);

        BlockPos landing = FlatGarageCommand.findSafeLanding(level, target);

        helper.assertTrue(landing != null, "no safe landing was found in a never-visited chunk");
        helper.succeed();
    }
}
