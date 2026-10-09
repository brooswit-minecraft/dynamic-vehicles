package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.Locale;

/**
 * Converts a {@link ConfirmedVillage#approxDistance()} BLOCKS value into the
 * player-facing KILOMETRES display required by MINECRAFT-109 AC2. Plain and
 * Minecraft-free so it is directly unit-testable.
 */
public final class OfferDistanceFormat {
    private static final double BLOCKS_PER_KILOMETRE = 1000.0;

    private OfferDistanceFormat() {
    }

    /** @param approxDistanceBlocks a {@link ConfirmedVillage#approxDistance()} value, in blocks */
    public static double toKilometres(double approxDistanceBlocks) {
        return approxDistanceBlocks / BLOCKS_PER_KILOMETRE;
    }

    /** @param approxDistanceBlocks a {@link ConfirmedVillage#approxDistance()} value, in blocks */
    public static String format(double approxDistanceBlocks) {
        return String.format(Locale.ROOT, "%.1f km", toKilometres(approxDistanceBlocks));
    }
}
