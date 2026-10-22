package io.github.brooswitminecraft.dynamicvehicles.hud;

public final class DrivingHudPresentation {
    private DrivingHudPresentation() {}

    public static boolean visible(boolean hidden, boolean car, boolean driver, boolean telemetryAvailable) {
        return !hidden && car && driver && telemetryAvailable;
    }

    public static double fraction(double value, double maximum) {
        if (!Double.isFinite(value) || !Double.isFinite(maximum) || maximum <= 0) return 0;
        return Math.clamp(Math.abs(value) / maximum, 0, 1);
    }

    /** Reviewable display scale: full armor bar means 4 m/s actual tire slip. */
    public static double slipFraction(double slipMetersPerSecond) { return fraction(slipMetersPerSecond, 4); }

    public static String speed(double metersPerSecond) {
        return Math.round(Math.abs(metersPerSecond) * 3.6) + " km/h";
    }

    public static String rpm(double engineRpm) {
        return Math.round(Math.max(0, engineRpm)) + " RPM";
    }
}
