package io.github.brooswitminecraft.dynamicvehicles;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Minecraft-free ejection decisions for {@link CarEntity} (MINECRAFT-249/MINECRAFT-204), kept out of the
 * entity class so unit tests can exercise the production logic without Minecraft on the test classpath.
 */
final class MobEjection {
    private MobEjection() {
    }

    /** Eject on the crash latch's rising edge, and keep retrying every tick while latched with a mob aboard. */
    static boolean shouldAttemptCrashEjection(boolean risingEdge, boolean latched, boolean mobAboard) {
        return risingEdge || (latched && mobAboard);
    }

    /**
     * If no collision-free point exists, neither callback runs: the mob stays mounted rather than being
     * placed inside a block.
     */
    static boolean tryEjectAtSafePosition(double originX, double originY, double originZ,
            Predicate<double[]> isFree, Runnable stopRiding, Consumer<double[]> setPosition) {
        double[] safe = SafeEjectPlacement.find(originX, originY, originZ, isFree);
        if (safe == null) {
            return false;
        }
        stopRiding.run();
        setPosition.accept(safe);
        return true;
    }
}
