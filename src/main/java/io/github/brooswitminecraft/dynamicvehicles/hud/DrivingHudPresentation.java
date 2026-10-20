package io.github.brooswitminecraft.dynamicvehicles.hud;

public final class DrivingHudPresentation {
    private DrivingHudPresentation() {}

    public static boolean visible(boolean hidden, boolean car, boolean driver, boolean telemetryAvailable) {
        return !hidden && car && driver && telemetryAvailable;
    }

    public static String speed(double metersPerSecond) {
        return Math.round(Math.abs(metersPerSecond) * 3.6) + " km/h";
    }

    public static String rpm(double engineRpm) {
        return Math.round(Math.max(0, engineRpm)) + " RPM";
    }
}
