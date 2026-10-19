package io.github.brooswitminecraft.dynamicvehicles.town;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * MINECRAFT-388's "estimate ground height from surface terrain excluding trees" requirement,
 * shared by {@link TownConstructionService} (terrain preparation before a building's floor is
 * laid) and {@link TownRoadConstructionService}/{@link TownRoadPlanner} (the reference height a
 * road ramps toward). {@code Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} alone only ignores LEAF
 * blocks, not log/trunk blocks, so a tree trunk standing on otherwise-flat ground would still read
 * as "the ground" under that heightmap alone; this additionally walks down through any log/leaf
 * material directly below that heightmap's answer until it finds real terrain.
 */
public final class TownTerrain {
    private TownTerrain() {}

    /** How far this will walk down through tree material before giving up and returning the
     *  heightmap's own answer - bounds the scan so a pathological column (shouldn't occur with
     *  real trees) cannot make this walk all the way to the void. */
    private static final int MAX_TREE_SCAN_BLOCKS = 48;

    /**
     * The real top of whatever is physically standing on this column right now - trunks AND
     * leaves both included (unlike {@link #estimateGroundHeight}, which deliberately strips trees
     * back out). Callers that need to know how far up they must SCAN to find clearable material
     * (preflight's whole-footprint check, terrain prep's clearing bound) need this value, not the
     * tree-excluded one: {@link #estimateGroundHeight} would already report the bare ground
     * height as if the tree were not there, which is correct for "what height should the floor
     * be" but would make a scan bounded by it skip over the very tree material it needs to find.
     */
    public static int rawCanopyTop(ServerLevel level, int blockX, int blockZ) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
    }

    public static int estimateGroundHeight(ServerLevel level, int blockX, int blockZ) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        return walkDownThroughTreeMaterial(level, blockX, blockZ, y);
    }

    /**
     * Like {@link #estimateGroundHeight}, but starts the tree-exclusion walk from a KNOWN anchor Y
     * (a campfire's own current position) instead of re-deriving a starting point from the
     * heightmap. Needed because the campfire block ITSELF registers as motion-blocking for
     * {@code Heightmap.Types.MOTION_BLOCKING_NO_LEAVES} once placed (its collision shape is short,
     * but still non-empty), which would otherwise skew a heightmap-based query taken at the
     * campfire's own column one block too high - exactly backwards from "find the real ground
     * this campfire is standing on". Since the caller already knows the campfire's exact position,
     * there is no need to rediscover a starting point via the heightmap at all.
     */
    public static int estimateGroundHeightBelow(ServerLevel level, BlockPos campfire) {
        return walkDownThroughTreeMaterial(level, campfire.getX(), campfire.getZ(), campfire.getY());
    }

    private static int walkDownThroughTreeMaterial(ServerLevel level, int blockX, int blockZ, int startY) {
        int y = startY;
        int minY = level.getMinBuildHeight();
        int scanned = 0;
        while (y > minY && scanned < MAX_TREE_SCAN_BLOCKS) {
            BlockState below = level.getBlockState(new BlockPos(blockX, y - 1, blockZ));
            if (!isTreeMaterial(below)) {
                break;
            }
            y--;
            scanned++;
        }
        return y;
    }

    /** Natural terrain this mod is allowed to clear/level without a protected-content preflight
     *  rejection: ordinary ground, stone variants, sand/gravel, and tree material. Deliberately
     *  excludes everything else (chests, crafted/player-placed blocks, other mods' blocks,
     *  campfires), which {@link TownSitePreflight} treats as "occupied/protected". */
    public static boolean isNaturalTerrain(BlockState state) {
        return state.isAir()
                || !state.getFluidState().isEmpty()
                || isTreeMaterial(state)
                || state.is(BlockTags.DIRT)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                || state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.SAND)
                || state.is(net.minecraft.world.level.block.Blocks.RED_SAND)
                || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.REPLACEABLE);
    }

    private static boolean isTreeMaterial(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    /** {@code null} when the block does not salvage into any {@link TownClearingYields} category
     *  (e.g. it was air, a fluid, or small vegetation that is simply cleared for free). */
    public static TownClearingYields.ClearedMaterial classify(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            return TownClearingYields.ClearedMaterial.LOG;
        }
        if (state.is(BlockTags.LEAVES)) {
            return TownClearingYields.ClearedMaterial.LEAVES;
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.STONE_ORE_REPLACEABLES)) {
            return TownClearingYields.ClearedMaterial.STONE;
        }
        if (state.is(BlockTags.DIRT) || state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || state.is(net.minecraft.world.level.block.Blocks.SAND)
                || state.is(net.minecraft.world.level.block.Blocks.RED_SAND)
                || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)) {
            return TownClearingYields.ClearedMaterial.DIRT;
        }
        return null;
    }
}
