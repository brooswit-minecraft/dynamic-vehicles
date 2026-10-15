package io.github.brooswitminecraft.dynamicvehicles.delivery;

import java.util.UUID;

/**
 * Pure scheduling inputs for a Dispatcher's daily offer board.
 *
 * <p>The schedule intentionally depends only on the Dispatcher identity and
 * the Minecraft day. World seed remains an input to village-placement
 * prediction and to offer terms; it must not make two Dispatchers share the
 * same daily ordering.</p>
 */
public final class DispatcherOfferSchedule {

    private DispatcherOfferSchedule() {
    }

    /** Converts Minecraft game time to its zero-based day number. */
    public static long day(long gameTime) {
        return Math.floorDiv(gameTime, 24_000L);
    }

    /**
     * Produces the deterministic per-day seed used to order placement
     * regions. A changed day deliberately produces a different seed, while
     * reopening the same Dispatcher on the same day produces the same one.
     */
    public static long orderSeed(UUID dispatcherId, long minecraftDay) {
        long z = dispatcherId.getMostSignificantBits()
                ^ Long.rotateLeft(dispatcherId.getLeastSignificantBits(), 23)
                ^ (minecraftDay * 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
