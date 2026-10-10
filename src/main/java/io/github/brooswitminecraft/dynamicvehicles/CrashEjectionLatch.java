package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Latches "too fast to carry a mob safely" while this vehicle's speed exceeds {@link
 * #CRASH_EJECT_SPEED_THRESHOLD} (MINECRAFT-249): a mob ejected for crossing it must not immediately
 * re-board through contact ({@code CarEntity.push}) while the vehicle is still that fast. Pure and
 * Minecraft-type-free so it can be unit tested directly -- mirrors {@link StoppedTimer}'s own latch, but
 * edge-triggered immediately rather than after a sustained duration, since a crash/fast-movement
 * ejection must happen the instant the threshold is crossed, not after a delay. {@code CarEntity} is the
 * only caller.
 */
public final class CrashEjectionLatch {

    /**
     * Horizontal speed, blocks/tick, above which every mob passenger is ejected immediately and no mob
     * may board. Only the bus carries mob passengers today ({@link MobBoardingRules}); its top speed is
     * 20 m/s = 1.0 blocks/tick ({@link VehicleSpec#BUS}, at 20 ticks/second). This threshold (1.2) sits
     * above that with margin, so ordinary driving -- even at the bus's own top speed -- never crosses it;
     * it exists to catch an abnormal speed spike (a crash impulse, a knockback, a drop off a ledge) that
     * normal driving cannot reach on its own, not to cap ordinary bus speed. It is also far above {@link
     * StoppedTimer#STOP_SPEED_THRESHOLD} (0.1 blocks/tick), so the two never overlap.
     */
    public static final double CRASH_EJECT_SPEED_THRESHOLD = 1.2;

    private boolean latched;

    /**
     * Advances one tick given this tick's horizontal speed. Returns {@code true} exactly on the tick the
     * vehicle first crosses above {@link #CRASH_EJECT_SPEED_THRESHOLD} -- the tick a caller should eject
     * every mob passenger -- and stays latched (see {@link #isLatched}) for as long as the speed remains
     * above the threshold, clearing the moment it drops back at or under it.
     */
    public boolean tick(double horizontalSpeedBlocksPerTick) {
        boolean overThreshold = horizontalSpeedBlocksPerTick > CRASH_EJECT_SPEED_THRESHOLD;
        boolean justCrossed = overThreshold && !latched;
        latched = overThreshold;
        return justCrossed;
    }

    /** Whether the vehicle is currently too fast to board: set the tick {@link #tick} first returns
     * {@code true}, cleared the tick speed drops back at or under {@link #CRASH_EJECT_SPEED_THRESHOLD}. */
    public boolean isLatched() {
        return latched;
    }
}
