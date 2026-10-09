package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Tracks how long a vehicle has been stopped (MINECRAFT-211), so an auto-boarded mob can dismount once
 * it has stayed stopped for a short duration rather than the instant it slows down -- and so a mob does
 * not immediately re-board while it is still touching the vehicle and the vehicle is still stopped. Pure
 * and Minecraft-type-free so it can be unit tested directly; {@code CarEntity} is the only caller.
 *
 * <p>{@link #tick} fires {@code true} exactly ONCE per stop -- the tick the vehicle first crosses into
 * "stopped past threshold" -- never again while it stays stopped, so a caller that dismounts a passenger
 * on that signal does it once, not every tick (a repeated dismount call each tick, with the mob still
 * touching the vehicle and immediately re-boarding via contact, is exactly the board/dismount flicker a
 * review of this ticket's first version caught). {@link #isLatched} stays {@code true} for the whole
 * stopped stretch (not just the firing tick), so a caller can refuse boarding for its entire duration --
 * both a mob that just dismounted and any other mob touching an already-long-stopped vehicle -- clearing
 * only once the vehicle moves again.
 */
public final class StoppedTimer {

    /** Horizontal speed, blocks/tick, at or below which the vehicle counts as stopped. */
    public static final double STOP_SPEED_THRESHOLD = 0.1;

    /** Consecutive stopped ticks required before an auto-boarded mob dismounts (2 seconds at 20 TPS). */
    public static final int STOPPED_DISMOUNT_TICKS = 40;

    private int stoppedTicks;
    private boolean latched;

    /**
     * Advances one tick given this tick's horizontal speed. Returns {@code true} exactly on the tick the
     * vehicle first crosses into "stopped past threshold" (and sets {@link #isLatched} from that tick
     * on); any speed above {@link #STOP_SPEED_THRESHOLD} resets the count and clears the latch.
     */
    public boolean tick(double horizontalSpeedBlocksPerTick) {
        if (horizontalSpeedBlocksPerTick > STOP_SPEED_THRESHOLD) {
            stoppedTicks = 0;
            latched = false;
            return false;
        }
        if (stoppedTicks < STOPPED_DISMOUNT_TICKS) {
            stoppedTicks++;
        }
        if (stoppedTicks >= STOPPED_DISMOUNT_TICKS && !latched) {
            latched = true;
            return true;
        }
        return false;
    }

    /**
     * Whether the vehicle is currently latched "stopped past threshold": set on the tick {@link #tick}
     * first returns {@code true}, and held -- regardless of how many more ticks pass -- until the vehicle
     * moves again. While set, no mob may board.
     */
    public boolean isLatched() {
        return latched;
    }
}
