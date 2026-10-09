package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Tracks how many consecutive ticks a vehicle has been stopped (MINECRAFT-211), so an auto-boarded mob
 * can dismount once it has stayed stopped for a short duration rather than the instant it slows down.
 * Pure and Minecraft-type-free so it can be unit tested directly; {@code CarEntity} is the only caller.
 */
public final class StoppedTimer {

    /** Horizontal speed, blocks/tick, at or below which the vehicle counts as stopped. */
    public static final double STOP_SPEED_THRESHOLD = 0.1;

    /** Consecutive stopped ticks required before an auto-boarded mob dismounts (2 seconds at 20 TPS). */
    public static final int STOPPED_DISMOUNT_TICKS = 40;

    private int stoppedTicks;

    /**
     * Advances one tick given this tick's horizontal speed. Any speed above {@link #STOP_SPEED_THRESHOLD}
     * resets the count to zero. Returns whether the vehicle has now been stopped for at least
     * {@link #STOPPED_DISMOUNT_TICKS} consecutive ticks.
     */
    public boolean tick(double horizontalSpeedBlocksPerTick) {
        if (horizontalSpeedBlocksPerTick > STOP_SPEED_THRESHOLD) {
            stoppedTicks = 0;
            return false;
        }
        if (stoppedTicks < STOPPED_DISMOUNT_TICKS) {
            stoppedTicks++;
        }
        return stoppedTicks >= STOPPED_DISMOUNT_TICKS;
    }
}
