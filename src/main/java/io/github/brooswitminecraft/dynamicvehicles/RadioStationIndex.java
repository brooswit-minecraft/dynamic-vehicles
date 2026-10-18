package io.github.brooswitminecraft.dynamicvehicles;

/**
 * Pure cycling math for the vehicle radio (MINECRAFT-394): Off, then every configured station in order,
 * then back to Off. No Minecraft types, so it is unit-tested on plain ints like {@link WheelMapping}.
 *
 * <p>"Off" is always a first-class choice, never one of the server-configured station URLs -- it exists
 * regardless of how many stations are configured, including zero.
 */
public final class RadioStationIndex {
    /** Sentinel meaning no station is selected ("Off"). */
    public static final int OFF = -1;

    private RadioStationIndex() {}

    /**
     * The next selection after {@code current}: Off (-1) advances to station 0, each station advances to
     * the next, and the last configured station wraps back to Off. With no stations configured at all
     * ({@code stationCount <= 0}), every call stays at Off -- cycling can never "select" a station that
     * does not exist.
     */
    public static int next(int current, int stationCount) {
        if (stationCount <= 0) {
            return OFF;
        }
        int next = clamp(current, stationCount) + 1;
        return next >= stationCount ? OFF : next;
    }

    /** Whether {@code index} is the Off selection. */
    public static boolean isOff(int index) {
        return index < 0;
    }

    /**
     * {@code index} constrained to a valid selection for {@code stationCount} stations -- Off if it is
     * negative, out of range (a shrunk config since this index was last saved), or there are no stations
     * configured at all.
     */
    public static int clamp(int index, int stationCount) {
        return stationCount <= 0 || index < 0 || index >= stationCount ? OFF : index;
    }

    /** A short human-readable label for the driver's HUD message. */
    public static String label(int index, int stationCount) {
        return isOff(index) ? "Off" : "Station " + (index + 1) + "/" + stationCount;
    }
}
