package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * MINECRAFT-110 AC6's tight arrival criterion: a candidate position has
 * arrived only when it is in the SAME 16x16 chunk column as the
 * destination's recovered actual village start position
 * ({@link ConfirmedVillage#startBlockPos()}) - never the whole structure
 * placement region, which can be hundreds of blocks across. Plain integer
 * math (mirrors {@code SectionPos.blockToSectionCoord}'s {@code >> 4}
 * without needing the Minecraft type) so the near-miss case AC9 requires is
 * directly unit-testable.
 */
public final class ArrivalPredicate {

    private static final int CHUNK_SIZE_BLOCKS = 16;

    private ArrivalPredicate() {
    }

    /**
     * @param destinationStartX the destination village start position's X (block coordinate)
     * @param destinationStartZ the destination village start position's Z (block coordinate)
     * @param candidateX         the position to test, X (block coordinate)
     * @param candidateZ         the position to test, Z (block coordinate)
     * @return true only if {@code candidateX,candidateZ} falls in the same chunk column as the destination start
     */
    public static boolean hasArrived(long destinationStartX, long destinationStartZ, long candidateX, long candidateZ) {
        return chunkCoord(destinationStartX) == chunkCoord(candidateX)
                && chunkCoord(destinationStartZ) == chunkCoord(candidateZ);
    }

    private static long chunkCoord(long blockCoord) {
        return Math.floorDiv(blockCoord, CHUNK_SIZE_BLOCKS);
    }
}
