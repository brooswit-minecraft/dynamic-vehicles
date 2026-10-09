package io.github.brooswitminecraft.dynamicvehicles.delivery;

/**
 * Pure tick-window arithmetic deciding when a contract gets its one roll
 * attempt (MINECRAFT-130 AC1, epic AC1): a fixed {@code rollIntervalTicks}
 * cadence measured from the contract's own {@code acceptedAtTick}, checked
 * once per {@link ContractTickHandler} sweep rather than on a second timer.
 * A pure function of its inputs (no injected clock needed beyond the two
 * tick values the sweep already has), so it is directly unit-testable.
 */
public final class AmbushSchedule {

    private AmbushSchedule() {
    }

    /**
     * @return true exactly once per {@code rollIntervalTicks} window - when {@code currentTick} is the
     *         first sweep tick (spaced {@code sweepIntervalTicks} apart) to have crossed a window boundary
     *         at or after {@code acceptedAtTick + rollIntervalTicks}. Never fires for the window containing
     *         acceptance itself (a brand new contract waits one full interval before its first roll).
     */
    public static boolean isRollTick(long acceptedAtTick, long currentTick, long sweepIntervalTicks, long rollIntervalTicks) {
        long elapsed = currentTick - acceptedAtTick;
        if (elapsed < rollIntervalTicks) {
            return false;
        }
        long previousElapsed = elapsed - sweepIntervalTicks;
        long window = elapsed / rollIntervalTicks;
        long previousWindow = previousElapsed < 0 ? -1 : previousElapsed / rollIntervalTicks;
        return window != previousWindow;
    }
}
