package io.github.brooswitminecraft.dynamicvehicles.town;

/**
 * Clearing/leveling a construction site returns materials to matching stores, per MINECRAFT-388:
 * stone to stone, trees (logs/leaves) to wood, dirt to a SMALL amount of stone, leaves to a SMALL
 * amount of wood. "Small" is implemented as needing several cleared blocks to earn one credit
 * (see {@link TownConstants#CLEARING_DIRT_BLOCKS_PER_STONE_CREDIT} and
 * {@link TownConstants#CLEARING_LEAVES_BLOCKS_PER_WOOD_CREDIT}) rather than a fractional credit per
 * block, since the store only holds whole-number credits. A clearing pass should accumulate a
 * running block count per category across the whole site and call this once per category rather
 * than once per block, so the "several blocks per credit" rates don't silently round every single
 * block down to zero.
 */
public final class TownClearingYields {
    private TownClearingYields() {}

    public enum ClearedMaterial { STONE, LOG, LEAVES, DIRT }

    public record Yield(TownResourceCategory category, int creditsPerBlock, int blocksPerCredit) {}

    private static final Yield STONE_YIELD = new Yield(TownResourceCategory.STONE, TownConstants.CLEARING_STONE_RETURN, 1);
    private static final Yield LOG_YIELD = new Yield(TownResourceCategory.WOOD, TownConstants.CLEARING_WOOD_RETURN, 1);
    private static final Yield DIRT_YIELD = new Yield(TownResourceCategory.STONE,
            TownConstants.CLEARING_DIRT_STONE_RETURN, TownConstants.CLEARING_DIRT_BLOCKS_PER_STONE_CREDIT);
    private static final Yield LEAVES_YIELD = new Yield(TownResourceCategory.WOOD,
            TownConstants.CLEARING_LEAVES_WOOD_RETURN, TownConstants.CLEARING_LEAVES_BLOCKS_PER_WOOD_CREDIT);

    public static Yield yieldFor(ClearedMaterial material) {
        return switch (material) {
            case STONE -> STONE_YIELD;
            case LOG -> LOG_YIELD;
            case DIRT -> DIRT_YIELD;
            case LEAVES -> LEAVES_YIELD;
        };
    }

    /** Credits earned for clearing {@code blockCount} blocks of {@code material}, floor-divided. */
    public static int creditsFor(ClearedMaterial material, int blockCount) {
        if (blockCount < 0) {
            throw new IllegalArgumentException("blockCount must be >= 0: " + blockCount);
        }
        Yield yield = yieldFor(material);
        return (blockCount / yield.blocksPerCredit()) * yield.creditsPerBlock();
    }
}
